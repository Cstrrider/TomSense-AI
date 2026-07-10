"""TTS provider abstraction.

Providers:
- piper        — local openedai-speech container (CPU, slow but free)
- cf-aura      — Cloudflare Deepgram Aura 2 EN (GPU, fast, 40 voices, paid in CF neurons)
- cf-melotts   — Cloudflare MeloTTS (GPU, multilingual)

All providers return a streaming async iterator of audio bytes plus the
HTTP media type, so /tts can proxy the bytes through without buffering.
"""

import logging
from typing import AsyncIterator, Awaitable, Callable, Optional

import httpx

from .cf import get_client as get_cf_client
from .config import settings


log = logging.getLogger("tomsense.tts")


_MEDIA = {
    "mp3": "audio/mpeg",
    "wav": "audio/wav",
    "opus": "audio/ogg",
    "aac": "audio/aac",
    "flac": "audio/flac",
}

# Voice catalogues for the UI
PIPER_VOICES = ["alloy", "echo", "fable", "onyx", "nova", "shimmer"]
AURA_VOICES = [
    "amalthea", "andromeda", "apollo", "arcas", "aries", "asteria",
    "athena", "atlas", "aurora", "callista", "cora", "cordelia",
    "delia", "draco", "electra", "harmonia", "helena", "hera",
    "hermes", "hyperion", "iris", "janus", "juno", "jupiter",
    "luna", "mars", "minerva", "neptune", "odysseus", "ophelia",
    "orion", "orpheus", "pandora", "phoebe", "pluto", "saturn",
    "thalia", "theia", "vesta", "zeus",
]


class TTSStream:
    """Wraps a streaming TTS response. Yield bytes via aiter(), then the
    caller is responsible for awaiting nothing further — cleanup happens
    in the generator's finally."""
    def __init__(
        self,
        media_type: str,
        gen: AsyncIterator[bytes],
        cleanup: Callable[[], Awaitable[None]],
    ):
        self.media_type = media_type
        self._gen = gen
        self._cleanup = cleanup

    async def aiter(self) -> AsyncIterator[bytes]:
        try:
            async for chunk in self._gen:
                yield chunk
        finally:
            try:
                await self._cleanup()
            except Exception as e:
                log.warning("tts cleanup failed: %s", e)


async def open_tts(
    provider: str, text: str, voice: Optional[str], fmt: str, user_id: str = None,
) -> TTSStream:
    p = provider or "piper"
    from . import providers as _prov
    if _prov.SEP in p:  # `provider::model` → user-configured OpenAI-compatible
        return await _open_custom(p, text, voice, fmt, user_id)
    p = p.lower()
    if p == "cf-aura":
        return await _open_cf_aura(text, voice, fmt)
    if p == "cf-melotts":
        return await _open_cf_melotts(text, voice, fmt)
    return await _open_piper(text, voice, fmt)


# ─── custom OpenAI-compatible provider (/audio/speech) ──────────────────────

def _wav_header(data_len: int, sample_rate: int, channels: int = 1, bits: int = 16) -> bytes:
    """44-byte canonical WAV/PCM header for `data_len` bytes of sample data."""
    import struct
    byte_rate = sample_rate * channels * bits // 8
    block_align = channels * bits // 8
    return (
        b"RIFF" + struct.pack("<I", 36 + data_len) + b"WAVE"
        + b"fmt " + struct.pack("<IHHIIHH", 16, 1, channels, sample_rate, byte_rate, block_align, bits)
        + b"data" + struct.pack("<I", data_len)
    )


async def _open_custom(
    provider_str: str, text: str, voice: Optional[str], fmt: str, user_id: str,
) -> TTSStream:
    from . import providers as _prov
    pid, model = _prov.parse_model_str(provider_str)
    provider = await _prov.resolve_provider(pid, user_id=user_id)
    if not provider:
        raise RuntimeError(f"TTS provider {pid!r} not found")
    base = (provider.get("base_url") or "").rstrip("/")
    if base.endswith("/chat/completions"):
        base = base[: -len("/chat/completions")]
    url = f"{base}/audio/speech"
    headers = {
        "Authorization": f"Bearer {provider.get('api_key') or ''}",
        "Content-Type": "application/json",
    }
    payload = {"model": model, "input": text[:4000], "voice": voice or "alloy",
               "response_format": fmt}
    client = httpx.AsyncClient(timeout=90.0)
    try:
        cm = client.stream("POST", url, headers=headers, json=payload)
        r = await cm.__aenter__()
    except Exception:
        await client.aclose()
        raise
    if not r.is_success:
        raw = await r.aread()
        status, body = r.status_code, raw[:400].decode(errors="replace")
        await cm.__aexit__(None, None, None)
        await client.aclose()
        # Some providers (Gemini TTS via an OpenAI-compat gateway) only emit raw
        # PCM and reject other formats; re-request PCM and wrap it in WAV.
        if status == 400 and "pcm" in body.lower():
            return await _open_custom_pcm(url, headers, model, text, voice)
        raise RuntimeError(f"{model} {status}: {body[:300]}")

    async def gen():
        async for chunk in r.aiter_bytes(8192):
            yield chunk

    async def cleanup():
        try:
            await cm.__aexit__(None, None, None)
        finally:
            await client.aclose()

    return TTSStream(media_for(fmt), gen(), cleanup)


