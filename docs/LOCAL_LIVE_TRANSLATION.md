# Local Live Translation (Whisper)

This feature adds optional live translation subtitles for `LIVE` playback in the player.

## Current app scope

- Supported for `LIVE` playback from `Xtream` and `M3U` providers.
- The player sends session metadata and tapped decoded PCM audio to a local translation service.
- The service is expected to run on the same Mac as the Android emulator (`http://10.0.2.2:8765` default).
- Source language is expected to be auto-detected by the service.
- Returned text is rendered as a single caption line, one phrase at a time
  (replaced in place), and cleared after a short idle/linger period.
- The app paces caption changes for readability: a line is held for at least
  `MIN_CAPTION_DISPLAY_MS` (~2.2s) before advancing to the latest text (intermediate
  updates are coalesced), trading a little latency so text doesn't change too fast.
- Control lives in Settings:
  - global Settings switch + endpoint

## Streaming model (phrase + VAD)

Adapted from [Vanyoo/realtime-subtitle](https://github.com/Vanyoo/realtime-subtitle).
The service accumulates audio into a phrase and uses simple RMS VAD to decide when
to emit text, keyed by an incrementing `chunkId`:

- The app uploads short audio increments (~1s) tapped from the decoded player audio.
- The service appends them into a per-session phrase buffer and, on each upload:
  - emits an in-progress **partial** (same `chunkId`) at most every
    `WHISPER_UPDATE_INTERVAL` so text appears while speech is ongoing;
  - **finalises** the phrase (`isFinal=true`, then resets the buffer and bumps
    `chunkId`) on a natural pause — a silence tail past ~2s, a short pause past
    `WHISPER_SOFT_LIMIT_SECONDS`, or a hard cap at `WHISPER_MAX_PHRASE_SECONDS`.
- Because each phrase is one `chunkId` and the buffer resets on finalise, text is
  never re-emitted — the app shows one line, replacing it in place. Partials of the
  same phrase grow the line; a new `chunkId` replaces it with the next phrase.
- No `initial_prompt` is used: with `task=translate`, feeding the previous
  translation back makes Whisper re-emit it at the start of the next phrase
  (re-showing already-displayed text). Hallucinations (repetition loops, silence)
  are still filtered server-side.

## Service API contract

The Android app uses these endpoints:

1. `POST /v1/live-translation/session`
   - Body:
     - `logicalStreamUrl`
     - `providerId`
     - `contentId`
     - `targetLanguage` (`"en"`)
     - `task` (`"translate"`)
     - `model` (`"whisper-large-v3"`)
     - `autoDetectSourceLanguage` (`true`)
   - Response:
     - `sessionId` (or `id`)

2. `POST /v1/live-translation/session/{sessionId}/audio?startMs=<long>&endMs=<long>&sampleRate=16000&channelCount=1&encoding=pcm_s16le`
   - Body:
     - raw 16-bit little-endian mono PCM at 16 kHz (sent as ~1s increments)
   - Response (`TranslationUpdate`):
     - `sessionId`
     - `chunkId` (phrase id; partials share it, finalise bumps it)
     - `isFinal` (true once the phrase is finalised)
     - `text` (English translation so far; blank when silence / no new text)
     - `sourceLanguage` (optional, auto-detected)

3. `DELETE /v1/live-translation/session/{sessionId}`
   - Best-effort session teardown.

## Whisper recommendation

To keep UI language selection simple, run Whisper in translation mode with auto language detection:

- `task=translate`
- do not force `language`
- use `whisper-large-v3` (app sends this in session start)

This yields English output subtitles from multilingual speech inputs.

## Quick local startup (ready-to-run)

See [TRANSLATION_SERVICE_OPERATIONS.md](TRANSLATION_SERVICE_OPERATIONS.md) for
the day-to-day start/stop guide. Short version: use
`tools/live-translation-service/run-native.sh` (native MLX, GPU). Docker is
CPU-only on macOS and runs slower than real time, so captions fall behind.

### Docker (not recommended on macOS — CPU-only, slower than real time)

The service ships with a `Dockerfile` and `docker-compose.yml`
(`tools/live-translation-service/`). The container restarts automatically if the
process crashes or after a reboot (`restart: unless-stopped`), caches the model in a
named volume, and is capped at 6 GB memory (large-v3 int8 peaks at ~3-4 GB — make
sure Docker Desktop's VM has at least 8 GB allocated):

```bash
cd tools/live-translation-service
docker compose up -d
```

Note: Docker on macOS has no GPU access, so the container always uses the
faster-whisper CPU backend. For the MLX (Metal GPU) backend, run natively as below.

### Native (venv)

Start the included local service with model `large-v3`:

```bash
HF_HOME="$(pwd)/tools/live-translation-service/.hf-cache" \
WHISPER_MODEL=large-v3 \
tools/live-translation-service/.venv/bin/uvicorn service:app \
  --app-dir tools/live-translation-service \
  --host 0.0.0.0 \
  --port 8765
```

### Compute resources (Apple Silicon)

- **CPU backend (default, `faster-whisper`)**: CTranslate2 is **CPU-only on macOS** — it
  cannot use the Mac GPU/Neural Engine. The service auto-detects and uses all
  performance cores (`hw.perflevel0.logicalcpu`, e.g. 20 on an M3 Ultra). Override
  with `WHISPER_CPU_THREADS`, `WHISPER_NUM_WORKERS`, `WHISPER_COMPUTE_TYPE` (default
  `int8`, the fastest on CPU). The model is warmed on startup to avoid first-call lag.
- **GPU backend (`mlx`)**: to actually use the M3 GPU (Metal), install MLX and switch
  backends:

  ```bash
  tools/live-translation-service/.venv/bin/pip install mlx-whisper
  # then start the service with:
  WHISPER_BACKEND=mlx WHISPER_MLX_MODEL=mlx-community/whisper-large-v3-mlx ...
  ```

  This is typically several times faster than CPU for `large-v3` on Apple Silicon.

`GET /health` reports the active `backend`, `cpuThreads`, `numWorkers`, `computeType`,
and `translationMode`/`llmModel`/`llmReady`.

### Translation mode: Whisper vs. local LLM

There are two ways to produce the English text:

- **`whisper` (default)** — Whisper itself translates (`task=translate`). One model,
  lowest latency, but it can occasionally re-emit already-shown text and cannot be
  *instructed* to avoid that.
- **`llm`** — Whisper transcribes the **source language** (`task=transcribe`), then a
  local OpenAI-compatible LLM (LM Studio / Ollama) translates each phrase into
  English. Partials (in-progress) are translated without prior context for low
  latency; finals use prior phrase context and are told not to repeat. HTTP uploads
  return immediately while Whisper+LLM run in a background worker per session, so
  audio keeps flowing. Default phrase window is shorter in LLM mode (6 s hard / 4 s
  soft) than whisper mode (12 s / 6 s).

Enable LLM mode (LM Studio running on `:1234`):

```bash
tools/live-translation-service/.venv/bin/pip install openai httpx
HF_HOME="$(pwd)/tools/live-translation-service/.hf-cache" \
WHISPER_MODEL=large-v3 WHISPER_BACKEND=mlx \
WHISPER_TRANSLATION_MODE=llm \
LLM_BASE_URL=http://localhost:1234/v1 \
LLM_MODEL=qwen3.6-35b-a3b \
tools/live-translation-service/.venv/bin/uvicorn service:app \
  --app-dir tools/live-translation-service --host 0.0.0.0 --port 8765
```

LLM env vars: `LLM_BASE_URL`, `LLM_API_KEY` (default `lm-studio`), `LLM_MODEL`,
`LLM_TARGET_LANG` (default `English`), `LLM_NO_THINK` (default `true` — appends
`/no_think` and strips `<think>` blocks for reasoning models like Qwen3). The Android
app and PCM upload contract are unchanged; only the service config differs.

Tunable phrase/VAD env vars (all optional):

- `WHISPER_SILENCE_RMS` (default `0.01`) — silence amplitude threshold.
- `WHISPER_SILENCE_DURATION` (default `0.8`) — silence tail that finalises a phrase.
- `WHISPER_SOFT_LIMIT_SECONDS` (default `6`) / `WHISPER_MAX_PHRASE_SECONDS` (default `12`).
- `WHISPER_UPDATE_INTERVAL` (default `0.8`) — min spacing between partials.
- `WHISPER_LANGUAGE_PIN_MIN_PROB` (default `0.7`) — confidence to lock source language.

Equivalent docker/env setups are fine as long as your API accepts the same PCM upload contract.

## Emulator networking reminder

- Android emulator cannot reach host `localhost` directly for host-machine services.
- Use `10.0.2.2` for services running on your Mac.
- Default expected endpoint in app preferences: `http://10.0.2.2:8765`.
