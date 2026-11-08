#!/usr/bin/env python3
"""Builds the GitHub Pages site into docs/ (served at brickystudios.github.io/funkblick).

One home page per language plus one page per search intent. Texts live only here: change them, then run
python3 tools/build_site.py
"""
import html
import json
import re
from pathlib import Path

BASE = "https://brickystudios.github.io/funkblick/"
REPO = "https://github.com/BrickyStudios/funkblick"
APK = REPO + "/releases/latest/download/Funkblick.apk"
DOCS = Path(__file__).resolve().parent.parent / "docs"
IMG = BASE + "bilder/social-preview.png"
PAGES = []


def page(slug, lang, title, desc, body, schema, alt=None):
    """slug '' = home. alt = {lang: slug} of the same page in the other language."""
    assert len(title) <= 62, f"title too long ({len(title)}): {title}"
    assert len(desc) <= 160, f"description too long ({len(desc)}): {desc}"
    root = "../" * slug.count("/")
    de = lang == "de"
    alt = dict(alt or {})
    alt.setdefault(lang, slug)
    links = "".join(f'<link rel="alternate" hreflang="{l}" href="{BASE + s}">' for l, s in alt.items())
    if "en" in alt:
        links += f'<link rel="alternate" hreflang="x-default" href="{BASE + alt["en"]}">'
    other = alt.get("en" if de else "de", "" if de else "de/")
    nav = (
        f'<a href="{root}de/airtag-finden-android/">Tracker</a><a href="{root}de/versteckte-kamera-finden/">Kameras</a>'
        f'<a href="{root}de/wlan-kanal-fritzbox/">WLAN-Kanal</a><a href="{root}{other}" hreflang="en">English</a>'
        if de else
        f'<a href="{root}tracker-detector-app/">Trackers</a><a href="{root}hidden-camera-detector/">Cameras</a>'
        f'<a href="{root}wifi-analyzer/">Channels</a><a href="{root}{other}" hreflang="de">Deutsch</a>'
    )
    # browser language differs from page language: one dismissible line, never a redirect (Google crawls from the US)
    langbar = (
        f'<p class="langbar" id="langbar" hidden><a href="{root}{other}">{"Read this in English" if de else "Diese Seite auf Deutsch lesen"}</a>'
        f'<button type="button" onclick="this.parentNode.hidden=true;try{{localStorage.setItem(\'fb.lang\',\'1\')}}catch(e){{}}">{"Ausblenden" if de else "Hide"}</button></p>'
        f'<script>try{{if(!localStorage.getItem("fb.lang")&&(navigator.language||"").toLowerCase().startsWith("de")!=={str(de).lower()})document.getElementById("langbar").hidden=false}}catch(e){{}}</script>'
    )
    foot = (
        "Funkblick ist freie Software unter GPL-3.0. Herstellerdaten: IEEE. Blitzer: © OpenStreetMap-Mitwirkende (ODbL). "
        "Kein Projekt von Apple, Google, Samsung, Blitzer.de oder SØPHIA."
        if de else
        "Funkblick is free software under GPL-3.0. Maker data: IEEE. Speed cameras: © OpenStreetMap contributors (ODbL). "
        "Not affiliated with Apple, Google, Samsung, Blitzer.de or SØPHIA."
    )
    body = curly(body)
    out = f"""<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<meta name="description" content="{html.escape(desc)}">
<link rel="canonical" href="{BASE + slug}">{links}
<meta property="og:type" content="website">
<meta property="og:url" content="{BASE + slug}">
<meta property="og:title" content="{html.escape(title)}">
<meta property="og:description" content="{html.escape(desc)}">
<meta property="og:image" content="{IMG}">
<meta property="og:locale" content="{"de_DE" if de else "en_US"}">
<meta name="twitter:card" content="summary_large_image">
<meta name="theme-color" content="#0D0C0A">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='7' fill='%230D0C0A'/%3E%3Ccircle cx='16' cy='16' r='10' fill='none' stroke='%23FFB020' stroke-width='2'/%3E%3Cpath d='M16 16 L16 6 A10 10 0 0 1 24.7 11 Z' fill='%23FFB020'/%3E%3C/svg%3E">
<link rel="preload" href="{root}fonts/ChakraPetch-Bold.ttf" as="font" type="font/ttf" crossorigin>
<link rel="stylesheet" href="{root}style.css">
<script type="application/ld+json">{json.dumps(schema, ensure_ascii=False)}</script>
</head>
<body>
{langbar}
<header class="top"><a class="brand" href="{root or './'}">Funkblick</a><nav>{nav}</nav></header>
<main>
{body}
</main>
<footer><p>{foot}</p><ul><li><a href="{REPO}">{"Quellcode" if de else "Source code"}</a></li><li><a href="{REPO}/releases">Releases</a></li><li><a href="{root}sitemap.xml">Sitemap</a></li></ul></footer>
</body>
</html>
"""
    target = DOCS / slug / "index.html"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(out, encoding="utf-8")
    PAGES.append(slug)


def curly(s):
    """Straight apostrophes between letters become typographic ones; tags and attributes have none."""
    return re.sub(r"(?<=\w)'(?=\w)", "\u2019", s)


def app_schema(lang, desc):
    return {
        "@context": "https://schema.org", "@type": "SoftwareApplication", "name": "Funkblick",
        "operatingSystem": "Android 8.0+", "applicationCategory": "UtilitiesApplication",
        "description": desc, "inLanguage": lang, "license": "https://www.gnu.org/licenses/gpl-3.0.html",
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "EUR"},
        "downloadUrl": APK, "image": IMG, "url": BASE, "softwareHelp": REPO,
    }


def faq(items, de):
    blocks = "".join(f"<details><summary>{html.escape(q)}</summary><p>{a}</p></details>" for q, a in items)
    schema = {"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [
        {"@type": "Question", "name": q, "acceptedAnswer": {"@type": "Answer", "text": re.sub(r"<[^>]+>", "", a)}} for q, a in items]}
    return f'<section class="faq"><h2>{"Häufige Fragen" if de else "Questions"}</h2>{blocks}</section>', schema


