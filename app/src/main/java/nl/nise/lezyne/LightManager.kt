package nl.nise.lezyne

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class Conn { IDLE, SCANNING, CONNECTING, CONNECTED, NOT_FOUND, NO_PERMISSION, BT_OFF }

data class LightState(
    val conn: Conn = Conn.IDLE,
    val front: Protocol.Lamp? = null,
    val rear: Protocol.Lamp? = null,
)

sealed class LightEvent {
    data class LowBattery(val front: Boolean, val bars: Int) : LightEvent()
    data class BatteryDead(val front: Boolean) : LightEvent()
    data class Overheat(val front: Boolean) : LightEvent()
}

/**
 * Eén verbinding met de Lezyne-voorlamp voor het hele proces (extension, dataveld en scherm).
 *
 * Dual-role: wij zijn GATT-client van de lamp (status komt binnen via notify) én GATT-server
 * voor de lamp (commando's gaan eruit als notify op onze eigen characteristic).
 *
 * De verbinding staat alleen open zolang iemand hem "vasthoudt" via [acquire] (rit bezig,
 * dataveld zichtbaar, instellingenscherm open). Daarna sluit hij na [LINGER_MS].
 *
 * Alle Bluetooth-state wordt op de main thread bijgewerkt.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object LightManager {
    private const val TAG = "LEZYNE"
    private const val LINGER_MS = 60_000L
    private const val SCAN_MS = 15_000L
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 5 * 60_000L
    private val RETRY_S = longArrayOf(3, 10, 30, 60)

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(LightState())
    val state: StateFlow<LightState> = _state

    private val _events = MutableSharedFlow<LightEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LightEvent> = _events

    private val logLines = ArrayDeque<String>()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val holders = mutableSetOf<String>()

    private var manager: BluetoothManager? = null
    private var server: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
    private var serviceAdded = false
    private var afterServiceAdded: (() -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var subscriber: BluetoothDevice? = null
    private var clientReady = false
    private var serverReady = false
    private var scanning = false

    private var retries = 0
    private var directFailures = 0
    private var pendingOn: Boolean? = null
    private val lowWarned = BooleanArray(2) // [0]=voor, [1]=achter
    private val overheatWarned = BooleanArray(2)

    private val outQueue = ArrayDeque<ByteArray>()
    private var sending = false

    // ------------------------------------------------------------------ publiek

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("lezyne", Context.MODE_PRIVATE)
        manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    val savedAddress: String? get() = prefs.getString("address", null)

    /** Verbinding openhouden zolang [tag] dat wil. */
    fun acquire(tag: String) = main.post {
        val wasEmpty = holders.isEmpty()
        holders.add(tag)
        main.removeCallbacks(lingerDisconnect)
        if (wasEmpty) log("Verbinding gevraagd door: $tag")
        ensureConnected()
    }

    fun release(tag: String) = main.post {
        if (!holders.remove(tag)) return@post
        if (holders.isEmpty()) {
            log("Niemand heeft de lamp nog nodig – verbreken over ${LINGER_MS / 1000} s")
            main.postDelayed(lingerDisconnect, LINGER_MS)
        }
    }

    /** Gewenste aan/uit-stand. Wordt verstuurd zodra er verbinding en status is. */
    fun setOn(on: Boolean) = main.post {
        val f = _state.value.front
        if (_state.value.conn == Conn.CONNECTED && f != null) {
            if (f.on != on) { if (on) sendOn() else sendOff() }
            else log("Lamp staat al ${if (on) "aan" else "uit"}")
        } else {
            log("Onthouden: lamp ${if (on) "aan" else "uit"} zodra verbonden")
            pendingOn = on
            ensureConnected()
        }
    }

    fun toggle() = main.post {
        val f = _state.value.front
        if (!isConnected()) { log("Tik genegeerd: niet verbonden"); ensureConnected(); return@post }
        if (f?.on == true) sendOff() else sendOn()
    }

    fun nextMode() = main.post {
        val f = _state.value.front
        if (!isConnected()) { log("Tik genegeerd: niet verbonden"); ensureConnected(); return@post }
        if (f == null || !f.on) { sendOn(); return@post }
        send(byteArrayOf(Protocol.NEXT_MODE_FRONT))
        // Optimistisch bijwerken; de status hierna bevestigt het
        if (f.modes.isNotEmpty()) update { it.copy(front = f.copy(modeIndex = (f.modeIndex + 1) % f.modes.size)) }
        statusSoon()
    }

    fun requestStatus() = main.post { requestStatusNow() }

    /** Direct opnieuw proberen te verbinden (bv. na een tik op een niet-verbonden veld). */
    fun reconnectNow() = main.post {
        if (isConnected()) { requestStatusNow(); return@post }
        retries = 0
        main.removeCallbacks(retryRunnable)
        if (_state.value.conn == Conn.NOT_FOUND || _state.value.conn == Conn.IDLE ||
            _state.value.conn == Conn.BT_OFF || _state.value.conn == Conn.NO_PERMISSION) {
            setConn(Conn.IDLE)
        }
        ensureConnected()
    }

    fun sendRaw(bytes: ByteArray) = main.post {
        if (bytes.isNotEmpty() && bytes[0] in Protocol.FORBIDDEN) {
            log("❌ Geweigerd: 0x%02x is DFU/factory reset".format(bytes[0]))
            return@post
        }
        send(bytes)
        statusSoon()
    }

    /** Opgeslagen lamp vergeten en opnieuw zoeken. */
    fun findNewLamp() = main.post {
        prefs.edit().remove("address").remove("serial").apply()
        log("Opgeslagen lamp vergeten, opnieuw zoeken…")
        disconnectAll(keepHolders = true)
        directFailures = 0
        retries = 0
        ensureConnected()
    }

    fun hasPermissions(ctx: Context): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    // ------------------------------------------------------------------ verbinden

    private fun isConnected() = _state.value.conn == Conn.CONNECTED

    private fun ensureConnected() {
        if (holders.isEmpty() && pendingOn == null) return
        when (_state.value.conn) {
            Conn.SCANNING, Conn.CONNECTING, Conn.CONNECTED -> return
            else -> {}
        }
        main.removeCallbacks(retryRunnable)
        if (!hasPermissions(app)) {
            setConn(Conn.NO_PERMISSION)
            log("❌ Geen Bluetooth-rechten. Open de app één keer om ze te geven.")
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setConn(Conn.BT_OFF)
            log("❌ Bluetooth staat uit")
            scheduleRetry()
            return
        }
        openServer {
            val addr = savedAddress
            if (addr != null && directFailures < 2) {
                connectTo(adapter.getRemoteDevice(addr))
            } else {
                startScan(adapter)
            }
        }
    }

    private fun openServer(then: () -> Unit) {
        if (server != null && serviceAdded) { then(); return }
        if (server != null) { afterServiceAdded = then; return }
        val s = manager?.openGattServer(app, serverCallback)
        if (s == null) {
            log("❌ openGattServer mislukt")
            setConn(Conn.IDLE)
            scheduleRetry()
            return
        }
        val ch = BluetoothGattCharacteristic(
            Protocol.RXTX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        ch.addDescriptor(BluetoothGattDescriptor(Protocol.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
        val svc = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(ch)
        server = s
        serverChar = ch
        serviceAdded = false
        afterServiceAdded = then
        if (!s.addService(svc)) {
            log("❌ addService mislukt")
            resetServer()
            setConn(Conn.IDLE)
            scheduleRetry()
        }
    }

    private fun startScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: run { log("❌ Geen LE-scanner"); scheduleRetry(); return }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        seenInScan.clear()
        setConn(Conn.SCANNING)
        log("Zoeken naar Lezyne-voorlamp (${SCAN_MS / 1000} s)…")
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            scanning = false
            log("❌ Scan starten mislukt: ${e.message}")
            setConn(Conn.IDLE)
            scheduleRetry()
            return
        }
        main.postDelayed(scanTimeout, SCAN_MS)
    }

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        log("Geen lamp gevonden")
        setConn(Conn.NOT_FOUND)
        scheduleRetry()
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val seenInScan = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                val mfg = result.scanRecord?.manufacturerSpecificData?.let { if (it.size() > 0) it.valueAt(0) else null }
                val adv = Protocol.parseAdvert(mfg)
                val savedSerial = prefs.getLong("serial", -1L)
                val match = when {
                    savedSerial >= 0 -> adv?.serial == savedSerial
                    else -> adv == null || Protocol.isFrontModel(adv.model)
                }
                if (seenInScan.add(result.device.address)) log("Gevonden: ${result.device.address} ${adv?.let { Protocol.modelName(it.model) } ?: "?"}" +
                    if (match) " ✓" else " (overgeslagen)")
                if (!match) return@post
                stopScan()
                prefs.edit().putString("address", result.device.address)
                    .apply { if (adv != null) putLong("serial", adv.serial) }.apply()
                directFailures = 0
                connectTo(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                main.removeCallbacks(scanTimeout)
                log("❌ Scan mislukt (code $errorCode)")
                setConn(Conn.IDLE)
                scheduleRetry()
            }
        }
    }

    private fun connectTo(dev: BluetoothDevice) {
        setConn(Conn.CONNECTING)
        clientReady = false
        serverReady = false
        log("Verbinden met ${dev.address}…")
        gatt = if (Build.VERSION.SDK_INT >= 23)
            dev.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(app, false, clientCallback)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (_state.value.conn != Conn.CONNECTING) return@Runnable
        log("⚠️ Verbinden duurde te lang")
        failConnection()
    }

    private fun failConnection() {
        main.removeCallbacks(connectTimeout)
        closeGatt()
        directFailures++
        setConn(Conn.IDLE)
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (holders.isEmpty() && pendingOn == null) return
        val s = RETRY_S[minOf(retries, RETRY_S.size - 1)]
        retries++
        log("Opnieuw proberen over $s s")
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, s * 1000)
    }

    private val retryRunnable = Runnable { ensureConnected() }

    private var lingerExtensions = 0

    private val lingerDisconnect: Runnable = object : Runnable {
        override fun run() { lingerNow(this) }
    }

    private fun lingerNow(self: Runnable) {
        if (holders.isNotEmpty()) return
        if (pendingOn != null && lingerExtensions < 3) {
            lingerExtensions++
            log("Wacht nog op verbinding om de lamp ${if (pendingOn == true) "aan" else "uit"} te zetten…")
            main.postDelayed(self, LINGER_MS)
            return
        }
        lingerExtensions = 0
        run {
            log("Verbinding gesloten")
            pendingOn = null
            disconnectAll(keepHolders = false)
        }
    }

    private fun closeGatt() {
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        clientReady = false
        serverReady = false
        subscriber = null
        outQueue.clear()
        sending = false
        main.removeCallbacks(sendWatchdog)
        main.removeCallbacks(pollRunnable)
    }

    private fun resetServer() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        serverChar = null
        serviceAdded = false
        afterServiceAdded = null
    }

    private fun disconnectAll(keepHolders: Boolean) {
        stopScan()
        main.removeCallbacks(retryRunnable)
        main.removeCallbacks(connectTimeout)
        closeGatt()
        resetServer()
        if (!keepHolders) holders.clear()
        retries = 0
        _state.value = LightState(conn = Conn.IDLE)
    }

    // ------------------------------------------------------------------ GATT-server (commando's eruit)

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                serviceAdded = status == BluetoothGatt.GATT_SUCCESS
                if (!serviceAdded) {
                    log("❌ Service toevoegen mislukt (status $status)")
                    resetServer()
                    setConn(Conn.IDLE)
                    scheduleRetry()
                    return@post
                }
                val next = afterServiceAdded
                afterServiceAdded = null
                next?.invoke()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_DISCONNECTED && device.address == subscriber?.address) {
                    log("Lamp meldde zich af van ons commandokanaal – verbinding opnieuw opbouwen")
                    try { server?.cancelConnection(device) } catch (_: Exception) {}
                    closeGatt() // zoals LED Ally; niet wachten op een DISCONNECTED die soms uitblijft
                    _state.value = LightState(conn = Conn.IDLE)
                    scheduleRetry()
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val v = value ?: ByteArray(0)
            main.post {
                if (device.address != gatt?.device?.address) {
                    log("Abonnement van onbekend apparaat ${device.address} genegeerd")
                    return@post
                }
                if (v.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscriber = device
                    serverReady = true
                    log("Commandokanaal open")
                    checkBothReady()
                } else if (v.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    serverReady = false
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            main.post { log("Onverwachte write van lamp: ${Protocol.hex(value ?: ByteArray(0))}") }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                if (serverReady) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) log("⚠️ Versturen status $status")
                main.removeCallbacks(sendWatchdog)
                sending = false
                pump()
            }
        }
    }

    // ------------------------------------------------------------------ GATT-client (status erin)

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) { try { g.close() } catch (_: Exception) {}; return@post }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("Verbonden, services zoeken…")
                        main.postDelayed({ gatt?.discoverServices() }, 300)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasConnected = _state.value.conn == Conn.CONNECTED
                        log("Verbinding verbroken (status $status)")
                        main.removeCallbacks(connectTimeout)
                        closeGatt()
                        if (!wasConnected) directFailures++ else { retries = 0; directFailures = 0 }
                        _state.value = LightState(conn = Conn.IDLE)
                        scheduleRetry()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val ch = g.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.RXTX)
                val d = ch?.getDescriptor(Protocol.CCCD)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null || d == null) {
                    log("❌ Lezyne-service niet gevonden (status $status)")
                    failConnection()
                    return@post
                }
                g.setCharacteristicNotification(ch, true)
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(d)) { log("❌ Notificaties aanzetten mislukt"); failConnection() }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    clientReady = true
                    log("Statuskanaal open")
                    checkBothReady()
                } else {
                    log("❌ Notificaties aanzetten status $status")
                    failConnection()
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value?.copyOf() ?: return
            main.post { onData(v) }
        }
    }

    private fun checkBothReady() {
        if (!(clientReady && serverReady) || isConnected()) return
        main.removeCallbacks(connectTimeout)
        retries = 0
        directFailures = 0
        setConn(Conn.CONNECTED)
        log("✅ Verbonden met de lamp")
        main.postDelayed({ requestStatusNow() }, 1000)
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, POLL_MS)
    }

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isConnected()) return
            requestStatusNow()
            main.postDelayed(this, POLL_MS)
        }
    }

    // ------------------------------------------------------------------ data

    private fun onData(v: ByteArray) {
        log("<-- ${Protocol.describe(v)}")
        val lamp = Protocol.parseStatus(v)
        if (lamp != null) {
            update { if (lamp.front) it.copy(front = lamp) else it.copy(rear = lamp) }
            checkWarnings(lamp)
            if (lamp.front) {
                if (lamp.peerConnected && _state.value.rear == null) send(byteArrayOf(Protocol.GET_STATUS_REAR))
                pendingOn?.let { want ->
                    pendingOn = null
                    if (lamp.on != want) { if (want) sendOn() else sendOff() }
                }
            }
            return
        }
        if (v.isEmpty()) return
        // Alarmberichten: [cmd, ?, serial u32 LE] – serial bepaalt voor/achter
        val serial = if (v.size >= 6)
            java.nio.ByteBuffer.wrap(v).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(2).toLong() and 0xFFFFFFFFL
        else -1L
        val savedSerial = prefs.getLong("serial", -1L)
        val front = savedSerial < 0 || serial < 0 || serial == savedSerial
        val i = if (front) 0 else 1
        when (v[0].toInt()) {
            0x05, 0x06 -> _events.tryEmit(LightEvent.BatteryDead(front))
            0x07 -> if (!overheatWarned[i]) { overheatWarned[i] = true; _events.tryEmit(LightEvent.Overheat(front)) }
            0x08 -> overheatWarned[i] = false
        }
    }

    private fun checkWarnings(l: Protocol.Lamp) {
        val i = if (l.front) 0 else 1
        if (l.bars <= 1 && !lowWarned[i]) { lowWarned[i] = true; _events.tryEmit(LightEvent.LowBattery(l.front, l.bars)) }
        if (l.bars >= 3) lowWarned[i] = false
        if (l.overheat && !overheatWarned[i]) { overheatWarned[i] = true; _events.tryEmit(LightEvent.Overheat(l.front)) }
        if (!l.overheat) overheatWarned[i] = false
    }

    private fun hasRear(): Boolean = _state.value.rear != null || _state.value.front?.peerConnected == true

    private fun sendOn() {
        send(byteArrayOf(Protocol.RUN_FRONT))
        if (hasRear()) send(byteArrayOf(Protocol.RUN_REAR))
        update { s -> s.copy(front = s.front?.copy(on = true), rear = s.rear?.copy(on = true)) }
        statusSoon()
    }

    private fun sendOff() {
        send(byteArrayOf(Protocol.FRONT_OFF))
        if (hasRear()) send(byteArrayOf(Protocol.REAR_OFF))
        update { s -> s.copy(front = s.front?.copy(on = false), rear = s.rear?.copy(on = false)) }
        statusSoon()
    }

    private fun statusSoon() {
        main.removeCallbacks(statusRunnable)
        main.postDelayed(statusRunnable, 800)
    }

    private val statusRunnable = Runnable { requestStatusNow() }

    private fun requestStatusNow() {
        if (!isConnected()) return
        send(byteArrayOf(Protocol.GET_STATUS_FRONT))
        if (hasRear()) send(byteArrayOf(Protocol.GET_STATUS_REAR))
    }

    private fun send(bytes: ByteArray) {
        if (!serverReady || subscriber == null) { log("Niet verstuurd (geen verbinding): ${Protocol.hex(bytes)}"); return }
        outQueue.add(bytes)
        pump()
    }

    private fun pump() {
        if (sending) return
        val next = outQueue.poll() ?: return
        val dev = subscriber ?: return
        val ch = serverChar ?: return
        sending = true
        log("--> ${Protocol.hex(next)}")
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                server?.notifyCharacteristicChanged(dev, ch, false, next) == BluetoothGatt.GATT_SUCCESS
            } else {
                ch.value = next
                server?.notifyCharacteristicChanged(dev, ch, false) == true
            }
        } catch (e: Exception) { false }
        if (!ok) {
            log("❌ Versturen mislukt")
            sending = false
            main.postDelayed({ pump() }, 200)
        } else {
            // Vangnet als onNotificationSent uitblijft
            main.removeCallbacks(sendWatchdog)
            main.postDelayed(sendWatchdog, 1000)
        }
    }

    private val sendWatchdog = Runnable { if (sending) { sending = false; pump() } }

    // ------------------------------------------------------------------ hulp

    /** Log ook naar een bestand, zodat een rit achteraf terug te lezen is (adb pull). Max ~1 MB. */
    private fun appendToFile(line: String) {
        if (!::app.isInitialized) return
        try {
            val dir = app.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "lezyne-log.txt")
            if (f.length() > 1_000_000) f.renameTo(java.io.File(dir, "lezyne-log.old.txt"))
            f.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    private inline fun update(f: (LightState) -> LightState) { _state.value = f(_state.value) }

    private fun setConn(c: Conn) = update { it.copy(conn = c) }

    fun log(msg: String) {
        val line = "${time.format(Date())} $msg"
        Log.i(TAG, line)
        main.post {
            logLines.addLast(line)
            while (logLines.size > 300) logLines.removeFirst()
            _log.value = logLines.toList()
            appendToFile(line)
        }
    }
}
