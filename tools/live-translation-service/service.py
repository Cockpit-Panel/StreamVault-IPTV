import asyncio
import dataclasses
import logging
import os
import re
import time
import uuid
from collections import deque
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException, Query, Request
from pydantic import BaseModel
from faster_whisper import WhisperModel


logger = logging.getLogger("live-translation")
logging.basicConfig(level=logging.INFO)

TARGET_SAMPLE_RATE = 16000
DEFAULT_MODEL = os.environ.get("WHISPER_MODEL", "large-v3")

# --- Compute backend / resource configuration (tuned for Apple Silicon) ---
# NOTE: faster-whisper (CTranslate2) is CPU-only on macOS; it cannot use the Mac
# GPU/Neural Engine. To actually use the M3 GPU, set WHISPER_BACKEND=mlx (requires
# `pip install mlx-whisper`), which runs Whisper on Metal.
BACKEND = os.environ.get("WHISPER_BACKEND", "faster-whisper").lower()
# Quantization for the CPU (CTranslate2) backend. int8 is the fastest on CPU.
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "int8")
# Parallel inference workers (each can serve a concurrent request).
NUM_WORKERS = int(os.environ.get("WHISPER_NUM_WORKERS", "1"))
# MLX (GPU) model repo, used only when WHISPER_BACKEND=mlx.
MLX_MODEL = os.environ.get("WHISPER_MLX_MODEL", "mlx-community/whisper-large-v3-mlx")

# --- Translation mode ---
# "whisper": Whisper itself translates (task=translate).
# "llm": Whisper transcribes the source language, then a local OpenAI-compatible
#        LLM translates each finalised phrase to the target language. The LLM is
#        told not to repeat prior translations, which avoids re-showing text.
TRANSLATION_MODE = os.environ.get("WHISPER_TRANSLATION_MODE", "whisper").lower()
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "http://localhost:1234/v1")
LLM_API_KEY = os.environ.get("LLM_API_KEY", "lm-studio")
LLM_MODEL = os.environ.get("LLM_MODEL", "qwen3.6-35b-a3b")
LLM_TARGET_LANG = os.environ.get("LLM_TARGET_LANG", "English")
# Append "/no_think" and strip <think>...</think> for reasoning models (Qwen3 etc.).
LLM_NO_THINK = os.environ.get("LLM_NO_THINK", "true").lower() == "true"


def _detect_cpu_threads() -> int:
    """Use all performance cores by default (avoid the slower efficiency cores)."""
    env = os.environ.get("WHISPER_CPU_THREADS")
    if env and env.isdigit() and int(env) > 0:
        return int(env)
    try:
        import subprocess

        out = subprocess.run(
            ["sysctl", "-n", "hw.perflevel0.logicalcpu"],
            capture_output=True,
            text=True,
            timeout=2,
        )
        cores = int(out.stdout.strip())
        if cores > 0:
            return cores
    except Exception:
        pass
    return os.cpu_count() or 4


CPU_THREADS = _detect_cpu_threads()

# --- Phrase / VAD streaming parameters (adapted from Vanyoo/realtime-subtitle) ---
# RMS amplitude (on float32 audio normalised to [-1, 1]) below which audio is silence.
SILENCE_RMS = float(os.environ.get("WHISPER_SILENCE_RMS", "0.01"))
_LLM_MODE = TRANSLATION_MODE == "llm"
# Tail of silence (seconds) that, past a minimum phrase length, finalises a phrase.
SILENCE_DURATION = float(os.environ.get("WHISPER_SILENCE_DURATION", "1.0" if _LLM_MODE else "0.8"))
# Past this length, a short pause is enough to finalise (avoids huge latency).
SOFT_LIMIT_SECONDS = float(os.environ.get("WHISPER_SOFT_LIMIT_SECONDS", "5" if _LLM_MODE else "6"))
# Hard cap: force-finalise a phrase even with no pause.
MAX_PHRASE_SECONDS = float(os.environ.get("WHISPER_MAX_PHRASE_SECONDS", "8" if _LLM_MODE else "12"))
# Audio tail kept across phrase boundaries so words at cuts aren't lost.
PHRASE_OVERLAP_SECONDS = float(os.environ.get("WHISPER_PHRASE_OVERLAP_SECONDS", "0.4"))
# Minimum buffered audio before we emit a partial / finalise anything.
MIN_PHRASE_SECONDS = 0.5
# Minimum spacing (seconds) between partial (in-progress) transcriptions.
UPDATE_INTERVAL = float(os.environ.get("WHISPER_UPDATE_INTERVAL", "1.2" if _LLM_MODE else "0.8"))
# Only pin the source language once detection is confident, so a music/intro
# window can't lock us onto the wrong language for the whole session.
LANGUAGE_PIN_MIN_PROB = float(os.environ.get("WHISPER_LANGUAGE_PIN_MIN_PROB", "0.7"))