def howto(name, steps):
    schema = {"@context": "https://schema.org", "@type": "HowTo", "name": name,
              "step": [{"@type": "HowToStep", "position": i + 1, "text": re.sub(r"<[^>]+>", "", s)} for i, s in enumerate(steps)]}
    return '<ol class="steps">' + "".join(f"<li>{s}</li>" for s in steps) + "</ol>", schema


def cta(de):
    if de:
        return (f'<p class="cta"><a class="btn" href="{APK}">APK herunterladen</a><a class="quiet" href="{REPO}">Quellcode auf GitHub</a></p>'
                '<p class="small">Kostenlos, Android 8.0 oder neuer, läuft auf GrapheneOS ohne Google-Dienste. Kein Konto.</p>')
    return (f'<p class="cta"><a class="btn" href="{APK}">Download the APK</a><a class="quiet" href="{REPO}">Source on GitHub</a></p>'
            '<p class="small">Free, Android 8.0 or newer, runs on GrapheneOS without Google services. No account.</p>')


# the real app screens from docs/bilder/app/, taken with the Hamburg demo (tools/screenshots)
SHOTS = {1: "map", 2: "radar", 3: "notable", 4: "device", 5: "car", 6: "discreet", 7: "channels"}


def img(root, lang, n, alt, lazy=True):
    return (f'<img src="{root}bilder/app/{lang}-{n}-{SHOTS[n]}.png" width="540" height="1200" alt="{html.escape(alt)}"'
            + (' loading="lazy"' if lazy else ' fetchpriority="high"') + ">")


def hero(h1, line, intro, de, root, lang, n, alt):
    """h1 of seven words at most, one sentence of twelve at most, the actions and one screen. intro carries the long text."""
    assert len(h1.split()) <= 7 and len(line.split()) <= 12, (h1, line)
    return (f'<section class="hero"><div><h1>{h1}</h1><p class="lead">{line}</p>{cta(de)}</div>{img(root, lang, n, alt, lazy=False)}</section>'
            f'<section class="intro"><p>{intro}</p></section>')


def pair(root, lang, items):
    """Two screens side by side, each with a short caption."""
    return '<section class="pair">' + "".join(
        f"<figure>{img(root, lang, n, alt)}<figcaption>{cap}</figcaption></figure>" for n, alt, cap in items) + "</section>"


def limits(de):
    if de:
        items = [
            "<b>Keine Richtung.</b> Ein Handy misst nur, wie stark ein Signal ist. Der Winkel im Radar ist ein fester Platz pro Gerät, kein Kompass.",
            "<b>Nur Zugangspunkte.</b> Android zeigt WLAN-Router und Hotspots, keine Geräte, die nur verbunden sind. Eine Kamera im fremden Heimnetz sieht Funkblick nicht.",
            "<b>Kein Behördenfunk.</b> Polizei und Rettungsdienst funken per TETRA um 400 MHz. Das empfängt kein Handy. Funkblick sieht nur WLAN und Bluetooth ihrer Geräte, wenn sie welche haben.",
            "<b>Zufallsadressen.</b> Handys und Tracker wechseln ihre Bluetooth-Adresse. Funkblick liest dann, was das Gerät sendet, statt den Hersteller zu raten.",
        ]
        return '<section><h2>Was Funkblick nicht kann</h2><ul class="limits">' + "".join(f"<li>{i}</li>" for i in items) + "</ul></section>"
    items = [
        "<b>No direction.</b> A phone only measures how strong a signal is. The angle on the radar is a fixed spot per device, not a compass.",
        "<b>Access points only.</b> Android lists Wi-Fi routers and hotspots, not devices that are merely connected. A camera on someone's home network stays invisible.",
        "<b>No police radio.</b> Police and EMS talk over TETRA or P25, which no phone can receive. Funkblick only sees the Wi-Fi and Bluetooth of their gear, if it has any.",
        "<b>Random addresses.</b> Phones and trackers change their Bluetooth address. Funkblick then reads what the device broadcasts instead of guessing the maker.",
    ]
    return '<section><h2>What Funkblick can\'t do</h2><ul class="limits">' + "".join(f"<li>{i}</li>" for i in items) + "</ul></section>"


def features(de, root):
    p = root + ("de/" if de else "")
    rows = [
        ("Karte und Radar" if de else "Map and radar", "Jedes WLAN und Bluetooth-Gerät in Reichweite. Beim Fahren schätzt Funkblick, wo ein Gerät steht, und zeigt es auf einer Karte aus OpenStreetMap.",
         "Every Wi-Fi network and Bluetooth device in range. While you drive, Funkblick estimates where a device sits and puts it on an OpenStreetMap map.", None),
        ("Tracker-Schutz" if de else "Tracker protection", "AirTag, SmartTag, Tile, Chipolo, Google „Mein Gerät finden“, Pebblebee. Warnt, wenn einer über 10 Minuten bei dir ist.",
         "AirTag, SmartTag, Tile, Chipolo, Google Find My, Pebblebee. Warns when one stays with you for over 10 minutes.", "airtag-finden-android/" if de else "tracker-detector-app/"),
        ("Kameras" if de else "Cameras", "Findet Kameras, die ein eigenes WLAN aufmachen, und Kamera-Hersteller wie Hikvision, EZVIZ oder Ring.",
         "Finds cameras that run their own Wi-Fi, and camera makers like Hikvision, EZVIZ or Ring.", "versteckte-kamera-finden/" if de else "hidden-camera-detector/"),
        ("Auto-Modus" if de else "Car mode", "Große Anzeige, Ansagen, feste Blitzer aus OpenStreetMap, Fahrzeug-Router und mögliche Behördentechnik. Nur für Beifahrer.",
         "Big numbers, spoken alerts, fixed speed cameras from OpenStreetMap, vehicle routers and possible public safety gear.", "blitzer-app-beifahrer/" if de else "police-detector-app/"),
        ("WLAN-Kanal" if de else "Channel tips", "Zeigt, welcher Kanal bei dir am freiesten ist. EU-Kanäle 1 bis 13, DFS-Hinweis, Tipp für die FRITZ!Box.",
         "Shows which channel is the quietest where you are. EU channels 1 to 13, DFS hints.", "wlan-kanal-fritzbox/" if de else "wifi-analyzer/"),
        ("Logs und Export" if de else "Logs and export", "Jede Sitzung als JSON auf dem Handy. Export als CSV, JSON, GPX, GeoJSON oder KI-Bericht zum Einfügen in ChatGPT oder Claude.",
         "Every session saved as JSON on the phone. Export as CSV, JSON, GPX, GeoJSON or an AI debrief to paste into ChatGPT or Claude.", None),
    ]
    more = {"airtag-finden-android/": "Tracker finden", "tracker-detector-app/": "Find a tracker",
            "versteckte-kamera-finden/": "Zimmer prüfen", "hidden-camera-detector/": "Check a room",
            "blitzer-app-beifahrer/": "Was erlaubt ist", "police-detector-app/": "What it can tell",
            "wlan-kanal-fritzbox/": "Kanal einstellen", "wifi-analyzer/": "Pick a channel"}
    items = "".join(
        f'<div><h3>{t}</h3><p>{d if de else e}</p>{f"<a href=\"{p}{link}\">{more[link]}</a>" if link else ""}</div>'
        for t, d, e, link in rows)
    return f'<section><h2>{"Was drin ist" if de else "What it does"}</h2><div class="spec">{items}</div></section>'


