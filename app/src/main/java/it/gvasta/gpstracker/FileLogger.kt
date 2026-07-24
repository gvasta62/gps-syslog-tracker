package it.gvasta.gpstracker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scrive i log su file, dentro la cartella privata dell'app
 * (Android/data/it.gvasta.gpstracker/files/logs/). Serve per capire cosa
 * e' successo anche quando l'app girava in background o e' andata in crash.
 */
object FileLogger {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX = 1_000_000L // 1 MB: oltre questa soglia il log viene ruotato.

    private fun logDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "logs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun logFile(ctx: Context): File = File(logDir(ctx), "app.log")

    /** Aggiunge una riga al log principale. Sincronizzato = sicuro tra thread. */
    @Synchronized
    fun log(ctx: Context, tag: String, msg: String) {
        try {
            val f = logFile(ctx)
            // Rotazione: se il file e' troppo grande, lo rinomina in app.log.1.
            if (f.exists() && f.length() > MAX) {
                val bak = File(logDir(ctx), "app.log.1")
                if (bak.exists()) bak.delete()
                f.renameTo(bak)
            }
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
        } catch (_: Exception) {
            // Non far mai fallire il logging.
        }
        android.util.Log.i(tag, msg)
    }

    /** Scrive un file dedicato con lo stack trace del crash. */
    @Synchronized
    fun logCrash(ctx: Context, text: String) {
        try {
            val f = File(logDir(ctx), "crash_${System.currentTimeMillis()}.log")
            f.writeText(text)
            logFile(ctx).appendText("${fmt.format(Date())} [CRASH] salvato in ${f.name}\n$text\n")
        } catch (_: Exception) {
        }
    }

    /** Restituisce la parte finale del log (per mostrarla nell'app). */
    fun readTail(ctx: Context, maxChars: Int = 6000): String {
        return try {
            val f = logFile(ctx)
            if (!f.exists()) "(nessun log ancora)"
            else {
                val s = f.readText()
                if (s.length <= maxChars) s else s.substring(s.length - maxChars)
            }
        } catch (e: Exception) {
            "(errore lettura log: ${e.message})"
        }
    }
}
