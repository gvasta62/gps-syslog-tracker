# Architettura della soluzione — GPS Syslog Tracker

Documento end-to-end dell'intera soluzione di tracciamento: dall'app sul
telefono fino alla mappa e ai file giornalieri in Home Assistant. Per gli
interni dell'app Android vedi [`ARCHITETTURA.md`](ARCHITETTURA.md).

---

## 1. Scopo e contesto

Tracciare in tempo reale una **flotta di mezzi** (es. autobus) dotati di uno
smartphone Android, visualizzandoli sulla **mappa di Home Assistant** e
salvando le posizioni in **file giornalieri** per l'analisi.

Vincoli reali che hanno guidato le scelte:

- I mezzi girano in citta' → i telefoni sono su **rete mobile (4G)**, non in LAN.
- Home Assistant e' pubblicato su internet tramite **Cloudflare Tunnel**
  (`https://...`), che trasporta **solo HTTP/HTTPS**.
- La rete di casa e' con ogni probabilita' **dietro CGNAT** (nessun IP pubblico
  su cui inoltrare porte: e' il motivo per cui si usa un tunnel Cloudflare).
- Il telefono puo' essere **senza Google Play Services** (installazione sideload).

---

## 2. Vista d'insieme (end-to-end)

```
   ┌──────────────────────────┐
   │   Smartphone (Android)   │
   │   app "GPS Syslog        │   posizione ogni N secondi
   │   Tracker" v1.1          │   (LocationManager + GnssStatus)
   └───────────┬──────────────┘
               │
       ┌───────┴───────────────────────────────────────┐
       │ DUE MODALITA' DI INVIO (selezionabili)         │
       │                                                │
   (A) │ Home Assistant (HTTP)         (B) Syslog        │
       │ POST HTTPS /api/states            UDP/TCP NMEA  │
       ▼                                   ▼
  ╔══════════════════════╗          ┌────────────────────┐
  ║   HOME ASSISTANT     ║          │  Collector          │
  ║   device_tracker.    ║◄─────────┤  (server/ o add-on) │
  ║   gps_<host>         ║  REST     │  decodifica NMEA    │
  ╚═════════╤════════════╝          └────────────────────┘
            │
      ┌─────┴───────────────┬───────────────────────────┐
      ▼                     ▼                           ▼
 ┌──────────┐        ┌─────────────┐            ┌────────────────┐
 │  Mappa   │        │ AppDaemon   │            │  Storico HA     │
 │ (plancia │        │ gps_daily_  │            │  (solo ultima   │
 │  auto-   │        │ logger      │            │   posizione!)   │
 │ entities)│        │ CSV + GPX   │            └────────────────┘
 └──────────┘        │ per giorno  │
                     └─────────────┘
```

In **produzione** e' attivo il percorso **(A)**: l'app parla direttamente
l'API di HA. Il percorso **(B)** resta disponibile per chi ha un server syslog
raggiungibile.

---

## 3. I due percorsi di deployment

### (A) Diretto app → Home Assistant (HTTP) — *in produzione*

L'app invia la posizione con una `POST` HTTPS all'API REST di HA:

```
POST {ha_url}/api/states/device_tracker.<prefix><host>
Authorization: Bearer <token>
User-Agent: curl/8.5.0
{ "state": "not_home",
  "attributes": { "source_type":"gps", "latitude":..., "longitude":...,
                  "speed_kmh":..., "course":..., "altitude":...,
                  "sats_used":..., "sats_in_view":..., "nmea":"$GPRMC... $GPGGA..." } }
```

Pro: usa il tunnel Cloudflare gia' esistente, funziona da 4G, nessun server
aggiuntivo, nessuna VPN. Contro: un token HA risiede nel telefono.

### (B) Syslog + Collector/Add-on

L'app invia frasi **NMEA** via **syslog** (UDP RFC 3164 / TCP RFC 5424) a un
**collector** che le decodifica e le "fa atterrare" in una destinazione a scelta
(console, CSV, JSONL, InfluxDB, Postgres/PostGIS, Home Assistant, MQTT). Su
HAOS il collector e' impacchettato come **add-on**
([`homeassistant-addon/`](homeassistant-addon/)) e scrive i device_tracker via
API interna del Supervisor.

Pro: mantiene il formato NMEA, scrive file nativamente, disaccoppia app e
destinazione. Contro: il telefono deve **raggiungere** il collector: in LAN e'
banale, da 4G serve una porta pubblica (port-forward) o una VPN (Tailscale) —
non praticabile se la rete e' dietro CGNAT/Cloudflare.

### Quando usare quale

| Situazione | Percorso |
|---|---|
| HA su internet (Cloudflare), telefoni in 4G | **(A) HTTP diretto** |
| Server syslog raggiungibile in LAN o via porta pubblica | (B) Syslog |
| Serve integrare piu' destinazioni (Influx/DB/MQTT) | (B) Syslog + collector |

---

## 4. Perche' e' stato scelto il percorso (A)

Diagnosi effettuata sul campo:

1. Il **Cloudflare Tunnel** (`peppe.shadowbreaker.ovh` → IP Cloudflare) inoltra
   **solo HTTP** → il syslog non passa.
2. Il **DDNS alternativo** (`windarienti.homepc.it`) risultava **scaduto**
   (puntava a un IP AWS di "parcheggio", reverse `expired.dyndns.it`) → nessun
   port-forward possibile da li'.
3. La presenza stessa del tunnel Cloudflare indica **CGNAT** (niente IP pubblico).

Conclusione: l'unica via che sfrutta l'infrastruttura esistente e funziona da
rete mobile e' l'**HTTP diretto all'API di HA** (percorso A).

---

## 5. Componente: App Android

Ruolo: acquisisce la posizione (`LocationManager` nativo + `GnssStatus` per i
satelliti), la formatta e la invia secondo la modalita' scelta. E' un
**foreground service always-on** con auto-riavvio a piu' livelli (START_STICKY,
BootReceiver, onTaskRemoved, WorkManager watchdog, CrashHandler). Dettagli
interni, threading e robustezza in [`ARCHITETTURA.md`](ARCHITETTURA.md).

