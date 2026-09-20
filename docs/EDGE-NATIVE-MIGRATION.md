# Stable → edge-native: feature inventory and migration scope

Inventory taken from `main` on 2026-09-20: **98 HTTP routes**, **34 server-side
tools**, **20 device tools**, ~17k lines of Python.

Purpose: decide *per feature* whether it ports, gets rewritten, gets replaced by
a platform service, or dies — before writing any of it. The failure mode this
document exists to prevent is porting the stable backend endpoint-by-endpoint
onto a runtime with different properties, and ending up with the same
architecture in TypeScript.

Sizing is relative (S / M / L / XL), not hours. Anything marked **XL** should be
re-scoped before it starts.

---

## 1. Disposition summary

| | Count | Meaning |
|---|---|---|
| **Done** | 6 | Already working on the beta |
| **Port** | 21 | Logic carries over largely intact |
| **Rewrite** | 9 | Same feature, materially different implementation |
| **Replace** | 4 | Platform service instead of our code |
| **Drop** | 5 | Obsolete under the new architecture |

The headline: **the device-tool layer gets dramatically simpler and the code
sandbox gets dramatically harder.** Everything else is ordinary work.

---

## 2. Already done on the beta

| Feature | Notes |
|---|---|
| Streaming chat | Same `text/reasoning/heartbeat/done` contract as stable |
| Provider registry + BYO keys | Superset of stable: keys encrypted at rest, never readable back |
| Capability resolution | Ported wholesale, CF catalogue generated from `cf_catalog.py` |
| Auth | **Better** than stable — stable trusts a header; beta verifies the JWT |
| Conversation + message storage | Local SQLite as source of truth, D1 as sync target |
| Usage accounting | `usage_daily` incl. `cache_read` |

---

## 3. Chat surface

| Feature | Stable route(s) | Disposition | Size | Notes |
|---|---|---|---|---|
| Send / stream | `POST /chat/stream` | **Done** | — | |
| Stop generation | `POST /chat/{id}/stop` | Rewrite | S | Becomes a DO message, not a process signal |
| Detached run reconnect | `GET /chat/stream/{run_id}`, `/chat/{id}/live` | **Port** | S | `DetachedRun` DO already written, unused |
| Tool-result round trip | `POST /chat/tool_result` | Port | M | Needed before ANY tool works |
| Regenerate | `POST /chats/{id}/regenerate` | Port | S | |
| Branch conversation | `POST /chats/{id}/branch` | Port | M | Needs a sync-safe copy; watch lamport assignment |
| Follow-up suggestions | `POST /chat/{id}/followups` | Port | S | Tier-1 model |
| Checkpoints + restore | `GET/POST /chats/{id}/checkpoints/...` | Rewrite | M | Interacts badly with LWW sync — see §9 |
| Chat CRUD, rename, delete, batch | `/chats*` | Port | M | Mostly local-first already; needs UI |
| Pin / folder / project / model | `PUT /chats/{id}/*` | Port | S | Add columns to the sync set |
| Per-chat system prompt | `PUT /chats/{id}/system_prompt` | Port | S | |
| Search | `GET /chats/search` | **Rewrite** | M | Local SQLite FTS5, not a server query |
| Export | `GET /chats/{id}/export` | Port | S | |
| Public share links | `POST /chats/{id}/share`, `GET /share/{token}` | Port | M | Works naturally — Access only guards `/auth/mobile` |

**Search is the interesting one.** On stable it's a Postgres query. Local-first
makes it strictly better: FTS5 over the device's own SQLite is instant and works
offline. Don't port the endpoint.

---

## 4. Tools — the bulk of the value

### 4a. Device tools (20) — the big architectural win

`get_location`, `get_calendar`, `get_health`, `create_calendar_event`,
`set_reminder`, `start_timer`, `set_alarm`, `launch_app`, `make_call`,
`send_sms`, `open_maps`, `open_url`, `share_text`, `get_contacts`,
`open_settings`, `set_volume`, `set_brightness`, `media_control`,
`get_device_status`, `play_music`

**Disposition: Rewrite — and it gets much simpler. Size: L (20 tools, but each S).**

On stable these round-trip: model → backend → SSE down to the Capacitor client →
JS plugin → Android API → result back up. The native client collapses that to a
direct Kotlin call. Lower latency, works offline for the local ones, and deletes
the whole `clienttools.py` dispatch protocol.

This is the single clearest justification for the native rewrite, so it should
land early enough to prove the thesis.

### 4b. Server tools (34)

| Group | Tools | Disposition | Size |
|---|---|---|---|
| Web | `web_search`, `fetch_page`, `deep_research` | **Replace** | M |
| Memory | `remember`, `forget`, `search_docs` | Port | M |
| Images | `generate_image`, `edit_image`, `reverse_image_lookup` | Port | M |
| Code | `python3`, `code_interpreter`, `consult_coder` | **Rewrite** | XL |
| Media/misc | `identify_song`, `get_weather`, `get_health` | Port | S |
| Artifacts | `update_artifact` | Port | S |

`web_search` currently hits self-hosted SearXNG. **Replace with Brave/Tavily/Exa**
— spec §8. Self-hosted scrapers break constantly and a request-scoped runtime is
the wrong host for one.

