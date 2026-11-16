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
    const val RUN_REAR: Byte = 0x07
    const val NEXT_MODE_FRONT: Byte = 0x08
    const val REAR_OFF: Byte = 0x0A
    const val FRONT_OFF: Byte = 0x0B

    /** Commando's die we nooit via de UI willen versturen (DFU, factory reset). */
    val FORBIDDEN: Set<Byte> = setOf(0x10, 0x11, 0x13)

    private val MODES = arrayOf(
        "Femto", "Econ", "Enduro", "Blast", "Overdrive",
        "Flash 1", "Flash 2", "Flash 3", "Flash 4", "Flash 5", "Flash 6",
        "Pulse", "Day Flash 1", "Day Flash 2", "Off"
    )

    fun modelName(id: Int): String = when (id) {
        6 -> "KTV Front"
        7 -> "KTV Rear"
        8 -> "Connect Drive"
        9 -> "Super Drive"
        10 -> "Mega Drive"
        11 -> "Super Drive"
        else -> "Lezyne ($id)"
    }

    fun isFrontModel(id: Int) = id in setOf(6, 8, 9, 10, 11)

    fun modeName(b: Byte): String = MODES.getOrElse(b.toInt() and 0xFF) { "Modus ${b.toInt() and 0xFF}" }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    /** Batterijbalkjes 0..5 volgens dezelfde drempels als LED Ally (spanning in mV). */
    fun batteryBars(model: Int, mv: Int): Int {
        val t = if (model == 6) intArrayOf(3374, 3539, 3607, 3644, 3667)
        else intArrayOf(3000, 3200, 3500, 3800, 4000)
        for (i in 4 downTo 0) if (mv > t[i]) return i + 1
        return 0
    }

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

    /** Toestand van één lamp, uit een statuspakket. */
    data class Lamp(
        val front: Boolean,
        val model: Int,
        val on: Boolean,
        val millivolt: Int,
        val bars: Int,
        val modeIndex: Int,
        val modes: List<String>,
        val inCombo: Boolean,
        val peerConnected: Boolean,
        val overheat: Boolean,
        val deadMode: Boolean,
        val fw: String,
    ) {
        val modeName: String get() = modes.getOrElse(modeIndex) { "?" }
    }

    /** Statuspakket (0x00 voor / 0x01 achter) → Lamp, of null als het geen (bruikbare) status is. */
    fun parseStatus(data: ByteArray): Lamp? {
        if (data.size < 20) return null
        val cmd = data[0].toInt()
        if (cmd != 0 && cmd != 1) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val raw = b.getShort(1).toInt() and 0xFFFF
        if (raw == 0xFFFF) return null
        val flags = b.get(5).toInt() and 0xFF
        val inCombo = flags and 0x01 == 0
        val count = (if (inCombo) b.get(6) else b.get(11)).toInt().let { if (it < 1 || it > 4) 4 else it }
        val modes = (if (inCombo) 7..10 else 12..15).map { modeName(b.get(it)) }.take(count)
        val idx = (if (inCombo) b.get(3) else b.get(4)).toInt()
        val model = b.get(19).toInt() and 0xFF
        // Met twee accu's: hoog byte ×100 = hoofdaccu (zelfde logica als LED Ally)
        val mv = if (flags and 0x40 != 0) ((raw shr 8) and 0xFF) * 100 else raw
        return Lamp(
            front = cmd == 0,
            model = model,
            on = flags and 0x08 == 0,
            millivolt = mv,
            bars = batteryBars(model, mv),
            modeIndex = idx,
            modes = modes,
            inCombo = inCombo,
            peerConnected = flags and 0x04 != 0,
            overheat = flags and 0x20 != 0,
            deadMode = flags and 0x10 != 0,
            fw = "${b.get(17)}.${b.get(18)}",
        )
    }

    /** Leesbare samenvatting voor het log. */
    fun describe(data: ByteArray): String {
        if (data.isEmpty()) return "leeg"
        parseStatus(data)?.let { l ->
            return "${if (l.front) "VOOR" else "ACHTER"} ${modelName(l.model)} fw ${l.fw}: " +
                "${if (l.on) "AAN" else "UIT"}, ${l.millivolt} mV (${l.bars}/5), " +
                "modus ${l.modeName} (#${l.modeIndex} van ${l.modes})" +
                (if (l.inCombo) ", combo" else "") +
                (if (l.peerConnected) ", peer verbonden" else "") +
                (if (l.overheat) ", OVERVERHIT" else "")
        }
        return when (data[0].toInt() and 0xFF) {
            0x00, 0x01 -> "Status zonder data"
            0x05 -> "Dead mode (batterij bijna leeg)"
            0x06 -> "Batterij leeg"
            0x07 -> "Oververhit"
            0x08 -> "Niet meer oververhit"
            else -> "Onbekend bericht 0x%02x".format(data[0].toInt() and 0xFF)
        }
    }
}
