package nl.nise.lezyne

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var link: LezyneLink
    private val logFile by lazy { File(getExternalFilesDir(null), "lezyne-log.txt") }
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(8, 8, 8, 8)
        }

        fun row(vararg buttons: Pair<String, () -> Unit>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in buttons) {
                r.addView(Button(this).apply {
                    text = label
                    textSize = 15f
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            root.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        row("Zoek & verbind" to { link.scan() }, "Status" to { link.send(byteArrayOf(Protocol.GET_STATUS_FRONT)) })
        row("Aan (06)" to { link.send(byteArrayOf(Protocol.RUN_FRONT)) },
            "Volgende (08)" to { link.send(byteArrayOf(Protocol.NEXT_MODE_FRONT)) },
            "Uit (0B)" to { link.send(byteArrayOf(Protocol.FRONT_OFF)) })

        val hexRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hexInput = EditText(this).apply {
            hint = "hex, bv. 01"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        hexRow.addView(hexInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
        hexRow.addView(Button(this).apply {
            text = "Stuur"
            setOnClickListener {
                val bytes = parseHex(hexInput.text.toString())
                if (bytes == null) log("Ongeldige hex") else link.send(bytes)
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        hexRow.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { link.close() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(hexRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)

        link = LezyneLink(this) { log(it) }
        log("Lezyne test ${BuildConfigInfo.version(this)} – Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Log wordt ook opgeslagen in ${logFile.absolutePath}")
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            log("Rechten OK")
            link.openServer()
        } else {
            log("Rechten aanvragen: ${missing.joinToString()}")
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        log(if (ok) "Rechten verleend" else "❌ Rechten geweigerd – scannen werkt niet")
        if (ok) link.openServer()
    }

    override fun onDestroy() {
        link.close()
        super.onDestroy()
    }

    private fun log(msg: String) {
        val line = "${time.format(Date())} $msg"
        Log.i("LEZYNE", line)
        logView.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        try { logFile.appendText(line + "\n") } catch (_: Exception) {}
    }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.replace(" ", "").replace("0x", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) { null }
    }
}

private object BuildConfigInfo {
    fun version(a: Activity): String = try {
        a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
