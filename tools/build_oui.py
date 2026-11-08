#!/usr/bin/env python3
"""Builds app/src/main/assets/oui.txt from the IEEE registries (MA-L /24, MA-M /28, MA-S /36).

One line per assignment: hex prefix (6, 7 or 9 digits), a tab, the shortened company name.
Run: python3 tools/build_oui.py            (downloads from standards-oui.ieee.org)
     python3 tools/build_oui.py DIR        (uses oui.csv, mam.csv, oui36.csv from DIR)
"""
import csv
import io
import re
import sys
import urllib.request
from pathlib import Path

SOURCES = {
    "oui.csv": "https://standards-oui.ieee.org/oui/oui.csv",
    "mam.csv": "https://standards-oui.ieee.org/oui28/mam.csv",
    "oui36.csv": "https://standards-oui.ieee.org/oui36/oui36.csv",
}
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/oui.txt"

# legal forms and filler that only make names longer on a phone screen
SUFFIX = re.compile(
    r"[,.\s]+(inc|incorporated|ltd|limited|llc|l\.l\.c|co|corp|corporation|company|gmbh|ag|kg|se|sa|s\.a|"
    r"srl|s\.r\.l|spa|s\.p\.a|bv|b\.v|nv|oy|ab|as|a/s|plc|pte|pty|ulc|uab|sas|kk|k\.k|technologies|technology|"
    r"tech|electronics|international|holdings|group|& co)\.?$",
    re.I,
)


def short(name: str) -> str:
    name = re.sub(r"\s+", " ", name.replace('"', "")).strip(" ,.")
    name = re.sub(r"^UAB\s+", "", name)
    for _ in range(4):
        new = SUFFIX.sub("", name).strip(" ,.")
        if new == name or not new:
            break
        name = new
    return name[:48]


def rows(text: str):
    for r in csv.DictReader(io.StringIO(text)):
        a = (r.get("Assignment") or "").strip().upper()
        n = (r.get("Organization Name") or "").strip()
        if re.fullmatch(r"[0-9A-F]{6,9}", a) and n:
            yield a, short(n)


def main():
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    entries = {}
    for fname, url in SOURCES.items():
        if src:
            text = (src / fname).read_text(encoding="utf-8")
        else:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (funkblick build_oui.py)"})
            text = urllib.request.urlopen(req, timeout=60).read().decode("utf-8")
        for a, n in rows(text):
            entries[a] = n
    # the 24-bit blocks the IEEE split into /28 and /36 are registered to the IEEE itself: drop them,
    # the longer prefixes carry the real owner
    entries = {a: n for a, n in entries.items() if not n.lower().startswith("ieee registration authority")}
    assert len(entries) > 40_000, f"only {len(entries)} entries, download broken?"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join(f"{a}\t{n}\n" for a, n in sorted(entries.items())), encoding="utf-8")
    print(f"{OUT}: {len(entries)} prefixes, {OUT.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
