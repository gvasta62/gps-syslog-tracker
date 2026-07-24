# Vista della cartella dei file di tracking nella plancia

Si', si puo' mostrare l'elenco dei file giornalieri (`/share/gps`) direttamente
sulla dashboard. Servono due cose: un **sensore folder** e una **card**.

## 1. Abilita il sensore "folder" (in `configuration.yaml`)

La cartella deve essere tra quelle consentite e va creato un sensore che ne
elenca i file. Aggiungi a `configuration.yaml` (se le chiavi `homeassistant:` o
`sensor:` esistono gia', **fondi** le voci, non duplicare le chiavi):

```yaml
homeassistant:
  allowlist_external_dirs:
    - /share/gps

sensor:
  - platform: folder
    folder: /share/gps
```

Poi **Impostazioni → Sistema → Riavvia** (riavvio di Home Assistant).

Dopo il riavvio nasce un sensore tipo `sensor.gps` (oppure `sensor.share_gps`):
il suo stato e' il numero di file, e l'attributo `file_list` e' l'elenco
completo dei percorsi.

## 2. Card nella plancia

Aggiungi questa card alla dashboard (sostituisci `SENSORE_FOLDER` con il nome
reale del sensore creato al passo 1). E' una stringa inline (ASCII) per non
rompere l'editor raw:

```yaml
      - type: markdown
        title: "File di tracking - /share/gps"
        content: "{{ states('sensor.SENSORE_FOLDER') }} file salvati.  Ultimi: {{ (state_attr('sensor.SENSORE_FOLDER','file_list') or []) | map('regex_replace','^.*/','') | join('  |  ') }}"
```

## Alternativa: sfogliare/scaricare i file dalla UI

Se invece vuoi anche **aprire/scaricare** i file dall'interfaccia, conviene
salvarli sotto `/media` (che HA espone nel pannello **Media**): cambia
`out_dir` dell'app AppDaemon in `/media/gps`. Attenzione: verifica che
AppDaemon abbia accesso in scrittura a `/media` (di norma `/share` e' piu'
sicuro per gli add-on). In quel caso i file compaiono nel browser multimediale
di HA e sono scaricabili con un click.
