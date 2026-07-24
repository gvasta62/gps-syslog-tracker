# GPS Daily Logger (AppDaemon)

Salva **ogni** posizione dei mezzi in **file giornalieri** (CSV + GPX),
catturandola in tempo reale da Home Assistant.

## Perche' serve

Un `device_tracker` ha stato sempre `not_home`: cambiano solo gli attributi
(lat/lon). Home Assistant **non conserva nello storico** gli aggiornamenti di
soli attributi, quindi la traccia va registrata **mentre arriva**. Questa app
AppDaemon ascolta gli aggiornamenti e li scrive su file.

Output (in `out_dir`, default `/share/gps`):
- `gps_AAAA-MM-GG.csv` — append live, una riga per posizione (per analisi).
- `gps_AAAA-MM-GG.gpx` — traccia per mezzo, rigenerata ogni ora e a fine giornata.

## Installazione

1. **Installa l'add-on AppDaemon** (Impostazioni -> Add-on -> Store -> cerca
   "AppDaemon"). Avvialo una prima volta per generare le cartelle.
2. Individua la cartella **apps** di AppDaemon (dipende dalla versione, es.
   `/addon_configs/<slug_appdaemon>/apps/` oppure `/config/appdaemon/apps/`).
   La raggiungi via l'add-on **Samba** o **File editor / SSH**.
3. Copia il file **`apps/gps_daily_logger.py`** in quella cartella apps.
4. Aggiungi al file **`apps.yaml`** il blocco che trovi in `apps.yaml` qui
   accanto (module `gps_daily_logger`, class `GpsDailyLogger`, `out_dir`,
   `prefix`).
5. Verifica che AppDaemon possa **scrivere in `out_dir`**. `/share/gps` e'
   comodo perche' lo vedi anche via Samba (`\\<ip-HA>\share\gps`). Se l'add-on
   non ha accesso a `/share`, usa una cartella dentro la sua config.
6. **Riavvia** l'add-on AppDaemon. Nei suoi **Log** deve comparire:
   `GPS daily logger attivo: out_dir=/share/gps ...`

Da quel momento ogni mezzo `device_tracker.gps_*` che si aggiorna finisce nel
CSV del giorno; il GPX viene rigenerato ogni ora e definitivamente alle 00:05
per il giorno precedente.

## Recuperare / convertire i file

- I file sono in `out_dir` (via Samba: `\\<ip-HA>\share\gps`).
- Per rigenerare un GPX da un CSV a mano (su qualunque PC con Python):
  ```bash
  python3 tools/convert_csv_gpx.py gps_2026-07-24.csv
  ```

## Note

- L'app usa solo la libreria standard di Python.
- Filtra per `prefix` (default `device_tracker.gps_`): copre in automatico ogni
  nuovo mezzo, senza modifiche.
- Il CSV e' la fonte "viva" (append per punto); il GPX si ricava dal CSV.
