# GPS Syslog Tracker — Architettura e Robustezza

Documento tecnico che descrive la struttura interna dell'applicazione, le scelte
di progettazione e i meccanismi che ne garantiscono il funzionamento continuo.

---

## 1. Scopo e requisiti

L'app trasforma uno smartphone Android in una **sonda GPS permanente** che invia
la propria posizione, in formato **NMEA**, a un **server syslog** di rete.

Requisiti funzionali e non funzionali che hanno guidato il progetto:

| Requisito | Vincolo tecnico che ne deriva |
|---|---|
| Servizio sempre attivo, anche a schermo spento | Foreground Service + WakeLock parziale |
| Deve sopravvivere al risparmio energetico (Doze) | Esclusione da battery optimization + WakeLock |
| Installabile senza Google Play Store | Nessuna dipendenza dai Google Play Services |
| Posizione affidabile su qualsiasi telefono | `LocationManager` di sistema, non FusedLocation |
| Parametri modificabili dall'utente | Persistenza su `SharedPreferences` + UI dedicata |
| Non deve mai fermarsi per un crash | Gestore globale delle eccezioni + auto-restart |
| Deve ripartire da sola in ogni scenario | 4 meccanismi ridondanti di riavvio |
| Tracciabilita' dei problemi | Log persistenti su file con rotazione |

---

## 2. Vista d'insieme dei componenti

```
                          ┌───────────────────────────────────────────┐
                          │                App (Application)           │
                          │  installa il CrashHandler globale all'avvio│
                          └───────────────────┬───────────────────────┘
                                              │
        ┌─────────────────────────────────────┼──────────────────────────────────┐
        │                                     │                                   │
 ┌──────▼───────┐                    ┌────────▼─────────┐                ┌────────▼────────┐
 │ MainActivity │  configura/avvia   │  LocationService │   scrive       │   FileLogger    │
 │   (UI)       ├───────────────────►│  (Foreground)    ├───────────────►│  log su file    │
 └──────────────┘                    └───┬─────────┬────┘                └─────────────────┘
        │ salva parametri                │         │
        ▼                                │         │ posizione + satelliti
 ┌──────────────┐                        │         ▼
 │    Prefs     │◄───legge───────────────┘   ┌─────────────┐
 │(SharedPrefs) │                            │ NmeaBuilder │  costruisce $GPRMC/$GPGGA/$GPGSV
 └──────────────┘                            └──────┬──────┘
                                                    │ frasi NMEA
                                                    ▼
                                             ┌─────────────┐
                                             │ SyslogSender│  incapsula in RFC 3164 / RFC 5424
                                             └──────┬──────┘
                                                    │ UDP / TCP
                                                    ▼
                                            ╔═══════════════╗
                                            ║ SERVER SYSLOG ║
                                            ╚═══════════════╝

  Componenti di resilienza (fuori dal flusso dati):
  ┌──────────────┐  ┌────────────────┐  ┌───────────────┐  ┌────────────────┐
  │ BootReceiver │  │ RestartReceiver│  │ WatchdogWorker│  │  CrashHandler  │
  │ (dopo boot)  │  │(dopo crash/kill)│ │(ogni ~15 min) │  │(cattura crash) │
  └──────────────┘  └────────────────┘  └───────────────┘  └────────────────┘
```

### Responsabilita' di ciascuna classe

| Classe | Tipo Android | Responsabilita' unica |
|---|---|---|
| `App` | `Application` | Punto d'ingresso del processo; installa il gestore crash. |
| `MainActivity` | `Activity` | Interfaccia: configurazione, permessi, avvio/stop, log. |
| `Prefs` | oggetto | Unica fonte di verita' per i parametri configurabili. |
| `LocationService` | `Service` (foreground) | Orchestratore: raccoglie posizione/satelliti e invia. |
| `NmeaBuilder` | oggetto puro | Trasforma una `Location` in frasi NMEA valide. |
| `SyslogSender` | oggetto puro | Incapsula e trasmette via UDP/TCP. |
| `FileLogger` | oggetto | Log persistente con rotazione a 1 MB. |
| `CrashHandler` | `UncaughtExceptionHandler` | Cattura i crash, li registra e programma il riavvio. |
| `BootReceiver` | `BroadcastReceiver` | Riavvia il servizio dopo il boot del telefono. |
| `RestartReceiver` | `BroadcastReceiver` | Riavvia il servizio dopo crash o rimozione dai recenti. |
| `WatchdogWorker` | `Worker` (WorkManager) | Controllo periodico che il servizio sia vivo. |

Il progetto segue il principio di **singola responsabilita'**: le classi "pure"
(`NmeaBuilder`, `SyslogSender`) non conoscono Android e sono facilmente testabili
in isolamento; l'unica classe che coordina il tutto e' `LocationService`.

---

## 3. Flusso dei dati (un ciclo di invio)

