package nl.nise.lezyne

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Instellingen/diagnose: rechten geven, lamp zoeken, handmatig bedienen, log bekijken. */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightManager.init(this)

        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()
        fun card(color: String) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = 12 * density }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = card("#2A2A2A")
            setOnClickListener { action() }
        }
        fun buttonLp() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(4.dp(), 0, 4.dp(), 0) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
        }

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setLineSpacing(0f, 1.25f)
            background = card("#161616")
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
        }
        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp() })

        fun row(vararg buttons: Pair<String, () -> Unit>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in buttons) r.addView(button(label, action), buttonLp())
            root.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 8.dp() })
        }

        row("Aan" to { LightManager.setOn(true) }, "Volgende" to { LightManager.nextMode() }, "Uit" to { LightManager.setOn(false) })
        row("Status" to { LightManager.requestStatus() }, "Andere lamp zoeken" to { LightManager.findNewLamp() })

        val hexRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hexInput = EditText(this).apply {
            hint = "hex-commando"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = card("#161616")
            setPadding(16.dp(), 10.dp(), 16.dp(), 10.dp())
        }
        hexRow.addView(hexInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f).apply { setMargins(0, 0, 4.dp(), 0) })
        hexRow.addView(button("Stuur") {
            val bytes = parseHex(hexInput.text.toString())
            if (bytes == null) LightManager.log("Ongeldige hex") else LightManager.sendRaw(bytes)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(4.dp(), 0, 0, 0) })
        root.addView(hexRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp() })

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        }
        scroll = ScrollView(this).apply {
            background = card("#0E0E0E")
            addView(logView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        scope.launch { LightManager.state.collect { renderStatus(it) } }
        scope.launch {
            LightManager.log.collect { lines ->
                logView.text = lines.joinToString("\n")
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (LightManager.hasPermissions(this)) {
            LightManager.acquire(HOLD)
        } else {
            val perms = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(perms, 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (LightManager.hasPermissions(this)) {
            LightManager.log("Rechten verleend")
            LightManager.acquire(HOLD)
        } else {
            LightManager.log("❌ Rechten geweigerd – de extension kan de lamp niet vinden")
        }
    }

    override fun onStop() {
        LightManager.release(HOLD)
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun renderStatus(s: LightState) {
        val f = s.front
        val sb = StringBuilder("Lezyne Lights ${BuildConfig.VERSION_NAME}\n")
        sb.append("Verbinding: ${s.conn}")
        LightManager.savedAddress?.let { sb.append(" ($it)") }
        if (f != null) {
            sb.append("\n${Protocol.modelName(f.model)}: ${if (f.on) "AAN – ${f.modeName}" else "UIT"}, ${f.bars}/5 (${f.millivolt} mV)")
        }
        s.rear?.let { r ->
            sb.append("\nAchter ${Protocol.modelName(r.model)}: ${if (r.on) "AAN" else "UIT"}, ${r.bars}/5 (${r.millivolt} mV)")
        }
        statusView.text = sb.toString()
    }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.replace(" ", "").replace("0x", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) { null }
    }

    companion object {
        private const val HOLD = "scherm"
    }
}
