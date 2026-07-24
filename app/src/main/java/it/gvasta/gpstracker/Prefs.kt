package it.gvasta.gpstracker

import android.content.Context

/**
 * Gestisce i parametri configurabili, salvandoli nella memoria del telefono
 * (SharedPreferences). Sono gli unici valori che l'utente puo' cambiare.
 */
object Prefs {
    private const val FILE = "gps_syslog_prefs"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Indirizzo del server syslog: puo' essere un IP o un nome/URL.
    fun getHost(ctx: Context): String = sp(ctx).getString("host", "192.168.1.100") ?: "192.168.1.100"
    fun setHost(ctx: Context, v: String) = sp(ctx).edit().putString("host", v).apply()

    // Nome host del device: inviato come campo "hostname" del syslog, cioe'
    // all'inizio della riga, subito prima della frase NMEA. Se vuoto, in fase
    // di invio si usa il modello del telefono (Build.MODEL).
    fun getHostname(ctx: Context): String = sp(ctx).getString("hostname", "") ?: ""
    fun setHostname(ctx: Context, v: String) = sp(ctx).edit().putString("hostname", v).apply()

    // Porta del server (514 e' la porta syslog standard).
    fun getPort(ctx: Context): Int = sp(ctx).getInt("port", 514)
    fun setPort(ctx: Context, v: Int) = sp(ctx).edit().putInt("port", v).apply()

    // Protocollo di trasporto: "UDP" oppure "TCP".
    fun getProto(ctx: Context): String = sp(ctx).getString("proto", "UDP") ?: "UDP"
    fun setProto(ctx: Context, v: String) = sp(ctx).edit().putString("proto", v).apply()

    // Ogni quanti secondi inviare la posizione.
    fun getIntervalSec(ctx: Context): Int = sp(ctx).getInt("interval", 10)
    fun setIntervalSec(ctx: Context, v: Int) = sp(ctx).edit().putInt("interval", v).apply()

    // Se inviare anche le frasi $GPGSV (dettaglio dei singoli satelliti).
    fun isGsvEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("gsv", true)
    fun setGsvEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("gsv", v).apply()

    // "true" se il servizio deve essere attivo (usato da watchdog e boot).
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()
}