class StartSessionRequest(BaseModel):
    logicalStreamUrl: Optional[str] = None
    providerId: Optional[int] = None
    contentId: Optional[int] = None
    targetLanguage: str = "en"
    task: str = "translate"
    model: Optional[str] = None
    autoDetectSourceLanguage: bool = True


class StartSessionResponse(BaseModel):
    sessionId: str
    status: str = "started"
    model: str


class TranslationUpdateDto(BaseModel):
    sessionId: str
    chunkId: int
    isFinal: bool
    text: str
    sourceLanguage: Optional[str] = None


@dataclasses.dataclass
class TranslationUpdate:
    chunk_id: int
    is_final: bool
    text: str
    source_language: Optional[str]


@dataclasses.dataclass
class PendingWork:
    snapshot: object
    chunk_id: int
    is_final: bool
    buffer_duration: float
    prompt: str
    pinned: Optional[str]
    use_llm: bool
    asr_task: str
    model_name: str


@dataclasses.dataclass
class SessionState:
    id: str
    created_at_monotonic: float
    model_name: str
    task: str
    lock: asyncio.Lock
    closed: bool
    # Accumulating PCM16 mono 16 kHz audio for the current phrase.
    audio: bytearray
    # Monotonically increasing id; one per finalised phrase.
    chunk_id: int
    # Last time a partial transcription was emitted (monotonic seconds).
    last_partial_at: float
    # Last finalised translation, reused as Whisper initial_prompt for continuity.
    last_final_text: str
    source_language: Optional[str]
    # LLM-mode context: previous source text and its translation (for continuity
    # and to instruct the LLM not to repeat).
    prev_source: str
    prev_translation: str
    # Latest caption produced by the background worker; returned on the next upload
    # once generation advances (so HTTP uploads stay fast and non-blocking).
    latest_update: Optional[TranslationUpdate] = None
    update_generation: int = 0
    last_returned_generation: int = 0
    # Finals are queued and never dropped; partials coalesce to the latest snapshot.
    pending_queue: deque = dataclasses.field(default_factory=deque)
    pending_partial: Optional[PendingWork] = None
    process_task: Optional[asyncio.Task] = None


def is_hallucination(text: str) -> bool:
    """Detect Whisper repetition-loop hallucinations (e.g. 'once once once')."""
    if not text:
        return False
    words = text.split()
    if not words:
        return False
    max_repeats = 0
    current = 1
    last = ""
    for word in words:
        if word == last:
            current += 1
        else:
            max_repeats = max(max_repeats, current)
            current = 1
            last = word
    max_repeats = max(max_repeats, current)
    if max_repeats > 4:
        return True
    if len(words) > 10:
        ratio = len(set(words)) / len(words)
        if ratio < 0.4:
            return True
    return False


def is_prompt_echo(text: str, prompt: str) -> bool:
    """Detect output that merely echoes the prompt (common on music/silence)."""
    if not text or not prompt:
        return False

    def normalize(value: str) -> str:
        return re.sub(r"[^\w\s]", "", value.lower()).strip()

    norm_text = normalize(text)
    norm_prompt = normalize(prompt)
    if not norm_text or not norm_prompt:
        return False
    if norm_text == norm_prompt:
        return True
    if norm_prompt.endswith(norm_text):
        return True
    return False


def _strip_thinking(text: str) -> str:
    """Remove <think>...</think> blocks emitted by reasoning models."""
    cleaned = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL)
    # Drop any dangling unclosed think prefix.
    cleaned = re.sub(r"^.*?</think>", "", cleaned, flags=re.DOTALL)
    return cleaned.strip()


