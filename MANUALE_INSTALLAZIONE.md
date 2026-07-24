# GPS Syslog Tracker — Manuale di installazione e persistenza

Guida passo-passo per installare l'app e configurare Android in modo che il
servizio **resti attivo il piu' a lungo possibile**, anche a schermo spento e in
risparmio energetico. Segui i capitoli in ordine.

---

## Indice
1. [Cosa serve](#1-cosa-serve)
2. [Trasferire l'APK sul telefono](#2-trasferire-lapk-sul-telefono)
3. [Abilitare l'installazione da origini sconosciute](#3-abilitare-linstallazione-da-origini-sconosciute)
4. [Installare l'app](#4-installare-lapp)
5. [Concedere i permessi](#5-concedere-i-permessi)
6. [Escludere l'app dal risparmio energetico](#6-escludere-lapp-dal-risparmio-energetico)
7. [Impostazioni per marca di telefono (IMPORTANTE)](#7-impostazioni-per-marca-di-telefono-importante)
8. [Configurare i parametri e avviare](#8-configurare-i-parametri-e-avviare)
9. [Verificare che funzioni](#9-verificare-che-funzioni)
10. [Checklist finale di persistenza](#10-checklist-finale-di-persistenza)
11. [Risoluzione dei problemi](#11-risoluzione-dei-problemi)

---

## 1. Cosa serve

- Uno smartphone **Android 7.0 o superiore**.
- Il file **`GpsSyslogTracker-debug.apk`** (dalla Release GitHub o dalla cartella
  OneDrive).
- Un **server syslog** raggiungibile in rete (IP o URL) e la sua porta.
- Consigliato: che il telefono e il server siano sulla **stessa rete** (Wi-Fi o
  VPN), oppure che il server sia esposto pubblicamente.

---

## 2. Trasferire l'APK sul telefono

Scegli uno di questi metodi:

- **Cavo USB**: collega il telefono al PC, copia l'APK nella cartella `Download`.
- **Email**: invia l'APK a te stesso come allegato e aprilo dal telefono.
- **Cloud** (Drive, OneDrive): carica l'APK e scaricalo dall'app del cloud.
- **Link diretto**: apri sul telefono la pagina della Release GitHub e scarica
  `GpsSyslogTracker-debug.apk`.

---

## 3. Abilitare l'installazione da origini sconosciute

Android blocca di default le app che non vengono dal Play Store. Devi
autorizzare **l'app con cui apri l'APK** (di solito il file manager o il browser).

- **Android 8+**: quando tocchi l'APK, appare "Per motivi di sicurezza...".
  Tocca **Impostazioni** → attiva **Consenti da questa origine** → torna indietro.
- **Android 7**: *Impostazioni → Sicurezza → Origini sconosciute* → attiva.

---

## 4. Installare l'app

1. Apri l'APK (dal file manager, cartella `Download`).
2. Tocca **Installa**.
3. Se compare Google Play Protect ("app non verificata"), tocca **Installa
   comunque** (l'app e' sicura, semplicemente non viene dallo store).
4. Al termine, l'icona **bus con antenna GPS** compare nel drawer delle app.

---

## 5. Concedere i permessi

Apri l'app e premi il pulsante **Concedi permessi**. Concedi nell'ordine:

1. **Posizione** — scegli **"Consenti sempre"** (fondamentale!).
   - Su Android 10+ potresti dover scegliere prima **"Mentre usi l'app"** e poi,
     in una seconda richiesta, **"Consenti sempre"** (posizione in background).
   - Se non vedi "Consenti sempre", vai in *Impostazioni → App → GPS Syslog
     Tracker → Autorizzazioni → Posizione → **Consenti sempre***.
2. **Notifiche** (Android 13+) — **Consenti** (serve per la notifica permanente
   del servizio).

> Senza "Consenti sempre" sulla posizione, il servizio si ferma quando lo
> schermo si spegne.

---

## 6. Escludere l'app dal risparmio energetico

Nell'app premi **Escludi dal risparmio energetico** e conferma **Consenti**.

In alternativa, manualmente:
*Impostazioni → App → GPS Syslog Tracker → Batteria → **Senza restrizioni** /
**Non ottimizzata***.

Questo evita che il sistema (Doze) sospenda rete e timer quando il telefono e'
fermo e a schermo spento.

---

## 7. Impostazioni per marca di telefono (IMPORTANTE)

Molti produttori aggiungono un "risparmio energetico aggressivo" che **chiude le
app in background** ignorando le impostazioni standard di Android. Questo e' il
motivo n.1 per cui un servizio smette di funzionare dopo qualche ora. Applica le
impostazioni relative alla tua marca. Il sito **dontkillmyapp.com** ha guide
aggiornate marca per marca.

### Xiaomi / Redmi / POCO (MIUI / HyperOS)
- *Impostazioni → App → Gestisci app → GPS Syslog Tracker*:
  - **Avvio automatico**: ATTIVA.
  - **Risparmio batteria** → **Nessuna restrizione**.
- *Impostazioni → Batteria → (ingranaggio) → Prestazioni*: disattiva
  l'ottimizzazione per questa app.
- Nei **Recenti**: tieni premuta l'app → icona **lucchetto** (la blocca cosi'
  non viene chiusa dallo "svuota tutto").

### Huawei / Honor (EMUI)
- *Impostazioni → Batteria → Avvio app*: trova l'app, **disattiva "Gestione
  automatica"** e attiva manualmente **Avvio automatico**, **Avvio secondario**,
  **Esecuzione in background**.
- *Impostazioni → Batteria → Altro → Non chiudere dopo blocco schermo*.

### OPPO / Realme / OnePlus (ColorOS / OxygenOS)
- *Impostazioni → Batteria → (Uso batteria in background / Ottimizzazione)*:
  imposta l'app su **Non ottimizzare / Consenti attivita' in background**.
- *Impostazioni → App → GPS Syslog Tracker → Avvio automatico*: ATTIVA.
- Nei Recenti: blocca l'app (lucchetto).

### Vivo / iQOO (Funtouch OS)
- *Impostazioni → Batteria → Consumo elevato in background*: consenti per l'app.
- *Impostazioni → App → Avvio automatico*: ATTIVA per l'app.

### Samsung (One UI)
- *Impostazioni → App → GPS Syslog Tracker → Batteria → **Senza restrizioni***.
- *Impostazioni → Batteria → Limiti uso in background → **App inattive/in
  sospensione***: assicurati che l'app **NON** sia nell'elenco; se c'e',
  rimuovila.
- Disattiva eventuale *"Rimuovi app inutilizzate"*.

### Android "puro" (Pixel, Motorola, Nokia, ecc.)
- *Impostazioni → App → GPS Syslog Tracker → Batteria → **Senza restrizioni***.
- Di solito i passi 5 e 6 bastano.

---

## 8. Configurare i parametri e avviare

Nella schermata dell'app compila:

| Campo | Cosa mettere |
|---|---|
| **Nome host del device** | Un nome per riconoscere questo telefono nei log (es. `bus-01`). Finisce all'inizio di ogni riga syslog. |
| **Server syslog (IP o URL)** | Indirizzo del server (es. `192.168.1.100` o `logs.miodominio.it`). |
| **Porta** | Porta del server (syslog standard: `514`). |
| **Protocollo** | **UDP** (leggero, standard) o **TCP** (consegna garantita). |
| **Intervallo di invio (secondi)** | **La frequenza di polling.** Es. `10` = invio ogni 10 secondi. Minimo 1. |
| **Invia dettaglio satelliti ($GPGSV)** | Attivo = invia anche il dettaglio dei singoli satelliti. |

Poi:
1. Premi **Salva impostazioni**.
2. Premi **Avvia servizio**.
3. Compare la **notifica permanente** "GPS Syslog Tracker attivo": il servizio
   sta girando.

> **Frequenza di polling e batteria**: intervalli molto brevi (1-2 s) tengono il
> GPS piu' attivo e consumano di piu'. Per un buon compromesso, 10-30 secondi va
> bene nella maggior parte dei casi.

---

## 9. Verificare che funzioni

**Sul telefono**: la notifica mostra l'ultima posizione inviata; con
**Mostra log** vedi le righe `Send OK ...`.

**Sul server** (test rapido con netcat):
```bash
nc -u -l 514      # se hai scelto UDP sulla porta 514
nc -l 5140        # se hai scelto TCP (usa la stessa porta impostata nell'app)
```
Dovresti vedere righe tipo:
```
<134>Jul 24 10:11:12 bus-01 gpstracker: $GPGGA,101112.00,4305.1240,N,01229.5140,E,1,08,1.2,250.0,M,0.0,M,,*5C
```

Per la **struttura completa** delle frasi NMEA vedi `README.md`; per
l'architettura interna vedi `ARCHITETTURA.md`.

---

## 10. Checklist finale di persistenza

Spunta tutto per la massima continuita':

- [ ] Posizione impostata su **"Consenti sempre"**.
- [ ] Notifiche **consentite**.
- [ ] App **esclusa dal risparmio energetico** (batteria "Senza restrizioni").
- [ ] **Avvio automatico** attivato (per la tua marca).
- [ ] App **bloccata** nei recenti (lucchetto), dove previsto.
- [ ] Restrizioni batteria del **produttore** disattivate (vedi cap. 7).
- [ ] **Wi-Fi sempre attivo** anche in standby (se usi la rete locale):
      *Impostazioni → Wi-Fi → Avanzate → Mantieni Wi-Fi attivo durante lo
      standby → Sempre*.
- [ ] Servizio **avviato** e notifica permanente visibile.
- [ ] Verifica: la posizione arriva al server.

Il servizio riparte da solo dopo: riavvio del telefono, crash, rimozione dai
recenti, e viene ricontrollato ogni ~15 minuti da un watchdog interno. Le
impostazioni del cap. 7 servono proprio a non far uccidere il servizio dal
produttore prima che questi meccanismi possano intervenire.

---

## 11. Risoluzione dei problemi

| Sintomo | Causa probabile | Soluzione |
|---|---|---|
| Il servizio si ferma dopo qualche ora | Risparmio energetico del produttore | Applica il cap. 7 (autostart + no restrizioni + lucchetto). |
| Nessun dato al server con schermo spento | Posizione non "Consenti sempre" | Cap. 5: imposta "Consenti sempre". |
| "Nessun fix GPS" nei log | GPS spento o al chiuso | Attiva la posizione del telefono; prova all'aperto. |
| Niente arriva al server | Rete/porta/protocollo errati | Verifica IP/porta, che UDP/TCP coincidano, firewall del server. |
| Con TCP nessun dato, con UDP si' | Server non in ascolto in TCP | Configura il server per il TCP, o usa UDP. |
| Il servizio non riparte dopo il riavvio | Autostart disattivato | Cap. 7: attiva l'avvio automatico. |
| Consumo batteria alto | Intervallo troppo breve | Aumenta l'intervallo (es. 30 s) e/o disattiva il dettaglio satelliti. |
| Voglio vedere gli errori | — | Nell'app premi **Mostra log**; i file sono in `Android/data/it.gvasta.gpstracker/files/logs/`. |
