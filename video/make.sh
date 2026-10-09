#!/usr/bin/env bash
# Build the Moment hackathon film: voice-over -> timeline -> frames -> soundtrack -> MP4.
# Requirements: Python 3 with numpy, scipy, soundfile, kokoro-onnx; Node 18+ with Playwright and Chromium; ffmpeg.
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p .cache build
if [ ! -f .cache/kokoro.onnx ]; then
  curl -sSL -o .cache/kokoro.onnx https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx
  curl -sSL -o .cache/voices.bin https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin
fi

python3 build_voice.py                       # narration.wav, composition/timeline.js, captions
node render.cjs sfx build/sfx.json           # sound-effect cues exported by the composition
python3 build_audio.py                       # music, effects and mix
rm -rf build/frames
node render.cjs frames build/frames "${WORKERS:-3}"

ffmpeg -y -loglevel error -framerate 30 -i build/frames/f%05d.jpg -i build/mix.wav \
  -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -profile:v high -movflags +faststart \
  -af loudnorm=I=-16:TP=-1.5:LRA=11 -c:a aac -b:a 192k -ar 48000 -shortest \
  moment-hackathon.mp4
echo "wrote $(pwd)/moment-hackathon.mp4"
