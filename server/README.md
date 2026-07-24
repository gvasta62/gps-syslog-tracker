# GPS Syslog Collector (lato server)

Riceve i messaggi syslog inviati dall'app **GPS Syslog Tracker**, **decodifica**
le frasi NMEA e fa **atterrare** il dato in una destinazione a scelta.

```
 App (telefono) ──syslog UDP/TCP──▶ collector.py ──▶ [ SINK a scelta ]
                                     │  parse busta syslog (RFC 3164 / 5424)
                                     │  valida checksum NMEA
                                     │  decodifica GGA/RMC/GSV
                                     │  ricompone i GSV multipli
                                     ▼
                        record normalizzato per ogni fix
```

## Record prodotto (un oggetto per fix)

```json
{
  "hostname": "bus-01",
  "received_at": "2026-07-24T10:11:12.482+00:00",
  "fix_time": "2026-07-24T10:11:12+00:00",
  "lat": 43.085400, "lon": 12.491900,
  "alt_m": 250.0, "fix_quality": 1,
  "sats_used": 8, "hdop": 1.2,
  "speed_kn": 0.0, "speed_kmh": 0.0, "course_deg": 0.0,
  "sats_in_view": 11,
  "satellites": [ {"prn":5,"elev":72,"azim":120,"snr":42}, ... ]
}
```

## Requisiti

- **Python 3.8+**. Il nucleo usa **solo la libreria standard**.
- Dipendenze extra **solo** per alcuni sink:
  - `postgres` → `pip install psycopg2-binary`
  - `mqtt` → `pip install paho-mqtt`
  - `influxdb` e `homeassistant` → nessuna dipendenza (usano HTTP via stdlib).

## Avvio

```bash
python3 collector.py --config config.ini
```

Apri `config.ini` e imposta la porta, poi **scegli il sink** nella sezione
`[sink]` (`type = ...`). Porte < 1024 (es. 514) richiedono privilegi:
```bash
sudo python3 collector.py --config config.ini
# oppure dai la capability una tantura all'interprete:
sudo setcap 'cap_net_bind_service=+ep' $(readlink -f $(which python3))
```

## Le destinazioni (SINK)

| `type` | Dove atterra il dato | Note |
|---|---|---|
| `console` | Stampa una riga JSON per fix | Ideale per provare al volo. |
| `jsonl` | File JSON Lines (un oggetto per riga) | `[jsonl] path=`. |
| `csv` | File CSV (satelliti come JSON in colonna) | `[csv] path=`. |
| `influxdb` | InfluxDB 2.x (serie temporale) | Per Grafana. Nessuna dipendenza. |
| `postgres` | Tabella Postgres/PostGIS | `pip install psycopg2-binary`. |
| `homeassistant`| `device_tracker` in Home Assistant | Mostra i mezzi sulla mappa HA. |
| `mqtt` | Broker MQTT (+ HA Discovery) | `pip install paho-mqtt`. |

### InfluxDB
Crea un bucket e un token con permesso di scrittura, poi compila `[influxdb]`.
Scrive `measurement=gps_fix` con tag `host` e campi lat/lon/alt/speed/hdop/sats.
Perfetto da graficare in Grafana (mappa geomap + serie).

### Postgres / PostGIS
Crea la tabella (esempio):
```sql
CREATE TABLE gps_fix (
  id            BIGSERIAL PRIMARY KEY,
  hostname      TEXT,
  fix_time      TIMESTAMPTZ,
  received_at   TIMESTAMPTZ,
  lat           DOUBLE PRECISION,
  lon           DOUBLE PRECISION,
  alt_m         DOUBLE PRECISION,
  fix_quality   INTEGER,
  sats_used     INTEGER,
  hdop          DOUBLE PRECISION,
  speed_kn      DOUBLE PRECISION,
  speed_kmh     DOUBLE PRECISION,
  course_deg    DOUBLE PRECISION,
  sats_in_view  INTEGER,
  satellites    JSONB
);
-- Con PostGIS puoi aggiungere una colonna geografica e un trigger:
-- ALTER TABLE gps_fix ADD COLUMN geom geometry(Point,4326);
```

### Home Assistant (REST)
1. In HA: profilo utente → **Token di accesso a lunga durata** → crea token.
2. Compila `[homeassistant] base_url` e `token`.
3. Il collector crea/aggiorna `device_tracker.gps_<hostname>` con
   `source_type: gps` e lat/lon: i mezzi compaiono sulla **mappa** di HA.

### MQTT (+ Home Assistant Discovery)
1. `pip install paho-mqtt`, compila `[mqtt]`.
2. Con `ha_discovery = true`, il collector pubblica la configurazione di
   **MQTT Discovery**: Home Assistant crea automaticamente i `device_tracker`.
   (Richiede l'integrazione **MQTT** attiva in HA e un broker, es. Mosquitto.)

## Verifica rapida (senza telefono)

Avvia il collector su una porta alta con sink `console`:
```ini
[listen]
udp_port = 5514
tcp_port = 5514
[sink]
type = console
```
```bash
python3 collector.py --config config.ini
```
In un altro terminale invia una frase di prova (UDP):
```bash
printf '<134>Jul 24 10:11:12 bus-01 gpstracker: $GPRMC,101112.00,A,4305.1240,N,01229.5140,E,0.0,0.0,240726,,*15\n' | nc -u -w1 127.0.0.1 5514
printf '<134>Jul 24 10:11:12 bus-01 gpstracker: $GPGGA,101112.00,4305.1240,N,01229.5140,E,1,08,1.2,250.0,M,0.0,M,,*5C\n' | nc -u -w1 127.0.0.1 5514
```
Vedrai il record JSON stampato (dopo ~5 s di flush, o all'arrivo del fix successivo).

## Eseguirlo come servizio (systemd)

`/etc/systemd/system/gps-collector.service`:
```ini
[Unit]
Description=GPS Syslog Collector
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/gps-collector/collector.py --config /opt/gps-collector/config.ini
WorkingDirectory=/opt/gps-collector
Restart=always
User=gpscollector
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now gps-collector
```

## Note

- I messaggi con **checksum NMEA non valido** vengono scartati e loggati.
- Le frasi/sorgenti sconosciute vengono ignorate senza fermare il collector.
- Un errore del sink (DB/HA/broker irraggiungibile) viene loggato ma **non**
  interrompe la ricezione.
