package nl.nise.lezyne

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Zonhoogte (graden boven de horizon) volgens de NOAA-benadering. Nauwkeurig tot ~0,1°. */
object Sun {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    fun elevation(lat: Double, lng: Double, epochMillis: Long): Double {
        val jd = epochMillis / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0

        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) +
            sin(rad(3 * m)) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = asin(sin(rad(eps)) * sin(rad(lambda)))

        val y = tan(rad(eps / 2)).let { it * it }
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) +
                4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m))
        )

        val utcMinutes = (epochMillis.mod(86_400_000L)) / 60_000.0
        val trueSolar = (utcMinutes + eqTime + 4 * lng).mod(1440.0)
        val hourAngle = trueSolar / 4 - 180

        val cosZenith = (sin(rad(lat)) * sin(decl) + cos(rad(lat)) * cos(decl) * cos(rad(hourAngle)))
            .coerceIn(-1.0, 1.0)
        return 90 - deg(acos(cosZenith))
    }
}

/**
 * Beslist wanneer de lamp automatisch aan of uit moet. Alleen bij een overgang licht↔donker,
 * zodat een handmatige keuze van de rijder niet steeds wordt teruggedraaid.
 *
 * Hysterese: donker onder [onBelow]°, weer licht boven [offAbove]°.
 */
class AutoLightLogic(
    private val onBelow: Double = 3.0,
    private val offAbove: Double = 5.0,
) {
    enum class Action { TURN_ON, TURN_OFF }

    var dark: Boolean? = null
        private set

    fun reset() { dark = null }

    /** @return actie bij een overgang, of null. Eerste meting bij daglicht doet niets ("overdag uit laten"). */
    fun evaluate(elevation: Double): Action? {
        val prev = dark
        val nowDark = if (prev == true) elevation < offAbove else elevation < onBelow
        dark = nowDark
        return when {
            prev == null -> if (nowDark) Action.TURN_ON else null
            prev != nowDark -> if (nowDark) Action.TURN_ON else Action.TURN_OFF
            else -> null
        }
    }
}