```
  [Timer ogni N secondi sul thread "gps-send"]
        │
        ▼
  1. LocationService legge:
       • ultima Location (da LocationManager: GPS o rete)
       • n. satelliti nel fix   (da GnssStatus.usedInFix)
       • dettaglio satelliti     (da GnssStatus: PRN/elev/azim/SNR)
        │
        ▼
  2. NmeaBuilder costruisce le frasi:
       $GPRMC  (posizione, velocita', rotta, data)
       $GPGGA  (posizione, quota, HDOP, n. satelliti)
       $GPGSV  (dettaglio satelliti, se abilitato)   ← opzionale (interruttore)
        │
        ▼
  3. SyslogSender incapsula ogni frase:
       UDP → <134>Mmm gg hh:mm:ss HOSTNAME gpstracker: <frase>
       TCP → <134>1 ISO8601 HOSTNAME gpstracker PID - - <frase>\n
        │
        ▼
  4. Trasmissione:
       UDP → un datagramma per frase (DatagramSocket)
       TCP → una connessione, tutte le frasi, chiusura (timeout 5 s)
        │
        ▼
  5. FileLogger registra l'esito; la notifica mostra l'ultima posizione.
        │
        ▼
  [postDelayed(intervallo) → prossimo ciclo]
```

Il **conteggio satelliti** e i **dati per-satellite** non arrivano dalla
`Location` (dove il campo e' deprecato e spesso assente) ma da un canale
dedicato, `GnssStatus`, che riporta lo stato reale della costellazione al
momento del fix.

---

## 4. Modello di threading

La concorrenza e' volutamente semplice e prevedibile, senza librerie di
coroutine, per ridurre le superfici d'errore.

| Thread | Chi ci gira sopra | Perche' |
|---|---|---|
| **Main / UI** | `MainActivity`, callback di sistema del `Service` | Obbligatorio per la UI e per `startForeground`. |
| **`gps-send`** (`HandlerThread` dedicato) | ciclo periodico di invio + callback `GnssStatus` | La rete NON puo' girare sul main thread; qui vive tutta l'attivita' ripetitiva. |

Punti chiave:
- Il **timer** e' un `Handler.postDelayed` sul thread `gps-send`: dopo ogni
  invio si ripianifica da solo, quindi l'intervallo e' rispettato anche se un
  invio impiega tempo (es. timeout TCP).
- I dati condivisi tra thread (`lastLocation`, `satellitesInFix`, `satsInView`)
  sono marcati `@Volatile`: scrittura dalla callback GNSS, lettura dal ciclo di
  invio, senza lock espliciti (aggiornamenti atomici di riferimenti/interi).
- **Ordine di avvio critico**: il `HandlerThread` viene creato PRIMA di
  registrare la callback `GnssStatus`, perche' quest'ultima consegna i risultati
  proprio su quell'handler.

---

## 5. Robustezza: come l'app "non muore mai"

La resilienza e' ottenuta a strati ridondanti: se un meccanismo non scatta, ne
interviene un altro. Ogni riavvio e' subordinato al flag `enabled` in `Prefs`,
cosi' l'app riparte solo se l'utente l'aveva effettivamente avviata (e non dopo
uno stop volontario).

### 5.1 Livello sistema operativo

- **Foreground Service**: dichiarato con tipo `location`. Android tratta i
  servizi in primo piano come prioritari e li uccide solo in condizioni estreme
  di memoria; la notifica permanente e' il "prezzo" richiesto dal sistema.
- **`START_STICKY`**: se il sistema termina comunque il servizio per pressione
  di memoria, si impegna a **ricrearlo** appena possibile.
- **WakeLock parziale**: impedisce alla CPU di addormentarsi durante il Doze,
  cosi' il timer di invio continua a scattare a schermo spento.
- **Esclusione da battery optimization**: l'app chiede all'utente di essere
  messa in whitelist, evitando che il "Doze aggressivo" sospenda rete e allarmi.

### 5.2 Livello ciclo di vita dell'app

- **`BootReceiver`** (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED`): dopo un riavvio del telefono o un aggiornamento
  dell'app, il servizio riparte da solo.
- **`onTaskRemoved`**: se l'utente scorre via l'app dai "recenti", il servizio
  programma il proprio riavvio (via `AlarmManager` → `RestartReceiver`).

### 5.3 Livello gestione errori

- **`CrashHandler`** (installato in `App.onCreate`, quindi copre OGNI thread e
  componente): all'eccezione non gestita
  1. scrive lo **stack trace completo** in un file `crash_<timestamp>.log`;
  2. programma un `AlarmManager` a **+2 secondi** su `RestartReceiver`;
  3. lascia terminare il processo in modo pulito.

  Il risultato: un crash diventa un semplice "riavvio ritardato di 2 secondi",
  con tanto di traccia diagnostica salvata.

### 5.4 Livello watchdog

- **`WatchdogWorker`** (WorkManager, periodico ~15 min, minimo consentito dal
  sistema): rete di sicurezza finale. Se il servizio **dovrebbe** essere attivo
  (`enabled = true`) ma per qualsiasi ragione non lo e' (`isRunning = false`), lo
  fa ripartire. WorkManager persiste le sue richieste e sopravvive a riavvii,
  quindi il controllo continua nel tempo.

### 5.5 Robustezza nell'invio dati

- Ogni invio e' avvolto in `try/catch`: un server irraggiungibile, la rete
  assente o un timeout **non fermano il servizio**; l'errore viene loggato e il
  ciclo prosegue al prossimo intervallo.
- **UDP** e' "fire and forget": nessun blocco se il server non risponde.
- **TCP** usa timeout di connessione e lettura a **5 secondi**, cosi' un server
  lento non congela il thread di invio.
- La risoluzione dell'host accetta sia **IP** sia **nome/URL** (DNS).

### 5.6 Riepilogo scenari → reazione

| Evento | Meccanismo che interviene |
|---|---|
| Memoria bassa, servizio ucciso | `START_STICKY` lo ricrea |
| Riavvio del telefono | `BootReceiver` |
| App rimossa dai recenti | `onTaskRemoved` → `RestartReceiver` |
| Eccezione non gestita (crash) | `CrashHandler` → riavvio a +2 s |
| Servizio spento da causa ignota | `WatchdogWorker` (entro ~15 min) |
| Server syslog offline | `try/catch` + log, prosegue |
| Schermo spento / Doze | Foreground + WakeLock + whitelist batteria |
| Aggiornamento dell'app | `MY_PACKAGE_REPLACED` in `BootReceiver` |

---

## 6. Formato dei dati NMEA

Ad ogni intervallo l'app invia: **1× `$GPRMC` + 1× `$GPGGA` + N× `$GPGSV`**
(le `$GPGSV` sono opzionali, disattivabili dall'interruttore, e sono una ogni 4
satelliti in vista). La struttura campo-per-campo di ogni frase, con esempi, e'
documentata in dettaglio nel `README.md` (sezione "Struttura esatta della
stringa inviata").

In sintesi:
- **`$GPRMC`** — ora, validita', posizione, velocita', rotta, data.
- **`$GPGGA`** — ora, posizione, qualita' fix, **numero satelliti**, HDOP, quota.
- **`$GPGSV`** — per ogni satellite in vista: **PRN, elevazione, azimut, SNR**.
- Il `hostname` configurabile finisce nel campo hostname del syslog, cioe'
  all'inizio di ogni riga, prima della frase NMEA.
- Ogni frase termina col **checksum** NMEA (XOR esadecimale), calcolato in automatico.

---

## 7. Parametri configurabili

Persistiti in `SharedPreferences` (`Prefs`), modificabili dalla `MainActivity`:

| Parametro | Default | Note |
|---|---|---|
| Nome host device | modello telefono | Campo hostname del syslog. |
| Server (IP o URL) | `192.168.1.100` | Risoluzione DNS automatica. |
| Porta | `514` | Porta syslog standard. |
| Protocollo | `UDP` | `UDP` (RFC 3164) o `TCP` (RFC 5424). |
| Intervallo | `10 s` | Minimo 1 secondo. |
| Dettaglio satelliti (GSV) | attivo | Interruttore per includere/escludere `$GPGSV`. |

---

## 8. Vincoli Android e permessi

L'app affronta esplicitamente le restrizioni introdotte dalle versioni recenti
di Android:

| Versione | Restrizione | Come viene gestita |
|---|---|---|
| Android 10 (API 29) | Posizione in background separata | Permesso `ACCESS_BACKGROUND_LOCATION` richiesto DOPO quello in primo piano. |
| Android 10 (API 29) | Tipo del foreground service | `foregroundServiceType="location"`. |
| Android 12 (API 31) | Avvio foreground service ristretto | Servizio avviato dalla UI o da receiver validi. |
| Android 13 (API 33) | Permesso notifiche | `POST_NOTIFICATIONS` richiesto a runtime. |
| Android 14 (API 34) | Permesso dedicato per FGS location | `FOREGROUND_SERVICE_LOCATION` dichiarato. |
| Tutte | Doze / battery optimization | Richiesta di esclusione + WakeLock. |

> **Limite non aggirabile via codice**: alcuni produttori (Xiaomi/MIUI, Huawei,
> Oppo, Samsung) applicano restrizioni proprietarie di "avvio automatico" e
> gestione batteria. Su questi telefoni l'utente deve abilitare manualmente
> l'autostart e togliere le restrizioni batteria per l'app.

---

## 9. Stack tecnologico e build

- **Linguaggio**: Kotlin 1.9.24
- **Build**: Gradle 8.7 + Android Gradle Plugin 8.5.2
- **SDK**: `compileSdk`/`targetSdk` = 34 (Android 14), `minSdk` = 24 (Android 7)
- **Librerie**: AndroidX Core/AppCompat/Material, WorkManager (solo per il watchdog)
- **Nessuna** dipendenza da Google Play Services.

Il risultato e' un APK autonomo, sideloadabile, con una superficie di
dipendenze minima. Istruzioni di compilazione nel `README.md`.