Classi rilevanti per l'invio:
- `SyslogSender` — trasporto syslog UDP/TCP (percorso B).
- `HaSender` — POST HTTPS all'API di HA (percorso A).
- `NmeaBuilder` — genera `$GPRMC`/`$GPGGA`/`$GPGSV` (usate in B e come attributo
  `nmea` in A).

---

## 6. Componente: integrazione Home Assistant

L'entita' e' un `device_tracker.<prefix><host>` con `source_type: gps`. Con
lat/lon impostati, HA lo mostra sulla **mappa**. Aggiornamenti ripetuti
spostano lo **stesso** marker (nessun duplicato).

Dettagli operativi:
- **User-Agent** obbligatorio: HA dietro Cloudflare rifiuta richieste senza
  header `User-Agent` → sia `HaSender` (app) sia i sink server usano `curl/8.5.0`.
- **Token**: nel percorso A un token a lunga durata; nell'add-on (percorso B) si
  usa invece il token interno del Supervisor (`http://supervisor/core`).
- **entity_id**: `hostname` viene reso "slug" (minuscolo, `[^a-z0-9_]`→`_`).

---

## 7. Componente: visualizzazione (plancia)

La dashboard [`plancia_mezzi_gps.yaml`](plancia_mezzi_gps.yaml) usa la card
**`custom:auto-entities`** (HACS) filtrando `device_tracker.gps_*`: **ogni**
mezzo che invia compare **da solo** sulla mappa e nell'elenco. Condizione per la
flotta: stesso **prefisso** `gps_` e **nome host univoco** su ogni telefono.

Vincolo noto dell'editor raw di HA: niente block scalar `>`/`|` e solo ASCII →
il file usa stringhe inline e caratteri ASCII.

---

## 8. Componente: persistenza giornaliera (file per giorno)

**Problema di fondo**: un `device_tracker` ha stato sempre `not_home`; cambiano
solo gli attributi (lat/lon). Home Assistant registra nello storico i **cambi di
stato**, non gli aggiornamenti di soli attributi. Verifica sul campo: 3 POST di
posizioni diverse → **1 solo punto** nello storico (anche con
`significant_changes_only=0`). Quindi **HA conserva solo l'ultima posizione, non
la traccia**: un export a posteriori dallo storico e' inutilizzabile.

**Soluzione**: catturare le posizioni **mentre arrivano**, con l'app AppDaemon
[`homeassistant/appdaemon/apps/gps_daily_logger.py`](homeassistant/appdaemon/apps/gps_daily_logger.py):

- `listen_state("device_tracker", attribute="all")` → il callback scatta ad ogni
  aggiornamento (anche di soli attributi), per tutti i `gps_*`.
