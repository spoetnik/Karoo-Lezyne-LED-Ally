package nl.nise.lezyne

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Lezyne Smart Connect protocol. Zie PROTOCOL.md. */
object Protocol {
    val SERVICE: UUID = UUID.fromString("29580001-d281-83bd-bc46-ad9b5bb78380")
    val RXTX: UUID = UUID.fromString("29580003-d281-83bd-bc46-ad9b5bb78380")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val GET_STATUS_FRONT: Byte = 0x00
    const val GET_STATUS_REAR: Byte = 0x01
    const val RUN_FRONT: Byte = 0x06
    const val NEXT_MODE_FRONT: Byte = 0x08
    const val FRONT_OFF: Byte = 0x0B

    private val MODES = arrayOf(
        "Femto", "Econ", "Enduro", "Blast", "Overdrive",
        "Flash1", "Flash2", "Flash3", "Flash4", "Flash5", "Flash6",
        "Pulse", "DayFlash1", "DayFlash2", "Off"
    )

    fun modelName(id: Int): String = when (id) {
        6 -> "KTV Front"
        7 -> "KTV Rear"
        8 -> "Connect Drive"
        9 -> "Super Drive"
        10 -> "Mega Drive"
        11 -> "Super Drive Y17"
        else -> "onbekend($id)"
    }

    fun isFrontModel(id: Int) = id in setOf(6, 8, 9, 10, 11)

    fun modeName(b: Byte): String = MODES.getOrElse(b.toInt() and 0xFF) { "?${b.toInt() and 0xFF}" }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    /** Advertising: manufacturer data → model, serial, flags. */
    data class Advert(val model: Int, val serial: Long, val flags: Int) {
        val isOn get() = flags and 0x08 == 0
    }

    fun parseAdvert(mfg: ByteArray?): Advert? {
        if (mfg == null || mfg.size < 6) return null
        val b = ByteBuffer.wrap(mfg).order(ByteOrder.LITTLE_ENDIAN)
        val model = (b.get(0).toInt() shr 4) and 0x0F
        val serial = b.getInt(1).toLong() and 0xFFFFFFFFL
        val flags = b.get(5).toInt() and 0xFF
        return Advert(model, serial, flags)
    }

    /** Statuspakket (0x00 front / 0x01 rear) leesbaar maken. */
    fun describe(data: ByteArray): String {
        if (data.isEmpty()) return "leeg"
        val cmd = data[0].toInt() and 0xFF
        return when (cmd) {
            0x00, 0x01 -> describeStatus(data)
            0x05 -> "Dead mode (batterij bijna leeg)"
            0x06 -> "Batterij leeg"
            0x07 -> "Oververhit"
            0x08 -> "Niet meer oververhit"
            else -> "Onbekend bericht 0x%02x".format(cmd)
        }
    }

    private fun describeStatus(data: ByteArray): String {
        val which = if (data[0].toInt() == 0) "VOOR" else "ACHTER"
        if (data.size < 20) return "$which status (kort: ${data.size} bytes)"
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val mv = b.getShort(1).toInt() and 0xFFFF
        if (mv == 0xFFFF) return "$which: geen data"
        val flags = b.get(5).toInt() and 0xFF
        val on = flags and 0x08 == 0
        val comboIdx = b.get(3).toInt()
        val singleIdx = b.get(4).toInt()
        val inCombo = flags and 0x01 == 0
        val modes = if (inCombo) (7..10).map { modeName(b.get(it)) } else (12..15).map { modeName(b.get(it)) }
        val idx = if (inCombo) comboIdx else singleIdx
        val count = (if (inCombo) b.get(6) else b.get(11)).toInt().coerceIn(1, 4)
        val sb = StringBuilder()
        sb.append("$which ${modelName(b.get(19).toInt())} fw ${b.get(17)}.${b.get(18)}\n")
        sb.append("  ${if (on) "AAN" else "UIT"}, batterij $mv mV")
        if (flags and 0x40 != 0) sb.append(" (2 accu's)")
        sb.append("\n  modus #$idx van ${modes.take(count)}")
        sb.append(if (inCombo) " (combo)" else " (solo)")
        if (flags and 0x02 != 0) sb.append(", gekoppeld")
        if (flags and 0x04 != 0) sb.append(", peer verbonden")
        if (flags and 0x20 != 0) sb.append(", OVERVERHIT")
        return sb.toString()
    }
}