class LlmTranslator:
    """Translates text via a local OpenAI-compatible endpoint (e.g. LM Studio)."""

    def __init__(self, base_url: str, api_key: str, model: str, target_lang: str) -> None:
        from openai import OpenAI
        import httpx

        self.model = model
        self.target_lang = target_lang
        self.client = OpenAI(
            api_key=api_key or "dummy",
            base_url=base_url,
            http_client=httpx.Client(verify=False),
        )

    def translate(
        self,
        text: str,
        prev_source: str = "",
        prev_translation: str = "",
        *,
        use_context: bool = True,
    ) -> str:
        if not text or not text.strip():
            return ""
        if use_context and prev_source and prev_translation:
            system_prompt = (
                f"You are a professional real-time translator. Translate the user "
                f"message into {self.target_lang}.\n"
                f"<context>\n"
                f"Previous source: \"{prev_source}\"\n"
                f"Previous translation: \"{prev_translation}\"\n"
                f"</context>\n"
                f"Rules:\n"
                f"1. Use the context ONLY for terminology consistency.\n"
                f"2. Translate ONLY the user message.\n"
                f"3. Do NOT repeat the previous source or translation.\n"
                f"4. Output ONLY the translation, with no notes or quotes."
            )
        else:
            system_prompt = (
                f"You are a professional real-time translator. Translate the user "
                f"message into {self.target_lang}. Output ONLY the translation, with "
                f"no notes, explanations, or quotes."
            )
        user_content = f"{text} /no_think" if LLM_NO_THINK else text
        response = self.client.chat.completions.create(
            model=self.model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ],
            temperature=0.2,
            max_tokens=768,
            timeout=30.0,
        )
        message = response.choices[0].message
        raw = (message.content or "").strip()
        if not raw:
            reasoning = getattr(message, "reasoning_content", None) or ""
            if reasoning.strip():
                logger.warning("LLM returned empty content (reasoning only); retrying without context")
                if use_context and (prev_source or prev_translation):
                    return self.translate(text, use_context=False)
        return _strip_thinking(raw)


class ModelHolder:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._model: Optional[WhisperModel] = None
        self._model_name: Optional[str] = None

    async def get(self, model_name: str) -> WhisperModel:
        async with self._lock:
            resolved_model = resolve_model_path(model_name)
            if self._model is None or self._model_name != resolved_model:
                logger.info(
                    "loading faster-whisper model=%s compute=%s cpu_threads=%d num_workers=%d",
                    resolved_model,
                    COMPUTE_TYPE,
                    CPU_THREADS,
                    NUM_WORKERS,
                )
                self._model = WhisperModel(
                    resolved_model,
                    device="cpu",
                    compute_type=COMPUTE_TYPE,
                    cpu_threads=CPU_THREADS,
                    num_workers=NUM_WORKERS,
                    local_files_only=Path(resolved_model).exists(),
                )
                self._model_name = resolved_model
            return self._model


def resolve_model_path(model_name: str) -> str:
    if model_name not in {"large-v3", "whisper-large-v3"}:
        return model_name
    hf_home = Path(os.environ.get("HF_HOME", Path.home() / ".cache" / "huggingface"))
    snapshot_root = hf_home / "hub" / "models--Systran--faster-whisper-large-v3" / "snapshots"
    if snapshot_root.exists():
        snapshots = sorted(snapshot_root.iterdir(), key=lambda path: path.stat().st_mtime, reverse=True)
        if snapshots:
            return str(snapshots[0])
    return "large-v3"


