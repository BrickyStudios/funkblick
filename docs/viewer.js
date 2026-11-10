// Funkblick log viewer: opens JSON and GeoJSON exports in this browser. Nothing is uploaded.
// Logs come from other people, so every value goes in as text (textContent), never as HTML.
(() => {
  const de = document.documentElement.lang === "de";
  const T = de ? {
    devices: "Geräte", wifi: "WLAN", ble: "Bluetooth", open: "WLAN offen", trackers: "Tracker ohne Besitzer", police: "Behörde?",
    cars: "Auto-WLANs", notable: "Auffällig", kinds: "Arten", alerts: "Warnungen der Reihe nach", none: "Keine Warnungen im Log.",
    map: "Karte", nomap: "Für die Karte die GeoJSON-Datei derselben Fahrt dazulegen.", kind: "Art", name: "Name", maker: "Hersteller",
    seen: "Orte", time: "Dauer", bad: "Diese Datei ist kein Funkblick-Export: ", session: "Sitzung vom", safe: "sicherer Modus",
    probable: "wahrscheinlich Polizei", possible: "möglich", hidden: "(versteckt)", min: "min",
  } : {
    devices: "Devices", wifi: "Wi-Fi", ble: "Bluetooth", open: "Open Wi-Fi", trackers: "Trackers away from owner", police: "Police?",
    cars: "Car Wi-Fi", notable: "Notable", kinds: "Kinds", alerts: "Alerts in order", none: "No alerts in this log.",
    map: "Map", nomap: "For the map, add the GeoJSON file of the same drive.", kind: "Kind", name: "Name", maker: "Maker",
    seen: "Places", time: "Time", bad: "This file is not a Funkblick export: ", session: "Session of", safe: "safe mode",
    probable: "probably police", possible: "possible", hidden: "(hidden)", min: "min",
  };
  // the app's colours and short names per category (ui/Theme.kt)
  const CAT = {
    TRACKER: ["#FF4D5E", "Tracker", "Tracker"], PRIORITY: ["#6E95F0", "Behörde", "Police/EMS"], CAMERA: ["#D27BE8", "Kamera", "Camera"], HACKER: ["#FF6FB5", "Hacker-Werkzeug", "Hacking tool"],
    FLEET: ["#F0904E", "Fahrzeug-Router", "Vehicle router"], CAR: ["#8CBFE8", "Auto", "Car"], PUBLIC_WIFI: ["#E8CC8A", "Öffentlich", "Public"],
    INFRA: ["#B3A68F", "Firma", "Business"], HOME: ["#E3DCCF", "Zuhause", "Home"], PERSONAL: ["#A996EE", "Persönlich", "Personal"],
    UNKNOWN: ["#8A8172", "Unbekannt", "Unknown"],
  };
  const ORDER = Object.keys(CAT);
  const catName = (c) => (CAT[c] || CAT.UNKNOWN)[de ? 1 : 2];
  const catColor = (c) => (CAT[c] || CAT.UNKNOWN)[0];
  const ALERT = { tracker: "#FF4D5E", again: "#FF4D5E", speedcam: "#FF4D5E", priority: "#6E95F0", police: "#6E95F0", camera: "#D27BE8", hacker: "#FF6FB5", fleet: "#F0904E", follow: "#F0904E" };

  const log = { meta: null, devices: [], alerts: [], track: [], points: [] };
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = String(text); return e; };
  const dot = (color) => { const d = el("span", "dot"); d.style.background = color; return d; };
  const clock = (iso) => { const d = new Date(iso); return isNaN(d) ? "" : d.toLocaleTimeString(de ? "de-DE" : "en-GB", { hour: "2-digit", minute: "2-digit" }); };

  // JSON writes local time, GeoJSON UTC: the same alert in both files is the same instant
  const seen = (x) => log.alerts.some((a) => +new Date(a.time) === +new Date(x.time) && a.text === x.text);

  function take(o, file) {
    if (o && o.type === "FeatureCollection" && Array.isArray(o.features)) {
      for (const f of o.features) {
        const p = f.properties || {}, g = f.geometry || {};
        if (p.kind === "track" && g.type === "LineString") log.track = g.coordinates;
        else if (p.kind === "device" && g.type === "Point") log.points.push({ c: g.coordinates, p });
        else if (p.kind === "alert" && g.type === "Point" && !seen(p))
          log.alerts.push({ time: p.time, kind: p.alert, text: p.text, lon: g.coordinates[0], lat: g.coordinates[1] });
      }
    } else if (o && Array.isArray(o.devices)) {
      log.meta = { start: o.session_start, safe: o.safe_mode, app: o.app, build: o.build };
      log.devices = o.devices;
      for (const a of o.alerts || []) if (!seen(a)) log.alerts.push(a);
    } else throw new Error(file);
  }

  function stat(box, k, v) { const d = el("div"); d.append(el("span", "k", k), el("span", "v", v)); box.append(d); }

  function render() {
    const out = $("out"); out.replaceChildren(); out.hidden = false;
    const ds = log.devices;
    if (ds.length) {
      const wifi = ds.filter((d) => d.radio === "WIFI");
      const s = el("section", "stats");
      s.append(el("h2", null, T.session + (log.meta && log.meta.start ? " " + new Date(log.meta.start).toLocaleString(de ? "de-DE" : "en-GB", { dateStyle: "medium", timeStyle: "short" }) : "")));
      const box = el("div", "nums");
      stat(box, T.devices, ds.length); stat(box, T.wifi, wifi.length); stat(box, T.ble, ds.length - wifi.length);
      if (wifi.length) stat(box, T.open, Math.round(wifi.filter((d) => d.security === "OPEN").length * 100 / wifi.length) + " %");
      stat(box, T.cars, ds.filter((d) => d.category === "CAR").length);
      stat(box, T.trackers, ds.filter((d) => d.category === "TRACKER" && d.separated).length);
      stat(box, T.police, ds.filter((d) => d.police).length);
      s.append(box);
      const kinds = el("ul", "kinds");
      for (const c of ORDER) { const n = ds.filter((d) => d.category === c).length; if (n) { const li = el("li"); li.append(dot(catColor(c)), el("span", null, catName(c) + " " + n)); kinds.append(li); } }
      s.append(el("h3", null, T.kinds), kinds);
      if (log.meta && log.meta.safe) s.append(el("p", "small", (log.meta.app || "Funkblick") + " " + (log.meta.build || "") + ", " + T.safe));
      out.append(s);
    }
    if (log.points.length || log.track.length || log.alerts.some((a) => a.lat != null)) {
      const m = el("section"); m.append(el("h2", null, T.map), el("div", "map")); m.lastChild.id = "map"; out.append(m);
      showMap();
    } else if (ds.length) { const m = el("section"); m.append(el("h2", null, T.map), el("p", "small", T.nomap)); out.append(m); }
    if (ds.length) {
      const notable = ds.filter((d) => d.police || d.watch || ["TRACKER", "PRIORITY", "CAMERA", "HACKER", "FLEET", "CAR"].includes(d.category))
        .sort((a, b) => ORDER.indexOf(a.category) - ORDER.indexOf(b.category) || (b.places || 0) - (a.places || 0)).slice(0, 150);
      const sec = el("section"); sec.append(el("h2", null, T.notable + " " + notable.length));
      const wrap = el("div", "scroll"), table = el("table"), head = el("tr");
      for (const h of [T.kind, T.name, T.maker, "dBm", T.seen, T.time]) head.append(el("th", null, h));
      const thead = el("thead"); thead.append(head); table.append(thead);
      const body = el("tbody");
      for (const d of notable) {
        const tr = el("tr"), kind = el("td");
        kind.append(dot(catColor(d.category)), el("span", null, catName(d.category) + (d.police ? ", " + (d.police === "PROBABLE" ? T.probable : T.possible) : "")));
        const mins = d.first_seen && d.last_seen ? Math.round((new Date(d.last_seen) - new Date(d.first_seen)) / 60000) + " " + T.min : "";
        tr.append(kind, el("td", null, d.name || d.tracker || ""), el("td", null, d.vendor || ""), el("td", "num", d.rssi_max ?? ""), el("td", "num", d.places ?? ""), el("td", "num", mins));
        body.append(tr);
      }
      table.append(body); wrap.append(table); sec.append(wrap); out.append(sec);
    }
    const al = el("section"); al.append(el("h2", null, T.alerts));
    if (!log.alerts.length) al.append(el("p", "small", T.none));
    const ul = el("ul", "timeline");
    for (const a of [...log.alerts].sort((x, y) => new Date(x.time) - new Date(y.time))) {
      const li = el("li"); li.append(el("span", "t", clock(a.time)), dot(ALERT[a.kind] || "#FFB020"), el("span", null, a.text)); ul.append(li);
    }
    al.append(ul); out.append(al);
    out.scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
  }

  function showMap() {
    if (!window.maplibregl) { $("map").replaceWith(el("p", "small", "MapLibre could not load.")); return; }
    const fc = (features) => ({ type: "FeatureCollection", features });
    const pt = (c, props) => ({ type: "Feature", properties: props, geometry: { type: "Point", coordinates: c } });
    const pts = log.points.map((x) => pt(x.c, { color: catColor(x.p.category), r: ["TRACKER", "PRIORITY", "CAMERA"].includes(x.p.category) ? 7 : 5 }));
    const als = log.alerts.filter((a) => a.lat != null).map((a) => pt([a.lon, a.lat], { color: ALERT[a.kind] || "#FFB020" }));
    const all = [...log.track, ...log.points.map((x) => x.c), ...als.map((a) => a.geometry.coordinates)];
    const b = all.reduce((b, c) => [Math.min(b[0], c[0]), Math.min(b[1], c[1]), Math.max(b[2], c[0]), Math.max(b[3], c[1])], [180, 90, -180, -90]);
    const map = new maplibregl.Map({ container: "map", style: "https://tiles.openfreemap.org/styles/dark", bounds: [[b[0], b[1]], [b[2], b[3]]], fitBoundsOptions: { padding: 40, maxZoom: 16 }, attributionControl: { compact: true } });
    map.on("load", () => {
      if (log.track.length > 1) {
        map.addSource("track", { type: "geojson", data: { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: log.track } } });
        map.addLayer({ id: "track", type: "line", source: "track", paint: { "line-color": "#FFB020", "line-width": 3 } });
      }
      map.addSource("dev", { type: "geojson", data: fc(pts) });
      map.addLayer({ id: "dev", type: "circle", source: "dev", paint: { "circle-color": ["get", "color"], "circle-radius": ["get", "r"], "circle-stroke-color": "#0D0C0A", "circle-stroke-width": 1.5 } });
      map.addSource("alerts", { type: "geojson", data: fc(als) });
      map.addLayer({ id: "alerts", type: "circle", source: "alerts", paint: { "circle-color": "rgba(0,0,0,0)", "circle-radius": 10, "circle-stroke-color": ["get", "color"], "circle-stroke-width": 2 } });
    });
  }

  async function open(files) {
    const err = $("err"); err.hidden = true;
    for (const f of files) {
      try { take(JSON.parse(await f.text()), f.name); } catch { err.textContent = T.bad + f.name; err.hidden = false; }
    }
    if (log.devices.length || log.points.length || log.alerts.length) render();
  }

  const input = $("files");
  $("pick").addEventListener("click", () => input.click());
  input.addEventListener("change", () => open([...input.files]));
  addEventListener("dragover", (e) => e.preventDefault());
  addEventListener("drop", (e) => { e.preventDefault(); open([...e.dataTransfer.files]); });
  $("pick").hidden = false;
})();
