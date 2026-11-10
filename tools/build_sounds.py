#!/usr/bin/env python3
"""Builds the alert sounds in app/src/main/assets/sounds/.

tone_<kind>.ogg   short synthesized signal, one per alert kind, language-free
de_<kind>.ogg     spoken alert, Piper TTS, voice Thorsten (Thorsten-Voice dataset, CC0)
en_<kind>.ogg     spoken alert, Piper TTS, voice LJ Speech (public domain)

Needs: piper (pip install piper-tts), ffmpeg, numpy, the two voices in ~/.local/share/piper-voices
(de_DE-thorsten-high, en_US-ljspeech-high from huggingface.co/rhasspy/piper-voices).
Run: python3 tools/build_sounds.py [kind ...]   (only the named kinds, e.g. "home")
"""
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/sounds"
VOICES = Path.home() / ".local/share/piper-voices"
SR = 44_100

LINES = {
    "tracker": ("Achtung. Ein Tracker ist seit zehn Minuten bei dir.", "Warning. A tracker has been with you for ten minutes."),
    "again": ("Achtung. Dieser Tracker war schon bei einer früheren Fahrt dabei.", "Warning. This tracker came along on an earlier drive too."),
    "priority": ("Mögliche Polizei- oder Einsatztechnik.", "Possible police or emergency gear."),
    "speedcam": ("Blitzer voraus.", "Speed camera ahead."),
    "camera": ("Kamera in der Nähe.", "Camera nearby."),
    "fleet": ("Fahrzeug-Router in der Nähe.", "Vehicle router nearby."),
    "watch": ("Beobachtetes Gerät erkannt.", "Watched device detected."),
    "follow": ("Achtung. Ein Fahrzeug fährt seit Minuten mit dir.", "Heads up. A vehicle has been with you for minutes."),
    "police": ("Wahrscheinlich Polizei in der Nähe.", "Probably police nearby."),
    "start": ("Funkblick aktiv. Scan läuft.", "Funkblick online. Scanning."),
    "home": ("Neues Gerät an deinem Ort.", "New device at your place."),
    "hacker": ("Achtung. Hacker-Werkzeug in der Nähe.", "Warning. Hacking tool nearby."),
}

# Navigation is said in two pieces, "In 300 Metern" + "rechts abbiegen" (Alerter.say chains them). Build: "nav".
NAV = {
    "nav_start": ("Route berechnet. Los geht's.", "Route ready. Let's go."),
    "nav_reroute": ("Neue Route.", "New route."),
    "nav_arrived": ("Du hast dein Ziel erreicht.", "You have arrived."),
    "nav_now": ("Jetzt", "Now,"),
    "nav_d100": ("In hundert Metern", "In one hundred meters,"),
    "nav_d200": ("In zweihundert Metern", "In two hundred meters,"),
    "nav_d300": ("In dreihundert Metern", "In three hundred meters,"),
    "nav_d500": ("In fünfhundert Metern", "In five hundred meters,"),
    "nav_d800": ("In achthundert Metern", "In eight hundred meters,"),
    "nav_d1000": ("In einem Kilometer", "In one kilometer,"),
    "nav_d2000": ("In zwei Kilometern", "In two kilometers,"),
    "nav_dest": ("erreichst du dein Ziel.", "you reach your destination."),
    "nav_right": ("rechts abbiegen.", "turn right."),
    "nav_left": ("links abbiegen.", "turn left."),
    "nav_slight_right": ("halb rechts abbiegen.", "bear right."),
    "nav_slight_left": ("halb links abbiegen.", "bear left."),
    "nav_sharp_right": ("scharf rechts abbiegen.", "turn sharp right."),
    "nav_sharp_left": ("scharf links abbiegen.", "turn sharp left."),
    "nav_uturn": ("wenden.", "make a U-turn."),
    "nav_straight": ("geradeaus weiterfahren.", "continue straight."),
    "nav_keep_right": ("rechts halten.", "keep right."),
    "nav_keep_left": ("links halten.", "keep left."),
    "nav_exit_right": ("die Ausfahrt rechts nehmen.", "take the exit on the right."),
    "nav_exit_left": ("die Ausfahrt links nehmen.", "take the exit on the left."),
    "nav_ramp_right": ("rechts auf die Auffahrt.", "take the ramp on the right."),
    "nav_ramp_left": ("links auf die Auffahrt.", "take the ramp on the left."),
    "nav_merge": ("einfädeln.", "merge."),
    "nav_round": ("in den Kreisverkehr fahren.", "enter the roundabout."),
    "nav_round1": ("im Kreisverkehr die erste Ausfahrt nehmen.", "at the roundabout, take the first exit."),
    "nav_round2": ("im Kreisverkehr die zweite Ausfahrt nehmen.", "at the roundabout, take the second exit."),
    "nav_round3": ("im Kreisverkehr die dritte Ausfahrt nehmen.", "at the roundabout, take the third exit."),
    "nav_round4": ("im Kreisverkehr die vierte Ausfahrt nehmen.", "at the roundabout, take the fourth exit."),
    "nav_round5": ("im Kreisverkehr die fünfte Ausfahrt nehmen.", "at the roundabout, take the fifth exit."),
}


