"""Detect artifacts in assistant content. Currently extracts:
  - generated image URLs (from generate_image tool)
  - fenced code blocks (≥ 12 lines) with optional language tag

Detection is best-effort and intentionally conservative: too many false
positives is worse than too few. We run this after each assistant message is
persisted; results land in the `artifacts` table.
"""

import re
from typing import Optional

# Image markdown emitted by the generate_image tool:
#   ![Generated Image](/generated/<hex>.png)
_GEN_IMG_RE = re.compile(
    r"!\[Generated Image\]\((/generated/[A-Za-z0-9._-]+)\)"
)

# Fenced code blocks: ```lang\n...\n```
_CODE_FENCE_RE = re.compile(
    r"```([A-Za-z0-9_+\-]*)\s*\n(.*?)\n```",
    re.DOTALL,
)

MIN_CODE_LINES = 12
MAX_CODE_CHARS = 30_000


def _title_from_code(language: Optional[str], body: str) -> str:
    lang = (language or "code").lower() or "code"
    first = body.lstrip().split("\n", 1)[0].strip()
    # Pluck a meaningful first line — function signature, class name, etc.
    snippet = first[:60]
    return f"{lang}: {snippet}" if snippet else lang


def detect_artifacts(content: str) -> list[dict]:
    if not content:
        return []
    artifacts: list[dict] = []
    seen_urls: set[str] = set()

    for m in _GEN_IMG_RE.finditer(content):
        url = m.group(1)
        if url in seen_urls:
            continue
        seen_urls.add(url)
        artifacts.append({
            "kind": "image",
            "title": url.rsplit("/", 1)[-1],
            "url": url,
            "content": None,
            "language": None,
        })

    for m in _CODE_FENCE_RE.finditer(content):
        lang = (m.group(1) or "").strip() or None
        body = m.group(2)
        if body.count("\n") + 1 < MIN_CODE_LINES:
            continue
        if len(body) > MAX_CODE_CHARS:
            body = body[:MAX_CODE_CHARS] + "\n# [truncated]"
        artifacts.append({
            "kind": "code",
            "title": _title_from_code(lang, body),
            "url": None,
            "content": body,
            "language": lang,
        })

    return artifacts
