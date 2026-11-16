package nl.nise.lezyne

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Dataveld: links aan/uit + batterij, rechts huidige modus (tik = volgende).
 * Houdt de verbinding open zolang het veld zichtbaar is.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class LightDataType(extension: String) : DataTypeImpl(extension, "light") {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        LightManager.init(context)
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val toggle = ActionReceiver.intent(context, ActionReceiver.ACTION_TOGGLE)
        val next = ActionReceiver.intent(context, ActionReceiver.ACTION_NEXT)
        val connect = ActionReceiver.intent(context, ActionReceiver.ACTION_CONNECT)
        val big = (config.textSize * 0.6f).coerceIn(16f, 26f).sp
        val small = (config.textSize * 0.3f).coerceIn(10f, 16f).sp

        val tag = "veld-" + UUID.randomUUID().toString().take(8)
        if (!config.preview) LightManager.acquire(tag)

        val job = CoroutineScope(Dispatchers.IO).launch {
            val source = if (config.preview) flowOf(PREVIEW) else LightManager.state
            source.conflate().collect { state ->
                try {
                    val result = glance.compose(context, DpSize.Unspecified) {
                        LightView(state, toggle, next, connect, big, small)
                    }
                    emitter.updateView(result.remoteViews)
                } catch (e: Exception) {
                    LightManager.log("Dataveld tekenen mislukt: ${e.message}")
                }
                // ViewEmitter negeert updates binnen 0,9 s; wacht zodat de laatste stand altijd doorkomt
                delay(1000)
            }
        }
        emitter.setCancellable {
            job.cancel()
            if (!config.preview) LightManager.release(tag)
        }
    }

    companion object {
        private val PREVIEW = LightState(
            conn = Conn.CONNECTED,
            front = Protocol.Lamp(
                front = true, model = 9, on = true, millivolt = 3900, bars = 4, modeIndex = 1,
                modes = listOf("Blast", "Flash 1", "Day Flash 1"), inCombo = false,
                peerConnected = false, overheat = false, deadMode = false, fw = "1.0",
            ),
        )
    }
}

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Grey = Color(0xFF8A8A8A)
private val Green = Color(0xFF4CAF50)
private val Amber = Color(0xFFFFC107)
private val Red = Color(0xFFF44336)
private val Divider = Color(0xFF333333)

@Composable
private fun LightView(
    state: LightState,
    toggle: Intent,
    next: Intent,
    connect: Intent,
    big: TextUnit,
    small: TextUnit,
) {
    val f = state.front
    if (state.conn != Conn.CONNECTED || f == null) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(Black).clickable(actionSendBroadcast(connect)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label("LEZYNE", Grey, small)
            Value(connText(state.conn), Grey, small * 1.3f)
        }
        return
    }

    Row(modifier = GlanceModifier.fillMaxSize().background(Black)) {
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionSendBroadcast(toggle)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Value(if (f.on) "AAN" else "UIT", if (f.on) Green else Grey, big)
            Battery(f.bars)
            state.rear?.let { r -> Battery(r.bars) }
        }
        Box(modifier = GlanceModifier.width(1.dp).fillMaxHeight().background(Divider)) {}
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 2.dp)
                .clickable(actionSendBroadcast(next)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Value(if (f.on) f.modeName else "—", if (f.on) White else Grey, big * 0.65f)
            Label(if (f.overheat) "OVERVERHIT" else if (f.on) "volgende ›" else "tik = aan", if (f.overheat) Red else Grey, small)
        }
    }
}

private fun connText(c: Conn) = when (c) {
    Conn.SCANNING -> "zoeken…"
    Conn.CONNECTING -> "verbinden…"
    Conn.CONNECTED -> "status…"
    Conn.NOT_FOUND -> "niet gevonden"
    Conn.NO_PERMISSION -> "open app"
    Conn.BT_OFF -> "BT uit"
    Conn.IDLE -> "tik = verbind"
}

private fun batteryColor(bars: Int) = when {
    bars <= 1 -> Red
    bars == 2 -> Amber
    else -> Green
}

@Composable
private fun Battery(bars: Int) {
    val lit = bars.coerceIn(0, 5)
    val color = batteryColor(lit)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        for (i in 0 until 5) {
            if (i > 0) Box(modifier = GlanceModifier.width(2.dp).height(1.dp)) {}
            Box(modifier = GlanceModifier.width(8.dp).height(10.dp).background(if (i < lit) color else Divider)) {}
        }
    }
}

@Composable
private fun Value(text: String, color: Color, size: TextUnit) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
    )
}

@Composable
private fun Label(text: String, color: Color, size: TextUnit) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(color = ColorProvider(color), fontSize = size, textAlign = TextAlign.Center),
    )
}
