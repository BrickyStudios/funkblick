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
  ["en-1.jpg", 1080, 1920, "hoch", "Who is *on air*|around you?", "Wi-Fi and Bluetooth radar for Android. No root, no extra hardware.", "en-1-radar"],
  ["en-2.jpg", 1080, 1920, "hoch", "Spot the *tracker*|that follows you", "AirTag · SmartTag · Tile · Chipolo · Google Find My", "en-2-notable"],
  ["en-3.jpg", 1080, 1920, "hoch", "Every call|*explained*", "IEEE maker registry, what the device broadcasts, your own rules", "en-3-device"],
  ["en-4.jpg", 1080, 1920, "hoch", "*Car* mode", "Speed cameras, vehicle routers, possible police gear. Spoken alerts, passengers only.", "en-4-car"],
  ["en-5.jpg", 1080, 1920, "hoch", "The *quietest*|channel", "EU Wi-Fi channels 1 to 13, a tip for your router", "en-5-channels"],
  ["de-1.jpg", 1080, 1920, "hoch", "Wer *funkt*|um dich herum?", "WLAN- und Bluetooth-Radar fürs Handy. Ohne Root, ohne Zusatzgerät.", "de-1-radar"],
  ["de-2.jpg", 1080, 1920, "hoch", "Erkennt *Tracker*,|die dir folgen", "AirTag · SmartTag · Tile · Chipolo · Google „Mein Gerät finden“", "de-2-notable"],
  ["de-3.jpg", 1080, 1920, "hoch", "Jede Einordnung|*erklärt*", "IEEE-Herstellerregister, was das Gerät sendet, deine eigenen Regeln", "de-3-device"],
  ["de-4.jpg", 1080, 1920, "hoch", "*Auto*-Modus", "Blitzer, Fahrzeug-Router, mögliche Polizeitechnik. Mit Ansage, nur für Beifahrer.", "de-4-car"],
  ["de-5.jpg", 1080, 1920, "hoch", "Der *freieste*|WLAN-Kanal", "EU-Kanäle 1 bis 13, mit Tipp für die FRITZ!Box", "de-5-channels"],
  ["social-preview.png", 1280, 640, "quer", "Wi-Fi + Bluetooth|*radar* for Android", "Tracker alerts · car mode · GrapheneOS · open source", "en-1-radar"],
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
