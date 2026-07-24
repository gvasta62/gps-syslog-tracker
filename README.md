# GPS Syslog Tracker

App Android che gira come **servizio sempre attivo** su uno smartphone e invia
periodicamente la posizione del telefono (in formato **NMEA**: `$GPRMC` +
`$GPGGA`) a un **server syslog**. Pensata per essere **installata senza Google
Play Store** (sideload dell'APK) e per resistere al risparmio energetico e ai
crash.

## Caratteristiche

- **Servizio in primo piano (foreground)** con notifica permanente: resta
  attivo anche a schermo spento.
- **WakeLock parziale**: continua a inviare durante il Doze / risparmio energetico.
- **Parametri configurabili** dall'app:
  - **Nome host del device** (finisce nel campo *hostname* del syslog, cioe'
    all'inizio della riga, prima della frase NMEA);
  - Server syslog: **IP oppure URL** (risoluzione DNS automatica);
  - **Porta** (default 514);
  - **Protocollo**: **UDP (RFC 3164)** oppure **TCP (RFC 5424)**;
  - **Intervallo di invio** in secondi (la frequenza di polling);
  - **Dettaglio satelliti (`$GPGSV`)** attivabile/disattivabile con un interruttore.
- **Numero di satelliti del fix** incluso nella frase `$GPGGA`, letto dal vero
  tramite `GnssStatus` (conteggio dei satelliti `usedInFix`).
- **Posizione via `LocationManager` nativo** (GPS + rete): NON usa i servizi
  Google, quindi funziona anche su telefoni senza Play Services.
- **Robustezza / auto-riavvio (4 livelli)**:
  1. `START_STICKY` — il sistema ricrea il servizio se lo uccide;
  2. `BootReceiver` — riparte dopo il riavvio del telefono;
  3. `onTaskRemoved` — riparte se l'app viene tolta dai recenti;
  4. `WatchdogWorker` (WorkManager, ogni ~15 min) — se dovrebbe essere attivo
     ma non lo e', lo fa ripartire.
- **Gestione crash**: ogni eccezione non gestita viene **loggata su file** con
  lo stack trace completo e il servizio viene **riprogrammato per il riavvio**
  (via `AlarmManager`).
- **Log su file** consultabili dall'app (pulsante "Mostra log") e presenti in
  `Android/data/it.gvasta.gpstracker/files/logs/`.

## Requisiti telefono

- Android 7.0 (API 24) o superiore.
- "Origini sconosciute" abilitato per installare l'APK fuori dallo store.

> **Per l'installazione completa e la persistenza** (permessi, esclusione
> risparmio energetico, impostazioni per marca di telefono) vedi
> **[`MANUALE_INSTALLAZIONE.md`](MANUALE_INSTALLAZIONE.md)**.
> Per l'architettura interna vedi **[`ARCHITETTURA.md`](ARCHITETTURA.md)**.

## Come si usa (sul telefono)

1. Installa l'APK (vedi sotto).
2. Apri l'app.
3. Premi **Concedi permessi** e concedi: Posizione → scegli **"Consenti sempre"**
   (background), e Notifiche.
4. Premi **Escludi dal risparmio energetico** e conferma.
5. Inserisci **server**, **porta**, **protocollo** e **intervallo**, poi **Salva
   impostazioni**.
6. Premi **Avvia servizio**. Comparira' la notifica permanente.

> Su alcuni telefoni (Xiaomi/MIUI, Huawei, Oppo, Samsung) va anche abilitato
> manualmente l'**"avvio automatico"** e tolto il servizio dalle restrizioni
> batterie del produttore, altrimenti il sistema lo chiude comunque.

## Verifica lato server syslog

Esempio con `rsyslog` in ascolto, oppure test rapido con netcat:

```bash
# UDP (default, porta 514)
nc -u -l 514

# TCP
nc -l 5140
```

Dovresti vedere righe tipo (hostname = `smartphone-01`, 8 satelliti nel fix):

```
<134>Jul 24 10:11:12 smartphone-01 gpstracker: $GPRMC,101112.00,A,4305.1240,N,01229.5140,E,0.0,0.0,240726,,*XX
<134>Jul 24 10:11:12 smartphone-01 gpstracker: $GPGGA,101112.00,4305.1240,N,01229.5140,E,1,08,1.2,250.0,M,0.0,M,,*YY
```

### Struttura esatta della stringa inviata

Ad ogni intervallo vengono inviate **due righe** (una per frase NMEA). Ogni riga
e' cosi' composta: `INTESTAZIONE-SYSLOG` + `FRASE-NMEA`.

**Intestazione syslog UDP (RFC 3164):**
```
<134>Mmm gg hh:mm:ss HOSTNAME gpstracker: 
 |    |               |        |
 |    |               |        +- tag fisso
 |    |               +---------- NOME HOST configurabile (device)
 |    +-------------------------- data/ora locale
 +------------------------------- priorita' = facility(local0=16)*8 + severity(info=6) = 134
```

**Intestazione syslog TCP (RFC 5424):**
```
<134>1 2026-07-24T10:11:12.000+02:00 HOSTNAME gpstracker PID - - 
      |  |                            |        |         |
      |  |                            |        |         +- PID processo, poi MSGID(-) e STRUCTURED-DATA(-)
      |  |                            |        +----------- APP-NAME fisso
      |  |                            +-------------------- NOME HOST configurabile (device)
      |  +------------------------------------------------ timestamp ISO 8601 con fuso
      +--------------------------------------------------- versione formato = 1
```

**Frase `$GPRMC`** (posizione minima raccomandata):
```
$GPRMC,101112.00,A,4305.1240,N,01229.5140,E,0.0,0.0,240726,,*XX
   |      |       |     |     |      |     |  |   |     |   || |
   |      |       |     |     |      |     |  |   |     |   || +- checksum (XOR, esadecimale)
   |      |       |     |     |      |     |  |   |     |   |+--- variazione magnetica E/W (vuoto)
   |      |       |     |     |      |     |  |   |     |   +---- variazione magnetica (vuoto)
   |      |       |     |     |      |     |  |   |     +-------- data UTC (ggmmaa)
   |      |       |     |     |      |     |  |   +-------------- rotta su suolo (gradi)
   |      |       |     |     |      |     |  +------------------ velocita' su suolo (nodi)
   |      |       |     |     |      |     +--------------------- E/W (emisfero longitudine)
   |      |       |     |     |      +--------------------------- longitudine dddmm.mmmm
   |      |       |     |     +---------------------------------- N/S (emisfero latitudine)
   |      |       |     +---------------------------------------- latitudine ddmm.mmmm
   |      |       +---------------------------------------------- stato: A=valido (V=non valido)
   |      +------------------------------------------------------ ora UTC (hhmmss.ss)
   +------------------------------------------------------------- tipo frase
```

**Frase `$GPGGA`** (dati del fix, contiene il NUMERO DI SATELLITI):
```
$GPGGA,101112.00,4305.1240,N,01229.5140,E,1,08,1.2,250.0,M,0.0,M,,*YY
   |      |        |        |      |     | | |   |    |   |  |  | || |
   |      |        |        |      |     | | |   |    |   |  |  | || +- checksum
   |      |        |        |      |     | | |   |    |   |  |  | |+--- ID stazione differenziale (vuoto)
   |      |        |        |      |     | | |   |    |   |  |  | +---- eta' dato differenziale (vuoto)
   |      |        |        |      |     | | |   |    |   |  |  +------ unita' separazione geoide (M)
   |      |        |        |      |     | | |   |    |   |  +--------- separazione geoide (0.0)
   |      |        |        |      |     | | |   |    |   +------------ unita' altitudine (M)
   |      |        |        |      |     | | |   |    +--------------- ALTITUDINE sul livello medio del mare
   |      |        |        |      |     | | |   +------------------- HDOP (diluizione orizzontale, stimata)
   |      |        |        |      |     | | +----------------------- >>> NUMERO DI SATELLITI usati nel fix (2 cifre) <<<
   |      |        |        |      |     | +------------------------- qualita' fix: 1=GPS
   |      |        |        |      |     +--------------------------- E/W
   |      |        |        |      +--------------------------------- longitudine dddmm.mmmm
   |      |        |        +---------------------------------------- N/S
   |      |        +------------------------------------------------- latitudine ddmm.mmmm
   |      +---------------------------------------------------------- ora UTC
   +----------------------------------------------------------------- tipo frase
```

**Frasi `$GPGSV`** (dettaglio dei satelliti in vista) — una ogni 4 satelliti:
```
$GPGSV,3,1,11,05,72,120,42,13,60,045,40,15,45,210,38,20,30,300,35*7A
   |    | |  |  \______/  \______/  \______/  \______/
   |    | |  |     sat1      sat2      sat3      sat4   (blocco per satellite)
   |    | |  |     ognuno = prn,elevazione(gradi),azimut(gradi),snr(dB-Hz)
   |    | |  +- numero totale di satelliti in vista
   |    | +---- numero di questa frase (qui la 1a)
   |    +------ numero totale di frasi GSV (qui 3, cioe' fino a 12 satelliti)
   +----------- tipo frase
```
Il campo SNR resta vuoto se il satellite non e' ancora tracciato. Se i satelliti
sono piu' di 4, vengono emesse piu' righe `$GPGSV` consecutive.

Quindi ad ogni intervallo l'app invia: **1×`$GPRMC` + 1×`$GPGGA` + N×`$GPGSV`**
(N dipende dal numero di satelliti in vista).

## Compilare l'APK

Il progetto e' un normale progetto **Gradle + Kotlin**, apribile in **Android
Studio** (Open → cartella `gps-syslog-tracker` → attendi la sync → *Build > Build
App Bundle(s) / APK(s) > Build APK(s)*).

In alternativa, da riga di comando (con JDK 17 e Android SDK):

```bash
./gradlew assembleDebug     # APK di debug -> app/build/outputs/apk/debug/
./gradlew assembleRelease   # APK di release (da firmare)
```

L'APK **debug** e' gia' firmato con la chiave di debug ed e' installabile
direttamente per test.

## Struttura del codice

| File | Ruolo |
|---|---|
| `MainActivity.kt` | Schermata di configurazione e comandi. |
| `LocationService.kt` | Servizio foreground: legge la posizione e invia al syslog. |
| `NmeaBuilder.kt` | Costruisce le frasi NMEA `$GPRMC`/`$GPGGA` con checksum. |
| `SyslogSender.kt` | Invio UDP (RFC 3164) e TCP (RFC 5424). |
| `Prefs.kt` | Parametri configurabili (SharedPreferences). |
| `CrashHandler.kt` | Cattura i crash, li logga e riavvia il servizio. |
| `FileLogger.kt` | Log su file con rotazione. |
| `BootReceiver.kt` | Riavvio dopo il boot del telefono. |
| `RestartReceiver.kt` | Riavvio dopo crash / rimozione dai recenti. |
| `WatchdogWorker.kt` | Controllo periodico che il servizio sia vivo. |
| `App.kt` | Installa il gestore crash all'avvio del processo. |
