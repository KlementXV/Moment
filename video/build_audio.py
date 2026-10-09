"""Synthesize the music bed and sound effects, then mix them under the narration.

Inputs:  build/narration.wav (from build_voice.py), build/sfx.json (from render.cjs sfx)
Output:  build/mix.wav (48 kHz stereo)
Everything is generated procedurally, so the soundtrack carries no third-party license.
"""
import json
import os

import numpy as np
import soundfile as sf
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "build")
SR = 48_000
rng = np.random.default_rng(3)


def secs(d):
    return np.arange(int(round(d * SR))) / SR


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def sos(kind, f, order=2):
    nyq = SR / 2
    f = np.atleast_1d(f) / nyq
    return signal.butter(order, f if len(f) > 1 else f[0], kind, output="sos")


def filt(x, kind, f, order=2):
    return signal.sosfilt(sos(kind, f, order), x, axis=0)


def noise(d):
    return rng.standard_normal(int(round(d * SR)))


def sweep(x, f0, f1, shape, q=1.6, block=256):
    """Band-pass whose centre follows f0 * (f1 / f0) ** shape(p)."""
    out = np.zeros_like(x)
    zi = None
    n = len(x)
    for i in range(0, n, block):
        fc = f0 * (f1 / f0) ** shape(i / n)
        lo, hi = max(fc / q, 30), min(fc * q, SR / 2 * .95)
        s = sos("bandpass", [lo, hi])
        if zi is None:
            zi = np.zeros((s.shape[0], 2))
        out[i:i + block], zi = signal.sosfilt(s, x[i:i + block], zi=zi)
    return out


def norm(x, peak=1.0):
    m = np.max(np.abs(x))
    return x * (peak / m) if m > 0 else x


def stereo(x, pan=0.0):
    a = (pan + 1) * np.pi / 4
    return np.stack([x * np.cos(a), x * np.sin(a)], axis=1) * np.sqrt(2)


def bell(f, d, decay=3.5, partials=((1, 1), (2.0, .35), (2.76, .22), (5.4, .07))):
    t = secs(d)
    x = sum(a * np.sin(2 * np.pi * f * k * t) * np.exp(-t * decay * (1 + .6 * (k - 1))) for k, a in partials)
    return x * np.minimum(1, t / .004)


# ------------------------------------------------------------------ effects
def fx_whoosh(d=.75, f0=250, f1=3200):
    x = noise(d)
    y = sweep(x, f0, f1, lambda p: np.sin(np.pi * min(p * 1.15, 1)))
    p = np.linspace(0, 1, len(y))
    env = np.where(p < .6, (p / .6) ** 2, ((1 - p) / .4) ** 1.3)
    y = norm(y * env)
    pan = np.linspace(-.5, .5, len(y))
    return np.stack([y * np.cos((pan + 1) * np.pi / 4), y * np.sin((pan + 1) * np.pi / 4)], axis=1) * 1.3


def fx_pop():
    t = secs(.14)
    f = 950 * np.exp(-t * 28) + 260
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 32)
    x[:200] += noise(200 / SR) * .15 * np.exp(-np.arange(200) / 40)
    return norm(x)


def fx_tap():
    t = secs(.06)
    x = filt(noise(.06), "lowpass", 5000) * np.exp(-t * 320) + .35 * np.sin(2 * np.pi * 1700 * t) * np.exp(-t * 110)
    return norm(x)


def fx_tick():
    t = secs(.035)
    x = np.sin(2 * np.pi * 2600 * t) * np.exp(-t * 240) + .25 * filt(noise(.035), "highpass", 3000) * np.exp(-t * 500)
    return norm(x)


def fx_coin():
    x = bell(2093, .7, 7, ((1, 1), (1.26, .6), (1.5, .4), (2.0, .25), (3.1, .1)))
    y = np.zeros(len(x) + int(.06 * SR))
    y[:len(x)] += x
    y[int(.06 * SR):] += .55 * bell(2637, .7, 8, ((1, 1), (1.5, .4), (2.0, .2)))
    return norm(y)


