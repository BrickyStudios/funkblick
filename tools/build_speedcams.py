#!/usr/bin/env python3
"""Builds app/src/main/assets/speedcams.tsv: fixed speed cameras in Germany from OpenStreetMap.

Data © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright).
One line per camera: lat, lon, maxspeed (or empty), direction in degrees (or empty), tab-separated.
Run: python3 tools/build_speedcams.py
"""
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/speedcams.tsv"
QUERY = """[out:json][timeout:300];
area["ISO3166-1"="DE"][admin_level=2]->.de;
node["highway"="speed_camera"](area.de);
out body;"""
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


def main():
    data = urllib.parse.urlencode({"data": QUERY}).encode()
    nodes = None
    for server in SERVERS:  # public Overpass instances are often busy (502/504): try the next one
        req = urllib.request.Request(server, data=data, headers={"User-Agent": "funkblick build_speedcams.py (github.com/BrickyStudios/funkblick)"})
        try:
            nodes = json.load(urllib.request.urlopen(req, timeout=400))["elements"]
            break
        except Exception as e:
            print(f"{server}: {e}")
    assert nodes is not None, "no Overpass server answered"
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
