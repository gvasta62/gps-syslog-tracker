# GPS Syslog Collector — Add-on

Riceve i messaggi **syslog NMEA** inviati dall'app **GPS Syslog Tracker** e
crea/aggiorna automaticamente i `device_tracker` in Home Assistant (visibili
sulla mappa). Parla con HA tramite l'**API interna del Supervisor**: non serve
alcun token a lunga durata ne' passare da Cloudflare.

## Come funziona

```
telefono (app) ──syslog UDP/TCP:5514──▶ [add-on] ──API interna──▶ Home Assistant
```

Per ogni telefono viene creato `device_tracker.<prefix><hostname>` con
`source_type: gps` e attributi lat/lon, quota, velocita', rotta, satelliti.

## Opzioni

| Opzione | Default | Significato |
|---|---|---|
| `entity_prefix` | `gps_` | Prefisso dell'entity_id (`device_tracker.gps_bus_01`). |
| `allowed_hosts` | *(vuoto)* | Elenco (separato da virgola) degli hostname ammessi. **Consigliato** se esponi la porta su internet. Vuoto = accetta tutti. |
| `flush_timeout` | `5.0` | Secondi dopo cui l'ultimo fix in sospeso viene comunque emesso. |

## Porte

- **5514/tcp** — syslog TCP (RFC 5424). Consigliato per l'accesso da internet.
- **5514/udp** — syslog UDP (RFC 3164).

Puoi rimappare la porta host dalla scheda **Configurazione** dell'add-on.

## Accesso da rete mobile (bus in servizio)

Il telefono, fuori casa, deve raggiungere questa porta. Sul **router** inoltra
una porta pubblica verso l'IP della macchina di HA, porta 5514 (TCP consigliato).
Poi nell'app imposta:

- **Server** = il tuo DDNS (es. `windarienti.homepc.it`) — solo host, senza `https://`
- **Porta** = la porta pubblica inoltrata
- **Protocollo** = TCP
- **Nome host del device** = un identificativo per mezzo (es. `bus-01`)

E aggiungi quell'hostname in `allowed_hosts` per sicurezza.

> **Sicurezza**: esporre una porta su internet significa ricevere anche traffico
> indesiderato. Il collector valida il checksum NMEA e scarta tutto il resto, e
> con `allowed_hosts` accetta solo gli hostname noti. Usa comunque una porta alta
> e non standard.

## Verifica

Nei **log** dell'add-on vedi le righe di ricezione. In *Strumenti per sviluppatori
→ Stati* cerca `device_tracker.gps_...`. Sulla **mappa** compaiono i mezzi.
