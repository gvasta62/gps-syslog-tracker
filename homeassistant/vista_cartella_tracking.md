# Vista della cartella dei file di tracking nella plancia

L'elenco dei file giornalieri (`/share/gps`) si mostra sulla dashboard tramite
un sensore. Ci sono due modi.

## Modo consigliato: sensore pubblicato da AppDaemon (nessun `configuration.yaml`)

La versione aggiornata di `gps_daily_logger.py` pubblica **da sola** il sensore
**`sensor.gps_tracking_files`** con:
- stato = numero di file,
- attributi: `directory`, `file_list` (elenco nomi), `latest`, `total_kb`.

Non serve modificare `configuration.yaml` ne' riavviare Home Assistant: basta
aggiornare il file dell'app e riavviare **AppDaemon**.

La card e' gia' inclusa in `plancia_mezzi_gps.yaml`:

```yaml
      - type: markdown
        title: "File di tracking giornalieri"
        content: "Cartella {{ state_attr('sensor.gps_tracking_files','directory') }} - {{ states('sensor.gps_tracking_files') }} file ({{ state_attr('sensor.gps_tracking_files','total_kb') }} KB). Elenco: {{ (state_attr('sensor.gps_tracking_files','file_list') or []) | join('  |  ') }}"
```

## Modo alternativo: sensore "folder" nativo

Se preferisci il sensore integrato di HA, aggiungi a `configuration.yaml`
(fondendo con le chiavi esistenti) e riavvia HA:

```yaml
homeassistant:
  allowlist_external_dirs:
    - /share/gps
sensor:
  - platform: folder
    folder: /share/gps
```

## Sfogliare/scaricare i file dalla UI

Per aprirli/scaricarli dall'interfaccia, salva i file sotto `/media` (pannello
**Media** di HA): imposta `out_dir: /media/gps` nelle opzioni dell'app AppDaemon
(verifica che AppDaemon possa scrivere in `/media`; di norma `/share` e' piu'
sicuro per gli add-on).