class SessionRegistry:
    def __init__(self, model_holder: ModelHolder) -> None:
        self._sessions: dict[str, SessionState] = {}
        self._registry_lock = asyncio.Lock()
        self._model_holder = model_holder

    async def create(self, req: StartSessionRequest) -> SessionState:
        session_id = str(uuid.uuid4())
        model_name = req.model or DEFAULT_MODEL
        state = SessionState(
            id=session_id,
            created_at_monotonic=time.monotonic(),
            model_name=model_name,
            task=req.task,
            lock=asyncio.Lock(),
            closed=False,
            audio=bytearray(),
            chunk_id=1,
            last_partial_at=time.monotonic(),
            last_final_text="",
            source_language=None,
            prev_source="",
            prev_translation="",
        )
        async with self._registry_lock:
            self._sessions[session_id] = state
        return state

    async def get(self, session_id: str) -> SessionState:
        async with self._registry_lock:
            state = self._sessions.get(session_id)
        if state is None:
            raise HTTPException(status_code=404, detail="session not found")
        return state

    async def delete(self, session_id: str) -> bool:
        async with self._registry_lock:
            state = self._sessions.pop(session_id, None)
        if state is None:
            return False
        state.closed = True
        task = state.process_task
        if task is not None and not task.done():
            task.cancel()
        return True

    async def _take_unreturned_update(self, state: SessionState) -> Optional[TranslationUpdate]:
        async with state.lock:
            if state.latest_update and state.update_generation > state.last_returned_generation:
                state.last_returned_generation = state.update_generation
                return state.latest_update
            return None

    def _enqueue_work(self, state: SessionState, work: PendingWork) -> None:
        if work.is_final:
            # A final supersedes any coalesced partial for the same phrase.
            state.pending_partial = None
            state.pending_queue.append(work)
            logger.info(
                "queued final chunk=%d dur=%.1fs (queue=%d)",
                work.chunk_id,
                work.buffer_duration,
                len(state.pending_queue),
            )
        else:
            state.pending_partial = work
        self._schedule_processing(state)

    def _schedule_processing(self, state: SessionState) -> None:
        if state.process_task is not None and not state.process_task.done():
            return
        state.process_task = asyncio.create_task(self._process_pending(state))

    async def _process_pending(self, state: SessionState) -> None:
        try:
            while True:
                async with state.lock:
                    if state.closed:
                        state.process_task = None
                        return
                    work = None
                    if state.pending_queue:
                        work = state.pending_queue.popleft()
                    elif state.pending_partial is not None:
                        work = state.pending_partial
                        state.pending_partial = None
                    if work is None:
                        state.process_task = None
                        return

                update = await self._run_work(state, work)
                if update is not None:
                    async with state.lock:
                        state.latest_update = update
                        state.update_generation += 1
        except asyncio.CancelledError:
            async with state.lock:
                state.process_task = None
            raise
        except Exception:
            logger.exception("background translation failed for session=%s", state.id)
            async with state.lock:
                state.process_task = None

    async def _run_work(self, state: SessionState, work: PendingWork) -> Optional[TranslationUpdate]:
        # Phrase-level VAD already gates audio; inner Whisper VAD can drop words at
        # boundaries when we manage our own phrase windows (especially in LLM mode).
        skip_inner_vad = work.use_llm
        if BACKEND == "mlx":
            text, language, language_prob = await asyncio.to_thread(
                self._transcribe_mlx,
                work.snapshot,
                work.asr_task,
                work.prompt,
                work.pinned,
                skip_inner_vad,
            )
        else:
            model = await self._model_holder.get(work.model_name)
            text, language, language_prob = await asyncio.to_thread(
                self._transcribe,
                model,
                work.snapshot,
                work.asr_task,
                work.prompt,
                work.pinned,
                skip_inner_vad,
            )
        logger.info(
            "chunk=%d final=%s dur=%.1fs lang=%s p=%.2f text=%r",
            work.chunk_id,
            work.is_final,
            work.buffer_duration,
            language,
            language_prob,
            text,
        )
        if not text:
            return None

        async with state.lock:
            if (
                state.source_language is None
                and language
                and language_prob >= LANGUAGE_PIN_MIN_PROB
            ):
                state.source_language = language
            prev_source = state.prev_source
            prev_translation = state.prev_translation

        if work.use_llm and translator is not None:
            source_text = text
            if work.is_final:
                translated = await asyncio.to_thread(
                    translator.translate,
                    source_text,
                    prev_source,
                    prev_translation,
                    use_context=True,
                )
                if not translated.strip():
                    translated = await asyncio.to_thread(
                        translator.translate, source_text, use_context=False
                    )
                translated = translated.strip()
                if translated:
                    async with state.lock:
                        state.prev_source = source_text
                        state.prev_translation = translated
            else:
                translated = await asyncio.to_thread(
                    translator.translate, source_text, use_context=False
                )
                translated = translated.strip()
            logger.info(
                "chunk=%d llm final=%s: %r -> %r",
                work.chunk_id,
                work.is_final,
                source_text,
                translated,
            )
            if not translated:
                return None
            text = translated

        return TranslationUpdate(
            chunk_id=work.chunk_id,
            is_final=work.is_final,
            text=text,
            source_language=language,
        )

    async def ingest_pcm_chunk(
        self,
        state: SessionState,
        pcm_chunk: bytes,
        start_ms: int,
    ) -> Optional[TranslationUpdate]:
        if state.closed:
            raise HTTPException(status_code=410, detail="session closed")
        if not pcm_chunk:
            return await self._take_unreturned_update(state)

        use_llm = TRANSLATION_MODE == "llm" and translator is not None

        import numpy as np

        async with state.lock:
            state.audio.extend(pcm_chunk)
            buf = np.frombuffer(bytes(state.audio), dtype=np.int16).astype(np.float32) / 32768.0
            sample_rate = TARGET_SAMPLE_RATE
            buffer_duration = len(buf) / sample_rate
            now = time.monotonic()

            def tail_rms(seconds: float) -> float:
                samples = int(sample_rate * seconds)
                if samples <= 0 or len(buf) < samples:
                    return 1.0
                return float(np.sqrt(np.mean(buf[-samples:] ** 2)))

            overall_rms = float(np.sqrt(np.mean(buf ** 2))) if len(buf) else 0.0

            standard_cut = buffer_duration > 2.0 and tail_rms(SILENCE_DURATION) < SILENCE_RMS
            soft_limit_cut = buffer_duration > SOFT_LIMIT_SECONDS and tail_rms(0.4) < SILENCE_RMS
            hard_limit_cut = buffer_duration > MAX_PHRASE_SECONDS
            should_finalize = standard_cut or soft_limit_cut or hard_limit_cut

            should_queue = False
            snapshot = None
            chunk_id = state.chunk_id
            prompt = ""
            pinned = state.source_language
            is_final = False

            if should_finalize and buffer_duration > MIN_PHRASE_SECONDS:
                snapshot = buf.copy()
                chunk_id = state.chunk_id
                overlap_bytes = int(TARGET_SAMPLE_RATE * PHRASE_OVERLAP_SECONDS) * 2
                if overlap_bytes > 0 and len(state.audio) >= overlap_bytes:
                    state.audio = bytearray(state.audio[-overlap_bytes:])
                else:
                    state.audio = bytearray()
                state.chunk_id += 1
                state.last_partial_at = now
                is_final = True
                should_queue = overall_rms >= SILENCE_RMS
            elif (now - state.last_partial_at) >= UPDATE_INTERVAL and buffer_duration > MIN_PHRASE_SECONDS:
                snapshot = buf.copy()
                state.last_partial_at = now
                is_final = False
                should_queue = overall_rms >= SILENCE_RMS

            if should_queue and snapshot is not None:
                asr_task = "transcribe" if use_llm else state.task
                self._enqueue_work(
                    state,
                    PendingWork(
                        snapshot=snapshot,
                        chunk_id=chunk_id,
                        is_final=is_final,
                        buffer_duration=buffer_duration,
                        prompt=prompt,
                        pinned=pinned,
                        use_llm=use_llm,
                        asr_task=asr_task,
                        model_name=state.model_name,
                    ),
                )

        return await self._take_unreturned_update(state)

    @staticmethod
    def _transcribe(
        model: WhisperModel,
        audio_f32,
        task: str,
        prompt: str,
        language: Optional[str],
        skip_inner_vad: bool = False,
    ) -> tuple[str, Optional[str], float]:
        segments, info = model.transcribe(
            audio=audio_f32,
            task="translate" if task == "translate" else "transcribe",
            language=language,
            beam_size=5,
            temperature=0.0,
            condition_on_previous_text=False,
            initial_prompt=prompt or None,
            no_speech_threshold=0.6,
            log_prob_threshold=-1.0,
            compression_ratio_threshold=2.4,
            vad_filter=not skip_inner_vad,
            vad_parameters=dict(threshold=0.5, min_silence_duration_ms=300),
        )
        detected_language = language or getattr(info, "language", None)
        language_prob = float(getattr(info, "language_probability", 0.0) or 0.0)
        text = " ".join((seg.text or "").strip() for seg in segments if (seg.text or "").strip()).strip()
        if is_hallucination(text):
            logger.info("filtered hallucination: %r", text[:60])
            return "", detected_language, language_prob
        if prompt and is_prompt_echo(text, prompt):
            logger.info("filtered prompt echo: %r", text[:60])
            return "", detected_language, language_prob
        return text, detected_language, language_prob

    @staticmethod
    def _transcribe_mlx(
        audio_f32,
        task: str,
        prompt: str,
        language: Optional[str],
        skip_inner_vad: bool = False,
    ) -> tuple[str, Optional[str], float]:
        import mlx_whisper

        _ = skip_inner_vad  # mlx-whisper has no VAD filter; phrase VAD is external.
        result = mlx_whisper.transcribe(
            audio_f32,
            path_or_hf_repo=MLX_MODEL,
            task="translate" if task == "translate" else "transcribe",
            language=language,
            temperature=0.0,
            condition_on_previous_text=False,
            initial_prompt=prompt or None,
            no_speech_threshold=0.6,
        )
        text = (result.get("text") or "").strip()
        detected_language = language or result.get("language")
        # MLX doesn't return a language probability; treat a detected language as
        # confident enough to pin (the phrase/silence checks already gate this).
        language_prob = 1.0 if detected_language else 0.0
        if is_hallucination(text):
            logger.info("filtered hallucination: %r", text[:60])
            return "", detected_language, language_prob
        if prompt and is_prompt_echo(text, prompt):
            logger.info("filtered prompt echo: %r", text[:60])
            return "", detected_language, language_prob
        return text, detected_language, language_prob


