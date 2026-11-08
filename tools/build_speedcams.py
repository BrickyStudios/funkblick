#!/usr/bin/env python3
"""Builds app/src/main/assets/speedcams.tsv: fixed speed cameras in Germany from OpenStreetMap,
and with `traffic` app/src/main/assets/trafficcams.tsv: traffic cameras, number plate scanners (ALPR) and traffic counters.

Data © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright).
One line per camera: lat, lon, maxspeed (or empty), direction in degrees (or empty), kind (traffic file only), tab-separated.
Run: python3 tools/build_speedcams.py [traffic]
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/speedcams.tsv"
QUERY = """[out:json][timeout:300];
area["ISO3166-1"="DE"][admin_level=2]->.de;
node["highway"="speed_camera"](area.de);
out body;"""
TRAFFIC_OUT = OUT.with_name("trafficcams.tsv")
TRAFFIC = """[out:json][timeout:300];
area["ISO3166-1"="DE"][admin_level=2]->.de;
(node["man_made"="surveillance"]["surveillance:zone"="traffic"](area.de);
 node["man_made"="surveillance"]["surveillance:type"="ALPR"](area.de);
 nwr["man_made"="monitoring_station"]["monitoring:traffic"="yes"](area.de););
out center tags;"""
SERVERS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
           "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]
COMPASS = {"N": 0, "NE": 45, "E": 90, "SE": 135, "S": 180, "SW": 225, "W": 270, "NW": 315}


def direction(tag):
    if not tag:
        return ""
    tag = tag.split(";")[0].strip().upper()
    if tag in COMPASS:
        return str(COMPASS[tag])
    return str(int(float(tag)) % 360) if re.fullmatch(r"\d+(\.\d+)?", tag) else ""


def fetch(query):
    data = urllib.parse.urlencode({"data": query}).encode()
    nodes = None
    for server in SERVERS:  # public Overpass instances are often busy (502/504): try the next one
        req = urllib.request.Request(server, data=data, headers={"User-Agent": "funkblick build_speedcams.py (github.com/BrickyStudios/funkblick)"})
        try:
            nodes = json.load(urllib.request.urlopen(req, timeout=400))["elements"]
            break
        except Exception as e:
            print(f"{server}: {e}")
    assert nodes is not None, "no Overpass server answered"
    return nodes


def traffic():
    rows = set()
    for n in fetch(TRAFFIC):
        t = n.get("tags", {})
        if "highway" in t or "enforcement" in t:  # a speed camera mapped twice: it's in speedcams.tsv already
            continue
        lat, lon = (n["lat"], n["lon"]) if "lat" in n else (n["center"]["lat"], n["center"]["lon"])
        kind = "sensor" if t.get("man_made") == "monitoring_station" else "alpr" if t.get("surveillance:type") == "ALPR" else "cam"
        rows.add(f"{lat:.5f}\t{lon:.5f}\t\t\t{kind}")
    assert len(rows) > 1000, f"only {len(rows)} entries, query broken?"
    TRAFFIC_OUT.write_text("\n".join(sorted(rows)) + "\n", encoding="utf-8")
    print(f"{TRAFFIC_OUT}: {len(rows)} entries, {TRAFFIC_OUT.stat().st_size // 1024} KB")


def main():
    if "traffic" in sys.argv[1:]:
        return traffic()
    nodes = fetch(QUERY)
    rows = []
    for n in nodes:
        t = n.get("tags", {})
        speed = re.match(r"\d+", t.get("maxspeed", ""))
        rows.append(f'{n["lat"]:.5f}\t{n["lon"]:.5f}\t{speed.group(0) if speed else ""}\t{direction(t.get("direction"))}')
    assert len(rows) > 1000, f"only {len(rows)} cameras, query broken?"
    OUT.write_text("\n".join(sorted(rows)) + "\n", encoding="utf-8")
    print(f"{OUT}: {len(rows)} cameras, {OUT.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