def fx_chime():
    a, b = bell(1318.5, 1.8, 3.2), bell(1975.5, 1.8, 3.4)
    y = np.zeros(len(a) + int(.09 * SR))
    y[:len(a)] += a
    y[int(.09 * SR):] += .8 * b
    return norm(y)


def fx_unlock():
    notes = [987.8, 1318.5, 1975.5]
    y = np.zeros(int(1.8 * SR))
    for i, f in enumerate(notes):
        b = bell(f, 1.4, 3.2)
        o = int(i * .08 * SR)
        y[o:o + len(b)] += b * (1 - .15 * i)
    return norm(y)


def click(d=.008, lo=1800, hi=7000):
    t = secs(d)
    return filt(noise(d), "bandpass", [lo, hi]) * np.exp(-t * 600)


def fx_shutter():
    y = np.zeros(int(.25 * SR))
    for o, g in ((0, 1.0), (.065, .8)):
        c = click(.02)
        i = int(o * SR)
        y[i:i + len(c)] += g * c
    t = secs(.12)
    y[:len(t)] += .5 * np.sin(2 * np.pi * 110 * t) * np.exp(-t * 40)
    y[int(.01 * SR):int(.01 * SR) + int(.05 * SR)] += .15 * filt(noise(.05), "lowpass", 2500)
    return norm(y)


def fx_error():
    y = np.zeros(int(.4 * SR))
    for i, f in enumerate((330, 247)):
        t = secs(.16)
        x = sum(np.sin(2 * np.pi * f * k * t) / k for k in (1, 3, 5)) * np.minimum(1, t / .01) * np.exp(-t * 9)
        o = int(i * .15 * SR)
        y[o:o + len(x)] += x
    return norm(filt(y, "lowpass", 2500))


def fx_drop():
    t = secs(.5)
    f = 900 * (200 / 900) ** (t / .5)
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 5) * np.minimum(1, t / .005)
    return norm(x)


def fx_lock():
    t = secs(.25)
    x = .8 * np.sin(2 * np.pi * 150 * t) * np.exp(-t * 30)
    x[:int(.02 * SR)] += click(.02, 2500, 9000)
    o = int(.05 * SR)
    b = bell(3200, .2, 30)
    x[o:o + len(b)] += .4 * b
    return norm(x)


def fx_stamp():
    t = secs(.4)
    f = 130 * np.exp(-t * 8) + 55
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 11)
    x[:int(.06 * SR)] += .35 * filt(noise(.06), "lowpass", 1600) * np.exp(-secs(.06) * 60)
    return norm(x)


def fx_thud():
    t = secs(.7)
    f = 120 * np.exp(-t * 10) + 42
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 5.5)
    x[:int(.08 * SR)] += .5 * filt(noise(.08), "lowpass", 900) * np.exp(-secs(.08) * 45)
    return norm(x)


def fx_shimmer():
    y = np.zeros(int(2.0 * SR))
    for i in range(9):
        f = midi(rng.choice([86, 88, 90, 93, 95, 98]))
        b = bell(f, 1.0, 5) * (.5 + .5 * rng.random())
        o = int(rng.random() * .8 * SR)
        y[o:o + len(b)] += b
    return norm(y)


def fx_swell(d=2.0):
    x = noise(d)
    y = sweep(x, 200, 5000, lambda p: p ** 1.5, q=2.2)
    p = np.linspace(0, 1, len(y))
    env = p ** 2.2 * np.minimum(1, (1 - p) / .03)
    t = secs(d)
    sub = .5 * np.sin(2 * np.pi * 55 * t) * p ** 2
    return norm(y * env + sub * env)


