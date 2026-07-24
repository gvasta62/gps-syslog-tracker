package it.gvasta.gpstracker

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Invia i messaggi al server syslog. Supporta due modalita' selezionabili:
 *  - UDP con formato RFC 3164 (syslog "classico");
 *  - TCP con formato RFC 5424 (syslog "moderno", timestamp ISO 8601).
 *
 * IMPORTANTE: questi metodi fanno rete e vanno chiamati SOLO da un thread
 * secondario (nel servizio giriamo su un HandlerThread dedicato), mai dal
 * thread principale della UI.
 */
object SyslogSender {

    // Priorita' syslog = facility*8 + severity.
    // facility local0 = 16, severity info = 6  ->  16*8 + 6 = 134.
    private const val PRI = 134

    fun sendBatch(
        host: String,
        port: Int,
        proto: String,
        hostname: String,
        appName: String,
        pid: Int,
        messages: List<String>
    ) {
        if (proto.equals("TCP", ignoreCase = true)) {
            sendTcp(host, port, hostname, appName, pid, messages)
        } else {
            sendUdp(host, port, hostname, messages)
        }
    }

    // Intestazione RFC 3164: <PRI>MMM gg hh:mm:ss HOST TAG: messaggio
    private fun frame3164(hostname: String, tag: String, msg: String): String {
        val ts = SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(Date())
        return "<$PRI>$ts $hostname $tag: $msg"
    }

    // Intestazione RFC 5424: <PRI>1 TIMESTAMP HOST APP PID MSGID STRUCT messaggio
    private fun frame5424(hostname: String, app: String, pid: Int, msg: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
        return "<$PRI>1 $ts $hostname $app $pid - - $msg"
    }

    private fun sendUdp(host: String, port: Int, hostname: String, messages: List<String>) {
        DatagramSocket().use { sock ->
            // getByName accetta sia un IP sia un nome host/URL (risoluzione DNS).
            val addr = InetAddress.getByName(host)
            for (m in messages) {
                val data = frame3164(hostname, "gpstracker", m).toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(data, data.size, addr, port))
            }
        }
    }

    private fun sendTcp(
        host: String,
        port: Int,
        hostname: String,
        app: String,
        pid: Int,
        messages: List<String>
    ) {
        Socket().use { sock ->
            // Timeout di 5 secondi: se il server non risponde non blocchiamo l'app.
            sock.connect(InetSocketAddress(host, port), 5000)
            sock.soTimeout = 5000
            val sb = StringBuilder()
            for (m in messages) {
                // Ogni messaggio termina con newline (framing accettato da rsyslog/syslog-ng).
                sb.append(frame5424(hostname, app, pid, m)).append("\n")
            }
            sock.getOutputStream().apply {
                write(sb.toString().toByteArray(Charsets.UTF_8))
                flush()
            }
        }
    }
}
