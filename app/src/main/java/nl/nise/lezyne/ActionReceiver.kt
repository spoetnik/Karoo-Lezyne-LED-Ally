package nl.nise.lezyne

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Ontvangt tikken op het dataveld (via PendingIntent vanuit de Glance-view). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LightManager.init(context)
        when (intent.action) {
            ACTION_TOGGLE -> LightManager.toggle()
            ACTION_NEXT -> LightManager.nextMode()
            ACTION_CONNECT -> LightManager.reconnectNow()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "nl.nise.lezyne.TOGGLE"
        const val ACTION_NEXT = "nl.nise.lezyne.NEXT"
        const val ACTION_CONNECT = "nl.nise.lezyne.CONNECT"

        fun intent(context: Context, action: String): Intent = Intent(action).apply {
            component = ComponentName(context.packageName, ActionReceiver::class.java.name)
        }
    }
}
