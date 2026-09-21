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
| Stop generation | `POST /chat/{id}/stop` | **Done** | — | `POST /run/{id}/cancel` — aborts the upstream fetch, not just the client |
| Detached run reconnect | `GET /chat/stream/{run_id}`, `/chat/{id}/live` | **Done** | — | `GET /run/{id}/attach` — replays, then streams live |
| Tool-result round trip | `POST /chat/tool_result` | **Done** | — | `POST /run/{id}/tool_result`; the run parks in `awaiting_tools` between rounds |
| Regenerate | `POST /chats/{id}/regenerate` | **Done** | — | Client-side; reuses the row and drops the stale answer from history |
| Branch conversation | `POST /chats/{id}/branch` | Port | M | Needs a sync-safe copy; watch lamport assignment |
| Follow-up suggestions | `POST /chat/{id}/followups` | Port | S | Tier-1 model |
| Checkpoints + restore | `GET/POST /chats/{id}/checkpoints/...` | Rewrite | M | Interacts badly with LWW sync — see §9 |
| Chat CRUD, rename, delete, batch | `/chats*` | **Done** | — | The UI it needed did not exist; see §13 |
| Pin / project / model | `PUT /chats/{id}/*` | **Done** | — | `folder` deliberately not ported — §13 |
| Per-chat system prompt | `PUT /chats/{id}/system_prompt` | **Done** | — | Column + repo; no UI surface yet |
| Search | `GET /chats/search` | **Done** | — | FTS5, but NOT via SQLDelight — §13 |
| Export | `GET /chats/{id}/export` | **Done** | — | Local markdown → share sheet |
| Public share links | `POST /chats/{id}/share`, `GET /share/{token}` | **Done** | — | Verified live, logged-out read works |

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
**Status: DONE 2026-09-21 — 19 of 20 shipped. See §12.**

On stable these round-trip: model → backend → SSE down to the Capacitor client →
JS plugin → Android API → result back up. The native client collapses that to a
direct Kotlin call. Lower latency, works offline for the local ones, and deletes
the whole `clienttools.py` dispatch protocol.

This is the single clearest justification for the native rewrite, so it should
land early enough to prove the thesis.

`get_health` is the one not shipped: it needs Health Connect, which is a
dependency, a separate permission model and a published privacy policy. It is
NOT registered rather than stubbed — a tool that is always going to fail is
worse than a tool the model was never told about.

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
| ~~**A**~~ | ~~Tool-result round trip · detached-run reconnect · stop · regenerate~~ — **DONE 2026-09-20**, see §11 | Unblocks every tool; all small |
| ~~**B**~~ | ~~Device tools (20) · permission flow~~ — **DONE 2026-09-21** (19/20), see §12 | Proves the native thesis; highest value per line |
| **C** | Voice: wire `VoiceSession` end to end, measure on-device latency | Highest risk; must be validated before building on it |
| ~~**D**~~ | ~~Chat management: search (FTS5) · pin/project · branch · export · share~~ — **DONE 2026-09-21**, see §13 | Makes it a daily driver |
| **E** | Memory + uploads + RAG *(needs Vectorize)* · artifacts | Depth; externally blocked |
| **F** | Web tools (Brave/Tavily) · images · MCP client + server | Breadth |
| **G** | Schedules · push notifications · secrets · personas · starters | Long tail |
| **H** | Code mode on Containers | XL, deliberately last |

Assistant role and wake word (spec M5) slot alongside **B/C** — they're part of
proving the same thesis.

---

## 11. Phase A as built (2026-09-20)

Generation moved into the `DetachedRun` DO. The Worker creates a run and
attaches; it no longer produces the stream. Stop, reconnect and the tool round
trip are all consequences of that one change rather than three features.

Wire contract gained two events. `run` (first frame, carries the run id) and
`end` (terminal). **`done` is now a round boundary** — a tool-using generation
emits several. Anything that treats `done` as final will truncate replies once
tools exist.

| Route | Purpose |
|---|---|
| `POST /chat` | Create a run and attach to it |
| `GET /run/{id}/attach` | Rejoin — replays what was missed, then streams live |
| `POST /run/{id}/tool_result` | Answer outstanding calls; resumes the parked run |
| `POST /run/{id}/cancel` | Stop, including aborting the upstream provider fetch |
| `GET /run/{id}/state` · `GET /runs` | Find a run after a cold start |

Four bugs surfaced, three of which would have made tools quietly useless:

1. `streamWorkersAi` **dropped `tools` entirely** — the default Cloudflare
   models could not call a tool at all. Verified against the live API that
   Workers AI accepts OpenAI-wrapped tool schemas and streams OpenAI-shaped
   deltas when tools are present, so one schema now serves CF and BYO alike.
2. `flattenForTextModel` **dropped `tool_calls`/`tool_call_id`/`name`**,
   severing a tool result from the call it answers on every non-vision model.
3. A round producing only tool calls was marked `stalled` (it emits no text),
   which would have fired the fallback model on every tool call.
4. A default `TransformStream` has a readable highWaterMark of 0, so awaiting
   the first replay write in `attach` deadlocked. Writes are never awaited now:
   a subscriber that stops reading must not be able to stall the run.

Verified live: tool round trip end to end (glm-5.2 → `get_weather` → result →
answer), reconnect after a dropped connection (248-word reply finished with no
client attached), cancel, D1 status mirroring, and usage still accounted now
that the producer moved.

**Carried into phase B:** the client answers any tool call with "not available
on this device". That is the seam device tools plug into — 4a is now purely
client work, with no edge changes needed.

---

## 12. Phase B as built (2026-09-21)

19 device tools, implemented natively and dispatched in the client. No edge
changes were needed at all — the run parks, the phone answers — which is the
clearest evidence the phase A seam was cut in the right place.

**Contract and implementation are separate.** `ToolCatalog` (shared, no Android
types) is everything the model sees; the Android files bind each entry to code.
That split exists so the contract can be dumped and tested against a live model
without an emulator: `./gradlew -q :shared:dumpToolSchemas`. Testing
hand-copied schemas would have proved nothing.

**Nothing irreversible happens without a human.** `make_call` opens the dialer
and `send_sms` opens the messaging app, both pre-filled; the user presses the
final button. So the app ships with **no CALL_PHONE and no SEND_SMS permission
at all** — a model cannot ring a hallucinated number, and neither can anything
that compromises the app. The tool descriptions say so explicitly, and the
model does report "ready to send" rather than "sent".

Permissions are requested at the moment of use, never up front.

### The bug this phase found: the model had no clock

Asked to "remind me to call the dentist at 3pm today", the model called
`get_calendar`, then `get_calendar` again, then `get_device_status` — three
paid rounds probing tools to work out what day it was, never setting the
reminder. Nothing was sending it the time. A Worker cannot supply it either:
it runs wherever the request landed, so for a Los Angeles user its "today" is
already tomorrow from late afternoon on.

Fixed with `deviceSystemPrompt()` — the device's own clock and zone, prepended
to every request. The same prompt then produces `set_reminder` with the correct
ISO timestamp on the first round. **Anything time-relative was broken before
this**, tools or not.

### Verified against the live model, not just compiled

All 19 real schemas, routed by glm-5.2:

```
calendar tomorrow    → get_calendar({days:1})
dinner tomorrow 7pm  → create_calendar_event({start:"2026-09-21T19:00", …})
alarm 7:30           → set_alarm({hour:7, minute:30})
timer 10 min         → start_timer({seconds:600})
brightness 80%       → set_brightness({percent:80})
skip song            → media_control({action:"next"})
play Miles Davis     → play_music({query:"Miles Davis"})
wifi settings        → open_settings({screen:"wifi"})
"capital of France"  → no tool, just answers
"remind me at 3pm"   → declines: already past 3pm (the clock, working)
```

And the multi-round chain end to end: *"Text Sarah I'll be 10 minutes late"* →
`get_contacts("Sarah")` → number returned → `send_sms(+1310…, "…10 minutes
late…")` → *"ready to go, just hit send."*

---

## 13. Phase D as built (2026-09-21)

Chat management. The headline discovery was that this was not five small ports
onto an existing surface: **the app had no conversation list at all.** It
opened whatever chat was most recent (`MainActivity.kt:73`) and offered no way
to reach another, so every feature in this phase was unreachable regardless of
what the data layer supported. The list had to exist first.

| Feature | Where it landed |
|---|---|
| Conversation list, switcher, new chat | `ui/ConversationDrawer.kt` (shared) |
| Rename · pin · delete | Drawer row menu → `ChatRepository` |
| Search (FTS5 + titles) | `data/MessageSearch.kt` + drawer, debounced 150ms |
| Branch | `ChatRepository.branchConversation` |
| Export | `data/Export.kt` → Android share sheet |
| Share links | `edge/src/share.ts`, `POST /chats/{id}/share`, `GET /share/{token}` |