app = FastAPI(title="Live Translation Service")
model_holder = ModelHolder()


def _build_translator() -> Optional[LlmTranslator]:
    if TRANSLATION_MODE != "llm":
        return None
    try:
        translator = LlmTranslator(LLM_BASE_URL, LLM_API_KEY, LLM_MODEL, LLM_TARGET_LANG)
        logger.info("LLM translation enabled: model=%s url=%s", LLM_MODEL, LLM_BASE_URL)
        return translator
    except Exception as exc:  # noqa: BLE001
        logger.error("failed to init LLM translator (%s); falling back to whisper translate", exc)
        return None


translator = _build_translator()
registry = SessionRegistry(model_holder)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "model": DEFAULT_MODEL,
        "backend": BACKEND,
        "cpuThreads": CPU_THREADS,
        "numWorkers": NUM_WORKERS,
        "computeType": COMPUTE_TYPE,
        "translationMode": TRANSLATION_MODE,
        "llmModel": LLM_MODEL if TRANSLATION_MODE == "llm" else None,
        "llmReady": translator is not None,
    }


@app.on_event("startup")
async def _warmup() -> None:
    """Preload + warm the model so the first real phrase isn't slow."""
    import numpy as np

    silence = np.zeros(TARGET_SAMPLE_RATE, dtype=np.float32)
    try:
        if BACKEND == "mlx":
            await asyncio.to_thread(
                SessionRegistry._transcribe_mlx, silence, "translate", "", None
            )
            logger.info("MLX (GPU) backend warmed: model=%s", MLX_MODEL)
        else:
            model = await model_holder.get(DEFAULT_MODEL)
            await asyncio.to_thread(
                SessionRegistry._transcribe, model, silence, "translate", "", None
            )
            logger.info(
                "faster-whisper (CPU) backend warmed: cpu_threads=%d", CPU_THREADS
            )
    except Exception as exc:  # noqa: BLE001 - warmup is best-effort
        logger.warning("warmup skipped: %s", exc)