- Scrive in append `gps_AAAA-MM-GG.csv` (una riga per posizione).
- Rigenera `gps_AAAA-MM-GG.gpx` (una traccia `<trk>` per mezzo) ogni ora e in
  via definitiva alle 00:05 per il giorno precedente.
- Cartella `out_dir` (default `/share/gps`, accessibile via Samba).

Il convertitore [`tools/convert_csv_gpx.py`](tools/convert_csv_gpx.py) rigenera
un GPX da un CSV su qualunque PC.

Perche' AppDaemon e non un'automazione: le automazioni HA non hanno trigger a
wildcard sugli entity, e la scrittura file via `shell_command` e' fragile;
AppDaemon ascolta un intero dominio e ha pieno accesso ai file — copre la flotta
in automatico.

---

## 9. Sicurezza

- **Token nel telefono** (percorso A): usarne uno **dedicato e revocabile**; non
  tenerlo in file sincronizzati su cloud.
- **Firma APK release**: keystore dedicato; `keystore.properties` e `*.jks` sono
  **esclusi dal repo** (`.gitignore`). L'APK di release e' firmato con questa
  chiave; conservare la keystore (senza, non si potranno pubblicare aggiornamenti
  con la stessa identita').
- **Porta esposta** (percorso B): il collector valida il checksum NMEA e scarta
  tutto il resto; l'opzione `[security] allowed_hosts` accetta solo gli hostname
  noti. Usare porte alte e non standard.
- **Add-on**: parla con HA via API interna del Supervisor → nessun segreto
  esterno.

---

## 10. Mappa del repository

| Percorso | Contenuto |
|---|---|
| `app/` | App Android (Kotlin). Modalita' Syslog + Home Assistant. |
| `server/collector.py` | Collector syslog → sink selezionabile. |
| `server/README.md`, `config.ini` | Uso e configurazione del collector. |
| `homeassistant-addon/` | Add-on HAOS che esegue il collector (percorso B). |
| `homeassistant/appdaemon/` | App AppDaemon per i file giornalieri CSV/GPX. |
| `tools/convert_csv_gpx.py` | Convertitore CSV → GPX standalone. |
| `plancia_mezzi_gps.yaml` | Dashboard mappa auto-popolante. |
| `README.md` | Panoramica e uso. |
| `ARCHITETTURA.md` | Interni dell'app Android. |
| `ARCHITETTURA_SOLUZIONE.md` | Questo documento (end-to-end). |
| `MANUALE_INSTALLAZIONE.md` | Installazione app + persistenza per marca telefono. |

---

## 11. Checklist di deployment lato Home Assistant

Percorso in produzione (A) + file giornalieri:

- [ ] **Token** HA a lunga durata dedicato (profilo → Sicurezza).
- [ ] Su ogni **telefono**: app in modalita' Home Assistant, URL di HA (senza
      `https://` errato!), token, `prefisso = gps_`, **nome host univoco**.
- [ ] **HACS → auto-entities** installato (per la plancia auto-popolante).
- [ ] **Plancia** creata: nuova dashboard → editor raw → incolla
      `plancia_mezzi_gps.yaml`.
- [ ] **AppDaemon** installato; `gps_daily_logger.py` nella cartella `apps`;
      blocco in `apps.yaml` **allineato a sinistra** (voce separata!); `out_dir`
      scrivibile (`/share/gps`).
- [ ] Verifica: `device_tracker.gps_<host>` aggiorna sulla mappa e
      `\\<ip-HA>\share\gps\gps_AAAA-MM-GG.csv` cresce.

> Gotcha ricorrenti: (1) `apps.yaml` — se il blocco viene **indentato dentro**
> un'altra app, fonde le due (la nostra gira col nome sbagliato e rompe l'altra);
> la chiave `gps_daily_logger:` deve stare a **colonna 0**. (2) URL di HA: solo
> host+schema corretto (un typo nel dominio da' "Unable to resolve host").

---

## 12. Estensibilita'

- **Flotta**: aggiungere mezzi = accendere altri telefoni con nome host diverso;
  compaiono da soli (auto-entities) e vengono loggati (filtro sul prefisso).
- **Altre destinazioni**: in modalita' syslog il collector puo' scrivere anche su
  InfluxDB/Grafana, Postgres/PostGIS, MQTT.
- **NMEA grezzo**: l'attributo `nmea` conserva le frasi originali per analisi.
