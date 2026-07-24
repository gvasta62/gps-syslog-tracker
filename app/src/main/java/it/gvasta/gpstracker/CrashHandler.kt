package it.gvasta.gpstracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Intercetta qualsiasi eccezione non gestita (crash) dell'app:
 *  1) salva lo stack trace su file (per poterlo consultare dopo);
 *  2) programma il RIAVVIO del servizio dopo 2 secondi tramite AlarmManager;
 *  3) lascia terminare il processo in modo pulito.
 *
 * Insieme a START_STICKY, al BootReceiver e al watchdog WorkManager, garantisce
 * che il servizio torni sempre attivo.
 */
class CrashHandler(private val ctx: Context) : Thread.UncaughtExceptionHandler {

    // Gestore precedente (di sistema): lo richiamiamo alla fine.
    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            FileLogger.logCrash(ctx, "Thread: ${t.name}\n$sw")
            scheduleRestart(ctx)
        } catch (_: Throwable) {
            // In caso di crash mai bloccarsi qui.
        }
        // Passa il controllo al gestore di sistema (chiude il processo);
        // se non esiste, terminiamo noi.
        val prev = previous
        if (prev != null) {
            prev.uncaughtException(t, e)
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(2)
        }
    }

    companion object {
        /** Installa questo gestore come gestore globale dei crash. */
        fun install(ctx: Context) {
            val app = ctx.applicationContext
            // Evita doppie installazioni.
            if (Thread.getDefaultUncaughtExceptionHandler() !is CrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(CrashHandler(app))
            }
        }

        /** Programma il riavvio del servizio tra 2 secondi. */
        fun scheduleRestart(ctx: Context) {
            if (!Prefs.isEnabled(ctx)) return
            val intent = Intent(ctx, RestartReceiver::class.java)
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(ctx, 1001, intent, flags)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = SystemClock.elapsedRealtime() + 2000
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }
}