def env(n, attack=0.004, release=0.08):
    """Linear attack, exponential release: clicks off, tail natural."""
    t = np.arange(n) / SR
    a = np.clip(t / attack, 0, 1)
    r = np.exp(-t / release)
    return a * r


def tone(freq, dur, release=0.08, shape="sine", sweep_to=None):
    n = int(SR * dur)
    t = np.arange(n) / SR
    f = freq if sweep_to is None else freq * (sweep_to / freq) ** (t / dur)
    phase = 2 * np.pi * np.cumsum(f) / SR
    if shape == "tri":
        w = 2 / np.pi * np.arcsin(np.sin(phase))
    elif shape == "bell":  # inharmonic partials like a small bell
        w = np.sin(phase) + 0.45 * np.sin(2.76 * phase) * np.exp(-t / 0.15) + 0.25 * np.sin(5.4 * phase) * np.exp(-t / 0.06)
    else:
        w = np.sin(phase) + 0.18 * np.sin(2 * phase)
    return w * env(n, release=release)


def seq(*parts):
    """parts: (start_seconds, samples). Mixed onto one track."""
    end = max(int(s * SR) + len(x) for s, x in parts)
    out = np.zeros(end + int(0.05 * SR))
    for s, x in parts:
        i = int(s * SR)
        out[i:i + len(x)] += x
    return out


def echo(x, delay=0.11, gain=0.35, taps=3):
    out = np.concatenate([x, np.zeros(int(delay * SR * taps))])
    for k in range(1, taps + 1):
        i = int(delay * SR * k)
        out[i:i + len(x)] += x * gain ** k
    return out


