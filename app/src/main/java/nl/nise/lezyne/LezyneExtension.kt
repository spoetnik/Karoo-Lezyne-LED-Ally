package nl.nise.lezyne

import android.os.Handler
import android.os.Looper
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Karoo-extension:
 *  - verbinding met de lamp tijdens een rit (en zolang het dataveld zichtbaar is)
 *  - automatisch aan bij schemer, weer uit bij daglicht, uit bij het afsluiten van de rit
 *  - meldingen bij bijna lege batterij of oververhitting
 */
class LezyneExtension : KarooExtension("lezyne", BuildConfig.VERSION_NAME) {

    private lateinit var karooSystem: KarooSystemService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private val auto = AutoLightLogic()
    private var riding = false
    private var lat: Double? = null
    private var lng: Double? = null
    private var consumersRegistered = false
    private var firstRideState = true
    /** Eerste zonmeting na een herstart midden in een rit: alleen de stand onthouden, niets schakelen. */
    private var silentNextEvaluation = false

    override val types by lazy { listOf(LightDataType(extension)) }

    override fun onCreate() {
        super.onCreate()
        LightManager.init(this)
        LightManager.log("Extension gestart")
        karooSystem = KarooSystemService(this)
        karooSystem.connect { connected ->
            if (connected) main.post { onKarooConnected() }
        }

        // Zonnestand elke minuut opnieuw bekijken tijdens een rit
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (riding) evaluateSun()
            }
        }
        scope.launch { LightManager.events.collect { showAlert(it) } }
    }

    private fun onKarooConnected() {
        karooSystem.dispatch(RequestBluetooth(extension))
        if (consumersRegistered) return
        consumersRegistered = true
        karooSystem.addConsumer<RideState> { state -> main.post { onRideState(state) } }
        karooSystem.addConsumer<OnLocationChanged> { loc ->
            main.post {
                val first = lat == null
                lat = loc.lat
                lng = loc.lng
                if (first && riding) evaluateSun()
            }
        }
    }

    private fun onRideState(state: RideState) {
        val active = state is RideState.Recording || state is RideState.Paused
        val resumed = firstRideState && active
        firstRideState = false
        if (active && !riding) {
            riding = true
            auto.reset()
            silentNextEvaluation = resumed
            LightManager.log(if (resumed) "Extension herstart tijdens rit" else "Rit gestart")
            LightManager.acquire(HOLD_RIDE)
            evaluateSun()
        } else if (!active && riding) {
            riding = false
            auto.reset()
            LightManager.log("Rit afgesloten – lamp uit")
            LightManager.setOn(false)
            LightManager.release(HOLD_RIDE)
        }
    }

    private fun evaluateSun() {
        val la = lat ?: return
        val lo = lng ?: return
        val elev = Sun.elevation(la, lo, System.currentTimeMillis())
        val action = auto.evaluate(elev)
        if (silentNextEvaluation) {
            silentNextEvaluation = false
            LightManager.log("Zon %.1f° – stand onthouden, niets geschakeld".format(elev))
            return
        }
        when (action) {
            AutoLightLogic.Action.TURN_ON -> {
                LightManager.log("Schemer (zon %.1f°) – lamp aan".format(elev))
                LightManager.setOn(true)
            }
            AutoLightLogic.Action.TURN_OFF -> {
                LightManager.log("Daglicht (zon %.1f°) – lamp uit".format(elev))
                LightManager.setOn(false)
            }
            null -> {}
        }
    }

    private fun showAlert(e: LightEvent) {
        val (id, title, detail, color) = when (e) {
            is LightEvent.LowBattery -> Quad(
                "lezyne-battery", "${which(e.front)}: batterij bijna leeg", "Nog ${e.bars}/5", R.color.alert_warning
            )
            is LightEvent.BatteryDead -> Quad(
                "lezyne-dead", "${which(e.front)}: batterij leeg", "De lamp schakelt naar noodstand", R.color.alert_critical
            )
            is LightEvent.Overheat -> Quad(
                "lezyne-heat", "${which(e.front)}: oververhit", "De lamp dimt zichzelf", R.color.alert_warning
            )
        }
        LightManager.log("Melding: $title")
        karooSystem.dispatch(
            InRideAlert(
                id = id,
                icon = R.drawable.ic_lezyne,
                title = title,
                detail = detail,
                autoDismissMs = 10_000L,
                backgroundColor = color,
                textColor = R.color.alert_text,
            )
        )
    }

    private fun which(front: Boolean) = if (front) "Voorlicht" else "Achterlicht"

    private data class Quad(val id: String, val title: String, val detail: String, val color: Int)

    override fun onDestroy() {
        LightManager.log("Extension gestopt")
        LightManager.release(HOLD_RIDE)
        scope.cancel()
        try {
            karooSystem.dispatch(ReleaseBluetooth(extension))
            karooSystem.disconnect()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val HOLD_RIDE = "rit"
    }
}