---

## 5. Code mode — the one XL

Stable: `tools_code.py` (1284 lines) + `code_hints.py` (370) + `/sandbox/fs/*`
against a long-lived `tomsense-sandbox` container with a persistent filesystem.

Edge: Cloudflare Containers **sleep**, and nothing survives between sessions.
The model becomes rehydrate-from-R2 → exec → write back.

**Disposition: Rewrite. Size: XL. Recommend deferring to last** and running the
existing sandbox via the home agent in the meantime if code mode is needed
sooner. This is the single biggest piece of work in the whole migration and the
one most likely to be re-scoped once attempted.

---

## 6. Knowledge, memory, files

| Feature | Disposition | Size | Notes |
|---|---|---|---|
| Memories (3-layer) | Port | M | Schema already in D1 incl. decay + `source_msg_id` |
| Uploads | Port | M | R2 instead of disk |
| RAG / embeddings / reindex | Rewrite | L | **BLOCKED**: token has no Vectorize permission |
| Artifacts | Port | S | |
| Shared folder | **Drop** | — | Existed to escape the sandbox volume; R2 replaces it |

**Vectorize is the only hard external blocker in this document.** Everything
else is work; this one needs `Account · Vectorize · Edit` added to the API token.

---

## 7. Voice

| Feature | Stable | Edge | Size |
|---|---|---|---|
| STT | `POST /transcribe` (Whisper) | Deepgram `flux` | Rewrite, M |
| TTS | `POST /tts` (Piper) | Deepgram `aura-2` | Rewrite, M |
| Duplex + barge-in | *does not exist* | `VoiceSession` DO — **written, never executed** | L |

Voice is not a port. It's the feature the whole re-architecture was for, and
stable has no equivalent. Still the **highest-risk assumption** in the design.

---

## 8. Platform / ops

| Feature | Disposition | Size | Notes |
|---|---|---|---|
| MCP client (remote servers) | Port | M | `mcp.py`, 209 lines |
| MCP server (we expose tools) | Port | M | `mcp_server.py`, 216 lines |
| Scheduled prompts | Rewrite | M | Cron Triggers + DO; handler is already a stub |
| Notifications / push | Rewrite | M | `devices.push_token` column exists; needs FCM |
| Secrets | Port | S | Reuse `crypto.ts` |
| Personas | Port | S | |
| Projects | Port | S | Table exists in D1, unused |
| Starters | Port | S | |
| Prefs | Port | S | Partly done via `default_model` |
| Usage / neurons | **Done** | — | |
| Mounts / deploy targets | **Drop → home agent** | S | Docker-specific; belongs behind `HomeLink` |
| Setup flow (`setup.sh`, `/setup`) | **Drop** | — | Different deployment model entirely |
| `netguard`, `verify` | Port | S | SSRF guard still needed for `fetch_page` |
| SearXNG | **Replace** | — | See §4b |
| Jupyter | **Drop** | — | Subsumed by code mode |

---

## 9. Risks worth naming before starting

1. **Checkpoints vs. LWW sync.** Restoring a checkpoint rewrites history, which
   is exactly what last-writer-wins is worst at. A naive port will silently lose
   data when two devices restore different checkpoints. Design it as an
   append-only "restore event" rather than mutation, or accept single-device
   restore only.
2. **Branch + lamport.** Copying a conversation must assign fresh ids and
   lamports, or the copy will collide with its source on sync.
3. **Tool-result round trip gates everything.** 54 tools are worthless until
   `POST /chat/tool_result` has an equivalent. It should be near the front.
4. **Device tools need a permission story.** 20 native tools means contacts,
   SMS, calendar, location prompts. Stable could lean on the WebView; native
   cannot.
5. **Containers billing.** Code mode is the one thing that moves the monthly
   bill (spec §11). Don't leave a container warming by accident.

---

## 10. Proposed order

Sequenced so each phase is independently useful and the risky things get tested
early rather than discovered late.

| Phase | Contents | Why here |
|---|---|---|
| **A** | Tool-result round trip · detached-run reconnect · stop · regenerate | Unblocks every tool; all small |
| **B** | Device tools (20) · permission flow | Proves the native thesis; highest value per line |
| **C** | Voice: wire `VoiceSession` end to end, measure on-device latency | Highest risk; must be validated before building on it |
| **D** | Chat management: search (FTS5) · pin/folder/project · branch · export · share | Makes it a daily driver |
| **E** | Memory + uploads + RAG *(needs Vectorize)* · artifacts | Depth; externally blocked |
| **F** | Web tools (Brave/Tavily) · images · MCP client + server | Breadth |
| **G** | Schedules · push notifications · secrets · personas · starters | Long tail |
| **H** | Code mode on Containers | XL, deliberately last |

Assistant role and wake word (spec M5) slot alongside **B/C** — they're part of
proving the same thesis.

---

## 11. What this is not

This plan does **not** aim for 1:1 endpoint parity with `main`. Roughly 14 of
the 98 routes are dropped or replaced outright, and several more collapse into
the client. The target is feature parity *as experienced*, on an architecture
where voice, assistant role, and offline use are possible at all.
