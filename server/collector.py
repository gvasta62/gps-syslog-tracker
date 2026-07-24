#!/usr/bin/env python3
"""
GPS Syslog Collector
====================

Riceve i messaggi syslog inviati da GPS Syslog Tracker (frasi NMEA incapsulate
in RFC 3164 su UDP o RFC 5424 su TCP), li decodifica e li fa "atterrare" in una
destinazione (SINK) a scelta:

    console | csv | jsonl | influxdb | postgres | homeassistant | mqtt

Il nucleo usa solo la libreria standard di Python. Le dipendenze extra
(psycopg2 per Postgres, paho-mqtt per MQTT) vengono importate SOLO se scegli
quel sink.

Uso:
    python3 collector.py --config config.ini

Vedi README.md nella stessa cartella per la configurazione dettagliata.
"""

import argparse
import configparser
import csv
import json
import math
import os
import re
import socket
import socketserver
import sys
import threading
import time
from datetime import datetime, timezone


# ---------------------------------------------------------------------------
# Utility di logging (su stderr, con timestamp)
# ---------------------------------------------------------------------------

def log(msg):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"{ts} {msg}", file=sys.stderr, flush=True)


# ---------------------------------------------------------------------------
# Parsing NMEA
# ---------------------------------------------------------------------------

def nmea_valid(sentence):
    """Verifica il checksum NMEA (XOR di tutti i caratteri tra '$' e '*')."""
    s = sentence.strip()
    if not s.startswith("$") or "*" not in s:
        return False
    body, _, cks = s[1:].partition("*")
    calc = 0
    for ch in body:
        calc ^= ord(ch)
    try:
        return calc == int(cks[:2], 16)
    except ValueError:
        return False


