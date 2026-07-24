"""
GPS Daily Logger — app AppDaemon per Home Assistant.

Ascolta in tempo reale tutti i device_tracker che iniziano con un prefisso
(default device_tracker.gps_) e salva OGNI posizione in file giornalieri:

  - gps_AAAA-MM-GG.csv  -> append live, una riga per posizione (per analisi)
  - gps_AAAA-MM-GG.gpx  -> rigenerato dal CSV (una traccia per mezzo)

Serve perche' Home Assistant NON conserva nello storico gli aggiornamenti di
soli attributi (il device_tracker resta sempre "not_home"): la traccia va quindi
catturata mentre arriva.

Configurazione (apps.yaml):

  gps_daily_logger:
    module: gps_daily_logger
    class: GpsDailyLogger
    out_dir: /share/gps
    prefix: device_tracker.gps_
"""
import csv
import datetime
import os

import appdaemon.plugins.hass.hassapi as hass


class GpsDailyLogger(hass.Hass):

    def initialize(self):
        self.out_dir = self.args.get("out_dir", "/share/gps")
        self.prefix = self.args.get("prefix", "device_tracker.gps_")
        # Nome del sensore che elenca i file (mostrabile in dashboard).
        self.files_sensor = self.args.get("files_sensor", "sensor.gps_tracking_files")
        os.makedirs(self.out_dir, exist_ok=True)
        self._last_sensor = None
        # Ascolta tutti i device_tracker; il filtro per prefisso e' nel callback.
        self.listen_state(self.on_update, "device_tracker", attribute="all")
        # GPX di oggi ogni ora (:07), GPX definitivo di ieri alle 00:05.
        self.run_hourly(self.hourly, datetime.time(0, 7, 0))
        self.run_daily(self.nightly, datetime.time(0, 5, 0))
        # Pubblica subito il sensore con l'elenco file (senza toccare configuration.yaml).
        self.run_in(self.update_file_sensor, 15)
        self.log(f"GPS daily logger attivo: out_dir={self.out_dir} prefix={self.prefix}")

    def on_update(self, entity, attribute, old, new, kwargs):
        if not entity or not entity.startswith(self.prefix):
            return
        attrs = new.get("attributes", {}) if isinstance(new, dict) else {}
        lat = attrs.get("latitude")
        lon = attrs.get("longitude")
        if lat is None or lon is None:
            return
        now = datetime.datetime.now()
        day = now.strftime("%Y-%m-%d")
        host = attrs.get("friendly_name") or entity[len(self.prefix):]
        path = os.path.join(self.out_dir, f"gps_{day}.csv")
        new_file = (not os.path.exists(path)) or os.path.getsize(path) == 0
        try:
            with open(path, "a", newline="", encoding="utf-8") as f:
                w = csv.writer(f)
                if new_file:
                    w.writerow(["timestamp", "hostname", "lat", "lon", "speed_kmh",
                                "course", "altitude", "sats_used", "sats_in_view", "entity_id"])
                w.writerow([now.isoformat(timespec="seconds"), host, lat, lon,
                            attrs.get("speed_kmh"), attrs.get("course"), attrs.get("altitude"),
                            attrs.get("sats_used"), attrs.get("sats_in_view"), entity])
        except Exception as e:
            self.error(f"Scrittura CSV fallita: {e}")
        # Aggiorna il sensore file al massimo una volta al minuto.
        if self._last_sensor is None or (now - self._last_sensor).total_seconds() > 60:
            self._last_sensor = now
            self.update_file_sensor()

    def update_file_sensor(self, kwargs=None):
        """Pubblica sensor.gps_tracking_files con l'elenco dei file giornalieri."""
        try:
            files = sorted(f for f in os.listdir(self.out_dir)
                           if f.startswith("gps_") and (f.endswith(".csv") or f.endswith(".gpx")))
            total = 0
            for f in files:
                try:
                    total += os.path.getsize(os.path.join(self.out_dir, f))
                except OSError:
                    pass
            self.set_state(
                self.files_sensor,
                state=len(files),
                attributes={
                    "friendly_name": "File tracking GPS",
                    "icon": "mdi:folder-marker",
                    "unit_of_measurement": "file",
                    "directory": self.out_dir,
                    "file_list": files,
                    "latest": files[-1] if files else "",
                    "total_kb": round(total / 1024, 1),
                },
            )
        except Exception as e:
            self.error(f"Aggiornamento sensore file fallito: {e}")

    def hourly(self, kwargs):
        self._csv_to_gpx(datetime.date.today().strftime("%Y-%m-%d"))

    def nightly(self, kwargs):
        y = (datetime.date.today() - datetime.timedelta(days=1)).strftime("%Y-%m-%d")
        self._csv_to_gpx(y)

    def _csv_to_gpx(self, day):
        csv_path = os.path.join(self.out_dir, f"gps_{day}.csv")
        if not os.path.exists(csv_path):
            return
        tracks = {}
        with open(csv_path, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                tracks.setdefault(row["hostname"], []).append(row)

        def esc(s):
            return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        gpx_path = os.path.join(self.out_dir, f"gps_{day}.gpx")
        try:
            with open(gpx_path, "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
                f.write('<gpx version="1.1" creator="GPS Syslog Tracker" '
                        'xmlns="http://www.topografix.com/GPX/1/1">\n')
                for host, pts in tracks.items():
                    f.write(f'  <trk>\n    <name>{esc(host)}</name>\n    <trkseg>\n')
                    for r in pts:
                        ele = f'<ele>{r["altitude"]}</ele>' if r.get("altitude") else ''
                        t = f'<time>{esc(r["timestamp"])}</time>' if r.get("timestamp") else ''
                        f.write(f'      <trkpt lat="{r["lat"]}" lon="{r["lon"]}">{ele}{t}</trkpt>\n')
                    f.write('    </trkseg>\n  </trk>\n')
                f.write('</gpx>\n')
            self.log(f"GPX aggiornato: {gpx_path}")
        except Exception as e:
            self.error(f"Generazione GPX fallita: {e}")
