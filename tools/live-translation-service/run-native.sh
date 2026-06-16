#!/bin/zsh
# Runs the live translation service natively on the Mac so Whisper uses the
# M-series GPU via MLX. Docker on macOS has no GPU access; its CPU backend runs
# slower than real time and captions drift minutes behind.
SERVICE_DIR="$(cd "$(dirname "$0")" && pwd)"

export HF_HOME="${HF_HOME:-$SERVICE_DIR/.hf-cache}"
export WHISPER_MODEL="${WHISPER_MODEL:-large-v3}"
export WHISPER_BACKEND="${WHISPER_BACKEND:-mlx}"
export WHISPER_MLX_MODEL="${WHISPER_MLX_MODEL:-mlx-community/whisper-large-v3-mlx}"

# Caption readability tuning. Broadcast audio rarely drops below the default
# silence threshold (0.01), so phrases were all hitting the 12s hard cap as one
# huge rewriting line. Cut on real pauses and keep phrases sentence-sized; MLX
# transcribes ~10x faster than real time, so frequent finals are cheap.
export WHISPER_SILENCE_RMS="${WHISPER_SILENCE_RMS:-0.02}"
export WHISPER_SOFT_LIMIT_SECONDS="${WHISPER_SOFT_LIMIT_SECONDS:-4}"
export WHISPER_MAX_PHRASE_SECONDS="${WHISPER_MAX_PHRASE_SECONDS:-7}"
# Min spacing between partial (in-progress) updates; each partial re-transcribes
# the whole phrase and can rewrite the line, so don't emit them faster than the
# app's caption dwell.
export WHISPER_UPDATE_INTERVAL="${WHISPER_UPDATE_INTERVAL:-1.5}"

exec "$SERVICE_DIR/.venv/bin/uvicorn" service:app \
  --app-dir "$SERVICE_DIR" \
  --host 0.0.0.0 \
  --port 8765