def ddmm_to_deg(value, hemi):
    """Converte una coordinata NMEA 'ddmm.mmmm' in gradi decimali."""
    if not value:
        return None
    try:
        v = float(value)
    except ValueError:
        return None
    deg = int(v // 100)
    minutes = v - deg * 100
    dec = deg + minutes / 60.0
    if hemi in ("S", "W"):
        dec = -dec
    return round(dec, 7)


def nmea_datetime(timestr, datestr):
    """Costruisce un datetime UTC da ora 'hhmmss.ss' e data 'ddmmyy'."""
    if not timestr or not datestr:
        return None
    try:
        hh = int(timestr[0:2]); mm = int(timestr[2:4]); ss = float(timestr[4:] or 0)
        dd = int(datestr[0:2]); mo = int(datestr[2:4]); yy = 2000 + int(datestr[4:6])
        micro = int(round((ss - int(ss)) * 1_000_000))
        return datetime(yy, mo, dd, hh, mm, int(ss), micro, tzinfo=timezone.utc)
    except (ValueError, IndexError):
        return None


def _f(value):
    """float() tollerante: stringa vuota -> None."""
    try:
        return float(value)
    except (ValueError, TypeError):
        return None


def _i(value):
    """int() tollerante: stringa vuota -> None."""
    try:
        return int(value)
    except (ValueError, TypeError):
        return None


def parse_rmc(fields):
    # $GPRMC,time,status,lat,N/S,lon,E/W,speed_kn,course,date,magvar,magdir
    d = {}
    d["fix_dt"] = nmea_datetime(fields[1] if len(fields) > 1 else "",
                                fields[9] if len(fields) > 9 else "")
    d["status"] = fields[2] if len(fields) > 2 else ""
    d["lat"] = ddmm_to_deg(fields[3], fields[4]) if len(fields) > 4 else None
    d["lon"] = ddmm_to_deg(fields[5], fields[6]) if len(fields) > 6 else None
    d["speed_kn"] = _f(fields[7]) if len(fields) > 7 else None
    d["course"] = _f(fields[8]) if len(fields) > 8 else None
    return d


def parse_gga(fields):
    # $GPGGA,time,lat,N/S,lon,E/W,quality,numsats,hdop,alt,M,geoid,M,age,ref
    d = {}
    d["time"] = fields[1] if len(fields) > 1 else ""
    d["lat"] = ddmm_to_deg(fields[2], fields[3]) if len(fields) > 3 else None
    d["lon"] = ddmm_to_deg(fields[4], fields[5]) if len(fields) > 5 else None
    d["quality"] = _i(fields[6]) if len(fields) > 6 else None
    d["sats"] = _i(fields[7]) if len(fields) > 7 else None
    d["hdop"] = _f(fields[8]) if len(fields) > 8 else None
    d["alt"] = _f(fields[9]) if len(fields) > 9 else None
    return d


def parse_gsv(fields):
    # $GPGSV,total,msgnum,inview,{prn,elev,azim,snr}...
    total = _i(fields[1]) if len(fields) > 1 else None
    msgnum = _i(fields[2]) if len(fields) > 2 else None
    inview = _i(fields[3]) if len(fields) > 3 else None
    sats = []
    i = 4
    while i + 3 < len(fields):  # servono 4 campi: prn, elev, azim, snr
        prn = _i(fields[i])
        elev = _i(fields[i + 1])
        azim = _i(fields[i + 2])
        snr = _i(fields[i + 3])
        if prn is not None:
            sats.append({"prn": prn, "elev": elev, "azim": azim, "snr": snr})
        i += 4
    return total, msgnum, inview, sats


# ---------------------------------------------------------------------------
# Parsing della "busta" syslog (RFC 3164 e RFC 5424)
# ---------------------------------------------------------------------------

# 5424: <PRI>1 TIMESTAMP HOST APP PROCID MSGID STRUCTURED-DATA MSG
_RE_5424 = re.compile(
    r"^1\s+\S+\s+(?P<host>\S+)\s+\S+\s+\S+\s+\S+\s+(?:\[.*?\]|-)\s+(?P<msg>.*)$"
)
# 3164: <PRI>Mmm dd HH:MM:SS HOST TAG: MSG
_RE_3164 = re.compile(
    r"^[A-Z][a-z]{2}\s+\d+\s+\d{2}:\d{2}:\d{2}\s+(?P<host>\S+)\s+[^:]*:\s?(?P<msg>.*)$"
)


def parse_syslog(line):
    """Ritorna (hostname, payload) da una riga syslog. hostname None se ignoto."""
    line = line.strip()
    m = re.match(r"^<(\d+)>(.*)$", line)
    rest = m.group(2) if m else line

    m5 = _RE_5424.match(rest)
    if m5:
        return m5.group("host"), m5.group("msg")
    m3 = _RE_3164.match(rest)
    if m3:
        return m3.group("host"), m3.group("msg")

    # Fallback: cerca l'inizio della frase NMEA.
    idx = rest.find("$")
    if idx >= 0:
        return None, rest[idx:]
    return None, None


# ---------------------------------------------------------------------------
# Assemblatore: raggruppa RMC + GGA + GSV di uno stesso intervallo in un record
# ---------------------------------------------------------------------------

class HostState:
    def __init__(self):
        self.rmc = {}
        self.gga = {}
        self.gsv = []
        self.dirty = False
        self.last = time.monotonic()

    def reset_burst(self):
        self.rmc = {}
        self.gga = {}
        self.gsv = []
        self.dirty = False


class Assembler:
    """
    L'app invia, per ogni intervallo, in ordine: $GPRMC, $GPGGA, poi le $GPGSV.
    Rileviamo l'inizio di un nuovo intervallo dall'arrivo di una nuova $GPRMC:
    a quel punto emettiamo il record precedente. Un thread "reaper" emette
    l'ultimo record rimasto in sospeso dopo un periodo di inattivita'.
    """

    def __init__(self, sink):
        self.sink = sink
        self.states = {}
        self.lock = threading.Lock()

    def feed(self, host, sentence):
        stype = sentence[3:6]  # 'RMC' / 'GGA' / 'GSV' (vale anche per GN/GL/GA)
        fields = sentence.split("*")[0].split(",")
        with self.lock:
            st = self.states.setdefault(host, HostState())
            if stype == "RMC":
                if st.dirty:
                    self._emit(host, st)
                st.reset_burst()
                st.rmc = parse_rmc(fields)
                st.dirty = True
            elif stype == "GGA":
                st.gga = parse_gga(fields)
                st.dirty = True
            elif stype == "GSV":
                total, msgnum, inview, sats = parse_gsv(fields)
                if msgnum == 1:
                    st.gsv = []
                st.gsv.extend(sats)
                st.dirty = True
            else:
                return
            st.last = time.monotonic()

    def flush_stale(self, timeout):
        now = time.monotonic()
        with self.lock:
            for host, st in list(self.states.items()):
                if st.dirty and (now - st.last) > timeout:
                    self._emit(host, st)

    def _emit(self, host, st):
        rmc, gga, gsv = st.rmc, st.gga, st.gsv
        lat = gga.get("lat") if gga.get("lat") is not None else rmc.get("lat")
        lon = gga.get("lon") if gga.get("lon") is not None else rmc.get("lon")
        st.reset_burst()
        if lat is None or lon is None:
            log(f"[{host}] fix senza posizione valida, ignorato")
            return
        fix_dt = rmc.get("fix_dt")
        speed_kn = rmc.get("speed_kn")
        rec = {
            "hostname": host,
            "received_at": datetime.now(timezone.utc).isoformat(),
            "fix_time": fix_dt.isoformat() if fix_dt else None,
            "lat": lat,
            "lon": lon,
            "alt_m": gga.get("alt"),
            "fix_quality": gga.get("quality"),
            "sats_used": gga.get("sats"),
            "hdop": gga.get("hdop"),
            "speed_kn": speed_kn,
            "speed_kmh": round(speed_kn * 1.852, 2) if speed_kn is not None else None,
            "course_deg": rmc.get("course"),
            "sats_in_view": len(gsv),
            "satellites": gsv,
        }
        try:
            self.sink.write(rec)
        except Exception as e:  # un sink che fallisce non deve fermare il collector
            log(f"[SINK] errore nella scrittura: {e}")


# ---------------------------------------------------------------------------
# SINK: destinazioni del dato decodificato
# ---------------------------------------------------------------------------

CSV_COLUMNS = [
    "hostname", "received_at", "fix_time", "lat", "lon", "alt_m",
    "fix_quality", "sats_used", "hdop", "speed_kn", "speed_kmh",
    "course_deg", "sats_in_view", "satellites",
]


class ConsoleSink:
    def write(self, rec):
        print(json.dumps(rec, ensure_ascii=False), flush=True)

    def close(self):
        pass


class JsonlSink:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()

    def write(self, rec):
        with self.lock, open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    def close(self):
        pass


class CsvSink:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()

    def write(self, rec):
        row = dict(rec)
        row["satellites"] = json.dumps(rec["satellites"], ensure_ascii=False)
        new = not os.path.exists(self.path) or os.path.getsize(self.path) == 0
        with self.lock, open(self.path, "a", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
            if new:
                w.writeheader()
            w.writerow(row)

    def close(self):
        pass


class InfluxSink:
    """InfluxDB 2.x via HTTP line protocol (solo stdlib: urllib)."""

    def __init__(self, url, token, org, bucket, measurement):
        self.url = url.rstrip("/")
        self.token = token
        self.org = org
        self.bucket = bucket
        self.measurement = measurement

    def write(self, rec):
        import urllib.request

        def esc_tag(v):
            return str(v).replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=")

        fields = []
        for k in ("lat", "lon", "alt_m", "hdop", "speed_kn", "speed_kmh", "course_deg"):
            if rec.get(k) is not None:
                fields.append(f"{k}={float(rec[k])}")
        for k in ("sats_used", "sats_in_view", "fix_quality"):
            if rec.get(k) is not None:
                fields.append(f"{k}={int(rec[k])}i")
        if not fields:
            return
        line = f"{self.measurement},host={esc_tag(rec['hostname'])} {','.join(fields)}"
        dt = rec.get("fix_time") or rec.get("received_at")
        try:
            ts = datetime.fromisoformat(dt)
            line += f" {int(ts.timestamp() * 1_000_000_000)}"
        except Exception:
            pass

        endpoint = f"{self.url}/api/v2/write?org={self.org}&bucket={self.bucket}&precision=ns"
        req = urllib.request.Request(
            endpoint, data=line.encode("utf-8"), method="POST",
            headers={"Authorization": f"Token {self.token}",
                     "Content-Type": "text/plain; charset=utf-8"},
        )
        urllib.request.urlopen(req, timeout=5).read()

    def close(self):
        pass


class PostgresSink:
    """Postgres/PostGIS via psycopg2 (dipendenza opzionale)."""

    def __init__(self, dsn, table):
        try:
            import psycopg2  # noqa
        except ImportError:
            raise SystemExit("Sink 'postgres' richiede: pip install psycopg2-binary")
        self.psycopg2 = __import__("psycopg2")
        self.dsn = dsn
        self.table = table
        self.lock = threading.Lock()
        self.conn = self.psycopg2.connect(dsn)
        self.conn.autocommit = True

    def write(self, rec):
        sql = (
            f"INSERT INTO {self.table} "
            "(hostname, fix_time, received_at, lat, lon, alt_m, fix_quality, "
            " sats_used, hdop, speed_kn, speed_kmh, course_deg, sats_in_view, satellites) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
        )
        vals = (
            rec["hostname"], rec["fix_time"], rec["received_at"], rec["lat"], rec["lon"],
            rec["alt_m"], rec["fix_quality"], rec["sats_used"], rec["hdop"],
            rec["speed_kn"], rec["speed_kmh"], rec["course_deg"], rec["sats_in_view"],
            json.dumps(rec["satellites"]),
        )
        with self.lock:
            try:
                with self.conn.cursor() as cur:
                    cur.execute(sql, vals)
            except self.psycopg2.Error:
                # riconnessione in caso di connessione caduta
                self.conn = self.psycopg2.connect(self.dsn)
                self.conn.autocommit = True
                with self.conn.cursor() as cur:
                    cur.execute(sql, vals)

    def close(self):
        try:
            self.conn.close()
        except Exception:
            pass


class HomeAssistantSink:
    """Home Assistant via REST: imposta un device_tracker con lat/lon (source_type=gps)."""

    def __init__(self, base_url, token, entity_prefix="gps_", user_agent="curl/8.5.0"):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.entity_prefix = entity_prefix
        # Alcuni reverse proxy / Cloudflare bloccano richieste senza User-Agent.
        self.user_agent = user_agent

    @staticmethod
    def _slug(s):
        return re.sub(r"[^a-z0-9_]", "_", s.lower())

    def write(self, rec):
        import urllib.request

        entity = f"device_tracker.{self.entity_prefix}{self._slug(rec['hostname'])}"
        payload = {
            "state": "not_home",
            "attributes": {
                "source_type": "gps",
                "latitude": rec["lat"],
                "longitude": rec["lon"],
                "gps_accuracy": rec.get("hdop") or 0,
                "altitude": rec.get("alt_m"),
                "speed_kmh": rec.get("speed_kmh"),
                "course": rec.get("course_deg"),
                "sats_used": rec.get("sats_used"),
                "sats_in_view": rec.get("sats_in_view"),
                "friendly_name": rec["hostname"],
            },
        }
        req = urllib.request.Request(
            f"{self.base_url}/api/states/{entity}",
            data=json.dumps(payload).encode("utf-8"), method="POST",
            headers={"Authorization": f"Bearer {self.token}",
                     "Content-Type": "application/json",
                     "User-Agent": self.user_agent},
        )
        urllib.request.urlopen(req, timeout=10).read()

    def close(self):
        pass


class MqttSink:
    """MQTT via paho-mqtt (dipendenza opzionale). Con HA discovery opzionale."""

    def __init__(self, host, port, username, password, topic_prefix, ha_discovery):
        try:
            import paho.mqtt.client as mqtt
        except ImportError:
            raise SystemExit("Sink 'mqtt' richiede: pip install paho-mqtt")
        self.topic_prefix = topic_prefix.rstrip("/")
        self.ha_discovery = ha_discovery
        self.client = mqtt.Client()
        if username:
            self.client.username_pw_set(username, password)
        self.client.connect(host, port, keepalive=60)
        self.client.loop_start()
        self._announced = set()

    @staticmethod
    def _slug(s):
        return re.sub(r"[^a-z0-9_]", "_", s.lower())

    def write(self, rec):
        slug = self._slug(rec["hostname"])
        base = f"{self.topic_prefix}/{slug}"
        # JSON completo
        self.client.publish(f"{base}/fix", json.dumps(rec, ensure_ascii=False))

        if self.ha_discovery:
            if slug not in self._announced:
                cfg_topic = f"homeassistant/device_tracker/{slug}/config"
                cfg = {
                    "name": rec["hostname"],
                    "unique_id": f"gpssyslog_{slug}",
                    "json_attributes_topic": f"{base}/attributes",
                    "state_topic": f"{base}/state",
                    "source_type": "gps",
                }
                self.client.publish(cfg_topic, json.dumps(cfg), retain=True)
                self._announced.add(slug)
            attrs = {
                "latitude": rec["lat"], "longitude": rec["lon"],
                "gps_accuracy": rec.get("hdop") or 0,
                "altitude": rec.get("alt_m"), "speed_kmh": rec.get("speed_kmh"),
                "course": rec.get("course_deg"), "sats_used": rec.get("sats_used"),
                "sats_in_view": rec.get("sats_in_view"),
            }
            self.client.publish(f"{base}/attributes", json.dumps(attrs))
            self.client.publish(f"{base}/state", "not_home")

    def close(self):
        try:
            self.client.loop_stop()
            self.client.disconnect()
        except Exception:
            pass


def make_sink(cfg):
    t = cfg.get("sink", "type", fallback="console").lower()
    if t == "console":
        return ConsoleSink()
    if t == "jsonl":
        return JsonlSink(cfg.get("jsonl", "path", fallback="fixes.jsonl"))
    if t == "csv":
        return CsvSink(cfg.get("csv", "path", fallback="fixes.csv"))
    if t == "influxdb":
        return InfluxSink(
            cfg.get("influxdb", "url", fallback="http://localhost:8086"),
            cfg.get("influxdb", "token", fallback=""),
            cfg.get("influxdb", "org", fallback=""),
            cfg.get("influxdb", "bucket", fallback="gps"),
            cfg.get("influxdb", "measurement", fallback="gps_fix"),
        )
    if t == "postgres":
        return PostgresSink(
            cfg.get("postgres", "dsn", fallback=""),
            cfg.get("postgres", "table", fallback="gps_fix"),
        )
    if t == "homeassistant":
        return HomeAssistantSink(
            cfg.get("homeassistant", "base_url", fallback="http://localhost:8123"),
            cfg.get("homeassistant", "token", fallback=""),
            cfg.get("homeassistant", "entity_prefix", fallback="gps_"),
            cfg.get("homeassistant", "user_agent", fallback="curl/8.5.0"),
        )
    if t == "mqtt":
        return MqttSink(
            cfg.get("mqtt", "host", fallback="localhost"),
            cfg.getint("mqtt", "port", fallback=1883),
            cfg.get("mqtt", "username", fallback=""),
            cfg.get("mqtt", "password", fallback=""),
            cfg.get("mqtt", "topic_prefix", fallback="gps"),
            cfg.getboolean("mqtt", "ha_discovery", fallback=True),
        )
    raise SystemExit(f"Tipo di sink sconosciuto: {t}")


# ---------------------------------------------------------------------------
# Server UDP/TCP
# ---------------------------------------------------------------------------

class _App:
    def __init__(self, assembler):
        self.assembler = assembler

    def handle_line(self, line, src_ip):
        if not line or not line.strip():
            return
        host, msg = parse_syslog(line)
        if not msg:
            return
        msg = msg.strip()
        if not msg.startswith("$"):
            return
        if not nmea_valid(msg):
            log(f"NMEA con checksum non valido, scartato: {msg}")
            return
        self.assembler.feed(host or src_ip, msg)


class _UDPHandler(socketserver.BaseRequestHandler):
    def handle(self):
        data = self.request[0]
        text = data.decode("utf-8", errors="replace")
        for line in text.splitlines():
            self.server.app.handle_line(line, self.client_address[0])


class _TCPHandler(socketserver.StreamRequestHandler):
    def handle(self):
        for raw in self.rfile:  # itera per righe terminate da \n
            line = raw.decode("utf-8", errors="replace")
            self.server.app.handle_line(line, self.client_address[0])


class _ThreadingUDP(socketserver.ThreadingMixIn, socketserver.UDPServer):
    allow_reuse_address = True


class _ThreadingTCP(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="GPS Syslog Collector")
    ap.add_argument("--config", default="config.ini", help="percorso del file di configurazione")
    args = ap.parse_args()

    cfg = configparser.ConfigParser()
    if os.path.exists(args.config):
        cfg.read(args.config)
    else:
        log(f"Config '{args.config}' non trovata: uso i default (console, UDP 514).")

    bind = cfg.get("listen", "bind", fallback="0.0.0.0")
    udp_on = cfg.getboolean("listen", "udp", fallback=True)
    tcp_on = cfg.getboolean("listen", "tcp", fallback=True)
    udp_port = cfg.getint("listen", "udp_port", fallback=514)
    tcp_port = cfg.getint("listen", "tcp_port", fallback=514)
    flush_timeout = cfg.getfloat("collector", "flush_timeout", fallback=5.0)

    sink = make_sink(cfg)
    assembler = Assembler(sink)
    app = _App(assembler)

    servers = []
    if udp_on:
        srv = _ThreadingUDP((bind, udp_port), _UDPHandler)
        srv.app = app
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        servers.append(srv)
        log(f"In ascolto UDP su {bind}:{udp_port}")
    if tcp_on:
        srv = _ThreadingTCP((bind, tcp_port), _TCPHandler)
        srv.app = app
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        servers.append(srv)
        log(f"In ascolto TCP su {bind}:{tcp_port}")

    log(f"Sink attivo: {cfg.get('sink', 'type', fallback='console')}")

    # Thread "reaper": emette l'ultimo record rimasto in sospeso.
    def reaper():
        while True:
            time.sleep(1.0)
            assembler.flush_stale(flush_timeout)
    threading.Thread(target=reaper, daemon=True).start()

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        log("Arresto in corso...")
    finally:
        for srv in servers:
            srv.shutdown()
        sink.close()


if __name__ == "__main__":
    main()
