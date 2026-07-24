# Add-on Home Assistant — GPS Syslog Collector

Impacchetta il collector come **add-on locale** di Home Assistant. Da usare quando
HA gira come **Home Assistant OS / Supervised** (c'e' il Supervisor).

Vantaggio: l'add-on scrive i `device_tracker` tramite l'**API interna del
Supervisor** (`http://supervisor/core` + `SUPERVISOR_TOKEN`), quindi **niente
token a lunga durata e niente Cloudflare**.

## Installazione (add-on locale)

1. Sulla macchina di HA serve accesso alla cartella `/addons`. Il modo piu'
   semplice e' installare uno di questi add-on ufficiali:
   - **Samba share** (poi vedi la cartella `addons` da Windows in
     `\\<ip-di-HA>\addons`), oppure
   - **Advanced SSH & Web Terminal**.
2. Copia la cartella **`gps_syslog_collector/`** (con tutti i suoi file) dentro
   `/addons/`, cosi' da avere `/addons/gps_syslog_collector/config.yaml`.
3. In HA: **Impostazioni → Add-on → Store**, in alto a destra menu **⋮ →
   Controlla aggiornamenti** (o ricarica). Comparira' **"Local add-ons → GPS
   Syslog Collector"**.
4. Aprilo → **Installa** (la prima build richiede qualche minuto).
5. Scheda **Configurazione**: imposta `allowed_hosts` (es. `bus-01,bus-02`) e, se
   vuoi, rimappa la porta host. **Avvia** l'add-on e attiva "Avvio automatico".

## Rendere raggiungibile il telefono (da rete mobile)

Sul **router** inoltra una porta pubblica TCP verso `IP-di-HA:5514`. Poi
configura l'app come descritto in [`gps_syslog_collector/DOCS.md`](gps_syslog_collector/DOCS.md).

## Nota

Il file `gps_syslog_collector/collector.py` e' una copia di
[`../server/collector.py`](../server/collector.py): se aggiorni il collector,
ricopia il file nell'add-on e incrementa `version` in `config.yaml`.