TONES = {
    # three falling pulses: the one alert that is about your own safety
    "tracker": seq((0, tone(1318, 0.16, 0.05, "tri")), (0.19, tone(1108, 0.16, 0.05, "tri")), (0.38, tone(880, 0.32, 0.12, "tri"))),
    # high-low twice, short enough not to be mistaken for a real siren
    "priority": seq((0, tone(988, 0.15, 0.06)), (0.17, tone(740, 0.15, 0.06)), (0.34, tone(988, 0.15, 0.06)), (0.51, tone(740, 0.22, 0.1))),
    # radar chirp up, then a click
    "speedcam": seq((0, tone(520, 0.28, 0.2, "tri", sweep_to=1560)), (0.3, tone(1560, 0.05, 0.015))),
    # sonar ping with a tail
    "camera": echo(tone(1046, 0.5, 0.2, "bell")),
    # soft double blip
    "fleet": seq((0, tone(523, 0.12, 0.05)), (0.14, tone(659, 0.18, 0.08))),
    # bell
    "watch": tone(880, 0.9, 0.35, "bell"),
    # the public safety pair, three times and lower: stronger evidence than "possible"
    "police": seq((0, tone(880, 0.13, 0.05)), (0.15, tone(660, 0.13, 0.05)), (0.30, tone(880, 0.13, 0.05)), (0.45, tone(660, 0.13, 0.05)),
                  (0.60, tone(880, 0.13, 0.05)), (0.75, tone(587, 0.3, 0.12))),
    # two low pulses, the same pair again a step higher: something following you
    "follow": seq((0, tone(392, 0.14, 0.06, "tri")), (0.2, tone(392, 0.14, 0.06, "tri")), (0.5, tone(587, 0.14, 0.06, "tri")), (0.7, tone(587, 0.24, 0.1, "tri"))),
    # power-on self test: a sweep through the band, three chirps, a lock tone and a chord
    "start": seq((0, tone(300, 0.55, 0.4, "tri", sweep_to=1800)), (0.6, tone(1318, 0.05, 0.02)), (0.68, tone(1568, 0.05, 0.02)),
                 (0.76, tone(1760, 0.05, 0.02)), (0.92, tone(880, 0.12, 0.05)), (1.06, tone(523, 0.5, 0.3, "bell")),
                 (1.06, tone(659, 0.5, 0.3, "bell")), (1.06, tone(784, 0.5, 0.3, "bell"))),
    # doorbell: two bell tones, high then low: something new at your door
    "home": seq((0, tone(784, 0.5, 0.3, "bell")), (0.35, tone(622, 0.7, 0.4, "bell"))),
    # data stutter: a quick run of short triangle blips up and down
    "hacker": seq(*[(i * 0.07, tone(f, 0.05, 0.02, "tri")) for i, f in enumerate((1175, 880, 1319, 988, 1480, 1109, 1568))]),
    # scan stop: falling triad
    "stop": seq((0, tone(784, 0.12, 0.06)), (0.09, tone(659, 0.12, 0.06)), (0.18, tone(523, 0.3, 0.15))),
}
TONES["again"] = TONES["tracker"]  # same alarm as a following tag, its own sentence


def write_wav(path, x):
    x = x / max(1e-9, np.max(np.abs(x))) * 0.89
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())


def ogg(src, dst, filters=None):
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", str(src)]
    if filters:
        cmd += ["-af", filters]
    cmd += ["-ac", "1", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "5", str(dst)]
    subprocess.run(cmd, check=True)


# the "AI on the radio" colour: no rumble, a bit of presence, gently compressed, loud
VOICE_FX = ("highpass=f=140,lowpass=f=7800,equalizer=f=2800:t=q:w=1.2:g=4,"
            "acompressor=threshold=-20dB:ratio=4:attack=5:release=80,aecho=0.8:0.6:22:0.12,loudnorm=I=-14:TP=-1.5:LRA=7")

TRIM = ("silenceremove=start_periods=1:start_threshold=-45dB,areverse,"
        "silenceremove=start_periods=1:start_threshold=-45dB,areverse")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        only = set(sys.argv[1:])
        for kind, x in TONES.items():
            if only and kind not in only:
                continue
            write_wav(tmp / f"{kind}.wav", x)
            ogg(tmp / f"{kind}.wav", OUT / f"tone_{kind}.ogg", "loudnorm=I=-14:TP=-1.5")
        for lang, voice, i in (("de", "de_DE-thorsten-high", 0), ("en", "en_US-ljspeech-high", 1)):
            for kind, texts in {**LINES, **NAV}.items():
                if only and kind not in only and not (kind in NAV and "nav" in only):
                    continue
                raw = tmp / f"{lang}_{kind}.wav"
                subprocess.run(["piper", "-m", str(VOICES / f"{voice}.onnx"), "-f", str(raw), "--length-scale", "0.92"],
                               input=texts[i].encode(), check=True, capture_output=True)
                # pieces get their silence cut at both ends, so two of them sound like one sentence
                ogg(raw, OUT / f"{lang}_{kind}.ogg", (TRIM + "," if kind in NAV else "") + VOICE_FX)
    total = sum(p.stat().st_size for p in OUT.iterdir())
    print(f"{OUT}: {len(list(OUT.iterdir()))} files, {total // 1024} KB")


if __name__ == "__main__":
    main()