async def _open_custom_pcm(url, headers, model, text, voice) -> TTSStream:
    """Fallback for PCM-only providers: fetch raw PCM (24kHz/16-bit/mono, what
    OpenAI + Gemini TTS emit) and wrap it in a WAV container for the browser."""
    import os
    payload = {"model": model, "input": text[:4000], "voice": voice or "alloy",
               "response_format": "pcm"}
    async with httpx.AsyncClient(timeout=90.0) as client:
        r = await client.post(url, headers=headers, json=payload)
        if not r.is_success:
            raise RuntimeError(f"{model} (pcm) {r.status_code}: {r.text[:300]}")
        pcm = r.content
    sample_rate = int(os.getenv("TTS_PCM_SAMPLE_RATE", "24000"))
    wav = _wav_header(len(pcm), sample_rate) + pcm

    async def gen():
        yield wav

    async def cleanup():
        return

    return TTSStream("audio/wav", gen(), cleanup)


def media_for(fmt: str) -> str:
    return _MEDIA.get(fmt, "audio/mpeg")


# ─── piper ──────────────────────────────────────────────────────────────────

async def _open_piper(text: str, voice: Optional[str], fmt: str) -> TTSStream:
    client = httpx.AsyncClient(timeout=60.0)
    payload = {
        "model": "tts-1",
        "input": text[:4000],
        "voice": voice or "alloy",
        "response_format": fmt,
    }
    try:
        cm = client.stream("POST", f"{settings.piper_url}/v1/audio/speech", json=payload)
        r = await cm.__aenter__()
    except Exception:
        await client.aclose()
        raise
    if not r.is_success:
        raw = await r.aread()
        await cm.__aexit__(None, None, None)
        await client.aclose()
        raise RuntimeError(f"piper {r.status_code}: {raw[:300].decode(errors='replace')}")

    async def gen():
        async for chunk in r.aiter_bytes(8192):
            yield chunk

    async def cleanup():
        try:
            await cm.__aexit__(None, None, None)
        finally:
            await client.aclose()

    return TTSStream(media_for(fmt), gen(), cleanup)


# ─── cf-aura ────────────────────────────────────────────────────────────────

async def _open_cf_aura(text: str, voice: Optional[str], fmt: str) -> TTSStream:
    cli = get_cf_client()
    url = f"{settings.cf_run_url}/@cf/deepgram/aura-2-en"
    payload: dict = {
        "text": text[:4000],
        "speaker": voice or "luna",
    }
    # Map our format to CF Aura's encoding + container combo.
    # Aura rejects `container` when encoding is mp3/aac/flac (those formats
    # are already self-framing); container is only valid for raw PCM/opus.
    if fmt == "mp3":
        payload["encoding"] = "mp3"
    elif fmt == "wav":
        payload["encoding"] = "linear16"
        payload["container"] = "wav"
    elif fmt == "opus":
        payload["encoding"] = "opus"
        payload["container"] = "ogg"
    elif fmt == "aac":
        payload["encoding"] = "aac"
    elif fmt == "flac":
        payload["encoding"] = "flac"
    cm = cli.stream("POST", url, headers=settings.cf_headers, json=payload, timeout=60.0)
    r = await cm.__aenter__()
    if not r.is_success:
        raw = await r.aread()
        await cm.__aexit__(None, None, None)
        raise RuntimeError(f"cf-aura {r.status_code}: {raw[:300].decode(errors='replace')}")

    async def gen():
        async for chunk in r.aiter_bytes(8192):
            yield chunk

    async def cleanup():
        await cm.__aexit__(None, None, None)

    return TTSStream(media_for(fmt), gen(), cleanup)


# ─── cf-melotts ─────────────────────────────────────────────────────────────

async def _open_cf_melotts(text: str, voice: Optional[str], fmt: str) -> TTSStream:
    cli = get_cf_client()
    url = f"{settings.cf_run_url}/@cf/myshell-ai/melotts"
    payload = {"prompt": text[:4000], "lang": (voice or "en")}
    # MeloTTS returns mp3 by default
    cm = cli.stream("POST", url, headers=settings.cf_headers, json=payload, timeout=60.0)
    r = await cm.__aenter__()
    if not r.is_success:
        raw = await r.aread()
        await cm.__aexit__(None, None, None)
        raise RuntimeError(f"cf-melotts {r.status_code}: {raw[:300].decode(errors='replace')}")

    async def gen():
        async for chunk in r.aiter_bytes(8192):
            yield chunk

    async def cleanup():
        await cm.__aexit__(None, None, None)

    # MeloTTS always returns mp3
    return TTSStream("audio/mpeg", gen(), cleanup)
