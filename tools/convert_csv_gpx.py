#!/usr/bin/env python3
"""
Converte un CSV di posizioni (prodotto dal GPS Daily Logger) in un file GPX,
con una traccia per mezzo. Solo libreria standard.

Uso:
    python3 convert_csv_gpx.py gps_2026-07-24.csv [gps_2026-07-24.gpx]
"""
import csv
import os
import sys


def esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    if len(sys.argv) < 2:
        sys.exit("Uso: convert_csv_gpx.py INPUT.csv [OUTPUT.gpx]")
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(src)[0] + ".gpx"

    tracks = {}
    with open(src, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            tracks.setdefault(row["hostname"], []).append(row)

    with open(dst, "w", encoding="utf-8") as f:
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

    n = sum(len(v) for v in tracks.values())
    print(f"GPX scritto: {dst} ({n} punti, {len(tracks)} mezzi)")


if __name__ == "__main__":
    main()