@app.post("/v1/live-translation/session", response_model=StartSessionResponse)
async def start_session(req: StartSessionRequest) -> StartSessionResponse:
    model_name = req.model or DEFAULT_MODEL
    if model_name == "whisper-large-v3":
        model_name = "large-v3"
    elif model_name not in {"large-v3"}:
        model_name = "large-v3"
    state = await registry.create(req.model_copy(update={"model": model_name}))
    return StartSessionResponse(sessionId=state.id, model=model_name)


@app.post("/v1/live-translation/session/{session_id}/audio", response_model=TranslationUpdateDto)
async def upload_audio(
    session_id: str,
    request: Request,
    start_ms: int = Query(alias="startMs"),
    end_ms: int = Query(alias="endMs"),
    sample_rate: int = Query(alias="sampleRate"),
    channel_count: int = Query(alias="channelCount"),
    encoding: str = Query(default="pcm_s16le"),
) -> TranslationUpdateDto:
    if sample_rate != TARGET_SAMPLE_RATE or channel_count != 1 or encoding != "pcm_s16le":
        raise HTTPException(status_code=400, detail="expected 16 kHz mono pcm_s16le audio")
    state = await registry.get(session_id)
    pcm_chunk = await request.body()
    update = await registry.ingest_pcm_chunk(state, pcm_chunk, start_ms)
    if update is None:
        return TranslationUpdateDto(
            sessionId=session_id,
            chunkId=state.chunk_id,
            isFinal=False,
            text="",
            sourceLanguage=state.source_language,
        )
    return TranslationUpdateDto(
        sessionId=session_id,
        chunkId=update.chunk_id,
        isFinal=update.is_final,
        text=update.text,
        sourceLanguage=update.source_language,
    )


@app.delete("/v1/live-translation/session/{session_id}")
async def delete_session(session_id: str) -> dict:
    deleted = await registry.delete(session_id)
    return {"deleted": deleted}
