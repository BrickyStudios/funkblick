// Renders the README/website pictures from the app screenshots in docs/bilder/app (tools/promo.html).
// Run: node tools/promo.mjs   (needs Playwright with Chromium; path below is the maintainer's copy)
import { chromium } from "/home/user/Dokumente/VibeCoding/pferde-umkleide/node_modules/playwright/index.mjs";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "docs/bilder");
const shot = (n) => `file://${ROOT}/docs/bilder/app/${n}.png`;
const pics = [
  ["en-1.jpg", 1080, 1920, "hoch", "What is on air|around you", "Wi-Fi and Bluetooth on a map, while you drive", "en-1-map"],
  ["en-2.jpg", 1080, 1920, "hoch", "The *tracker*|that follows you", "AirTag, SmartTag, Tile, Chipolo, Google Find My", "en-3-notable"],
  ["en-3.jpg", 1080, 1920, "hoch", "Probably *police*,|with the reason", "Body cam signals, vehicles that come along", "en-4-device"],
  ["en-4.jpg", 1080, 1920, "hoch", "*Car* mode", "Speed cameras, vehicles, spoken alerts. For passengers.", "en-5-car"],
  ["en-5.jpg", 1080, 1920, "hoch", "Discreet mode", "An ordinary map. Alerts only in your headphones.", "en-6-discreet"],
  ["de-1.jpg", 1080, 1920, "hoch", "Was hier|*funkt*", "WLAN und Bluetooth auf der Karte, während du fährst", "de-1-map"],
  ["de-2.jpg", 1080, 1920, "hoch", "Der *Tracker*,|der dir folgt", "AirTag, SmartTag, Tile, Chipolo, Google", "de-3-notable"],
  ["de-3.jpg", 1080, 1920, "hoch", "Wahrscheinlich|*Polizei*, mit Grund", "Bodycam-Signale, Autos, die mitfahren", "de-4-device"],
  ["de-4.jpg", 1080, 1920, "hoch", "*Auto*-Modus", "Blitzer, Fahrzeuge, Ansagen. Für Beifahrer.", "de-5-car"],
  ["de-5.jpg", 1080, 1920, "hoch", "Diskret-Modus", "Eine normale Karte. Warnungen nur im Kopfhörer.", "de-6-discreet"],
  ["social-preview.png", 1280, 640, "quer", "Wi-Fi and Bluetooth|on a *map*", "Trackers, cameras, car mode. Android and GrapheneOS.", "en-1-map"],
];
mkdirSync(OUT, { recursive: true });
const b = await chromium.launch();
for (const [name, w, h, art, t, s, img] of pics) {
  const p = await b.newPage({ viewport: { width: w, height: h } });
  const u = new URL(`file://${ROOT}/tools/promo.html`);
  for (const [k, v] of Object.entries({ w, h, art, t, s, img: shot(img) })) u.searchParams.set(k, v);
  await p.goto(u.href, { waitUntil: "load" });
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(300);
  await p.screenshot({ path: join(OUT, name), ...(name.endsWith(".jpg") ? { type: "jpeg", quality: 82 } : {}) });
  await p.close();
}
await b.close();
console.log(`${pics.length} pictures in ${OUT}`);
