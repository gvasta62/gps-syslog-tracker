package it.gvasta.gpstracker

import android.location.Location
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Costruisce frasi NMEA standard ($GPRMC e $GPGGA) a partire dalla posizione
 * fornita da Android. Le frasi sono nel formato tipico dei ricevitori GPS,
 * con checksum finale, pronte per essere inviate al server syslog.
 */
object NmeaBuilder {

    /**
     * Dati di un singolo satellite "in vista", usati per la frase $GPGSV.
     * @param prn identificativo del satellite (SVID).
     * @param elevation elevazione in gradi (0-90).
     * @param azimuth azimut in gradi (0-359).
     * @param snr rapporto segnale/rumore in dB-Hz (0 = non tracciato).
     */
    data class Sat(val prn: Int, val elevation: Int, val azimuth: Int, val snr: Int)

    /**
     * @param loc posizione da convertire in NMEA.
     * @param satellites numero di satelliti usati per il fix (da GnssStatus).
     *        Se <= 0, si prova a leggerlo dagli "extras" della posizione.
     */
    fun build(loc: Location, satellites: Int): List<String> {
        val utc = TimeZone.getTimeZone("UTC")
        val timeFmt = SimpleDateFormat("HHmmss.SS", Locale.US).apply { timeZone = utc }
        val dateFmt = SimpleDateFormat("ddMMyy", Locale.US).apply { timeZone = utc }

        val t = loc.time // istante del fix (millisecondi dal 1970, UTC)
        val timeStr = timeFmt.format(t)
        val dateStr = dateFmt.format(t)

        val lat = toNmeaLat(loc.latitude)
        val latHemi = if (loc.latitude >= 0) "N" else "S"
        val lon = toNmeaLon(loc.longitude)
        val lonHemi = if (loc.longitude >= 0) "E" else "W"

        // Velocita' in nodi (Android la fornisce in m/s): 1 m/s = 1.943844 nodi.
        val speedKnots = if (loc.hasSpeed()) loc.speed * 1.943844f else 0f
        val course = if (loc.hasBearing()) loc.bearing else 0f
        val alt = if (loc.hasAltitude()) loc.altitude else 0.0
        // Preferisci il conteggio reale da GnssStatus; altrimenti prova gli extras.
        val sats = if (satellites > 0) satellites else (loc.extras?.getInt("satellites", 0) ?: 0)
        // HDOP non e' fornito direttamente: lo stimiamo dall'accuratezza.
        val hdop = if (loc.hasAccuracy()) (loc.accuracy / 5.0).coerceIn(0.5, 99.9) else 1.0

        // $GPRMC: posizione minima raccomandata (ora, stato, lat/lon, velocita', data).
        val rmcBody = String.format(
            Locale.US,
            "GPRMC,%s,A,%s,%s,%s,%s,%.1f,%.1f,%s,,",
            timeStr, lat, latHemi, lon, lonHemi, speedKnots, course, dateStr
        )
        val rmc = "\$$rmcBody*${checksum(rmcBody)}"

        // $GPGGA: dati di fix (ora, lat/lon, qualita', n. satelliti, HDOP, quota).
        val ggaBody = String.format(
            Locale.US,
            "GPGGA,%s,%s,%s,%s,%s,1,%02d,%.1f,%.1f,M,0.0,M,,",
            timeStr, lat, latHemi, lon, lonHemi, sats, hdop, alt
        )
        val gga = "\$$ggaBody*${checksum(ggaBody)}"

        return listOf(rmc, gga)
    }

    /**
     * Costruisce una o piu' frasi $GPGSV (satelliti in vista). Ogni frase
     * contiene fino a 4 satelliti; se ce ne sono di piu' si generano piu' frasi.
     * Struttura: $GPGSV,n_frasi_totali,n_frase,n_sat_in_vista,{prn,elev,azim,snr}...*CS
     */
    fun buildGsv(sats: List<Sat>): List<String> {
        if (sats.isEmpty()) return emptyList()
        val totalInView = sats.size
        val totalMsgs = (totalInView + 3) / 4 // arrotondamento per eccesso a gruppi di 4
        val out = ArrayList<String>(totalMsgs)
        for (msg in 0 until totalMsgs) {
            val sb = StringBuilder()
            sb.append(String.format(Locale.US, "GPGSV,%d,%d,%02d", totalMsgs, msg + 1, totalInView))
            for (k in 0 until 4) {
                val idx = msg * 4 + k
                if (idx >= totalInView) break // l'ultima frase puo' avere meno di 4 satelliti
                val s = sats[idx]
                // Il campo SNR resta vuoto se il satellite non e' tracciato (snr 0).
                val snrStr = if (s.snr > 0) String.format(Locale.US, "%02d", s.snr) else ""
                sb.append(String.format(Locale.US, ",%02d,%02d,%03d,%s", s.prn, s.elevation, s.azimuth, snrStr))
            }
            val body = sb.toString()
            out.add("\$$body*${checksum(body)}")
        }
        return out
    }

    // Latitudine in formato NMEA: gradi (2 cifre) + minuti (mm.mmmm).
    private fun toNmeaLat(lat: Double): String {
        val a = abs(lat)
        val deg = a.toInt()
        val min = (a - deg) * 60.0
        return String.format(Locale.US, "%02d%07.4f", deg, min)
    }

    // Longitudine in formato NMEA: gradi (3 cifre) + minuti (mm.mmmm).
    private fun toNmeaLon(lon: Double): String {
        val a = abs(lon)
        val deg = a.toInt()
        val min = (a - deg) * 60.0
        return String.format(Locale.US, "%03d%07.4f", deg, min)
    }

    // Checksum NMEA: XOR di tutti i caratteri tra "$" e "*", in esadecimale.
    private fun checksum(s: String): String {
        var c = 0
        for (ch in s) c = c xor ch.code
        return String.format("%02X", c and 0xFF)
    }
}