### FTS5 does not exist on Android — beta7 could not start

**beta7 shipped broken and had to be replaced by beta8.** It is worth writing
down exactly why, because the mistake was in the testing, not the code.

Android's system SQLite is **not compiled with FTS5**. This is why Room itself
supports only FTS3/FTS4 and recommends FTS4, and it is a known SQLDelight issue
(#1977). `CREATE VIRTUAL TABLE ... USING fts5` throws `no such module: fts5`.

That statement was in the v1→v2 migration, and migrations run from
`Application.onCreate` — so the database never opened and the app died before
drawing a frame. Not "search is broken": *nothing* worked.

It passed every test here because the tests ran against the **desktop** JDBC
driver (xerial), which bundles its own SQLite with FTS5 compiled in. Validating
a mobile code path on the desktop engine proved only that the SQL was
well-formed. The fix for that is not more tests of the same kind — it is that
**a device-specific capability must be verified on the device, or not relied
on.**

Three changes came out of it:

1. **The index is no longer created by a migration.** `1.sqm` now does only
   the schema changes the app cannot run without. The index is built at runtime
   in `MessageSearch`, where failure is caught rather than fatal. The governing
   rule: an optional feature must never be able to stop the app from starting.
   Search is a convenience; the conversations are the product.
2. **FTS4 instead of FTS5.** Available on Android since API 11. The cost is
   relevance ranking — FTS4 has no `bm25()` — so results are newest-first,
   which for one's own chat history is arguably the better order anyway.
3. **A real fallback.** If even FTS4 is missing (some AOSP-derived builds strip
   it), `MessageSearch.isAvailable` reports false, indexing no-ops, and the
   repository falls back to a `LIKE` scan with an excerpt built in Kotlin.
   Slower on a long history, never broken.

The backfill also moved off the constructor into `prepareSearchIndex()`, called
from a background scope at startup: an `INSERT ... SELECT` across a long
history in `Application.onCreate` is an ANR, which is the same mistake more
slowly.

Note on recovery: `SQLiteOpenHelper` wraps `onUpgrade` in a transaction and
SQLite rolls DDL back, so beta7's failed migration left the database **fully
intact at v1** — verified. No data was lost and beta8 upgrades cleanly from
beta6 or from a beta7 install.

### SQLDelight cannot do FTS at all

Separately from the Android problem: SQLDelight 2.0.2 cannot generate code
against a virtual table of any kind. Its SQLite dialect has no FTS module, so
the columns have no type — selecting one fails to compile, and merely
*referencing* the table in a subquery sends the generator into infinite
recursion (`StackOverflowError`). No dialect artifact adds it.

So every FTS statement lives in `MessageSearch.kt` as raw driver SQL — the only
queries in the client the compiler does not check, deliberately gathered into
one file rather than scattered.

### The schema now lives in migrations

`deriveSchemaFromMigrations` is on: `Schema.sq` holds only queries, and `0.sqm`
(the beta1–beta6 schema) plus `1.sqm` define the tables. beta6 is installed
with real conversations on it, so the upgrade path is the one that has to be
right, and this makes a fresh install *be* that path replayed.

`JdbcSqliteDriver` needed matching work — unlike the Android driver it neither
creates nor upgrades, and would have opened an old desktop database and thrown
"no such column" on the first query. It now tracks `PRAGMA user_version`.

### Indexing is explicit, not trigger-driven

Triggers on `message` would fire once per streamed token, re-tokenising a
growing answer hundreds of times per reply. Indexing happens once, at
completion, from the same place that already marks a row dirty — the same
reasoning that keeps `dirty = 0` while tokens arrive.

### Two decisions that shape the data

**`folder` was not ported.** Stable carried both a free-text `chats.folder` and
`project_id`, and already ran a one-time migration folding every folder into a
project (`backend/app/db.py:140`). Porting it would have resurrected a grouping
stable had retired; `project_id` is the one that survives.

**`share_token` is pull-only.** It is absent from `SYNCABLE` in `sync.ts`, so
`push` never writes it while `pull` (a `SELECT *`) still returns it. A client
that could push a token could choose a short or guessable one; this way the
edge mints it and every device still sees it.

### Verified

Migrations were replayed against real SQLite 3.45.2, both paths:

```
upgrade (beta6) v1 + data -> 1.sqm: 3 messages preserved, migration
                creates 0 virtual tables, new columns defaulted on
                old rows
runtime index   backfilled 2 of 3 (tombstone skipped), idempotent on
                a second launch, pre-migration history searchable
search          "sourdough" -> hit, "sour" prefix -> same hit,
                snippet() highlights with the FTS3/4 argument order
tokenising      "it's" -> `it* s*` (matches), "multi-word" ->
                `multi* word*` (matches), "OR" treated as a term not
                an operator, "((" yields no tokens and is skipped
notindexed      searching a msg_id matches 0 rows, as intended
rollback        a migration that throws leaves columns AND
                user_version untouched — so beta7's failure was
                non-destructive
```

The gap this list still has: **none of it runs on Android.** That is the
condition that produced the beta7 crash, and it is not closed by any test in
this repo.

Share links were tested against the live Worker: minted with a device token,
read with **no credential**, `<script>` in a title and `<b>` in a body both
escaped, `cache-control: private, no-store` + `x-robots-tag: noindex`, revoke
returns the link to 404, and another user's conversation is "not found" rather
than forbidden. Test rows were removed from D1 afterwards.

**Not yet verified on a device.** Phase C (voice) is next.

---

## 14. Model curation (2026-09-21)

Model discovery originally ran **only while adding a provider**, which froze
the model list at that moment. The search box on the settings screen then
searched that frozen list, so it found what was already configured rather than
what the provider actually offers — and a model the provider added later was
unreachable without deleting and re-creating the provider, losing the API key
with it.

Models can now be added and removed for **every** provider, after creation:

- `Providers → Models` opens the list, with *Fetch available* re-running
  discovery against the saved provider using its stored key.
- The list shown is the union of configured and discovered ids, so the current
  selection is always visible and cannot be hidden by a filter or dropped
  because a provider stopped advertising something still in use.
- Ids can be typed by hand at any time, not only when discovery fails — a
  provider can serve a model it does not advertise.

**Cloudflare is curatable too**, which it was not: `updateProvider` accepted
only `enabled` for it and the list came straight from the generated catalogue.
Now an empty stored list means "the whole catalogue" (so a fresh account still
works with zero configuration) and a non-empty one is honoured exactly.
Capabilities are merged from the catalogue by id, and an id the catalogue does
not know is kept as stored — Cloudflare ships models faster than
`cf_catalog.py` is regenerated, so hand-adding one has to work.

`POST /providers/discover` with `providerId: "cf"` returns the catalogue rather
than attempting a fetch: the Worker holds an AI *binding*, not an account API
token, so there is no `/models` endpoint it could call.

The search box now says it searches *configured* models. That wording is the
actual fix for the original confusion — the difference between "that model
doesn't exist" and "I haven't added it yet".

### Verified against the live Worker

CF discovery returned the catalogue; curating CF to 2 models was reflected in
both `/providers` and `/models` with capabilities merged; a hand-added id the
catalogue does not know survived the round trip with its declared
capabilities; an empty list restored the full catalogue. On a custom provider,
add and remove both worked and **the API key survived a model edit**. All test
rows were removed and the database verified byte-identical to its prior state.

### A latent bug found while testing — fixed (migration 0005)

`providers.id` was the primary key, and Cloudflare's row uses the constant
`'cf'` for every user. So the first account to persist anything about
Cloudflare owned that row globally: everyone else's insert hit
`ON CONFLICT DO NOTHING`, and their update — correctly scoped by `user_id` —
matched zero rows. Settings appeared to save and silently did not.

The key is now `(id, user_id)`, which is the right shape regardless of the bug:
a provider row is only ever meaningful inside one account, and every query in
the Worker already scoped by `user_id`. The one exception was the Cloudflare
upsert's `ON CONFLICT(id)`, now `ON CONFLICT(id, user_id)`.

SQLite cannot alter a primary key, so 0005 is a table rebuild. That is safe
here because **nothing references `providers` by foreign key** — checked
before writing it. `usage_daily.provider_id` is a plain column, not a
reference, and already carries `user_id` in its own composite key.

Verified on a local SQLite first — the bug reproduced on the old schema (0 rows
affected), then after the migration: data preserved including the encrypted
key, both users' writes landing, one user's write not touching the other's row,
repeat upserts still idempotent, a true duplicate still rejected, and the
`ON DELETE CASCADE` surviving the rebuild. Then on the live Worker with two
accounts curating Cloudflare simultaneously — independent model lists *and*
independent enabled flags, three `cf` rows coexisting. Test users removed and
the production row verified byte-identical to a snapshot taken beforehand.

One thing worth copying: the migration originally carried a trailing comment
containing a semicolon, which splits a statement in any naive SQL splitter.
Avoid semicolons inside SQL comments in migration files.

---

## 15. Model routing (2026-09-21)

Stable decides which model answers in **six layers**, spread across
`main.py` (~3130–3235), `_route_model`, `_budget_downshift` and
`_vision_model`. The beta had two: explicit pick, and saved default.

Ported into one file (`edge/src/routing.ts`), in precedence order:

| # | Layer | Trigger |
|---|---|---|
| 1 | Explicit pick | `model` on the request — always wins |
| 2 | Think mode | `think: true` → research slot, `reasoning_effort: high` |
| 3 | Vision override | an image is attached |
| 4 | Auto-route | tiny model rates EASY/HARD; HARD escalates |
| 5 | Saved default | the pre-existing beta behaviour |
| 6 | Budget downshift | applied LAST, over whatever won above |

Gathering them is most of the value: on stable the precedence is implicit in
the order the statements happen to appear, which is why "why did *that* model
reply?" was hard to answer. Budget mode is last deliberately — it is a cost
ceiling, so it must be able to override the router's own escalation.

Two behaviours carried over because each encodes a real incident:

- **The Vision slot owns image turns**, even when the chat model can also see
  (stable comment dated 2026-07-11: a model chosen for landmark recognition
  lost a photo turn to a merely vision-*capable* chat model). The exception is
  an explicit per-request pick that can see.
- **Auto-route short-circuits** messages under 60 characters with no code
  fence, so the common case never pays for the classifier call.

**Notices are a first-class stream event**, persisted on the run and replayed
on reconnect. A model swap the user never sees is one they will eventually be
confused by.

### Where the credential question landed

Budget mode needs neuron usage, which is CF's GraphQL analytics API and does
need an account token — unlike *running* inference, which the AI binding bills
directly. Rather than a Worker secret, the token is a **per-user setting**,
encrypted with the same envelope as provider keys and never returned by the
API. Budget mode stays dormant until one is supplied, and the Worker holds
nothing but its bindings by default.

### Two bugs found while testing

- The utility tier defaulted to `TIER1_MODEL`, which is **gemma-4 — a
  reasoning model**. Given a 4-token classifier budget it spent all four
  thinking and returned empty, silently disabling auto-routing entirely.
  Added `TASK_MODEL` (llama-3.2-3b, matching stable's `MODEL_TITLE`). The
  Utility slot in settings warns about this explicitly.
- Saving any preference returned a payload without `hasAnalyticsKey`, so the
  client default (`false`) won and budget mode reported itself unconfigured.

### Verified live, per layer

```
vision slot wins an image turn          -> llama-4-scout + notice
explicit pick beats the vision slot     -> gemma-4, no notice
think mode                              -> research slot (gpt-oss-120b)
induction proof (auto-route)            -> kimi-k2.7-code
casual chat of the same length          -> stays on default
message under 60 chars                  -> classifier never called
budget mode over cap, heavy model       -> downshifts to chat_fallback,
                                           notice with real neuron counts
budget mode, light model                -> untouched
analytics key                           -> encrypted at rest, never returned
prefs merge                             -> one slot set leaves others intact;
                                           empty string clears
```

Also threaded cache affinity through: `x-session-affinity` for Workers AI,
`x-session-id` for OpenRouter — keyed by conversation for chat and by
`(user, purpose)` for the utility tier, so each purpose lands on its own warm
prefix. Stable measured the CF hit ratio going ~60% → 80%.

---

## 16. Images, in and out (2026-09-21)

Three things that turned out to share one pipeline.

**Reasoning is collapsed by default.** It used to render inline above the
answer, which on a reasoning model routinely pushed the actual reply off
screen. Still one tap away — hiding it entirely would defeat the point of
running your own stack — and it shows a live "Thinking…" label while a reply
has no content yet, because at that moment it is the only sign of life.

**Attachments.** `POST /files` stores to R2 under `u/{userId}/…`; `GET
/files/{key}` serves it. The ownership rule is a property of the key rather
than a lookup that can be forgotten. Messages carry KEYS, never bytes — the
edge expands them into `image_url` data URLs at request time (`expandAttachments`),
so synced rows and replays stay small. Stable reached the same conclusion
(`uploads.image_data_url`): a private authenticated URL is not something a
model provider can fetch, so the bytes have to travel in the request.

The client downscales to a 1600px longest edge at JPEG 85 before uploading,
matching stable's constants, and decodes at `inSampleSize` first so a 50MP
photo is never fully decoded just to be shrunk.

**Image generation — the first SERVER tool.** Until now every tool was a
device tool: the run parked, the phone answered. That is right for the
calendar and wrong for anything needing the edge. A round's tool calls are now
SPLIT — server tools execute inline and the loop continues, device tools park
as before. Parking for a tool the phone cannot run would hang the run until it
was swept away.

`attachment` is a first-class stream event carrying the R2 key, persisted on
the run and replayed on reconnect, so a generated image survives a restart the
same way text does.

### The trap: flux-2-klein is not a drop-in

The obvious model choice fails. `@cf/black-forest-labs/flux-2-klein-4b`
rejects a plain prompt with `required properties at '/' are 'multipart'` — it
takes a different request shape entirely. The failure surfaces only at call
time, as the model apologising that it cannot generate images, and the model
then retries. `flux-1-schnell` accepts `{ prompt }` and returns
`{ image: base64 }`; verified against the live API. The image model is
overridable through a new `image` slot.

Workers AI image models are also inconsistent about their return shape — some
stream JPEG bytes, others return base64 JSON — so `imageBytes` handles both.

### Verified live, end to end

```
generate   "draw a red bicycle" -> attachment event with an R2 key
fetch      358KB, ffd8ff (JPEG), content-disposition inline, nosniff
ownership  a second live user reading that key -> 404
           no credential -> 401
upload     POST /files -> key returned
vision     sent back with a question -> routing claimed the turn,
           notice emitted, model answered "A red bicycle."
```

That last line is the whole pipeline closing: generated at the edge, stored,
served, re-uploaded, expanded to a data URL, routed to a vision model, and
correctly described. Test users and both R2 objects were removed afterwards.

**Not verified on a device** — the picker, the downscale and the bubble
rendering have only been compiled.

---

## 17. Capabilities come from Cloudflare, not from the model name (2026-09-21)

Reported: llama-4-scout handled an attached image, glm-5.3-flash did not,
"despite being multimodal".

Correct, and the cause was ours. `isVisionModel` matched substrings from a
hand-maintained hint list — `gemma-4`, `llama-4`, `kimi-k2.6`… — and nothing
in it matched `glm-5.3-flash`. So `modelCapabilities` said vision = false,
`flattenForTextModel` stripped the image parts, and the model answered a
question about a picture it was never sent.

**Cloudflare reports this directly.** `env.AI.models()` returns per-model
properties including `vision`, `reasoning` and `context_window`:

| model | CF says | old heuristic |
|---|---|---|
| llama-4-scout | vision = true | matched — worked by luck |
| glm-5.3-flash | vision = true | no match — **broken** |
| glm-5.2 | *(absent)* | correctly false |

Resolution is now **declared → live CF metadata → bundled catalogue →
heuristics**. The heuristics remain only so an un-annotated NON-Cloudflare
model degrades rather than breaks.

This is the same root cause stable fixed once before by removing scattered
name-substring guessing, and it reappeared because the catalogue covers 10 of
the 31 text models Workers AI now serves — so the heuristics were answering
for the other 21.

A second instance of the same bug sat one layer up: `cloudflareView` baked
catalogue capabilities into each model entry, which made them look DECLARED and
therefore outrank live data. llama-4-scout reported the generated 128000
context instead of the real 131000, and would have reported a stale vision flag
forever. CF models are now emitted as bare ids and resolved.

Verified live: glm-5.3-flash and llama-4-scout both answer "Rubber duck" for
the same uploaded image; glm-5.2 still reports vision = false; context windows
now read 1310720 / 131000 / 262144 from Cloudflare rather than the catalogue.

**Also fixed here:** the `image` slot existed on the edge but was never added
to the client, so there was no way to set the image model. It is now the third
row in Routing, with a warning that the flux-2-klein family will not work.

---

## 18. What this is not

This plan does **not** aim for 1:1 endpoint parity with `main`. Roughly 14 of
the 98 routes are dropped or replaced outright, and several more collapse into
the client. The target is feature parity *as experienced*, on an architecture
where voice, assistant role, and offline use are possible at all.
