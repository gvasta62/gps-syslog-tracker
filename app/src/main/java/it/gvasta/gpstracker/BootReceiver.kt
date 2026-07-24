package it.gvasta.gpstracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Riceve l'evento di avvio del telefono (BOOT_COMPLETED) e riavvia il servizio
 * se l'utente lo aveva lasciato attivo. Reagisce anche all'aggiornamento
 * dell'app (MY_PACKAGE_REPLACED).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Prefs.isEnabled(context)) {
            FileLogger.log(context, "BootReceiver", "Riavvio servizio dopo: ${intent?.action}")
            LocationService.start(context)
        }
    }
}
