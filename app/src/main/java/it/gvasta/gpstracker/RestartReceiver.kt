package it.gvasta.gpstracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Riavvia il servizio dopo un crash (programmato da CrashHandler) o dopo che
 * l'utente ha rimosso l'app dai recenti (programmato da onTaskRemoved).
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Prefs.isEnabled(context)) {
            FileLogger.log(context, "RestartReceiver", "Riavvio del servizio")
            LocationService.start(context)
        }
    }
}
