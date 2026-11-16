package nl.nise.lezyne

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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.ArrayDeque

/**
 * Minimale dual-role verbinding met een Lezyne Smart Connect-lamp:
 *  - wij zijn GATT-client van de lamp (status binnen via notify)
 *  - wij zijn GATT-server voor de lamp (commando's eruit via notify)
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class LezyneLink(private val ctx: Context, private val log: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter

    private var server: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
    private var gatt: BluetoothGatt? = null
    private var target: BluetoothDevice? = null
    /** Het device-object zoals het zich bij onze server heeft geabonneerd. */
    private var subscriber: BluetoothDevice? = null

    private var clientReady = false
    private var serverReady = false
    private var scanning = false

    private val outQueue = ArrayDeque<ByteArray>()
    private var sending = false

    private fun ui(msg: String) = main.post { log(msg) }

    // ---------------------------------------------------------------- server

    fun openServer(): Boolean {
        if (server != null) return true
        val s = manager.openGattServer(ctx, serverCallback)
        if (s == null) {
            ui("❌ openGattServer gaf null terug – GATT-server niet mogelijk op dit apparaat")
            return false
        }
        val ch = BluetoothGattCharacteristic(
            Protocol.RXTX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        ch.addDescriptor(BluetoothGattDescriptor(Protocol.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
        val svc = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(ch)
        val ok = s.addService(svc)
        ui(if (ok) "GATT-server geopend, service wordt toegevoegd…" else "❌ addService mislukt")
        server = s
        serverChar = ch
        return ok
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            ui(if (status == BluetoothGatt.GATT_SUCCESS) "✅ Service toegevoegd aan onze GATT-server" else "❌ onServiceAdded status=$status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            ui("server: ${device.address} state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_DISCONNECTED && device.address == subscriber?.address) {
                serverReady = false
                subscriber = null
                ui("⚠️ Lamp heeft zich van onze server afgemeld")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val v = value ?: ByteArray(0)
            ui("server: CCCD-write van ${device.address}: ${Protocol.hex(v)}")
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (v.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                subscriber = device
                serverReady = true
                ui("✅ Lamp heeft zich op onze server geabonneerd (commandokanaal open)")
                checkBothReady()
            } else if (v.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                serverReady = false
                ui("Lamp heeft notificaties uitgezet")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            ui("server: onverwachte WRITE van lamp: ${Protocol.hex(value ?: ByteArray(0))}")
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            ui("server: onverwachte READ van lamp op ${characteristic.uuid}")
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
                if (status != BluetoothGatt.GATT_SUCCESS) log("❌ notify status=$status")
                sending = false
                pump()
            }
        }
    }

    // ---------------------------------------------------------------- scan

    fun scan(seconds: Long = 10) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { ui("❌ Bluetooth staat uit of geen LE-scanner"); return }
        if (scanning) return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        ui("Scannen ($seconds s)… zet de lamp aan")
        scanner.startScan(filters, settings, scanCallback)
        main.postDelayed({ stopScan() }, seconds * 1000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        ui("Scan gestopt")
    }

    private val seen = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val rec = result.scanRecord
            val mfg = rec?.manufacturerSpecificData?.let { if (it.size() > 0) it.valueAt(0) else null }
            val adv = Protocol.parseAdvert(mfg)
            if (seen.add(dev.address)) {
                ui("Gevonden: ${rec?.deviceName ?: dev.name ?: "?"} ${dev.address} rssi=${result.rssi}" +
                    (adv?.let { " model=${Protocol.modelName(it.model)} serial=${it.serial} ${if (it.isOn) "aan" else "uit"}" } ?: "") +
                    "\n  mfg=${mfg?.let { Protocol.hex(it) } ?: "-"}")
            }
            // Voorkeur: voorlamp (bedient de achterlamp mee als ze gekoppeld zijn)
            if (target == null && (adv == null || Protocol.isFrontModel(adv.model))) {
                stopScan()
                connect(dev)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            ui("❌ Scan mislukt, code $errorCode (rechten? locatie aan?)")
        }
    }

    // ---------------------------------------------------------------- client

    private fun connect(dev: BluetoothDevice) {
        if (!openServer()) return
        target = dev
        ui("Verbinden met ${dev.address}…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            dev.connectGatt(ctx, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(ctx, false, clientCallback)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    ui("✅ Verbonden (client), services ontdekken…")
                    main.postDelayed({ g.discoverServices() }, 300)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ui("⚠️ Verbinding verbroken (status=$status)")
                    clientReady = false; serverReady = false
                    g.close()
                    if (gatt == g) gatt = null
                    target = null
                    seen.clear()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ui("Services (status=$status): " + g.services.joinToString { it.uuid.toString().substring(0, 8) })
            val ch = g.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.RXTX)
            if (ch == null) { ui("❌ Lezyne-characteristic niet gevonden"); return }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(Protocol.CCCD)
            if (d == null) { ui("❌ Geen CCCD op de lamp"); return }
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!g.writeDescriptor(d)) ui("❌ writeDescriptor mislukt")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                clientReady = true
                ui("✅ Notificaties van de lamp aan (statuskanaal open)")
                checkBothReady()
            } else ui("❌ onDescriptorWrite status=$status")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            ui("<-- ${Protocol.hex(v)}\n${Protocol.describe(v)}")
        }
    }

    private fun checkBothReady() {
        main.post {
            if (clientReady && serverReady) {
                log("🎉 Beide kanalen open – status opvragen over 1 s")
                main.postDelayed({ send(byteArrayOf(Protocol.GET_STATUS_FRONT)) }, 1000)
            } else if (clientReady) {
                log("Wachten tot de lamp zich op onze server abonneert…")
                main.postDelayed({
                    if (clientReady && !serverReady) log("⚠️ Na 10 s nog geen abonnement van de lamp op onze server. Dit is het kernprobleem om uit te zoeken.")
                }, 10_000)
            }
        }
    }

    // ---------------------------------------------------------------- send

    fun send(bytes: ByteArray) {
        main.post {
            if (!serverReady || subscriber == null) {
                log("❌ Kan niet versturen: lamp niet geabonneerd op onze server")
                return@post
            }
            outQueue.add(bytes)
            pump()
        }
    }

    private fun pump() {
        if (sending) return
        val next = outQueue.poll() ?: return
        val dev = subscriber ?: return
        val ch = serverChar ?: return
        sending = true
        log("--> ${Protocol.hex(next)}")
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(dev, ch, false, next) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.value = next
            server?.notifyCharacteristicChanged(dev, ch, false) == true
        }
        if (!ok) {
            log("❌ notifyCharacteristicChanged mislukt")
            sending = false
        }
    }

    fun close() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        server?.close()
        server = null
        clientReady = false; serverReady = false
        target = null; subscriber = null
        seen.clear()
        ui("Alles gesloten")
    }
}
