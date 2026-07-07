"""STT provider abstraction.

Providers:
- whisper       — local faster-whisper-server (CPU, distil-medium.en)
- cf-whisper    — Cloudflare @cf/openai/whisper-large-v3-turbo (GPU)
"""

import base64
import logging

import httpx

from .cf import get_client as get_cf_client
from .config import settings


log = logging.getLogger("tomsense.stt")


class STTInputError(RuntimeError):
    """The STT provider rejected the audio as invalid input (4xx) — distinct
    from the provider being unreachable or erroring (5xx / connection)."""


def _stt_error(label: str, status: int, text: str) -> RuntimeError:
    msg = f"{label} {status}: {text[:300]}"
    return STTInputError(msg) if 400 <= status < 500 else RuntimeError(msg)


async def transcribe(
    provider: str,
    audio_bytes: bytes,
    filename: str,
    content_type: str,
) -> str:
    p = (provider or "whisper").lower()
    if p == "cf-whisper":
        return await _cf_whisper(audio_bytes)
    return await _whisper_local(audio_bytes, filename, content_type)


async def _whisper_local(audio_bytes: bytes, filename: str, content_type: str) -> str:
    files = {"file": (filename, audio_bytes, content_type)}
    data = {"language": "en", "response_format": "json"}
    async with httpx.AsyncClient(timeout=60.0) as client:
        r = await client.post(
            f"{settings.whisper_url}/v1/audio/transcriptions",
            files=files,
            data=data,
        )
    if not r.is_success:
        raise _stt_error("whisper", r.status_code, r.text)
    out = r.json()
    return (out.get("text") or "").strip()


async def _cf_whisper(audio_bytes: bytes) -> str:
    cli = get_cf_client()
    url = f"{settings.cf_run_url}/@cf/openai/whisper-large-v3-turbo"
    payload = {
        "audio": base64.b64encode(audio_bytes).decode("ascii"),
        "task": "transcribe",
        "language": "en",
    }
    r = await cli.post(url, headers=settings.cf_headers, json=payload, timeout=90.0)
    if not r.is_success:
        raise _stt_error("cf-whisper", r.status_code, r.text)
    data = r.json()
    result = data.get("result") or {}
    # CF returns either {text: "..."} or {transcription_info: ..., text: "..."}
    return (result.get("text") or result.get("transcription") or "").strip()