def fx_scroll(d=3.0):
    y = .08 * filt(noise(d), "bandpass", [400, 2500]) * np.minimum(1, np.linspace(0, d, int(d * SR)) / .3)
    t, rate = 0.0, 16.0
    tick = fx_tick()
    while t < d - .05:
        rem = d - t
        r = rate if rem > 1.0 else max(3, rate * rem)
        i = int(t * SR)
        seg = tick[: max(0, min(len(tick), len(y) - i))]
        y[i:i + len(seg)] += .5 * seg * (.7 + .3 * rng.random())
        t += 1 / r
    fade = np.minimum(1, (d - np.arange(len(y)) / SR) / .4)
    return norm(y * fade)


def fx_sheet():
    return fx_whoosh(.35, 150, 900) * .8


def fx_swish():
    return fx_whoosh(.3, 900, 5500)


FX = {
    "whoosh": fx_whoosh, "pop": fx_pop, "tap": fx_tap, "tick": fx_tick, "coin": fx_coin, "chime": fx_chime,
    "unlock": fx_unlock, "shutter": fx_shutter, "error": fx_error, "drop": fx_drop, "lock": fx_lock,
    "stamp": fx_stamp, "thud": fx_thud, "shimmer": fx_shimmer, "swell": fx_swell, "scroll": fx_scroll,
    "sheet": fx_sheet, "swish": fx_swish,
}
LEVEL = {"whoosh": .32, "swish": .22, "sheet": .2, "pop": .3, "tap": .3, "tick": .22, "coin": .2, "chime": .22,
         "unlock": .25, "shutter": .45, "error": .3, "drop": .3, "lock": .35, "stamp": .45, "thud": .55,
         "shimmer": .12, "swell": .28, "scroll": .3}


def add(bus, x, t, gain=1.0):
    if x.ndim == 1:
        x = stereo(x)
    i = int(round(t * SR))
    if i >= len(bus):
        return
    j = min(len(bus), i + len(x))
    bus[max(i, 0):j] += gain * x[max(0, -i):j - i]


def reverb_ir(d=2.2, damp=4000):
    n = int(d * SR)
    t = np.arange(n) / SR
    ir = np.stack([filt(noise(d), "lowpass", damp), filt(noise(d), "lowpass", damp)], axis=1)
    ir *= np.exp(-t * 3.2)[:, None]
    ir[: int(.012 * SR)] = 0
    return ir / np.sqrt(np.sum(ir ** 2, axis=0))


def reverb(x, ir, wet):
    out = np.stack([signal.fftconvolve(x[:, c], ir[:, c])[: len(x)] for c in range(2)], axis=1)
    return x + wet * out


# ------------------------------------------------------------------ music
BPM = 100
BEAT = 60 / BPM
BAR = 4 * BEAT
CHORDS = [  # (bass root, pad voicing, arp notes)
    (35, [47, 54, 57, 62, 66], [71, 74, 78, 81]),  # Bm7
    (31, [43, 50, 54, 59, 62], [67, 71, 74, 78]),  # Gmaj7
    (38, [50, 57, 61, 66, 69], [74, 78, 81, 85]),  # Dmaj7
    (33, [45, 52, 59, 61, 64], [69, 73, 76, 81]),  # A add9
]
TONIC = (38, [50, 57, 62, 66, 69, 74], [74, 78, 81, 86])


def pad_voice(f, d, bright):
    t = secs(d)
    out = np.zeros((len(t), 2))
    for c, cents in enumerate((-6, 6)):
        ff = f * 2 ** (cents / 1200)
        ph = rng.random() * 2 * np.pi
        tone = sum(np.sin(2 * np.pi * ff * k * t + ph * k) / k * np.exp(-(k - 1) * (1.1 - .6 * bright)) for k in range(1, 7))
        out[:, c] = tone
    return out


