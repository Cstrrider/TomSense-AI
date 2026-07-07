"""Tool definitions + async dispatchers.

Every chat-mode tool has:
- OpenAI-shaped JSON schema in TOOL_SPECS (sent to CF in `tools=`)
- A `_tool_xxx` async function that takes the parsed args dict
- A name → callable mapping in DISPATCH

`consult_coder` and `deep_research` recurse into a sync CF call themselves
(not back into the agentic loop), so they're a leaf operation from the
chat model's perspective.
"""

import asyncio
import base64
import json
import os
import re
import time
import uuid
from typing import Any, Optional

from . import netguard, rag
from .cf import get_client, strip_template_tokens
from .config import settings
from .providers import dispatch_chat_complete, parse_model_str
from .tools_code import CODE_DISPATCH, CODE_TOOL_SPECS


# ─────────────────────────────────────────────────────────────────────────────
# Tool schemas — same shape we proved out in the existing pipeline
# ─────────────────────────────────────────────────────────────────────────────

TOOL_SPECS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "Single quick web search for one fact, date, price, or score.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "3-8 word query."}
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "fetch_page",
            "description": "Fetch and read a web page in full. Use after web_search when snippets are too brief.",
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {"type": "string", "description": "Full URL."}
                },
                "required": ["url"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "generate_image",
            "description": (
                "Generate a brand-new image from a text description. "
                "Use ONLY when the user wants a new image created from scratch — "
                "no attached image to modify."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "prompt": {"type": "string",  "description": "Detailed visual description."},
                    "hd":     {"type": "boolean", "description": "true only if user asks for HD/4K/best quality."},
                },
                "required": ["prompt"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "edit_image",
            "description": (
                "Modify, restyle, combine, or remix one or more user-attached images. "
                "Use ONLY when the user has attached an image AND wants it changed "
                "(edited, restyled, merged with another, etc.). All attached images "
                "are forwarded to the editing model as references."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "prompt": {"type": "string",  "description": "What to change / how to restyle / how to combine."},
                    "hd":     {"type": "boolean", "description": "true only if user asks for HD/4K/best quality."},
                },
                "required": ["prompt"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "code_interpreter",
            "description": (
                "Run Python in a Jupyter sandbox (numpy/pandas/scipy/matplotlib/sklearn/sympy). "
                "Use for data analysis, statistics, simulations, regex testing. print() to surface results."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "code": {"type": "string", "description": "Self-contained Python; use print() to surface results."}
                },
                "required": ["code"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "consult_coder",
            "description": (
                "Delegate hard coding tasks to GPT-OSS 120B. Use for full programs, "
                "real algorithms, large refactors. Skip for snippets / syntax / concepts."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "task": {"type": "string", "description": "Self-contained task: language, constraints, expected behavior."}
                },
                "required": ["task"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "deep_research",
            "description": (
                "Multi-source research synthesis (GPT-OSS 120B). Use for comparisons, "
                "'tell me about X', 'how has X changed', historical surveys, deep "
                "analysis. Returns a structured report — present it verbatim."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Research question as a full sentence with any constraints."}
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_docs",
            "description": (
                "Semantic search over the user's previously uploaded text and PDF "
                "documents. Call this whenever the user references 'the doc', 'that "
                "PDF', 'the file I uploaded', or asks a question whose answer is "
                "likely in their personal documents. Returns top-k chunks with "
                "filenames and similarity scores."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Question or topic to retrieve relevant chunks for."},
                    "k": {"type": "integer", "description": "Number of chunks to retrieve (1-10, default 5)."},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "remember",
            "description": (
                "Save a short fact about the user for future chats. Use when the "
                "user says 'remember that I…', 'note that…', or states a durable "
                "preference (location, allergies, projects, tools, names). Keep "
                "each fact under 200 chars and atomic — split compound facts."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "fact": {"type": "string", "description": "A single, concise statement about the user."},
                },
                "required": ["fact"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "forget",
            "description": (
                "Delete memories matching a substring. Use when the user says "
                "'forget that…' or 'I no longer…'. Substring matching is case-insensitive."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Substring of the memory to remove."},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_artifact",
            "description": (
                "Replace the content of a code artifact in this chat (canvas-"
                "style iterate-in-place). Use when the user asks you to modify "
                "code/HTML you produced earlier INSTEAD of pasting a whole new "
                "block. Omit artifact_id to update the most recent code "
                "artifact. Provide the COMPLETE new content, not a diff."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "content": {"type": "string", "description": "The complete new artifact content."},
                    "artifact_id": {"type": "integer", "description": "Artifact to update (optional — defaults to the chat's latest code artifact)."},
                    "title": {"type": "string", "description": "Optional new title."},
                },
                "required": ["content"],
            },
        },
    },
    # ── Client-fulfilled tools ────────────────────────────────────────────
    # These run on the user's DEVICE, not the server. chat.py intercepts them
    # (see clienttools.CLIENT_TOOL_NAMES) and bounces a request out over SSE;
    # there is deliberately no DISPATCH entry for them.
    {
        "type": "function",
        "function": {
            "name": "get_location",
            "description": (
                "Get the user's current geographic location (latitude/longitude "
                "and, when available, a place name). Call this whenever an answer "
                "depends on where the user is: weather, nearby places, 'near me', "
                "travel time, local time zone, 'how far is X from here'. No args."
            ),
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_calendar",
            "description": (
                "Read the user's upcoming calendar events. Call this when the "
                "user asks about their schedule, what's next, whether they're "
                "free, or wants to plan around existing commitments."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "description": "How many days ahead to look (default 7, max 30).",
                    }
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_health",
            "description": (
                "Read a summary of the user's recent health data from Android "
                "Health Connect — activity (steps, distance, calories, floors, "
                "workouts), vitals (heart rate, resting HR, HRV, blood pressure, "
                "blood glucose, blood oxygen, respiratory rate, body temp, VO2 "
                "max), body measurements (weight, body fat, height, BMR), sleep, "
                "nutrition (calories eaten, hydration), and cycle tracking. Call "
                "this for any question about the user's fitness, activity, sleep, "
                "weight, heart, or other health metrics."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "description": "How many days back to summarize (default 1, max 14).",
                    }
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_calendar_event",
            "description": (
                "Open a pre-filled new-event editor in the user's calendar so "
                "they can review and save it. Call this whenever the user "
                "wants to schedule something — a meeting, appointment, plan."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "Event title."},
                    "start": {
                        "type": "string",
                        "description": "Start time in the user's local zone, ISO 8601 with no timezone suffix (e.g. 2026-05-22T14:00:00).",
                    },
                    "end": {
                        "type": "string",
                        "description": "End time, same format. Optional — defaults to one hour after start.",
                    },
                    "location": {"type": "string", "description": "Optional location."},
                    "notes": {"type": "string", "description": "Optional description / notes."},
                },
                "required": ["title", "start"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_reminder",
            "description": (
                "Schedule a reminder notification on the user's device for a "
                "'remind me to …' request. Give EITHER `time` (a clock time) "
                "OR `in_minutes` (for 'in N minutes/hours' phrasing)."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "message": {"type": "string", "description": "What to remind the user about."},
                    "time": {
                        "type": "string",
                        "description": "Absolute time, user's local zone, ISO 8601 with no timezone suffix.",
                    },
                    "in_minutes": {
                        "type": "integer",
                        "description": "Minutes from now — use for relative 'in N minutes/hours' requests.",
                    },
                },
                "required": ["message"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "start_timer",
            "description": (
                "Start a countdown timer in the device clock app. Call this "
                "for 'set a timer for N minutes' requests."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "minutes": {"type": "integer", "description": "Timer length in minutes."},
                    "label": {"type": "string", "description": "Optional timer label."},
                },
                "required": ["minutes"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_alarm",
            "description": (
                "Set an alarm in the device clock app. Call this for 'wake me "
                "at …' / 'set an alarm for …' requests."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "time": {
                        "type": "string",
                        "description": "Alarm time, user's local zone, ISO 8601 with no timezone suffix. Only the hour and minute are used.",
                    },
                    "label": {"type": "string", "description": "Optional alarm label."},
                },
                "required": ["time"],
            },
        },
    },
    # ── Android device-action client tools ──────────────────────────────────
    {
        "type": "function",
        "function": {
            "name": "launch_app",
            "description": (
                "Open an installed app on the user's Android device by its "
                "common name. Call this when the user says 'open', 'launch', "
                "or 'start' followed by an app name: 'open Spotify', "
                "'launch Gmail', 'start YouTube'."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "app_name": {
                        "type": "string",
                        "description": "App name as the user would say it (e.g. 'Spotify', 'Gmail', 'YouTube', 'Chrome').",
                    },
                },
                "required": ["app_name"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "make_call",
            "description": (
                "Open the phone dialer pre-filled with a number so the user "
                "can place a call. The user must tap the call button themselves "
                "— nothing is dialled automatically. Call this for 'call …' or "
                "'dial …' requests. Use get_contacts first if the user gave a "
                "name instead of a number."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "phone_number": {
                        "type": "string",
                        "description": "Phone number to pre-fill (digits, spaces, +, -, () all accepted).",
                    },
                },
                "required": ["phone_number"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "send_sms",
            "description": (
                "Open the SMS/messaging app pre-filled with a recipient and "
                "message text so the user can review and send it. Call this "
                "when the user wants to text or message someone. Use "
                "get_contacts first if a name was given instead of a number."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "phone_number": {
                        "type": "string",
                        "description": "Recipient phone number.",
                    },
                    "message": {
                        "type": "string",
                        "description": "Message text to pre-fill in the composer. Omit to open a blank composer.",
                    },
                },
                "required": ["phone_number"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "open_maps",
            "description": (
                "Open the maps or navigation app pointed at a destination. "
                "Call this when the user wants directions, to navigate "
                "somewhere, or to see a location on the map."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "destination": {
                        "type": "string",
                        "description": "Address, place name, or 'lat,lon' coordinates.",
                    },
                    "mode": {
                        "type": "string",
                        "enum": ["driving", "walking", "transit", "cycling"],
                        "description": "Navigation mode. Omit to let the maps app choose.",
                    },
                },
                "required": ["destination"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "open_url",
            "description": (
                "Open a web URL or app deep link on the device. Use for "
                "websites (https://...) AND for app-specific URI schemes: "
                "'spotify:search:queen' opens Spotify searching for Queen, "
                "'spotify:search:bohemian+rhapsody' searches for that song. "
                "Always prefer the Spotify URI over launching the app separately "
                "when the user asks to play or find music."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {
                        "type": "string",
                        "description": "Full URL including https://.",
                    },
                },
                "required": ["url"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "share_text",
            "description": (
                "Show the Android share sheet so the user can send text to "
                "any app — messages, email, notes, social media, etc. Use "
                "when the user asks to 'share', 'send to', or 'copy to' "
                "a piece of text or content from the conversation."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "text": {
                        "type": "string",
                        "description": "The text to share.",
                    },
                    "title": {
                        "type": "string",
                        "description": "Optional subject / title for apps that use one (e.g. email subject).",
                    },
                },
                "required": ["text"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_contacts",
            "description": (
                "Search the user's contacts by name and return matching "
                "names and phone numbers. Call this before make_call or "
                "send_sms when the user gives a person's name instead of "
                "a number. Returns up to 20 matches."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Name to search for (partial matches are returned).",
                    },
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "open_settings",
            "description": (
                "Open a specific Android settings panel. Use when the user "
                "asks to change a device setting, toggle WiFi or Bluetooth, "
                "check battery, adjust sound, etc."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "panel": {
                        "type": "string",
                        "enum": [
                            "wifi", "bluetooth", "location", "notifications",
                            "sound", "display", "battery", "apps", "storage",
                            "security", "accessibility",
                        ],
                        "description": "Which settings screen to open. Omit for the main Settings app.",
                    },
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_volume",
            "description": (
                "Change the device media volume. Give EITHER `level` (an "
                "absolute percentage: 'set volume to 50%') OR `action` "
                "('turn it up/down', 'mute', 'unmute'). Changes take effect "
                "immediately — no confirmation screen."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "level": {
                        "type": "integer",
                        "description": "Absolute volume 0-100.",
                    },
                    "action": {
                        "type": "string",
                        "enum": ["up", "down", "mute", "unmute"],
                        "description": "Relative change — use when no exact level was given.",
                    },
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_brightness",
            "description": (
                "Set the screen brightness to an absolute percentage. Call "
                "this for 'dim the screen', 'brightness to 80%', 'make it "
                "brighter/darker' (pick a sensible new level)."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "level": {
                        "type": "integer",
                        "description": "Brightness 0-100 (0 = minimum, 100 = maximum).",
                    },
                },
                "required": ["level"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "media_control",
            "description": (
                "Control whatever media is currently playing on the device "
                "(any app — Spotify, YouTube, podcasts). Call this for "
                "'pause', 'resume the music', 'skip this song', 'previous "
                "track', 'stop playback'."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["play", "pause", "toggle", "next", "previous", "stop"],
                        "description": "Playback command. Use 'toggle' when unsure whether media is playing.",
                    },
                },
                "required": ["action"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_device_status",
            "description": (
                "Read the device's current status: battery percentage and "
                "charging state, network connection type, free storage, "
                "media volume, and screen brightness. Call this for 'how's "
                "my battery', 'am I on WiFi', 'how much space is left'. No args."
            ),
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "play_music",
            "description": (
                "Start music playing on the device — playback begins "
                "immediately, no extra tap needed. Works with a song, artist, "
                "album, or playlist name; plays via Spotify by default. "
                "ALWAYS prefer this over open_url/launch_app when the user "
                "asks to play music: 'play Bohemian Rhapsody', 'put on my "
                "workout playlist', 'play some Queen'."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "What to play — song title, artist, album, or playlist name.",
                    },
                    "kind": {
                        "type": "string",
                        "enum": ["song", "artist", "album", "playlist", "any"],
                        "description": "What the query names. Use 'playlist' for the user's playlists. Default 'any'.",
                    },
                    "app": {
                        "type": "string",
                        "enum": ["spotify", "youtube music", "any"],
                        "description": "Music app to use. Default 'spotify'.",
                    },
                },
                "required": ["query"],
            },
        },
    },
]

# Code-mode tools are offered only to code chats (see chat.py), but every
# tool the model can ever call must be allow-listed for dispatch.
ALLOWED_TOOL_NAMES = frozenset(
    t["function"]["name"] for t in (TOOL_SPECS + CODE_TOOL_SPECS)
)


# ─────────────────────────────────────────────────────────────────────────────
# web_search
# ─────────────────────────────────────────────────────────────────────────────

async def tool_web_search(args: dict) -> str:
    query = (args.get("query") or "").strip()
    if not query:
        return "Error: empty query"
    print(f"[tool:search] {query[:100]}")
    try:
        r = await get_client().get(
            f"{settings.searxng_url}/search",
            params={
                "q": query,
                "format": "json",
                "engines": "google,bing,duckduckgo",
                "safesearch": 0,
            },
            timeout=15.0,
        )
        r.raise_for_status()
        data = r.json()
    except Exception as e:
        return f"Search error: {e}"

    results = (data.get("results") or [])[: settings.searxng_max_results]
    lines = [f"Search results for: {query}\n"]
    for i, item in enumerate(results, 1):
        title = (item.get("title") or "").strip()
        url = item.get("url") or ""
        snippet = (item.get("content") or "").strip()[:300]
        lines.append(f"[{i}] {title}")
        lines.append(f"URL: {url}")
        if snippet:
            lines.append(snippet)
        lines.append("")
    if len(lines) <= 1:
        return "No results found."
    lines.append(
        "(When your answer draws on these results, cite them inline with "
        "their bracketed numbers like [1] or [2][3], and end with a "
        "'Sources' list of ONLY the numbered URLs you actually cited.)"
    )
    return "\n".join(lines)


# ─────────────────────────────────────────────────────────────────────────────
# fetch_page
# ─────────────────────────────────────────────────────────────────────────────

_HTML_BLOCK_RE = re.compile(
    r"<(script|style|nav|header|footer)[^>]*>.*?</\1>",
    re.DOTALL | re.IGNORECASE,
)
_HTML_BLOCK_OPEN_RE = re.compile(r"<(p|div|br|li|h[1-6]|tr)[^>]*>", re.IGNORECASE)


_YOUTUBE_RE = re.compile(
    r"(?:youtube\.com/watch\?.*?v=|youtu\.be/|youtube\.com/shorts/)([A-Za-z0-9_-]{11})"
)


def _youtube_transcript_sync(video_id: str) -> str:
    """Fetch captions for a YouTube video (auto-generated included).
    Best-effort — datacenter IPs occasionally get consent-walled."""
    from youtube_transcript_api import YouTubeTranscriptApi
    segments = YouTubeTranscriptApi().fetch(video_id, languages=["en", "en-US", "sv"])
    return " ".join(s.text.strip() for s in segments if s.text.strip())


async def tool_fetch_page(args: dict) -> str:
    url = (args.get("url") or "").strip()
    if not url:
        return "Error: empty url"
    print(f"[tool:fetch] {url[:100]}")
    # YouTube links: the page body is useless JS — fetch the transcript instead.
    yt = _YOUTUBE_RE.search(url)
    if yt:
        loop = asyncio.get_running_loop()
        try:
            transcript = await loop.run_in_executor(None, _youtube_transcript_sync, yt.group(1))
            if len(transcript) > settings.max_page_chars * 3:
                transcript = transcript[: settings.max_page_chars * 3] + "\n\n[truncated]"
            return f"Transcript of YouTube video {yt.group(1)}:\n\n{transcript}"
        except Exception as e:
            return f"Could not fetch YouTube transcript ({e}). The video may have no captions."
    try:
        # SSRF guard: the model picks this URL, and the backend can reach
        # every service on the docker network — refuse private/internal
        # addresses. Redirects are followed manually so each hop gets the
        # same check (a public page could 302 to an internal service).
        for _hop in range(5):
            if not settings.fetch_allow_private:
                await netguard.assert_public_url(url)
            r = await get_client().get(
                url,
                follow_redirects=False,
                headers={"User-Agent": "Mozilla/5.0"},
                timeout=15.0,
            )
            if r.is_redirect and r.headers.get("location"):
                url = str(r.next_request.url) if r.next_request else r.headers["location"]
                continue
            break
        r.raise_for_status()
        if "text/html" not in r.headers.get("content-type", ""):
            return f"Not HTML content at {url}"
        html = r.text
    except netguard.PrivateAddressError as e:
        return f"Fetch refused (private/internal address): {e}"
    except Exception as e:
        return f"Fetch failed: {e}"

    html = _HTML_BLOCK_RE.sub(" ", html)
    html = _HTML_BLOCK_OPEN_RE.sub("\n", html)
    text = re.sub(r"<[^>]+>", "", html)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    if len(text) > settings.max_page_chars:
        text = text[: settings.max_page_chars] + "\n\n[truncated]"
    return f"Content from {url}:\n\n{text}"


# ─────────────────────────────────────────────────────────────────────────────
# generate_image (Flux on CF)
# ─────────────────────────────────────────────────────────────────────────────

# Where we save generated images and serve them from
GENERATED_DIR = "/data/generated_images"


def _ensure_dir() -> None:
    os.makedirs(GENERATED_DIR, exist_ok=True)


def _all_image_uploads(context: dict) -> list[dict]:
    """All image uploads from the request context, in original order."""
    uploads = (context or {}).get("uploads") or []
    return [u for u in uploads if u.get("kind") == "image" and u.get("storage_path")]


# Flux 2 Klein on CF accepts input_image_0 for sure; the suffix _N pattern
# strongly suggests _1, _2, … are also accepted as reference images. Cap at
# this many in edit_mode to avoid surprising payload sizes.
FLUX_MAX_INPUT_IMAGES = 4


def _flux_prepare_image(raw: bytes) -> tuple[bytes, int, int]:
    """Resize an image for Flux 2 Klein img2img: longest edge ≤ 1024, both
    dimensions snapped to a multiple of 64. Returns (png_bytes, w, h)."""
    from PIL import Image
    import io as _io
    img = Image.open(_io.BytesIO(raw))
    # Drop alpha — Flux input expects RGB
    if img.mode != "RGB":
        img = img.convert("RGB")
    w, h = img.size
    scale = min(1024 / w, 1024 / h, 1.0)
    out_w = max(64, (int(w * scale) // 64) * 64 or 64)
    out_h = max(64, (int(h * scale) // 64) * 64 or 64)
    if (out_w, out_h) != img.size:
        img = img.resize((out_w, out_h), Image.LANCZOS)
    buf = _io.BytesIO()
    img.save(buf, format="PNG", optimize=False)
    return buf.getvalue(), out_w, out_h


def _save_image_bytes(b: bytes, ext: str = "png") -> str:
    _ensure_dir()
    filename = f"{uuid.uuid4().hex}.{ext}"
    with open(os.path.join(GENERATED_DIR, filename), "wb") as f:
        f.write(b)
    return f"/generated/{filename}"


def _save_output_file(b: bytes, orig_name: str) -> str:
    """Persist a code-interpreter output file under /generated (statically
    served) with a collision-proof but recognizable name."""
    _ensure_dir()
    safe = re.sub(r"[^\w.\-]", "_", orig_name)[-80:] or "file"
    filename = f"{uuid.uuid4().hex[:8]}-{safe}"
    with open(os.path.join(GENERATED_DIR, filename), "wb") as f:
        f.write(b)
    return f"/generated/{filename}"


def _cf_model_steps_override(context: dict, model: str) -> Optional[int]:
    """Look up a user-configured `steps` value for `model` in cf_models.
    Returns None when no override is set or when cf_models isn't populated."""
    cf_models = (context or {}).get("cf_models") or []
    for entry in cf_models:
        if isinstance(entry, dict) and entry.get("id") == model:
            s = entry.get("steps")
            if isinstance(s, int) and 1 <= s <= 50:
                return s
    return None


async def _call_flux(
    *, model: str, prompt: str, hd: bool,
    edit_srcs: Optional[list[dict]] = None,
    steps_override: Optional[int] = None,
) -> tuple[Optional[bytes], Optional[str]]:
    """Common Flux 2 (klein / dev) path. Returns (png_bytes, error_str).
    multipart/form-data. For img2img we pass input_image_0..N and snap
    output dims to the first source image's prepared dimensions.

    Step-count strategy:
    - Klein is distilled — 4-8 steps is enough. Billing is per-MP not
      per-step so step changes only affect latency, not cost.
    - Dev is the full non-distilled model — 25 steps is the CF-recommended
      value. Billing IS per-step ($0.00041/tile/step), so step count
      directly drives cost. Without an explicit `steps` value Dev defaults
      to ~50 → ~$0.08/img.
    NOTE the form field is `steps`, NOT `num_steps`. Flux 1 used the latter
    but Flux 2 uses the bare `steps` name (per CF docs)."""
    is_dev = "flux-2-dev" in model.lower()
    form: dict[str, str] = {"prompt": prompt}
    files: Optional[dict] = None
    if edit_srcs:
        files = {}
        first_dims: Optional[tuple[int, int]] = None
        try:
            for i, src in enumerate(edit_srcs):
                with open(src["storage_path"], "rb") as f:
                    raw = f.read()
                resized, out_w, out_h = _flux_prepare_image(raw)
                if first_dims is None:
                    first_dims = (out_w, out_h)
                files[f"input_image_{i}"] = ("image.png", resized, "image/png")
            assert first_dims is not None
            form["width"] = str(first_dims[0])
            form["height"] = str(first_dims[1])
        except Exception as e:
            return None, f"Could not read source image(s): {e}"
    # `steps` works for both Klein (billed per MP) and Dev (billed per step).
    # Pin them explicitly to avoid Dev's expensive 50-step default. A user
    # override from the CF model editor takes precedence over the default.
    if steps_override is not None:
        steps_val = steps_override
    elif is_dev:
        steps_val = 25
    else:
        steps_val = 8 if hd else 4
    form["steps"] = str(steps_val)
    try:
        r = await get_client().post(
            f"{settings.cf_run_url}/{model}",
            headers={"Authorization": f"Bearer {settings.cf_api_token}"},
            data=form,
            files=files,
            timeout=180.0,
        )
        if not r.is_success:
            return None, f"CF {r.status_code}: {r.text[:300]}"
        data = r.json()
    except Exception as e:
        return None, f"Network error: {e}"
    b64 = (data.get("result") or {}).get("image") or data.get("image")
    if not b64:
        return None, "no image data in response"
    return base64.b64decode(b64), None


async def _post_json(url: str, payload: dict, timeout: float = 180.0,
                     headers: Optional[dict] = None) -> tuple[Optional[dict], Optional[bytes], Optional[str]]:
    """POST JSON, return (json_data, raw_bytes, error). raw_bytes is set when
    the response is an image/* content-type; json_data when it's JSON.
    `headers` overrides the default Workers AI auth — pass the gateway headers
    here for gateway-routed calls."""
    hdrs = headers if headers is not None else {
        "Authorization": f"Bearer {settings.cf_api_token}",
        "Content-Type": "application/json",
    }
    try:
        r = await get_client().post(url, headers=hdrs, json=payload, timeout=timeout)
        if not r.is_success:
            return None, None, f"CF {r.status_code}: {r.text[:300]}"
        ct = r.headers.get("content-type", "")
        if ct.startswith("image/"):
            return None, r.content, None
        return r.json(), None, None
    except Exception as e:
        return None, None, f"Network error: {e}"


async def _fetch_bytes(url: str, timeout: float = 60.0) -> tuple[Optional[bytes], Optional[str]]:
    """GET a URL and return raw bytes — used when a passthrough model returns
    {image: "<URL>"} rather than inline base64."""
    try:
        r = await get_client().get(url, timeout=timeout)
        if not r.is_success:
            return None, f"image fetch {r.status_code}"
        return r.content, None
    except Exception as e:
        return None, f"image fetch network error: {e}"


async def _resolve_image_field(data: dict) -> tuple[Optional[bytes], Optional[str]]:
    """Pull image bytes out of any of CF / OpenAI / Google response shapes.
    Handles both base64-inline AND URL-reference shapes (passthrough partner
    models like Imagen 4 / Nano Banana 2 return a URL string)."""
    if not isinstance(data, dict):
        return None, "non-dict response"
    image_field = (
        (data.get("result") or {}).get("image")
        or data.get("image")
        or data.get("b64_json")
    )
    # OpenAI-shape nested array fallback
    if not image_field:
        arr = data.get("data") or (data.get("result") or {}).get("data")
        if isinstance(arr, list) and arr and isinstance(arr[0], dict):
            image_field = arr[0].get("b64_json") or arr[0].get("url") or arr[0].get("image")
    if not image_field or not isinstance(image_field, str):
        return None, f"no image field in response: {str(data)[:200]}"
    # Three flavors: data: URI, http URL, or raw base64
    if image_field.startswith("data:"):
        try:
            b64 = image_field.split(",", 1)[1]
            return base64.b64decode(b64), None
        except Exception as e:
            return None, f"bad data URI: {e}"
    if image_field.startswith("http://") or image_field.startswith("https://"):
        return await _fetch_bytes(image_field)
    # Assume raw base64
    try:
        return base64.b64decode(image_field), None
    except Exception as e:
        return None, f"bad base64: {e}"


async def _call_gateway_workers_ai(model: str, payload: dict) -> tuple[Optional[bytes], Optional[str]]:
    """Call a partner image model through AI Gateway's Unified Billing path.
    URL: {gateway}/workers-ai/run/{model}. Body shape is the model's own
    native input (no `model` field — that's in the URL). Returns image bytes
    or an error string. Surfaces CF's `402 Insufficient balance` verbatim
    with a helpful hint."""
    url = f"{settings.cf_gateway_url}/workers-ai/run/{model}"
    data, img_bytes, err = await _post_json(
        url, payload, timeout=240.0, headers=settings.cf_gateway_headers,
    )
    if err:
        # Friendlier wording when CF flags an empty wallet.
        if "Insufficient balance" in err:
            return None, (
                f"{err}\n\nAdd funds at dash.cloudflare.com → AI → AI Gateway → "
                "default → Settings → Unified Billing, or set up BYOK with your "
                "own provider key for this model."
            )
        return None, err
    if img_bytes:
        return img_bytes, None
    if data is None:
        return None, "empty response"
    return await _resolve_image_field(data)


def _read_src(src: dict) -> Optional[bytes]:
    try:
        with open(src["storage_path"], "rb") as f:
            return f.read()
    except Exception:
        return None


async def _call_flux_dev(
    *, model: str, prompt: str, hd: bool,
    edit_src: Optional[dict] = None,
    steps_override: Optional[int] = None,
) -> tuple[Optional[bytes], Optional[str]]:
    """Flux 2 Dev uses the same multipart/form-data shape as Klein (per CF
    docs, not the JSON shape I had earlier). Reuse the Klein helper — same
    `prompt`, `steps`, `width`, `height` form fields and same
    `input_image_N` slots for multi-reference img2img. ~$0.04/img at 1024²
    with default 25 steps."""
    srcs = [edit_src] if edit_src else None
    return await _call_flux(
        model=model, prompt=prompt, hd=hd, edit_srcs=srcs,
        steps_override=steps_override,
    )


async def _call_sd15_img2img(
    *, model: str, prompt: str, src: Optional[dict] = None,
    strength: float = 0.75,
) -> tuple[Optional[bytes], Optional[str]]:
    """SD v1.5 img2img — `@cf/runwayml/stable-diffusion-v1-5-img2img`.
    Beta, $0.00/step (free). JSON shape with `image` (base64 string),
    `num_steps` (max 20), `strength` (0-1), `guidance`. Optional src — the
    model also accepts txt2img-style calls with no input image."""
    payload: dict = {
        "prompt": prompt,
        "num_steps": 20,
        "strength": float(strength),
        "guidance": 7.5,
    }
    if src is not None:
        raw = _read_src(src)
        if raw is None:
            return None, "Could not read source image"
        payload["image"] = base64.b64encode(raw).decode()
    data, img_bytes, err = await _post_json(f"{settings.cf_run_url}/{model}", payload)
    if err:
        return None, err
    if img_bytes:
        return img_bytes, None
    return await _resolve_image_field(data or {})


def _encode_srcs(srcs: list[dict]) -> list[str]:
    out: list[str] = []
    for s in srcs:
        raw = _read_src(s)
        if raw is not None:
            out.append(base64.b64encode(raw).decode())
    return out


async def _call_imagen(
    *, model: str, prompt: str, aspect_ratio: str = "1:1",
) -> tuple[Optional[bytes], Optional[str]]:
    """Google Imagen 4 via AI Gateway workers-ai run. Text-to-image only."""
    return await _call_gateway_workers_ai(model, {
        "prompt": prompt,
        "aspect_ratio": aspect_ratio,
        "person_generation": "allow_adult",
    })


async def _call_nano_banana(
    *, model: str, prompt: str, hd: bool,
    edit_srcs: Optional[list[dict]] = None,
) -> tuple[Optional[bytes], Optional[str]]:
    """Google Nano Banana 2 via AI Gateway workers-ai run. Supports
    multi-reference editing (max 3 refs per docs)."""
    payload: dict = {
        "prompt": prompt,
        "resolution": "2K" if hd else "1K",
        "output_format": "png",
        "aspect_ratio": "match_input_image" if edit_srcs else "1:1",
    }
    if edit_srcs:
        imgs = _encode_srcs(edit_srcs[:3])
        if imgs:
            payload["image_input"] = imgs
    return await _call_gateway_workers_ai(model, payload)


async def _call_gpt_image(
    *, model: str, prompt: str, hd: bool,
    edit_srcs: Optional[list[dict]] = None,
) -> tuple[Optional[bytes], Optional[str]]:
    """OpenAI gpt-image-2 via AI Gateway workers-ai run. Token-based
    billing — high quality 1024² ≈ $0.12-0.18 per image. Valid fields per
    CF's error message: prompt, images, quality, size, background, output_format."""
    payload: dict = {
        "prompt": prompt,
        "size": "1024x1024",
        "quality": "high" if hd else "medium",
        "output_format": "png",
    }
    if edit_srcs:
        imgs = _encode_srcs(edit_srcs)
        if imgs:
            payload["images"] = imgs  # always an array per CF's schema
    return await _call_gateway_workers_ai(model, payload)


def _model_family(model: str) -> str:
    m = model.lower()
    if "flux-2-klein" in m:
        return "flux_klein"
    if "flux-2-dev" in m or ("flux" in m and "dev" in m):
        return "flux_dev"
    if "flux" in m:
        # Unknown Flux variant — assume Klein (multipart) shape as fallback
        return "flux_klein"
    if "stable-diffusion-v1-5" in m or "stable-diffusion-1-5" in m:
        return "sd15"
    if "imagen" in m:
        return "imagen"
    if "nano-banana" in m or ("gemini" in m and "image" in m):
        return "nano_banana"
    if "gpt-image" in m:
        return "gpt_image"
    return "unknown"


async def _execute_image_model(
    model: str, prompt: str, *, hd: bool, edit_srcs: Optional[list[dict]],
    steps_override: Optional[int] = None,
) -> tuple[Optional[bytes], Optional[str]]:
    """Single entry point for "generate one image with this model". Picks the
    right adapter by family; returns (bytes, error). edit_srcs=None means
    text-to-image; non-empty means img2img. `steps_override` only matters
    for Flux family — other adapters ignore it."""
    family = _model_family(model)
    if family == "flux_klein":
        return await _call_flux(
            model=model, prompt=prompt, hd=hd, edit_srcs=edit_srcs,
            steps_override=steps_override,
        )
    if family == "flux_dev":
        first = (edit_srcs or [None])[0]
        return await _call_flux_dev(
            model=model, prompt=prompt, hd=hd, edit_src=first,
            steps_override=steps_override,
        )
    if family == "sd15":
        first = (edit_srcs or [None])[0]
        return await _call_sd15_img2img(model=model, prompt=prompt, src=first)
    if family == "imagen":
        if edit_srcs:
            return None, "Imagen doesn't support editing — pick another model."
        return await _call_imagen(model=model, prompt=prompt)
    if family == "nano_banana":
        return await _call_nano_banana(model=model, prompt=prompt, hd=hd, edit_srcs=edit_srcs)
    if family == "gpt_image":
        return await _call_gpt_image(model=model, prompt=prompt, hd=hd, edit_srcs=edit_srcs)
    return None, f"`{model}` isn't wired for image generation/editing."


def resolve_image_model(context: dict, tool_key: str, hd: bool) -> str:
    """Pick the model id for an image tool. `tool_key` is `image` or
    `image_edit`; when `hd` is True we prefer the `_hd` variant of the pref
    key (image_hd / image_edit_hd) and fall back to the SD pref or CF
    defaults if HD isn't configured."""
    tm = (context or {}).get("tool_models") or {}
    # Build the lookup chain. Most-specific first.
    chain: list[str] = []
    if hd:
        chain.append(f"{tool_key}_hd")
    chain.append(tool_key)
    if tool_key == "image_edit":
        if hd:
            chain.append("image_hd")
        chain.append("image")  # legacy / backward-compat fallback
    for k in chain:
        raw = tm.get(k)
        if not raw:
            continue
        provider_id, model = parse_model_str(raw)
        # CF native models — strip the cf:: prefix we may have stored.
        if provider_id == "cf":
            return model
        # Passthrough ids (e.g. google/imagen-4) pass through verbatim;
        # the family detector handles them downstream.
        return raw
    # Nothing configured → settings default for the requested quality.
    return settings.model_image_hd if hd else settings.model_image_sd


async def tool_generate_image(args: dict, context: dict) -> str:
    prompt = (args.get("prompt") or "").strip()
    if not prompt:
        return "Error: empty prompt"
    hd = bool(args.get("hd", False))
    model = resolve_image_model(context, "image", hd)
    steps_override = _cf_model_steps_override(context, model)
    print(f"[tool:gen_image] {'HD' if hd else 'SD'} model={model} steps_override={steps_override} | {prompt[:80]}")
    png, err = await _execute_image_model(
        model, prompt, hd=hd, edit_srcs=None, steps_override=steps_override,
    )
    if err or png is None:
        return f"Image generation failed: {err or 'no data'}"
    url = _save_image_bytes(png)
    # No inline model label — the stats footer already names the model.
    return f"![Generated Image]({url})"


async def tool_edit_image(args: dict, context: dict) -> str:
    prompt = (args.get("prompt") or "").strip()
    if not prompt:
        return "Error: empty prompt"
    hd = bool(args.get("hd", False))
    srcs = _all_image_uploads(context)[:FLUX_MAX_INPUT_IMAGES]
    used_prior = False
    if not srcs:
        # Follow-on UX: when the user says "edit the image" without re-uploading,
        # use the most recent generated image from this conversation. The chat
        # loop hands us `prior_image_path` when one's available.
        prior = (context or {}).get("prior_image_path")
        if prior and os.path.exists(prior):
            srcs = [{"kind": "image", "storage_path": prior, "filename": os.path.basename(prior)}]
            used_prior = True
        else:
            return (
                "Image editing needs an image. Either attach the image you want "
                "to modify, or first generate one in this conversation (then I "
                "can edit that one without you re-uploading)."
            )
    n = len(srcs)
    src_tag = "prior" if used_prior else "attached"
    model = resolve_image_model(context, "image_edit", hd)
    steps_override = _cf_model_steps_override(context, model)
    print(f"[tool:edit_image] {'HD' if hd else 'SD'} model={model} steps_override={steps_override} src={src_tag} refs={n} | {prompt[:80]}")
    png, err = await _execute_image_model(
        model, prompt, hd=hd, edit_srcs=srcs, steps_override=steps_override,
    )
    if err or png is None:
        return f"Image edit failed: {err or 'no data'}"
    url = _save_image_bytes(png)
    # No inline model label — the stats footer already names the model.
    return f"![Edited Image]({url})"


# ─────────────────────────────────────────────────────────────────────────────
# code_interpreter (Jupyter websocket)
# ─────────────────────────────────────────────────────────────────────────────

def _as_text(v) -> str:
    """Jupyter MIME values are usually strings, but the protocol allows a list
    of line-strings — coerce both to a single string."""
    if isinstance(v, list):
        return "".join(str(x) for x in v)
    return v if isinstance(v, str) else str(v or "")


def _sanitize_kernel_html(s: str) -> str:
    """Kernel text/html (e.g. pandas DataFrames) is rendered via {@html}. Strip
    <script>/<style> blocks and inline event handlers — the sandbox runs the
    user's own code, but we still don't pipe live script into the page."""
    s = re.sub(r"<script\b[^>]*>.*?</script>", "", s, flags=re.I | re.S)
    s = re.sub(r"<style\b[^>]*>.*?</style>", "", s, flags=re.I | re.S)
    s = re.sub(r"\son\w+\s*=\s*(\"[^\"]*\"|'[^']*'|[^\s>]+)", "", s, flags=re.I)
    return s.strip()


_MAX_OUTPUT_FILES = 5
_MAX_OUTPUT_FILE_BYTES = 8 * 1024 * 1024


def _jupyter_snapshot_files(jupyter_url: str, auth_headers: dict) -> dict:
    """{path: last_modified} for files in the kernel workspace root."""
    import urllib.request
    try:
        req = urllib.request.Request(f"{jupyter_url}/api/contents/", headers=auth_headers)
        data = json.loads(urllib.request.urlopen(req, timeout=10).read())
        return {
            c["path"]: c.get("last_modified")
            for c in (data.get("content") or [])
            if c.get("type") == "file"
        }
    except Exception:
        return {}


def _jupyter_download_file(jupyter_url: str, auth_headers: dict, path: str) -> Optional[bytes]:
    import urllib.request, urllib.parse
    try:
        req = urllib.request.Request(
            f"{jupyter_url}/api/contents/{urllib.parse.quote(path)}?format=base64&content=1",
            headers=auth_headers,
        )
        data = json.loads(urllib.request.urlopen(req, timeout=30).read())
        if data.get("format") == "base64" and data.get("content"):
            return base64.b64decode(data["content"])
        if isinstance(data.get("content"), str):
            return data["content"].encode()
    except Exception:
        pass
    return None


def _run_jupyter_sync(code: str, jupyter_url: str, timeout: int, token: str = "") -> str:
    """Sync helper run in a thread — the Jupyter kernel protocol is websocket-based."""
    import urllib.request
    from websocket import create_connection

    ws_url = jupyter_url.replace("https://", "wss://").replace("http://", "ws://")
    # Kernel-API auth (empty token = unauthenticated pre-token deploys).
    auth_headers = {"Authorization": f"token {token}"} if token else {}

    # File-output support: snapshot the workspace so files the code CREATES
    # (CSVs, xlsx, zips…) can be surfaced as download links afterwards.
    # The kernel starts in $HOME, but only the contents root (~/work) is
    # visible over the API — chdir there so outputs are reachable. (Costs
    # one line in tracebacks' line numbers.)
    code = "import os as __os; __os.chdir(__os.path.expanduser('~/work'))\n" + code
    files_before = _jupyter_snapshot_files(jupyter_url, auth_headers)

    # Create a kernel
    try:
        req = urllib.request.Request(
            f"{jupyter_url}/api/kernels",
            method="POST",
            data=b'{"name":"python3"}',
            headers={"Content-Type": "application/json", **auth_headers},
        )
        kr = json.loads(urllib.request.urlopen(req, timeout=15).read())
        kid = kr["id"]
    except Exception as e:
        return f"Code interpreter: cannot create kernel ({e})"

    stdout_parts, result_parts, image_parts = [], [], []
    latex_parts, html_parts = [], []
    error_text: Optional[str] = None
    try:
        time.sleep(0.4)
        ws = create_connection(
            f"{ws_url}/api/kernels/{kid}/channels",
            timeout=10,
            header=[f"Authorization: token {token}"] if token else None,
        )
        try:
            msg_id = f"tomsense-{int(time.time()*1000)}"
            ws.send(json.dumps({
                "header": {
                    "msg_id":   msg_id,
                    "msg_type": "execute_request",
                    "username": "tomsense",
                    "session":  "tomsense-session",
                    "date":     time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "version":  "5.3",
                },
                "parent_header": {}, "metadata": {},
                "content": {
                    "code": code,
                    "silent": False,
                    "store_history": True,
                    "user_expressions": {},
                    "allow_stdin": False,
                    "stop_on_error": True,
                },
                "channel": "shell",
            }))
            ws.settimeout(timeout)
            deadline = time.time() + timeout
            done = False
            while not done and time.time() < deadline:
                m = json.loads(ws.recv())
                mt = m.get("msg_type") or m.get("header", {}).get("msg_type")
                c = m.get("content", {}) or {}
                if mt == "stream":
                    stdout_parts.append(c.get("text", ""))
                elif mt in ("execute_result", "display_data"):
                    data = c.get("data") or {}
                    # Each output is a MIME bundle; prefer the richest
                    # representation and fall back to the text/plain repr that
                    # always rides along (e.g. "<Figure ...>" for a plot).
                    if data.get("image/png"):
                        try:
                            url = _save_image_bytes(base64.b64decode(data["image/png"]))
                            image_parts.append(f"![plot]({url})")
                        except Exception:
                            pass
                    elif data.get("image/jpeg"):
                        try:
                            url = _save_image_bytes(
                                base64.b64decode(data["image/jpeg"]), ext="jpg"
                            )
                            image_parts.append(f"![plot]({url})")
                        except Exception:
                            pass
                    elif data.get("image/svg+xml"):
                        try:
                            svg = _as_text(data["image/svg+xml"]).encode("utf-8")
                            image_parts.append(
                                f"![plot]({_save_image_bytes(svg, ext='svg')})"
                            )
                        except Exception:
                            pass
                    elif data.get("text/latex"):
                        # sympy / IPython.display.Math — render as block math.
                        tex = _as_text(data["text/latex"]).strip().strip("$").strip()
                        if tex:
                            latex_parts.append(f"$$\n{tex}\n$$")
                    elif data.get("text/html"):
                        # pandas DataFrames, styled tables, etc.
                        h = _sanitize_kernel_html(_as_text(data["text/html"]))
                        if len(h) > 6000:
                            h = h[:6000] + "\n<!-- truncated -->"
                        if h:
                            html_parts.append(h)
                        elif data.get("text/plain"):
                            result_parts.append(_as_text(data["text/plain"]))
                    elif data.get("text/plain"):
                        result_parts.append(_as_text(data["text/plain"]))
                elif mt == "error":
                    tb = "\n".join(c.get("traceback") or [])
                    error_text = re.sub(r"\x1b\[[0-9;]*m", "", tb).strip()
                elif mt == "execute_reply":
                    done = True
        finally:
            try: ws.close()
            except Exception: pass
    except Exception as e:
        error_text = f"runtime error: {e}"
    finally:
        try:
            urllib.request.urlopen(
                urllib.request.Request(
                    f"{jupyter_url}/api/kernels/{kid}",
                    method="DELETE",
                    headers=auth_headers,
                ),
                timeout=5,
            )
        except Exception:
            pass

    out = "".join(stdout_parts).rstrip()
    if result_parts:
        out = (out + "\n" if out else "") + "\n".join(result_parts)
    if len(out) > 4000:
        out = out[:4000] + "\n…[truncated]"

    sections: list[str] = []
    if error_text:
        sections.append(
            f"**stdout:**\n```\n{out}\n```\n\n**error:**\n```\n{error_text[:1500]}\n```"
            if out
            else f"**error:**\n```\n{error_text[:2000]}\n```"
        )
    elif out:
        sections.append(f"```\n{out}\n```")
    if latex_parts:
        sections.append("\n\n".join(latex_parts))
    if html_parts:
        sections.append("\n\n".join(html_parts))
    if image_parts:
        sections.append("\n\n".join(image_parts))

    # Surface files the code wrote (CSV/xlsx/zip…): diff the workspace
    # listing and copy new/changed files into /generated as download links.
    files_after = _jupyter_snapshot_files(jupyter_url, auth_headers)
    new_files = [
        p for p, mtime in files_after.items()
        if files_before.get(p) != mtime
    ][:_MAX_OUTPUT_FILES]
    file_links = []
    for p in new_files:
        blob = _jupyter_download_file(jupyter_url, auth_headers, p)
        if blob is None or len(blob) > _MAX_OUTPUT_FILE_BYTES:
            continue
        url = _save_output_file(blob, p.rsplit("/", 1)[-1])
        file_links.append(f"- [{p}]({url}) ({len(blob):,} B)")
    if file_links:
        sections.append("**Files produced:**\n" + "\n".join(file_links))

    if not sections:
        return "(executed — no stdout/result)"
    return "\n\n".join(sections)


async def tool_code_interpreter(args: dict) -> str:
    code = (args.get("code") or "").strip()
    if not code:
        return "Error: empty code"
    print(f"[tool:code] {code[:120].replace(chr(10), ' ⏎ ')}")
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None,
        _run_jupyter_sync,
        code,
        settings.jupyter_url,
        settings.code_interp_timeout,
        settings.jupyter_token,
    )


# ─────────────────────────────────────────────────────────────────────────────
# consult_coder — delegate to gpt-oss-120b
# ─────────────────────────────────────────────────────────────────────────────

CODER_SYSTEM = (
    "You are an expert software engineer. Produce clean, production-quality "
    "code. Include a 1-2 sentence intro, the code itself in a fenced block, "
    "a short usage example, and 2-3 test cases or assertions where applicable. "
    "No tools available — you write code, not execute it."
)


async def tool_consult_coder(args: dict, context: dict) -> str:
    task = (args.get("task") or "").strip()
    if not task:
        return "Error: empty task"
    model = ((context or {}).get("tool_models") or {}).get("code") or settings.model_writer
    user_id = (context or {}).get("user_id")
    print(f"[tool:coder] {task[:120]} (model={model})")
    result = await dispatch_chat_complete(
        user_id=user_id,
        model_str=model,
        messages=[
            {"role": "system", "content": CODER_SYSTEM},
            {"role": "user",   "content": task},
        ],
        max_tokens=settings.max_tokens_coder,
        tools=None,
    )
    text = strip_template_tokens(result.get("content", "")).strip()
    return text or "(coder returned no content)"


# ─────────────────────────────────────────────────────────────────────────────
# deep_research — web_search + parallel fetch_page + gpt-oss-120b synthesis
# ─────────────────────────────────────────────────────────────────────────────

RESEARCH_SYSTEM = (
    "You are a research analyst. Synthesize the search results and fetched "
    "pages below into a structured response.\n\n"
    "Required structure (use these exact headings):\n"
    "**TL;DR** — 1-2 sentences summarizing the answer.\n"
    "**Key findings** — 3-6 bullet points with inline citations like [1], [2].\n"
    "**Detail** — supporting paragraphs grouped by theme.\n"
    "**Caveats** — anything unverified, contested, missing, or potentially outdated.\n\n"
    "Cite every non-trivial claim using [N] where N is the page index from "
    "the headings above. If sources disagree, surface the disagreement."
)


# Deep research v2 knobs — bounded so a research call can't run away with
# the neuron budget: ≤3 rounds, ≤12 pages, planner runs on the tiny model.
_RESEARCH_MAX_ROUNDS = 3
_RESEARCH_MAX_PAGES = 12
_RESEARCH_PAGE_CHARS = 2500
_RESEARCH_FETCH_PER_QUERY = 3


async def _research_ask_small(prompt: str, user_id: Optional[str]) -> str:
    """One-shot call on the cheap task model (planning / gap-checking)."""
    try:
        r = await dispatch_chat_complete(
            user_id=user_id,
            model_str=settings.model_title,
            messages=[{"role": "user", "content": prompt}],
            max_tokens=300,
        )
        return (r.get("content") or "").strip()
    except Exception as e:
        print(f"[tool:research] planner error: {e}")
        return ""


def _research_parse_queries(text: str, limit: int) -> list[str]:
    """Pull up to `limit` queries out of a numbered/bulleted planner reply.
    Small models often answer with PROSE instead of queries — filter hard:
    a search query is a short noun phrase, not a sentence."""
    out = []
    for line in text.splitlines():
        q = re.sub(r"^[\s\d.\-*)•]+", "", line).strip().strip('"')
        if not q:
            continue
        if q.upper().rstrip(".") == "NONE":
            return []
        words = q.split()
        if len(q) < 9 or len(q) > 80 or len(words) > 10:
            continue  # too short / prose-length
        if q.lower().startswith((
            "here", "sure", "these", "based", "note", "i'm", "i ", "it ",
            "the key", "unfortunately", "as an", "this ",
        )):
            continue  # planner chatter, not a query
        if q.endswith((".", ":")) and len(words) > 6:
            continue  # full sentence
        out.append(q)
        if len(out) >= limit:
            break
    return out


async def tool_deep_research(args: dict, context: dict) -> str:
    """Multi-round agentic research: plan queries → search → fetch pages in
    parallel → gap-check → refine, up to _RESEARCH_MAX_ROUNDS. Synthesis
    cites sources as [n] against a numbered source list."""
    query = (args.get("query") or "").strip()
    if not query:
        return "Error: empty query"
    model = ((context or {}).get("tool_models") or {}).get("research") or settings.model_research
    user_id = (context or {}).get("user_id")
    print(f"[tool:research] {query[:120]} (model={model})")

    # Round 0 — plan the opening queries on the tiny model.
    plan = await _research_ask_small(
        "You are planning web research. For the question below, write 3 "
        "distinct, specific web search queries that together cover it. One "
        "per line, no numbering, no commentary.\n\n"
        f"Question: {query}",
        user_id,
    )
    pending = _research_parse_queries(plan, 3) or [query]

    sources: list[str] = []              # numbered source list, insertion order
    pages: dict[str, str] = {}           # url → extracted text
    snippets: list[str] = []             # search-result snippets per round
    seen_queries: set[str] = set()

    for round_no in range(1, _RESEARCH_MAX_ROUNDS + 1):
        if not pending or len(pages) >= _RESEARCH_MAX_PAGES:
            break
        round_queries = [q for q in pending if q.lower() not in seen_queries][:3]
        pending = []
        if not round_queries:
            break
        print(f"[tool:research] round {round_no}: {round_queries}")

        # Search all round queries in parallel, harvest URLs.
        results = await asyncio.gather(
            *[tool_web_search({"query": q}) for q in round_queries],
            return_exceptions=True,
        )
        fetch_urls: list[str] = []
        for q, res in zip(round_queries, results):
            seen_queries.add(q.lower())
            if not isinstance(res, str):
                continue
            snippets.append(f"[search: {q}]\n{res[:1500]}")
            for u in re.findall(r"URL:\s*(https?://\S+)", res)[:_RESEARCH_FETCH_PER_QUERY]:
                if u not in pages and u not in fetch_urls:
                    fetch_urls.append(u)
        fetch_urls = fetch_urls[: _RESEARCH_MAX_PAGES - len(pages)]

        # Fetch new pages in parallel.
        if fetch_urls:
            fetched = await asyncio.gather(
                *[tool_fetch_page({"url": u}) for u in fetch_urls],
                return_exceptions=True,
            )
            for u, res in zip(fetch_urls, fetched):
                if isinstance(res, str) and not res.startswith(("Fetch failed", "Fetch refused", "Not HTML")):
                    pages[u] = res
                    if u not in sources:
                        sources.append(u)

        # Gap check (skip after the final round — nothing left to spend it on).
        if round_no < _RESEARCH_MAX_ROUNDS and len(pages) < _RESEARCH_MAX_PAGES:
            notes = "\n".join(f"- {u}" for u in sources[-6:])
            gap = await _research_ask_small(
                "You are steering web research.\n"
                f"Question: {query}\n"
                f"Pages read so far:\n{notes or '- none'}\n\n"
                "If important aspects of the question are still uncovered, "
                "output up to 2 NEW web search queries — short keyword "
                "phrases, one per line, nothing else. Do NOT answer the "
                "question, do NOT explain. If coverage is sufficient, output "
                "exactly the single word: NONE",
                user_id,
            )
            pending = _research_parse_queries(gap, 2)

    # Synthesis — numbered sources so the model can cite [n].
    ctx_parts = [f"# Research Question\n{query}"]
    for i, u in enumerate(sources, start=1):
        body = (pages.get(u) or "")[:_RESEARCH_PAGE_CHARS]
        ctx_parts.append(f"\n# Source [{i}] {u}\n{body}")
    if snippets:
        ctx_parts.append("\n# Additional search snippets\n" + "\n\n".join(snippets[:4]))
    ctx = "\n".join(ctx_parts)[:60_000]

    result = await dispatch_chat_complete(
        user_id=user_id,
        model_str=model,
        messages=[
            {"role": "system", "content": RESEARCH_SYSTEM + (
                "\nCite claims inline with bracketed source numbers like [1] "
                "or [2][3] that refer to the numbered sources provided. Do "
                "not invent sources."
            )},
            {"role": "user", "content": ctx},
        ],
        max_tokens=settings.max_tokens_research,
        tools=None,
    )
    text = strip_template_tokens(result.get("content", "")).strip()
    if not text:
        return "(research synthesis returned no content)"
    if sources:
        src_md = "\n".join(f"{i}. [{u}]({u})" for i, u in enumerate(sources, 1))
        text = f"{text}\n\n**Sources** ({len(sources)} pages, {len(seen_queries)} searches)\n{src_md}"

    # Persist the report as an artifact (kind 'report') so it survives in
    # /files and the Artifacts panel independent of the chat scroll.
    chat_id = (context or {}).get("chat_id")
    if chat_id:
        try:
            from . import db as _db
            await _db.insert_artifacts(chat_id, None, [{
                "kind": "report",
                "title": query[:120],
                "url": None,
                "content": text[:100_000],
                "language": "markdown",
            }])
        except Exception as e:
            print(f"[tool:research] artifact save failed: {e}")
    return text


# ─────────────────────────────────────────────────────────────────────────────
# Dispatch table
# ─────────────────────────────────────────────────────────────────────────────

async def tool_search_docs(args: dict, context: dict) -> str:
    query = (args.get("query") or "").strip()
    if not query:
        return "Error: empty query"
    k = int(args.get("k") or 5)
    k = max(1, min(10, k))
    user_id = (context or {}).get("user_id")
    if not user_id:
        return "Error: no user context"
    # Project chats search ONLY the project's knowledge files.
    project_upload_ids = (context or {}).get("project_upload_ids")
    try:
        hits = await rag.search(
            user_id=user_id, query=query, k=k, upload_ids=project_upload_ids,
        )
    except Exception as e:
        return f"Error: doc search failed: {e}"
    if not hits:
        return "No relevant chunks found in your documents."
    lines = [f"Top {len(hits)} chunks for: {query}\n"]
    for i, h in enumerate(hits, 1):
        # Return full chunk body — they're already bounded at CHUNK_CHARS
        # (~1800). Truncating here hides the very content the model is
        # looking for.
        snippet = (h.get("text", "") or "")[:1800]
        score = h.get("score", 0.0)
        lines.append(f"[{i}] {h.get('filename')} (score {score:.3f})\n{snippet}\n")
    return "\n".join(lines)


async def tool_update_artifact(args: dict, context: dict) -> str:
    """Canvas iterate-in-place: rewrite a code artifact's content. Without an
    explicit artifact_id, targets the chat's most recent code artifact."""
    from . import db as _db
    content = args.get("content")
    if not isinstance(content, str) or not content.strip():
        return "Error: content required"
    if len(content) > 200_000:
        return "Error: content too large (200k max)"
    user_id = (context or {}).get("user_id")
    chat_id = (context or {}).get("chat_id")
    if not user_id:
        return "Error: no user context"
    artifact_id = args.get("artifact_id")
    if artifact_id is None:
        if not chat_id:
            return "Error: no chat context — pass artifact_id explicitly"
        arts = await _db.list_artifacts(chat_id)
        code_arts = [a for a in arts if a.get("kind") == "code"]
        if not code_arts:
            return "Error: this chat has no code artifacts yet"
        artifact_id = code_arts[0]["id"]  # list is newest-first
    patch = {"content": content}
    title = (args.get("title") or "").strip()
    if title:
        patch["title"] = title[:200]
    a = await _db.update_user_artifact(user_id, int(artifact_id), patch)
    if a is None:
        return f"Error: artifact {artifact_id} not found"
    return (
        f"Artifact {a['id']} ('{a.get('title') or 'untitled'}') updated "
        f"({len(content)} chars). The user sees the new version in the "
        "Artifacts panel — no need to repeat the code in your reply."
    )


async def tool_remember(args: dict, context: dict) -> str:
    fact = (args.get("fact") or "").strip()
    if not fact:
        return "Error: empty fact"
    if len(fact) > 500:
        fact = fact[:500].rstrip() + "…"
    user_id = (context or {}).get("user_id")
    if not user_id:
        return "Error: no user context"
    from . import db
    try:
        await db.add_memory(user_id, fact)
    except Exception as e:
        return f"Error saving memory: {e}"
    return f"Saved: {fact}"


async def tool_forget(args: dict, context: dict) -> str:
    query = (args.get("query") or "").strip()
    if not query:
        return "Error: empty query"
    user_id = (context or {}).get("user_id")
    if not user_id:
        return "Error: no user context"
    from . import db
    try:
        removed = await db.delete_memories_matching(user_id, query)
    except Exception as e:
        return f"Error: {e}"
    if removed == 0:
        return f"No memories matched '{query}'."
    return f"Forgot {removed} memor{'y' if removed == 1 else 'ies'} matching '{query}'."


DISPATCH = {
    "web_search":       tool_web_search,
    "fetch_page":       tool_fetch_page,
    "generate_image":   tool_generate_image,
    "edit_image":       tool_edit_image,
    "code_interpreter": tool_code_interpreter,
    "consult_coder":    tool_consult_coder,
    "deep_research":    tool_deep_research,
    "search_docs":      tool_search_docs,
    "remember":         tool_remember,
    "forget":           tool_forget,
    "update_artifact":  tool_update_artifact,
}

# Code-mode file/bash tools — proxied to the sandbox container.
DISPATCH.update(CODE_DISPATCH)

# Tools that need the per-request context (uploads, chat_id, user_id,
# tool_models, …). Other tools get the plain args dict.
_CONTEXT_AWARE = {
    "generate_image", "edit_image", "search_docs", "remember", "forget",
    "consult_coder", "deep_research", "update_artifact",
}


async def dispatch(name: str, args: dict, context: Optional[dict] = None) -> str:
    # Remote MCP tools: mcp__<server-slug>__<tool> — proxied over JSON-RPC.
    if name.startswith("mcp__"):
        from . import db as _db, mcp as _mcp
        user_id = (context or {}).get("user_id")
        if not user_id:
            return "Error: no user context for MCP tool"
        try:
            return await _mcp.dispatch_mcp(name, args or {}, user_id, _db)
        except Exception as e:
            return f"MCP tool error: {e}"
    fn = DISPATCH.get(name)
    if fn is None:
        return f"Unknown tool: {name}"
    try:
        if name in _CONTEXT_AWARE:
            return await fn(args, context or {})
        return await fn(args)
    except Exception as e:
        return f"Tool {name} failed: {e}"
