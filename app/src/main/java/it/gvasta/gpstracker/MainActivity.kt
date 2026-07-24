package it.gvasta.gpstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Schermata unica dell'app: permette di configurare i parametri, avviare/fermare
 * il servizio, concedere i permessi, escludere l'app dal risparmio energetico e
 * consultare i log.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etHostname: EditText
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etInterval: EditText
    private lateinit var rgProto: RadioGroup
    private lateinit var rbUdp: RadioButton
    private lateinit var rbTcp: RadioButton
    private lateinit var cbGsv: CheckBox
    private lateinit var tvStatus: TextView

    private val REQ = 7001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHostname = findViewById(R.id.etHostname)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etInterval = findViewById(R.id.etInterval)
        rgProto = findViewById(R.id.rgProto)
        rbUdp = findViewById(R.id.rbUdp)
        rbTcp = findViewById(R.id.rbTcp)
        cbGsv = findViewById(R.id.cbGsv)
        tvStatus = findViewById(R.id.tvStatus)

        loadPrefs()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            savePrefs(); toast("Impostazioni salvate"); refreshStatus()
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            savePrefs()
            if (ensurePermissions()) {
                LocationService.start(this)
                toast("Servizio avviato")
                refreshStatus()
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            Prefs.setEnabled(this, false)
            LocationService.stop(this)
            toast("Servizio fermato")
            refreshStatus()
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { ensurePermissions() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBattery() }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            tvStatus.text = FileLogger.readTail(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadPrefs() {
        // Se non e' mai stato impostato, propone il modello del telefono.
        etHostname.setText(Prefs.getHostname(this).ifBlank { Build.MODEL ?: "" })
        etHost.setText(Prefs.getHost(this))
        etPort.setText(Prefs.getPort(this).toString())
        etInterval.setText(Prefs.getIntervalSec(this).toString())
        if (Prefs.getProto(this).equals("TCP", true)) rbTcp.isChecked = true else rbUdp.isChecked = true
        cbGsv.isChecked = Prefs.isGsvEnabled(this)
    }

    private fun savePrefs() {
        // Spazi non ammessi nel campo hostname del syslog: li sostituiamo.
        Prefs.setHostname(this, etHostname.text.toString().trim().replace(" ", "_"))
        Prefs.setHost(this, etHost.text.toString().trim())
        Prefs.setPort(this, etPort.text.toString().trim().toIntOrNull() ?: 514)
        Prefs.setIntervalSec(this, etInterval.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 10)
        Prefs.setProto(this, if (rbTcp.isChecked) "TCP" else "UDP")
        Prefs.setGsvEnabled(this, cbGsv.isChecked)
    }

    private fun refreshStatus() {
        tvStatus.text = buildString {
            append("Stato servizio: ${if (LocationService.isRunning) "ATTIVO" else "fermo"}\n")
            append("Hostname: ${Prefs.getHostname(this@MainActivity).ifBlank { Build.MODEL ?: "android" }}\n")
            append("Server: ${Prefs.getHost(this@MainActivity)}:${Prefs.getPort(this@MainActivity)} (${Prefs.getProto(this@MainActivity)})\n")
            append("Intervallo: ${Prefs.getIntervalSec(this@MainActivity)} s\n")
            append("Escluso dal risparmio energetico: ${if (isIgnoringBattery()) "SI" else "NO"}\n\n")
            append("Premi 'Mostra log' per i dettagli.")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- Permessi ----------

    /** Ritorna true se i permessi essenziali (posizione) ci sono gia'. */
    private fun ensurePermissions(): Boolean {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ)
            return false
        }

        // Il permesso "posizione in background" va chiesto DOPO quello in primo piano.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ + 1
            )
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                ensurePermissions() // ora chiede il background
                toast("Permessi concessi: ora puoi avviare il servizio")
            } else {
                toast("Permesso posizione negato: il servizio non puo' funzionare")
            }
        }
        refreshStatus()
    }

    // ---------- Risparmio energetico ----------

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            pm.isIgnoringBatteryOptimizations(packageName) else true
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBattery()) {
            toast("Gia' escluso dal risparmio energetico")
            return
        }
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            // Fallback: apre la schermata generale delle ottimizzazioni batteria.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
