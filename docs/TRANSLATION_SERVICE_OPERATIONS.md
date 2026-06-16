# Translation Service — Start / Stop Guide

How to run the local live-translation service that the app's `LIVE` playback
uses for translated subtitles. For the feature itself (API contract, streaming
model, tuning), see [LOCAL_LIVE_TRANSLATION.md](LOCAL_LIVE_TRANSLATION.md).

> **Run it natively, not in Docker.** Docker on macOS has no GPU access, so the
> container falls back to a CPU backend that transcribes ~3.5x slower than real
> time — finalized phrases queue up and captions drift minutes behind live
> audio. The native MLX (Metal GPU) backend transcribes a ~12s phrase in ~1.3s.

## Start

Run from the repository root:

```bash
./tools/live-translation-service/run-native.sh
```

Runs in the foreground on port **8765** (Ctrl-C to stop). To keep it running
after the terminal closes, start it in the background with logging:

```bash
nohup ./tools/live-translation-service/run-native.sh \
  >> ~/Library/Logs/live-translation.log 2>&1 &
```

The script selects the MLX (GPU) backend with `large-v3` and applies the
caption-readability tuning (silence threshold, phrase caps, partial interval).
Every setting is an overridable env var — see the script and
[LOCAL_LIVE_TRANSLATION.md](LOCAL_LIVE_TRANSLATION.md) for the full list.

The model is cached in `tools/live-translation-service/.hf-cache`; first-ever
start downloads ~3 GB, after that startup takes a few seconds (the model is
warmed before the port starts accepting work).

## Check it's running

```bash
curl -s http://localhost:8765/health
```

Healthy response includes `"status":"ok"` and `"backend":"mlx"` — if `backend`
says `faster-whisper`, you're on the slow CPU path; check that the script (not
a bare `uvicorn`) started it.

```bash
tail -f ~/Library/Logs/live-translation.log     # live logs (if started via nohup)
```

In the logs, `queued final chunk=N (queue=M)` with `M` steadily growing means
the service can't keep up with real time — captions are falling behind.

## Stop

```bash
kill $(lsof -ti :8765)
```

or, equivalently:

```bash
pkill -f "uvicorn service:app"
```

Sessions are in-memory only; stopping mid-playback is safe. The app shows
"Live translation unavailable" and resumes when the service is back.

## Restart (e.g. after changing settings)

From the repository root:

```bash
kill $(lsof -ti :8765)
nohup ./tools/live-translation-service/run-native.sh \
  >> ~/Library/Logs/live-translation.log 2>&1 &
sleep 5 && curl -s http://localhost:8765/health
```

## After a reboot

The service does **not** start automatically — you must rerun the start
command. A LaunchAgent can't reliably do it if the repo is checked out under a
TCC-protected location such as `~/Desktop`, `~/Documents`, or `~/Downloads`:
macOS privacy protection blocks launchd background processes from reading those
directories ("Operation not permitted"). If auto-start matters, check the repo
out somewhere unprotected (e.g. `~/src`) or grant `/bin/zsh` Full Disk Access in
System Settings → Privacy & Security.

## Endpoints the app uses

| Client | Endpoint |
| --- | --- |
| Android emulator | `http://10.0.2.2:8765` |
| Fire TV / real device | `http://<Mac LAN IP>:8765` |

The service binds `0.0.0.0`, so LAN devices can reach it directly.

## Docker (legacy — not recommended)

The compose setup still exists for reference:

```bash
cd tools/live-translation-service
docker compose up -d      # start
docker compose down       # stop
```

Expect captions to lag minutes behind live audio (CPU-only, slower than real
time). Only useful if the API needs to be up and translation latency is
irrelevant.