def music(duration, sections):
    n = int(duration * SR) + SR
    pad = np.zeros((n, 2))
    bass = np.zeros((n, 2))
    drums = np.zeros((n, 2))
    arp = np.zeros((n, 2))

    def on(name, t):
        return any(a <= t < b for a, b in sections.get(name, []))

    def level(name, t):
        return max((1.0 for a, b in sections.get(name, []) if a <= t < b), default=0.0)

    tonic_at = sections["tonic"]
    bars = int(np.ceil(duration / BAR)) + 1
    for b in range(bars):
        t0 = b * BAR
        if t0 >= tonic_at:
            break
        root, voicing, notes = CHORDS[b % 4]
        d = min(BAR + 1.2, tonic_at - t0 + 1.2)
        for m in voicing:
            v = pad_voice(midi(m), d, sections["bright"](t0))
            env = np.minimum(1, secs(d) / .45) * np.minimum(1, (d - secs(d)) / 1.2)
            add(pad, v * env[:, None] * .12, t0)
        for k, beat in enumerate((0, 1.5, 2, 3)):
            tb = t0 + beat * BEAT
            if on("bass", tb):
                dd = .5 * BEAT if beat == 1.5 else .9 * BEAT
                t = secs(dd)
                f = midi(root + 12)
                x = (np.sin(2 * np.pi * f * t) + .25 * np.sin(4 * np.pi * f * t)) * np.minimum(1, t / .006) * np.minimum(1, (dd - t) / .05)
                add(bass, stereo(x * (.9 if k == 0 else .65)), tb, .5)
        for s in range(8):
            ts = t0 + s * BEAT / 2
            if on("arp", ts):
                f = midi(notes[[0, 2, 1, 3, 2, 0, 3, 1][s]])
                t = secs(.5)
                x = (np.sin(2 * np.pi * f * t) + .3 * np.sin(4 * np.pi * f * t) + .1 * np.sin(6 * np.pi * f * t)) * np.exp(-t * 7) * np.minimum(1, t / .003)
                add(arp, stereo(x, -.45 if s % 2 else .45), ts, .07)
        for beat in range(4):
            tb = t0 + beat * BEAT
            if on("kick", tb) and beat in (0, 2):
                t = secs(.45)
                f = 140 * np.exp(-t * 32) + 47
                x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 7.5)
                add(drums, stereo(x), tb, .55)
            if on("clap", tb) and beat in (1, 3):
                t = secs(.3)
                x = filt(noise(.3), "bandpass", [900, 4500]) * np.exp(-t * 16) * .8 + .4 * np.sin(2 * np.pi * 190 * t) * np.exp(-t * 28)
                add(drums, stereo(x), tb, .16)
            for half in (.5,):
                th = tb + half * BEAT
                if on("hats", th):
                    t = secs(.06)
                    x = filt(noise(.06), "highpass", 7000) * np.exp(-t * 65)
                    add(drums, stereo(x, .25), th, .12 * (.8 + .4 * rng.random()))
    # final tonic chord on the end card
    d = duration - tonic_at + 1.0
    for m in TONIC[1]:
        v = pad_voice(midi(m), d, 1.0)
        t = secs(d)
        env = np.minimum(1, t / .3) * np.exp(-t * .18)
        add(pad, v * env[:, None] * .13, tonic_at)
    for i, m in enumerate(TONIC[2] + [TONIC[2][0] + 12]):
        b = bell(midi(m), 2.5, 1.6)
        add(arp, stereo(b, (-.5, .5)[i % 2]), tonic_at + .25 + i * .18, .06)

    # pad brightness is baked per bar; low-pass the pad bus to keep it warm
    pad = filt(pad, "lowpass", 2600)
    # sidechain the pad and bass to the kick
    kicks = [b * BAR + k * BEAT for b in range(bars) for k in (0, 2) if on("kick", b * BAR + k * BEAT)]
    duck = np.ones(n)
    for k in kicks:
        i = int(k * SR)
        m = min(n - i, int(.4 * SR))
        if m > 0:
            duck[i:i + m] *= 1 - .45 * np.exp(-np.arange(m) / SR / .12)
    pad *= duck[:, None]
    bass *= duck[:, None]
    return pad + bass + drums + arp


