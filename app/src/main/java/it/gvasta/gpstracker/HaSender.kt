package it.gvasta.gpstracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Invia la posizione DIRETTAMENTE all'API REST di Home Assistant via HTTPS,
 * creando/aggiornando un device_tracker (che compare sulla mappa).
 *
 * Utile quando HA e' pubblicato su internet (es. dietro Cloudflare) e il
 * telefono e' su rete mobile: si usa lo stesso URL HTTPS di HA, senza syslog,
 * senza collector, senza VPN.
 *
 * IMPORTANTE: da chiamare solo da un thread secondario (nel servizio gira sul
 * HandlerThread dedicato), mai dal thread della UI.
 */
object HaSender {

    /**
     * POST {baseUrl}/api/states/{entityId} con stato e attributi.
     * @throws RuntimeException se la risposta non e' 2xx.
     */
    fun sendDeviceTracker(
        baseUrl: String,
        token: String,
        entityId: String,
        attributes: JSONObject,
        state: String = "not_home"
    ) {
        val url = URL("${baseUrl.trimEnd('/')}/api/states/$entityId")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            // Alcuni reverse proxy / Cloudflare rifiutano richieste senza User-Agent.
            conn.setRequestProperty("User-Agent", "curl/8.5.0")

            val body = JSONObject()
            body.put("state", state)
            body.put("attributes", attributes)

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.readText()
                } catch (e: Exception) {
                    null
                }
                throw RuntimeException("HTTP $code ${err ?: ""}".trim())
            }
            // Consuma la risposta per liberare la connessione.
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
