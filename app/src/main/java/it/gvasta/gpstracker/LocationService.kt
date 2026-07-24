package it.gvasta.gpstracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Cuore dell'app: un servizio "in primo piano" (foreground) che resta sempre
 * attivo. Ogni N secondi legge la posizione, costruisce le frasi NMEA e le
 * invia al server syslog. Mostra una notifica permanente (richiesta da Android
 * per i servizi di localizzazione) e tiene un WakeLock per non fermarsi durante
 * il risparmio energetico (Doze).
 */
class LocationService : Service() {

    companion object {
        const val CHANNEL_ID = "gps_syslog_channel"
        const val NOTIF_ID = 42
        const val WORK_NAME = "gps_watchdog"

        // Indica se il servizio e' attualmente attivo (usato dal watchdog).
        @Volatile
        var isRunning = false

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationService::class.java))
        }

        /** Programma il watchdog periodico (controllo ogni 15 minuti). */
        fun scheduleWatchdog(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req
            )
        }
    }

    private lateinit var lm: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // Ultima posizione ricevuta dai provider di localizzazione.
    @Volatile
    private var lastLocation: Location? = null
    // Numero di satelliti usati per il fix, aggiornato da GnssStatus.
    @Volatile
    private var satellitesInFix: Int = 0
    // Dettaglio dei satelliti in vista (per la frase $GPGSV).
    @Volatile
    private var satsInView: List<NmeaBuilder.Sat> = emptyList()
    private var lastSentInfo: String = "in attesa del primo invio..."

    // Ascolta lo stato dei satelliti GNSS: conta quelli nel fix e cattura il
    // dettaglio (PRN, elevazione, azimut, SNR) di tutti quelli in vista.
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val list = ArrayList<NmeaBuilder.Sat>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
                list.add(
                    NmeaBuilder.Sat(
                        prn = status.getSvid(i),
                        elevation = f2i(status.getElevationDegrees(i)),
                        azimuth = f2i(status.getAzimuthDegrees(i)),
                        snr = f2i(status.getCn0DbHz(i))
                    )
                )
            }
            satellitesInFix = used
            satsInView = list
        }

        // Converte un Float in Int gestendo eventuali valori non validi (NaN).
        private fun f2i(v: Float): Int = if (v.isNaN()) 0 else v.toInt()
    }

    // Ascolta gli aggiornamenti di posizione e memorizza l'ultimo.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Richiesto da versioni vecchie di Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // Ciclo periodico: invia la posizione e si ri-programma per l'intervallo dato.
    private val sendRunnable = object : Runnable {
        override fun run() {
            try {
                doSend()
            } catch (e: Exception) {
                FileLogger.log(this@LocationService, "Service", "Errore nel ciclo di invio: ${e.message}")
            } finally {
                val intervalMs = Prefs.getIntervalSec(this@LocationService).coerceAtLeast(1) * 1000L
                handler?.postDelayed(this, intervalMs)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        isRunning = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()

        // WakeLock parziale: tiene sveglia la CPU (schermo spento) per continuare
        // a inviare anche in risparmio energetico.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpstracker:wakelock").apply {
            setReferenceCounted(false)
            acquire()
        }
        FileLogger.log(this, "Service", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.setEnabled(this, true)

        // Diventa servizio in primo piano: obbligatorio entro pochi secondi.
        if (!startForegroundSafely()) {
            // Manca il permesso di posizione: fermiamo e riproviamo col watchdog.
            FileLogger.log(this, "Service", "Impossibile avviare in primo piano: manca il permesso posizione")
            scheduleWatchdog(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // Avvia (una sola volta) il thread secondario: serve anche alla callback
        // GNSS registrata dentro requestLocationUpdates().
        if (thread == null) {
            thread = HandlerThread("gps-send").also { it.start() }
            handler = Handler(thread!!.looper)
        }

        requestLocationUpdates()

        handler?.removeCallbacks(sendRunnable)
        handler?.post(sendRunnable)

        scheduleWatchdog(this)
        FileLogger.log(this, "Service", "onStartCommand: servizio attivo")

        // START_STICKY: se il sistema uccide il servizio, prova a ricrearlo.
        return START_STICKY
    }

    private fun startForegroundSafely(): Boolean {
        return try {
            val notif = buildNotification(lastSentInfo)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (e: Exception) {
            FileLogger.log(this, "Service", "startForeground fallito: ${e.message}")
            false
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) {
            FileLogger.log(this, "Service", "Permesso posizione mancante: apri l'app e concedilo")
            return
        }
        try {
            val intervalMs = Prefs.getIntervalSec(this).coerceAtLeast(1) * 1000L
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, locationListener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs, 0f, locationListener)
            }
            // Registra il conteggio satelliti (usa l'handler del thread dedicato).
            try {
                lm.registerGnssStatusCallback(gnssCallback, handler)
            } catch (e: Exception) {
                FileLogger.log(this, "Service", "GnssStatus non disponibile: ${e.message}")
            }
        } catch (e: SecurityException) {
            FileLogger.log(this, "Service", "SecurityException sugli aggiornamenti: ${e.message}")
        }
    }

    private fun currentLocation(): Location? {
        lastLocation?.let { return it }
        if (!hasLocationPermission()) return null
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    private fun doSend() {
        val loc = currentLocation()
        if (loc == null) {
            FileLogger.log(this, "Service", "Nessuna posizione disponibile (nessun fix)")
            updateNotification("Nessun fix GPS ancora")
            return
        }
        if (Prefs.getMode(this) == "ha") {
            sendToHa(loc)
        } else {
            sendToSyslog(loc)
        }
    }

    private fun sendToSyslog(loc: Location) {
        // $GPRMC + $GPGGA, e (se abilitato) le frasi $GPGSV col dettaglio satelliti.
        val sentences = if (Prefs.isGsvEnabled(this)) {
            NmeaBuilder.build(loc, satellitesInFix) + NmeaBuilder.buildGsv(satsInView)
        } else {
            NmeaBuilder.build(loc, satellitesInFix)
        }
        val host = Prefs.getHost(this)
        val port = Prefs.getPort(this)
        val proto = Prefs.getProto(this)
        // Hostname configurato dall'utente; se vuoto usa il modello del telefono.
        // Niente spazi: il campo hostname del syslog non li ammette.
        val hostname = Prefs.getHostname(this).ifBlank { Build.MODEL ?: "android" }.replace(" ", "_")
        try {
            SyslogSender.sendBatch(
                host, port, proto, hostname, "gpstracker",
                android.os.Process.myPid(), sentences
            )
            lastSentInfo = "%.6f, %.6f via %s -> %s:%d"
                .format(loc.latitude, loc.longitude, proto, host, port)
            FileLogger.log(this, "Send", "OK $lastSentInfo | ${sentences.joinToString(" ")}")
            updateNotification(lastSentInfo)
        } catch (e: Exception) {
            FileLogger.log(this, "Send", "ERRORE invio a $host:$port ($proto): ${e.message}")
            updateNotification("Errore invio: ${e.message}")
        }
    }

    private fun sendToHa(loc: Location) {
        val base = Prefs.getHaUrl(this)
        val token = Prefs.getHaToken(this)
        val prefix = Prefs.getHaPrefix(this)
        val hostname = Prefs.getHostname(this).ifBlank { Build.MODEL ?: "android" }
        // entity_id valido: minuscolo, solo lettere/numeri/underscore.
        val slug = hostname.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        val entity = "device_tracker.$prefix$slug"

        if (base.isBlank() || token.isBlank()) {
            FileLogger.log(this, "Send", "Modalita' HA: URL o token mancante")
            updateNotification("Configura URL e token di Home Assistant")
            return
        }

        val attrs = org.json.JSONObject()
        attrs.put("source_type", "gps")
        attrs.put("latitude", loc.latitude)
        attrs.put("longitude", loc.longitude)
        attrs.put("gps_accuracy", if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0)
        if (loc.hasAltitude()) attrs.put("altitude", loc.altitude)
        if (loc.hasSpeed()) attrs.put("speed_kmh", (loc.speed * 3.6).toDouble())
        if (loc.hasBearing()) attrs.put("course", loc.bearing.toDouble())
        attrs.put("sats_used", satellitesInFix)
        attrs.put("sats_in_view", satsInView.size)
        attrs.put("friendly_name", hostname)
        // Manteniamo anche l'NMEA come attributo, per tracciabilita'.
        attrs.put("nmea", NmeaBuilder.build(loc, satellitesInFix).joinToString(" "))

        try {
            HaSender.sendDeviceTracker(base, token, entity, attrs)
            lastSentInfo = "%.6f, %.6f -> HA %s".format(loc.latitude, loc.longitude, entity)
            FileLogger.log(this, "Send", "OK $lastSentInfo")
            updateNotification(lastSentInfo)
        } catch (e: Exception) {
            FileLogger.log(this, "Send", "ERRORE invio a HA ($entity): ${e.message}")
            updateNotification("Errore invio HA: ${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "GPS Syslog Tracker", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Servizio di tracciamento posizione"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Syslog Tracker attivo")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_gps)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        FileLogger.log(this, "Service", "onDestroy")
        isRunning = false
        try { lm.removeUpdates(locationListener) } catch (_: Exception) {}
        try { lm.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
        handler?.removeCallbacks(sendRunnable)
        thread?.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // L'utente ha rimosso l'app dai recenti: riprogramma il riavvio.
        FileLogger.log(this, "Service", "onTaskRemoved: riprogrammo riavvio")
        if (Prefs.isEnabled(this)) CrashHandler.scheduleRestart(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