def main():
    with open(os.path.join(BUILD, "sfx.json")) as f:
        cues = json.load(f)
    tl_path = os.path.join(HERE, "composition", "timeline.js")
    with open(tl_path) as f:
        src = f.read()
    tl = json.loads(src[src.index("=") + 1:].strip().rstrip(";"))
    S = {s["id"]: (s["start"], s["start"] + s["dur"]) for s in tl["scenes"]}
    cue = lambda sid, c: S[sid][0] + next(s for s in tl["scenes"] if s["id"] == sid)["cues"][c]["t"]
    duration = tl["duration"]

    words = cue("outro", "words")
    end = cue("outro", "end")
    bright_points = [(0, .1), (S["problem"][0], .35), (S["ritual"][0], .7), (S["decay"][0], .85),
                     (S["skr"][0], .5), (S["tech"][0], .75), (S["outro"][0], .9)]

    def bright(t):
        return [b for a, b in bright_points if a <= t][-1]

    sections = {
        "bright": bright,
        "bass": [(S["problem"][0], S["skr"][0]), (S["tech"][0], words)],
        "kick": [(S["ritual"][0], S["skr"][0]), (S["tech"][0] + 1.0, words)],
        "clap": [(S["decay"][0], S["skr"][0]), (S["program"][0], words)],
        "hats": [(cue("problem", "rarer"), S["skr"][0]), (S["tech"][0], words)],
        "arp": [(S["ritual"][0], S["tech"][0]), (S["program"][0], words)],
        "tonic": end - .3,
    }
    mus = music(duration, sections)
    n = int(duration * SR) + SR
    mus = mus[:n]

    sfx = np.zeros((n, 2))
    for e in cues["sfx"]:
        gen = FX[e["type"]]
        x = gen(e["dur"]) if "dur" in e and e["type"] in ("swell", "scroll") else gen()
        add(sfx, x, e["t"], .63 * LEVEL[e["type"]] * e["gain"])

    ir = reverb_ir()
    mus = reverb(mus, ir, .22)
    sfx = reverb(sfx, ir, .16)

    voice, vsr = sf.read(os.path.join(BUILD, "narration.wav"), dtype="float64")
    voice = signal.resample_poly(voice, SR // vsr, 1)
    voice = filt(voice, "highpass", 70)
    voice = np.pad(voice, (0, max(0, n - len(voice))))[:n]
    voice = voice / np.max(np.abs(voice)) * .89

    # duck the music under the voice
    env = filt(np.abs(voice), "lowpass", 6)
    env = np.clip(env / .035, 0, 1)
    rel = np.zeros_like(env)
    a_att, a_rel = np.exp(-1 / (.03 * SR)), np.exp(-1 / (.45 * SR))
    acc = 0.0
    for i in range(0, n, 64):
        v = env[i]
        acc = v + (acc - v) * (a_att ** 64 if v > acc else a_rel ** 64)
        rel[i:i + 64] = acc
    duck = 1 - .68 * rel

    mus *= .032 / np.sqrt(np.mean(mus[: int(duration * SR)] ** 2))
    mus *= duck[:, None]
    fade_out = np.clip((duration - np.arange(n) / SR) / 1.2, 0, 1)
    fade_in = np.clip(np.arange(n) / SR / .6, 0, 1)
    if os.environ.get("DEBUG_BUSES"):
        sf.write(os.path.join(BUILD, "bus-music.wav"), mus.astype(np.float32), SR, subtype="FLOAT")
        sf.write(os.path.join(BUILD, "bus-sfx.wav"), sfx.astype(np.float32), SR, subtype="FLOAT")
    mix = (mus + sfx) * (fade_out * fade_in)[:, None] + stereo(voice) * .72
    mix = np.tanh(mix * 1.1) / 1.1
    mix = mix[: int(np.ceil(duration * SR))]
    sf.write(os.path.join(BUILD, "mix.wav"), mix.astype(np.float32), SR, subtype="FLOAT")
    print(f"mix {len(mix) / SR:.2f}s peak {np.max(np.abs(mix)):.2f}")


if __name__ == "__main__":
    main()
