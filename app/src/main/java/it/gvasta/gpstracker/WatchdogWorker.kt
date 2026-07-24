package it.gvasta.gpstracker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * "Cane da guardia": WorkManager lo esegue periodicamente (ogni ~15 minuti,
 * minimo consentito dal sistema). Se il servizio dovrebbe essere attivo ma per
 * qualche motivo non lo e', lo fa ripartire. E' l'ultima rete di sicurezza.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (Prefs.isEnabled(ctx) && !LocationService.isRunning) {
            FileLogger.log(ctx, "Watchdog", "Servizio abilitato ma non attivo: riavvio")
            LocationService.start(ctx)
        }
        return Result.success()
    }
}