INSTALL_EN = [
    f'Download <a href="{APK}">Funkblick.apk</a> on your phone and open it. Allow installs from your browser if Android asks.',
    "Open Funkblick and tap <b>Start scan</b>. Allow location (Android hands out Wi-Fi results only with it) and nearby devices.",
    "Or tap <b>open the demo</b> first to see the radar with invented surroundings.",
]
INSTALL_DE = [
    f'<a href="{APK}">Funkblick.apk</a> auf dem Handy herunterladen und öffnen. Fragt Android, die Installation aus dem Browser erlauben.',
    "Funkblick öffnen und <b>Scan starten</b> tippen. Standort erlauben (ohne gibt Android keine WLAN-Ergebnisse heraus) und Geräte in der Nähe.",
    "Oder erst <b>Demo ansehen</b> tippen: das Radar mit erfundener Umgebung.",
]


# Every term from the OpenSEO checks on 08.11.2026 (monthly volume, keyword difficulty), mapped to the page that has to
# carry it. build() fails when a term is missing from that page's text. Volume/KD: DE = Germany, EN = United States.
KEYWORDS = [
    # DE
    ("airtag android", "de/airtag-finden-android/"),            # 8100, KD 0
    ("tracker app android", "de/airtag-finden-android/"),       # 880, KD 0
    ("airtag finden", "de/airtag-finden-android/"),             # 590, KD 5
    ("airguard", "de/airtag-finden-android/"),                  # 480, KD 6
    ("fremden airtag finden", "de/airtag-finden-android/"),     # 210, KD 0
    ("airtag finden android", "de/airtag-finden-android/"),     # 90, KD 0
    ("airtag aufspüren", "de/airtag-finden-android/"),          # 90, KD 1
    ("tracker erkennen app", "de/airtag-finden-android/"),      # 20
    ("airtag erkennen android", "de/airtag-finden-android/"),   # 10
    ("airtag scanner android", "de/airtag-finden-android/"),    # 10, KD 59
    ("wlan scanner", "de/"),                                    # 720, KD 2
    ("bluetooth scanner", "de/"),                               # 480, KD 0
    ("ble scanner", "de/"),                                     # 260, KD 0
    ("wlan scanner app", "de/"),                                # 140, KD 5
    ("bluetooth scanner app", "de/"),                           # 110, KD 0
    ("wlan analyse app", "de/wlan-kanal-fritzbox/"),            # 170, KD 12
    ("grapheneos app", "de/"),                                  # "grapheneos apps" 50, KD 65
    ("versteckte kamera finden", "de/versteckte-kamera-finden/"),   # 480, KD 0
    ("versteckte kameras finden", "de/versteckte-kamera-finden/"),  # 480, KD 0
    ("kamera detektor app", "de/versteckte-kamera-finden/"),    # 90, KD 0
    ("kamera finden app", "de/versteckte-kamera-finden/"),      # 40, KD 0
    ("versteckte kamera app", "de/versteckte-kamera-finden/"),  # 20
    ("wlan kanal", "de/wlan-kanal-fritzbox/"),                  # 390, KD 0
    ("wlan kanal ändern", "de/wlan-kanal-fritzbox/"),           # 170, KD 0
    ("bester wlan kanal", "de/wlan-kanal-fritzbox/"),           # 110, KD 0
    ("wlan analyzer", "de/wlan-kanal-fritzbox/"),               # 90, KD 0
    ("blitzer app", "de/blitzer-app-beifahrer/"),               # 40500, KD 69: hard, carried as part of the long tail
    ("blitzerwarner app", "de/blitzer-app-beifahrer/"),         # 40500, KD 52
    ("blitzer app erlaubt", "de/blitzer-app-beifahrer/"),       # 210, KD 0
    ("blitzer app beifahrer", "de/blitzer-app-beifahrer/"),     # 170, KD 0
    ("blitzer app android", "de/blitzer-app-beifahrer/"),       # 90, KD 42
    # EN
    ("ble scanner", ""),                                        # 880, KD 15
    ("ble scanner app", ""),                                    # 480, KD 8
    ("rf scanner app", ""),                                     # 210, KD 3
    ("bluetooth scanner android", ""),                          # 140, KD 18
    ("wifi scanner android", ""),                               # 90, KD 43
    ("wifi bluetooth scanner", ""),                             # 10
    ("grapheneos app", ""),                                     # "grapheneos apps" 110, KD 33
    ("tracker detector app", "tracker-detector-app/"),          # 2400, KD 21
    ("airtag detector", "tracker-detector-app/"),               # 880, KD 15
    ("airtag detector android", "tracker-detector-app/"),       # 90, KD 19
    ("bluetooth tracker detector", "tracker-detector-app/"),    # 90, KD 16
    ("find airtag android", "tracker-detector-app/"),           # 20, KD 20
    ("airtag scanner android", "tracker-detector-app/"),        # 10
    ("hidden camera detector", "hidden-camera-detector/"),      # 6600, KD 11
    ("hidden camera detector app", "hidden-camera-detector/"),  # 6600, KD 42
    ("hidden camera finder", "hidden-camera-detector/"),        # 4400, KD 41
    ("hidden camera detector android", "hidden-camera-detector/"),  # 20, KD 17
    ("wifi analyzer", "wifi-analyzer/"),                        # 9900, KD 46
    ("wifi analyzer android", "wifi-analyzer/"),                # 590, KD 40
    ("best wifi channel", "wifi-analyzer/"),                    # 260, KD 17
    ("police detector app", "police-detector-app/"),            # 720, KD 16
    ("sophia android", "sophia-alternative/"),                  # 90, KD 22
]
# Researched and left out on purpose:
#   "police scanner app" (12100): people want to listen to police radio, which no phone app can do here
#   "wigle" (3600), "wardriving app" (40): WiGLE's own brand; Funkblick has no WiGLE upload (yet)
#   "flock camera detector" (480): US licence plate cameras, no verified signature to detect them


