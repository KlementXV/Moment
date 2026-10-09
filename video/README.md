# Hackathon film

`moment-hackathon.mp4` is a 2 min 50 s motion-design presentation of Moment for the Solana Mobile CLOCK IN hackathon: 1920×1080, 30 fps, English voice-over. `moment-hackathon.srt` holds the matching English captions.

| Time | Section | Content |
| --- | --- | --- |
| 0:00 | Intro | Logo, tagline |
| 0:10 | Why | Endless scrolling vs. showing up; the rear photo + selfie format |
| 0:23 | 01 · The daily ritual | Wallet connection, SKR stake, capture, signature, feed unlock, on a recreated app UI |
| 0:44 | 02 · The SKR economy | UTC rounds, 10% decay per missed day, compounding over 30 days |
| 1:02 | 03 · The daily pool | Penalties fund the day's pool, 06:00 UTC closure, pro-rata split with a worked example |
| 1:20 | 04 · Sustainable by design | No minting, no protocol fee, pool roll-over, 48 h withdrawal |
| 1:39 | 05 · Why SKR | Locking, consistency rewards, `.skr` identity |
| 1:52 | 06 · Under the hood | App, wallet, keyserver, Solana, R2, PostgreSQL; manifest, commitment, AES-GCM, moderation, cosigning |
| 2:18 | 07 · On-chain | Anchor accounts and the permissionless crank |
| 2:35 | Outro | Devnet status, call to action, repository link |

Numbers shown on screen follow the devnet configuration in `program/scripts/bootstrap-devnet.ts` and the program logic in `program/programs/moment/src`. The phone screens are recreations of the app's UI, not screen recordings.

## Rebuild

The film is generated from code, so editing the script or a scene and rerunning the pipeline keeps voice, captions, animation and sound in sync.

```sh
cd video
./make.sh
```

Requirements: Python 3 with `numpy`, `scipy`, `soundfile` and `kokoro-onnx`; Node.js with Playwright and Chromium; `ffmpeg`. The first run downloads the Kokoro TTS model (about 350 MB) into `video/.cache/`.

| File | Role |
| --- | --- |
| `narration.json` | Voice-over script, one entry per line, grouped by scene |
| `build_voice.py` | Kokoro TTS voice-over, captions, and `composition/timeline.js` cue times |
| `composition/` | HTML/CSS/JS motion design; `window.render(t)` draws any frame deterministically |
| `render.cjs` | Headless Chromium renderer: stills for review, every frame, and the sound-effect cue list |
| `build_audio.py` | Procedural music bed and sound effects, ducked under the narration |
| `make.sh` | Full pipeline and final H.264/AAC encode |

To preview a moment of the film without a full render:

```sh
node render.cjs stills /tmp/stills 12.5,64,130
```

## Credits

Voice: [Kokoro-82M](https://github.com/hexgrad/kokoro) (Apache-2.0), voice `af_heart`. Music and sound effects are synthesized by `build_audio.py`. Illustrations reproduce the app's demo artwork from `app/app/src/main/java/com/klementxv/moment/ui/Feed.kt`.