def norm(s):
    """Lower case, Wi-Fi = wifi, hyphens and tags gone: how a searcher's words show up in running text."""
    s = re.sub(r"<[^>]+>", " ", html.unescape(s)).lower().replace("wi-fi", "wifi")
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", " ", s))


def check_keywords():
    missing = []
    for kw, slug in KEYWORDS:
        if norm(kw) not in norm((DOCS / slug / "index.html").read_text(encoding="utf-8")):
            missing.append(f"{kw!r} on /{slug}")
    assert not missing, "keywords missing: " + ", ".join(missing)
    print(f"{len(KEYWORDS)} keywords found on their pages")


def build():
    # ------------------------------------------------------------ EN home
    desc = "Free Android app that shows every Wi-Fi and Bluetooth device around you, warns about trackers and hidden cameras and works on GrapheneOS."
    steps, hs = howto("Install Funkblick", INSTALL_EN)
    f, fs = faq([
        ("Does it need root?", "No. Funkblick uses the Wi-Fi and Bluetooth scan every Android phone has. No root, no extra hardware, no account."),
        ("Does it work on GrapheneOS?", "Yes. Funkblick is built as a GrapheneOS app: plain Android APIs, no Google Play services. Location and nearby-devices permission are enough, a setup checklist shows what is missing."),
        ("Is it an RF scanner app?", "For the Wi-Fi and Bluetooth bands, yes: a Wi-Fi scanner (Android 8 and up) and a Bluetooth scanner, Android's own radios, no extra hardware. It can't hear police radio, FM or mobile networks."),
        ("Does my data leave the phone?", "No. Scans, logs and positions stay on the phone. The only network request is the update check against GitHub Releases."),
        ("Is it a copy of SØPHIA?", f'No, it is an open-source app with similar features and some of its own. See the <a href="sophia-alternative/">comparison</a>.'),
    ], False)
    page("", "en", "Funkblick: Wi-Fi & BLE scanner app with AirTag detector", desc,
         hero("Wi-Fi and Bluetooth radar for Android",
              "See every network and device nearby, and know which one follows you.",
              "Funkblick is a Wi-Fi/Bluetooth scanner and BLE scanner app: it shows every network and Bluetooth device your phone can hear, says who made it and what it probably is, and speaks up when something deserves attention: "
              "a tracker that has been with you for ten minutes, a camera running its own Wi-Fi, a speed camera ahead. No root, no account, open source.",
              False, "", "en", 1, "Funkblick map during a demo drive through Hamburg: the route, devices placed along it and a tracker warning")
         + features(False, "")
         + pair("", "en", [(5, "Car mode: 48 km/h, a 50 speed camera 350 m ahead, counters for public safety gear, routers, followers and trackers",
                            "Car mode, for the passenger: speed, the next fixed speed camera and spoken alerts."),
                           (6, "Discreet mode: a plain light street map with only your own position",
                            "Discreet mode: an ordinary light map, no radar, alerts only as sound. Three taps end it.")])
         + limits(False)
         + f'<section><h2>Install</h2>{steps}</section>' + f,
         [app_schema("en", desc), hs, fs], {"de": "de/"})

    # ------------------------------------------------------------ DE home
    desc = "Kostenlose Android-App: zeigt jedes WLAN und Bluetooth-Gerät in der Nähe, warnt vor Trackern und versteckten Kameras, läuft auf GrapheneOS."
    steps, hs = howto("Funkblick installieren", INSTALL_DE)
    f, fs = faq([
        ("Braucht Funkblick Root?", "Nein. Funkblick nutzt den WLAN- und Bluetooth-Scan, den jedes Android-Handy hat. Kein Root, kein Zusatzgerät, kein Konto."),
        ("Läuft es auf GrapheneOS?", "Ja. Funkblick ist als GrapheneOS-App gebaut: nur normale Android-Schnittstellen, keine Google-Play-Dienste. Standort und Geräte in der Nähe reichen, eine Checkliste zeigt beim Start, was fehlt."),
        ("Was passiert mit meinen Daten?", "Nichts verlässt das Handy. Scans, Logs und Positionen bleiben lokal. Ins Netz geht nur der Update-Check bei GitHub. Exporte sind standardmäßig gekürzt (sicherer Modus)."),
        ("Ist das eine Alternative zu AirGuard?", 'AirGuard von der TU Darmstadt ist auf Tracker spezialisiert und kann sie klingeln lassen. Funkblick erkennt dieselben Tracker-Arten, zeigt dazu alle WLANs, Kameras und Fahrzeug-Router und hat einen Auto-Modus. <a href="airtag-finden-android/">Mehr zu Trackern</a>.'),
    ], True)
    page("de/", "de", "WLAN-Scanner & Bluetooth-Scanner-App mit Tracker-Warnung", desc,
         hero("WLAN- und Bluetooth-Radar fürs Handy",
              "Zeigt jedes Netz und Gerät in der Nähe und was dir folgt.",
              "Funkblick ist eine WLAN-Scanner-App und ein BLE-Scanner fürs Handy: Sie zeigt jedes WLAN und jedes Bluetooth-Gerät, das dein Handy hört, sagt, wer es gebaut hat und was es vermutlich ist, und meldet sich, wenn es wichtig wird: "
              "ein Tracker, der seit zehn Minuten mitfährt, eine Kamera mit eigenem WLAN, ein Blitzer voraus. Ohne Root, ohne Konto, Open Source.",
              True, "../", "de", 1, "Funkblick-Karte auf einer Demo-Fahrt durch Hamburg: Route, Geräte entlang der Strecke und eine Tracker-Warnung")
         + features(True, "../")
         + pair("../", "de", [(5, "Auto-Modus: 48 km/h, 50er-Blitzer in 350 m, Zähler für Behördentechnik, Router, Verfolger und Tracker",
                              "Auto-Modus für Beifahrer: Tempo, der nächste feste Blitzer und Ansagen."),
                             (6, "Diskret-Modus: eine helle Straßenkarte, nur mit dem eigenen Standort",
                              "Diskret-Modus: eine gewöhnliche helle Karte ohne Radar, Meldungen nur als Ton. Dreimal tippen beendet ihn.")])
         + limits(True)
         + f'<section><h2>Installieren</h2>{steps}</section>' + f,
         [app_schema("de", desc), hs, fs], {"en": ""})

    # ------------------------------------------------------------ trackers
    desc = "Find out if an AirTag, SmartTag, Tile or Google Find My tracker is following you. Free Android app, no Apple device needed, works offline."
    f, fs = faq([
        ("Can Android detect AirTags?", "Yes. An AirTag away from its owner broadcasts a Find My signal every two seconds. Funkblick works as an AirTag scanner: Android's normal Bluetooth scan is all it needs."),
        ("Find AirTag, Android only: how?", "Start the scan, filter the list by Tracker, tap the one marked separated and open Find mode. The number rises and the beep speeds up as you get closer."),
        ("When does Funkblick warn?", "When a tag that is separated from its owner has been near you for more than ten minutes without a gap of five minutes and, if GPS is on, at three places at least 300 m apart. Tags next to their owner (a friend's keys) never ring."),
        ("Can it make the tracker beep?", "No. That needs a Bluetooth connection, which Funkblick never opens. Instead, Find mode shows a big signal meter and beeps faster as you get closer."),
        ("What should I do when I find one?", "Keep it, it is evidence. If you feel threatened, call the police. In Germany the violence-against-women helpline is 116 016, victim support Weisser Ring 116 006."),
    ], False)
    page("tracker-detector-app/", "en", "AirTag detector for Android: tracker detector app", desc,
         hero("AirTag detector for Android",
              "Warns when a tracker that left its owner stays with you.",
              "Funkblick is an AirTag detector (Android, no iPhone needed) and a Bluetooth tracker detector. It recognises AirTags and other Find My accessories, Samsung SmartTags, Tile, Chipolo, Pebblebee and tags in Google's Find My network. "
              "It tells a tag next to its owner from one that is on its own, and warns once a separated tag has been with you for ten minutes.",
              False, "../", "en", 3, "List of notable devices: an AirTag away from its owner for 14 minutes, a Samsung SmartTag, a Tile")
         + '<section><h2>How it tells trackers apart</h2><div class="spec two">'
         + "".join(f'<div><h3>{n}</h3><p>{t}</p></div>' for n, t in [
             ("Apple Find My", "Company id 0x004C, message type 0x12. A long key means the tag is away from its owner. Status bits separate AirTag, AirPods and third-party Find My tags."),
             ("Samsung SmartTag", "Service 0xFD5A. The low bits of the first byte say whether the tag is connected to its phone or offline."),
             ("Google Find My", "Service 0xFEAA with frame type 0x40 or 0x41. 0x41 is the unwanted-tracking mode a tag enters when it is away from its owner."),
             ("Tile, Chipolo, Pebblebee", "Service 0xFEED, 0xFE33 and 0xFA25. These do not say whether the owner is near, so Funkblick lists them but does not warn."),
         ]) + "</div></section>" + limits(False) + f,
         [app_schema("en", desc), fs], {"de": "de/airtag-finden-android/"})

    desc = "AirTag oder anderen Tracker mit Android finden: Funkblick erkennt AirTag, SmartTag, Tile und Google-Tracker und warnt, wenn einer dir folgt."
    steps, hs = howto("Einen fremden AirTag finden: Android-Anleitung", [
        "Funkblick öffnen und <b>Scan starten</b>. Tracker-Schutz ist von Anfang an an.",
        "In der Liste den Filter <b>Tracker</b> wählen. Ein roter Hinweis <b>getrennt</b> heißt: der Tracker ist nicht bei seinem Besitzer.",
        "Den Tracker antippen, dann <b>Peilen</b>. Langsam Taschen, Jacke, Rucksack oder Auto absuchen: die Zahl steigt und es piept schneller, je näher du kommst.",
        "Gefunden? Behalten, nicht wegwerfen: es ist ein Beweismittel. Bei Bedrohung die Polizei rufen (110).",
    ])
    f, fs = faq([
        ("AirTag erkennen, Android statt iPhone: geht das?", "Ja. Funkblick ist ein AirTag-Scanner (Android 8 und neuer) und braucht weder iPhone noch Apple-Konto."),
        ("Tracker erkennen: App oder Android-Einstellung?", "Android meldet unbekannte Tracker selbst, aber nur mit Google-Play-Diensten. Auf GrapheneOS ohne Play-Dienste fehlt das, dort braucht es eine App wie Funkblick oder AirGuard."),
        ("Kann man einen AirTag mit Android finden?", "Ja. Ein AirTag ohne seinen Besitzer sendet alle zwei Sekunden ein Find-My-Signal. Funkblick liest es mit dem normalen Bluetooth-Scan des Handys, ohne Apple-Gerät."),
        ("Wann warnt Funkblick?", "Wenn ein vom Besitzer getrennter Tracker über zehn Minuten in deiner Nähe ist, ohne Pause von fünf Minuten, und mit GPS an drei Orten, die mindestens 300 m auseinanderliegen. Tracker neben ihrem Besitzer (die Schlüssel einer Freundin) lösen nie aus."),
        ("Kann Funkblick den Tracker piepen lassen?", "Nein, dafür braucht es eine Bluetooth-Verbindung, und Funkblick verbindet sich nie. Das kann AirGuard von der TU Darmstadt. Die Signalbalken reichen meist, um ihn zu finden."),
        ("Wo bekomme ich Hilfe?", "Polizei 110. Hilfetelefon Gewalt gegen Frauen 116 016, rund um die Uhr, kostenlos, anonym. Weisser Ring (Opferhilfe) 116 006."),
    ], True)
    page("de/airtag-finden-android/", "de", "AirTag mit Android finden: fremden AirTag aufspüren", desc,
         hero("AirTag mit Android finden",
              "Warnt, wenn ein fremder Tracker über zehn Minuten bei dir bleibt.",
              "Wer nach „AirTag Android“ oder „Tracker App Android“ sucht, landet meist bei Apples Tracker Detect, die nur sucht, wenn man sie antippt. Funkblick läuft im Hintergrund weiter und erkennt AirTags und andere Find-My-Tracker, Samsung SmartTags, Tile, Chipolo, Pebblebee und Tracker im Google-Netz „Mein Gerät finden“. "
              "Es unterscheidet einen Tracker neben seinem Besitzer von einem, der allein unterwegs ist, und warnt, sobald so einer zehn Minuten bei dir ist.",
              True, "../../", "de", 3, "Liste auffälliger Geräte: ein AirTag seit 14 Minuten ohne Besitzer, ein Samsung SmartTag, ein Tile")
         + f'<section><h2>AirTag finden: Android statt iPhone</h2>{steps}</section>' + limits(True) + f,
         [app_schema("de", desc), hs, fs], {"en": "tracker-detector-app/"})

    # ------------------------------------------------------------ cameras
    desc = "Hidden camera detector for Android that checks Wi-Fi and Bluetooth, honest about what a phone can and can't find. Free, open source."
    steps, hs = howto("Check a holiday flat for cameras", [
        "Start the scan in the flat and leave it running for two minutes.",
        "In the list, pick the filter <b>Camera</b>. Pink entries are camera makers (Hikvision, Dahua, EZVIZ, Ring, Arlo …) or Wi-Fi names cameras use in setup mode (V380, IPC, LookCam …).",
        "Walk through the rooms. The strongest signal is usually the closest device.",
        "Also look with your eyes: smoke detectors, chargers and clocks facing the bed or shower.",
    ])
    f, fs = faq([
        ("Which hidden camera detector, Android or iPhone?", "Funkblick runs on Android only, because iPhones don't let apps scan Wi-Fi. As a hidden camera detector, Android phones can see every access point in range."),
        ("Can a phone find every hidden camera?", "No. It finds cameras that open their own Wi-Fi or send Bluetooth. A camera that is only connected to the host's Wi-Fi is invisible to any app, because Android only lists access points."),
        ("Why not use the infrared trick?", "Pointing the camera at lenses to spot IR LEDs works only for cameras with night vision on. Do it as well; Funkblick covers the radio side."),
        ("Is a Hikvision network always a camera?", "Not always, but the maker builds almost nothing else. Funkblick shows why it flagged something, so you can judge."),
    ], False)
    page("hidden-camera-detector/", "en", "Hidden camera detector & finder app for Android", desc,
         hero("Hidden cameras in your Airbnb?",
              "Funkblick checks the Wi-Fi and Bluetooth around you for camera hardware.",
              "Funkblick is a hidden camera detector app and hidden camera finder for your phone. Many cheap spy cameras open their own Wi-Fi so the owner can set them up, and many security cameras come from a handful of makers. "
              "Funkblick checks both: camera makers from the IEEE registry and Wi-Fi names cameras typically use, and warns as soon as one shows up.",
              False, "../", "en", 3, "List of notable devices; at the bottom a camera running its own open Wi-Fi (V380)")
         + f'<section><h2>Check a room in five minutes</h2>{steps}</section>' + limits(False) + f,
         [app_schema("en", desc), hs, fs], {"de": "de/versteckte-kamera-finden/"})

    desc = "Versteckte Kamera finden mit dem Handy: Funkblick prüft WLAN und Bluetooth auf Kamera-Hersteller und Kamera-Netze. Kostenlos, ohne Werbung."
    steps, hs = howto("Ferienwohnung auf Kameras prüfen", [
        "In der Wohnung den Scan starten und zwei Minuten laufen lassen.",
        "In der Liste den Filter <b>Kamera</b> wählen. Rosa Einträge sind Kamera-Hersteller (Hikvision, Dahua, EZVIZ, Ring, Arlo …) oder WLAN-Namen, die Kameras beim Einrichten nutzen (V380, IPC, LookCam …).",
        "Durch die Zimmer gehen: das stärkste Signal ist meist das nächste Gerät.",
        "Zusätzlich hinschauen: Rauchmelder, Ladegeräte und Uhren, die aufs Bett oder in die Dusche zeigen.",
    ])
    f, fs = faq([
        ("Findet das Handy jede versteckte Kamera?", "Nein. Es findet Kameras, die ein eigenes WLAN aufmachen oder per Bluetooth senden. Eine Kamera, die nur im WLAN des Vermieters hängt, sieht keine App, weil Android nur Zugangspunkte auflistet."),
        ("Was ist mit dem Infrarot-Trick?", "Mit der Handykamera nach leuchtenden IR-LEDs suchen klappt nur bei Kameras mit Nachtsicht. Mach es zusätzlich; Funkblick deckt die Funk-Seite ab."),
        ("Darf der Vermieter in der Ferienwohnung filmen?", "In Schlaf- und Badezimmern nie. Heimliche Aufnahmen im höchstpersönlichen Lebensbereich sind nach § 201a StGB strafbar. Fotografiere den Fund und ruf die Polizei."),
    ], True)
    page("de/versteckte-kamera-finden/", "de", "Versteckte Kameras finden mit dem Handy: Kamera-Detektor", desc,
         hero("Kamera in der Ferienwohnung?",
              "Funkblick prüft WLAN und Bluetooth in der Nähe auf Kamera-Technik.",
              "Funkblick ist eine Kamera-Detektor-App für Android. Vorab: Keine versteckte-Kamera-App findet alles, auch keine Kamera-finden-App aus dem Play Store. Viele billige Mini-Kameras machen ein eigenes WLAN auf, damit man sie einrichten kann, und viele Überwachungskameras kommen von einer Handvoll Hersteller. "
              "Funkblick prüft beides: Kamera-Hersteller aus dem IEEE-Register und typische Kamera-WLAN-Namen, und meldet sich, sobald eine auftaucht.",
              True, "../../", "de", 3, "Liste auffälliger Geräte; unten eine Kamera mit eigenem offenen WLAN (V380)")
         + f'<section><h2>Zimmer in fünf Minuten prüfen</h2>{steps}</section>' + limits(True) + f,
         [app_schema("de", desc), hs, fs], {"en": "hidden-camera-detector/"})

    # ------------------------------------------------------------ channels
    desc = "Free Wi-Fi analyzer for Android: see every network per channel and get the quietest 2.4 and 5 GHz channel for your router. No ads."
    f, fs = faq([
        ("Which 2.4 GHz channel is best?", "Usually 1, 6 or 11, because they don't overlap. In Europe 13 is allowed too and often empty. Funkblick weighs the neighbours by signal strength and picks one."),
        ("What is DFS?", "5 GHz channels 52 to 144 are shared with weather and other radar. A router has to listen for a minute before it uses them and must switch away when it hears radar. They are often quiet for exactly that reason."),
    ], False)
    steps, hs = howto("Change your router's Wi-Fi channel", [
        "Start the scan in Funkblick and open <b>Wi-Fi channels for your router</b> under More. Best where your Wi-Fi is weak.",
        "Note the suggestion for 2.4 and 5 GHz.",
        "Open your router's web page, usually printed on its label, and find the radio or channel settings.",
        "Enter the channel, save, and check again in Funkblick after a few minutes.",
    ])
    page("wifi-analyzer/", "en", "Wi-Fi analyzer for Android: find the best Wi-Fi channel", desc,
         hero("Find the best Wi-Fi channel",
              "Funkblick counts the networks per channel and picks the quietest.",
              "Funkblick is a Wi-Fi analyzer: Android shows it every network in range, and Funkblick counts them per channel, weighs them by signal strength and suggests the quietest 2.4 GHz and 5 GHz channel for your own router.",
              False, "../", "en", 7, "Wi-Fi channels: suggestion channel 13 and 40, bar chart of networks per 2.4 GHz channel")
         + f'<section><h2>Set it on your router</h2>{steps}</section>' + f,
         [app_schema("en", desc), hs, fs], {"de": "de/wlan-kanal-fritzbox/"})

    desc = "Besten WLAN-Kanal für die FRITZ!Box finden: Funkblick zählt die Netze pro Kanal und empfiehlt den freiesten Kanal auf 2,4 und 5 GHz."
    steps, hs = howto("WLAN-Kanal der FRITZ!Box ändern", [
        "In Funkblick den Scan starten und unter Mehr die <b>WLAN-Kanäle</b> öffnen. Am besten dort, wo das WLAN schwach ist.",
        "Die Empfehlung für 2,4 und 5 GHz merken.",
        "fritz.box im Browser öffnen, dann unter WLAN, Funkkanal die Funkkanal-Einstellungen anpassen.",
        "Kanal eintragen, übernehmen, nach ein paar Minuten in Funkblick prüfen.",
    ])
    f, fs = faq([
        ("Bester WLAN-Kanal: woran erkennt man ihn?", "An wenigen und schwachen Nachbarn auf demselben Kanal. Funkblick wertet beides aus und empfiehlt einen Kanal je Band."),
        ("Welcher 2,4-GHz-Kanal ist der beste?", "Meist 1, 6 oder 11, weil sie sich nicht überlappen. In Europa ist auch 13 erlaubt und oft frei. Funkblick gewichtet die Nachbarn nach Signalstärke und wählt einen aus."),
        ("Was bedeutet DFS?", "Die 5-GHz-Kanäle 52 bis 144 teilt sich das WLAN mit Wetter- und anderen Radaren. Der Router hört erst eine Minute hin und muss wechseln, wenn er Radar hört. Gerade deshalb sind sie oft frei."),
        ("Soll ich die automatische Kanalwahl ausschalten?", "Nur wenn es hakt. Die FRITZ!Box wählt selbst, aber nur nach dem, was sie am Router hört. Funkblick misst dort, wo du sitzt."),
    ], True)
    page("de/wlan-kanal-fritzbox/", "de", "WLAN-Kanal ändern: bester Kanal für die FRITZ!Box", desc,
         hero("WLAN-Kanal ändern, aber welcher?",
              "Funkblick zählt die Netze pro Kanal und empfiehlt den freiesten.",
              "Funkblick ist eine WLAN-Analyse-App, ein WLAN-Analyzer ohne Werbung, und zeigt dir den besten WLAN-Kanal. Dafür zählt es die Netze um dich herum pro Kanal, gewichtet sie nach Signalstärke und empfiehlt den freiesten Kanal auf 2,4 und 5 GHz. Nach EU-Regeln, also mit Kanal 12 und 13.",
              True, "../../", "de", 7, "WLAN-Kanäle: Empfehlung Kanal 13 und 40, Balken mit Netzen pro 2,4-GHz-Kanal")
         + f'<section><h2>So stellst du ihn ein</h2>{steps}</section>' + f,
         [app_schema("de", desc), hs, fs], {"en": "wifi-analyzer/"})

    # ------------------------------------------------------------ car / police
    desc = "What a phone can really detect of police cars and body cams: vehicle routers and body cam makers over Wi-Fi and Bluetooth. Free Android app."
    f, fs = faq([
        ("Can an app detect police cars?", "Only indirectly. Many patrol cars carry cellular routers (Cradlepoint, Sierra Wireless) and officers wear body cams (Axon, Motorola Solutions) that send Wi-Fi or Bluetooth. Funkblick spots that hardware. The same routers sit in buses, ambulances and delivery vans."),
        ("Is it legal?", "It depends on where you are. Receiving signals that everyone broadcasts is legal in most places. Germany forbids drivers to use functions that point out traffic enforcement (StVO § 23 (1c)), Switzerland bans speed camera warnings altogether. Funkblick keeps these alerts off until you agree to a warning."),
        ("Does it hear police radio?", "No. Police radio runs on P25 or TETRA, which phones can't receive."),
    ], False)
    page("police-detector-app/", "en", "Police detector app for Android: what a phone can detect", desc,
         hero("What a phone sees of police gear",
              "Car mode names vehicle routers and body cam makers out loud.",
              "Funkblick's car mode looks for cellular routers built for vehicles and for body cam makers in the Wi-Fi and Bluetooth around you, announces them by voice and counts them on a big screen. "
              "A match only tells you who built the hardware.",
              False, "../", "en", 5, "Car mode: 48 km/h, a speed camera 350 m ahead, counters for public safety gear, routers, followers and trackers")
         + limits(False) + f,
         [app_schema("en", desc), fs])

    desc = "Funkblick warnt vor festen Blitzern aus OpenStreetMap und Fahrzeug-Funk, mit Ansage. Was erlaubt ist: für Fahrer verboten, für Beifahrer okay."
    f, fs = faq([
        ("Blitzer-App: Beifahrer darf, Fahrer nicht?", "Ja. Für den Beifahrer ist die Blitzer-App erlaubt, solange sie nicht für den Fahrer warnt. Deshalb fragt Funkblick vor dem Einschalten nach."),
        ("Ist Funkblick eine Blitzerwarner-App?", "Teilweise: Es warnt vor festen Blitzern aus OpenStreetMap. Mobile Blitzer kennt es nicht, dafür öffnet ein Knopf Blitzer.de."),
        ("Sind Blitzer-Apps erlaubt?", "Besitzen ja, als Fahrer benutzen nein. § 23 Abs. 1c StVO verbietet Fahrern, Geräte oder App-Funktionen zu nutzen, die Verkehrskontrollen anzeigen: 75 € und ein Punkt. Das gilt laut Gerichten auch, wenn das Handy des Beifahrers für den Fahrer warnt."),
        ("Woher kommen die Blitzer?", "Aus OpenStreetMap: rund 5.200 feste Blitzer in Deutschland, fest in der App, offline. Mobile Blitzer kennt Funkblick nicht. Dafür öffnet ein Knopf die Blitzer.de-App."),
        ("Erkennt Funkblick Polizeiautos?", "Nur indirekt: Fahrzeug-Router (Cradlepoint, Sierra Wireless, Teltonika) und Bodycam-Hersteller (Axon, Motorola Solutions) senden WLAN oder Bluetooth. Dieselben Router stecken in Bussen, Rettungs- und Lieferwagen. Polizeifunk (TETRA) empfängt kein Handy."),
    ], True)
    page("de/blitzer-app-beifahrer/", "de", "Blitzer-App erlaubt? Auto-Modus für Beifahrer", desc,
         hero("Blitzer-App: erlaubt für Beifahrer",
              "Tempo, feste Blitzer und Fahrzeug-Funk mit Ansage, nur für Beifahrer.",
              "Eine Blitzer-App (Android) mit Beifahrer-Modus: großes Tempo, Abstand zum nächsten festen Blitzer, Zähler für Fahrzeug-Router und mögliche Behördentechnik, alles mit Ansage. "
              "Standardmäßig aus: wer es einschaltet, bestätigt, dass er nicht selbst fährt.",
              True, "../../", "de", 5, "Auto-Modus: 48 km/h, Blitzer in 350 m mit 50er-Schild, Zähler und Meldungen")
         + limits(True) + f,
         [app_schema("de", desc), fs])

    # ------------------------------------------------------------ SØPHIA
    desc = "Funkblick vs. SØPHIA Android: a free, open-source passive Wi-Fi and BLE scanner with radar, watch rules, car mode and tracker alerts."
    rows = [
        ("Price", "$39, closed source", "free, GPL-3.0"),
        ("Wi-Fi + BLE radar", "yes", "yes"),
        ("Maker lookup (IEEE /24, /28, /36)", "yes", "yes, 54,000 prefixes"),
        ("Watch and mute rules", "yes", "yes, mute wins"),
        ("Voice alerts", "audio alerts", "own sound per kind + voice, EN/DE"),
        ("Tracker protection (AirTag & co.)", "no", "yes, with the 10-minute rule"),
        ("Speed cameras", "no", "fixed cameras from OpenStreetMap"),
        ("Channel tips", "no", "yes"),
        ("Export", "CSV, JSON, AI debrief", "CSV, JSON, AI debrief, session logs"),
        ("Updates", "download again from Gumroad", "updates itself from GitHub"),
    ]
    table = '<table><thead><tr><th></th><th>SØPHIA Android 2.2.1</th><th>Funkblick</th></tr></thead><tbody>' + "".join(
        f"<tr><th>{a}</th><td>{b}</td><td>{c}</td></tr>" for a, b, c in rows) + "</tbody></table>"
    page("sophia-alternative/", "en", "Funkblick vs. SØPHIA (Sophia Android): free alternative", desc,
         hero("An open-source alternative to SØPHIA",
              "Same passive Wi-Fi and Bluetooth scan, free, with the source on GitHub.",
              "SØPHIA is a paid passive Wi-Fi and Bluetooth scanner sold on Gumroad. Funkblick does the same job for free, with the source on GitHub, "
              "and adds tracker protection, speed cameras and channel tips. Comparison based on SØPHIA's product page in November 2026.",
              False, "../", "en", 2, "Funkblick radar: Wi-Fi as circles and Bluetooth as squares around the phone, rings in dBm")
         + f'<section><h2>Side by side</h2>{table}</section>' + limits(False),
         [app_schema("en", desc)])

    # ------------------------------------------------------------ sitemap
    urls = "".join(f"<url><loc>{BASE + s}</loc></url>" for s in PAGES)
    (DOCS / "sitemap.xml").write_text(f'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{urls}</urlset>\n', encoding="utf-8")
    (DOCS / ".nojekyll").write_text("")
    print(f"{len(PAGES)} pages in {DOCS}")
    check_keywords()


if __name__ == "__main__":
    build()
