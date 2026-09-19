# TomSense Edge-Native — architecture spec

Status: **beta branch `beta/edge-native`**, in development.
Supersedes: nothing yet. `main` keeps working throughout; this branch is additive
until the client cutover.

Origin: design conversation 2026-09-13 ("if you were to start from scratch of
TomSense…"). This document is that answer, made buildable.

---

## 1. Why re-shape at all

The feature list is not the problem — most of it is already built and shipped on
`main`. What blocks "this replaces Gemini on my phone" is architectural, and it
reduces to one sentence:

> **The server renders the app.**

A Capacitor WebView pointed at `server.url` cannot be a `VoiceInteractionService`,
cannot own the audio pipeline, and shows a connection error when the tunnel is
down. Three consequences, all fatal to the goal:

1. **No assistant role.** The big three are *there* before you decide to ask —
   power-button hold, wake word, lock screen, screen read.
2. **No sub-second voice.** Whisper → LLM → Piper round-trips in 2–5s. That reads
   as "demo", not "assistant".
3. **It dies with the house.** Home internet, power, or a bad `compose up` takes
   the assistant with it.

## 2. The one change

Invert the client, and split the backend by **uptime requirement** rather than by
function.

```
┌─ Native client ─────────────┐   local SQLite = source of truth.
│ Android: assistant role,    │   Renders instantly, works in airplane
│ voice, on-device model      │   mode, syncs when it can.
│ Desktop: hotkey overlay     │
└─────────────┬───────────────┘
              │
┌─────────────▼───────────────┐   Stateless model router, BYO-key fanout,
│ Control + data plane (edge) │   auth, streaming, D1/Vectorize/R2, DOs.
│ always up, global, cheap    │   Home can be on fire; chat still works.
└─────────────┬───────────────┘
              │ outbound WS only
┌─────────────▼───────────────┐   ONE container. Narrow MCP tool surface
│ Home LAN agent              │   for things only reachable on 192.168.x.
│ 5% of today's footprint     │   No inbound ports. No tunnel for the app.
└─────────────────────────────┘
```

Roughly **95% edge, 5% home**. Ten services get deleted, not ported.

## 3. Service mapping

| Home today | Edge | Verdict |
|---|---|---|
| Postgres | **D1** (10 GB/db, 1 TB/acct) | Fits — as a *sync target*, see §5 |
| Qdrant | **Vectorize** (20M vec, 1536-dim) | Fine at personal scale |
| `/shared` | **R2** | Trivial |
| `tomsense-sandbox` | **Containers** (4 vCPU / 12 GiB / 20 GB) | Fits, with a rewrite — see §8 |
| Whisper | **Workers AI** Deepgram `nova-3` / `flux` | Better than home |
| Piper | **Workers AI** Deepgram `aura-2` | Better than home |
| Detached runs (`run_id`) | **Durable Objects** + **Workflows** | Better than home |
| Scheduled prompts | **Cron Triggers** | Fits |
| nginx + SvelteKit | **Workers Static Assets** | Trivial |
| SearXNG | Brave / Tavily / Exa | Replace, don't port |
| Jupyter | Container + DO session | Rewrite |
| Docker proxy, backrest, LAN | **Home agent** (§7) | Cannot move |

D1 fits *specifically because* of the local-first decision. Its weaknesses —
single-threaded per database, sequential queries, one home region — would hurt on
a hot read path. It isn't one. The phone reads local SQLite; D1 takes batched
writes. 10 GB is far more than a lifetime of chat text.

## 4. Model tiers

Keep the provider-agnostic registry from `rearch/provider-agnostic` wholesale —
including `capabilities.py`'s declared-capability resolution, which is the right
design and must survive the port. What's missing is the bottom rung.

| Tier | Runs | For |
|---|---|---|
| **0 — on-device** | Gemma 4 nano via MediaPipe LLM Inference | timers, flashlight, volume, next meeting. Never touches network. |
| 1 — cheap | Gemma / Qwen on Workers AI | classification, titles, memory extraction |
| 2 — mid | GLM 5.2 (DeepInfra), MiniMax M3 | default chat |
| 3 — frontier | Opus 4.8 / GPT-5.x / Gemini 3 Pro, BYO key | hard reasoning, code |

Tier 0 is the one that makes it *feel* instant, and it's most assistant
invocations by volume. Route by task class with an always-available explicit
override. Prompt caching on every tier that supports it — `cache_read` is already
instrumented in `usage_daily` and that instrumentation carries over.

**Event contract is preserved from `providers.stream_round`:** the router emits
`text` / `reasoning` / `heartbeat` / `done` events, keeps stall detection, and
keeps silent-model fallback. Clients written against the current protocol stay
close to working.

## 5. Sync protocol

Local SQLite is authoritative. D1 is a convergence target, not a master.

- Every row carries `(id, updated_at_ms, device_id, lamport)`.
- Writes push in batches; conflicts resolve **last-writer-wins on `lamport`,
  tie-broken by `device_id`** — message bodies are append-only in practice, so
  real conflicts are confined to edits, titles, and settings.
- Pulls are cursor-based on a monotonic server sequence, not wall-clock.
- **Tombstones**, not deletes, so a delete on one device propagates.
- Detached runs keep working: a run lives in a DO, `run_id` reconnects, and the
  transcript lands in D1 whether or not the phone was awake. This is strictly
  better than today's in-process implementation.

## 6. Voice — its own path, not "chat with an audio attachment"

**Correction to the first version of this design:** I originally claimed
sub-second voice required on-device STT plus a paid realtime vendor. That is
wrong as of now. Workers AI hosts Deepgram **`flux`** — built for voice agents,
so it does endpointing and turn-taking rather than bare transcription — and
**`aura-2`** streaming TTS. The whole duplex path runs on one provider at the
edge.

```
mic ──► flux (streaming STT, endpointing)
          │  partial transcript
          ├──► speculative generation starts  ◄── discard if final differs
          │
        final ──► router ──► sentence-chunked ──► aura-2 ──► speaker
                                    ▲
        VAD fires ──── barge-in ────┘  (kill TTS immediately)
```

- Wake word on-device (Porcupine / openWakeWord).
- Speculative execution on the partial buys ~500ms.
- On-device STT stays — as the **offline** path, no longer as the latency fix.
- A true speech-to-speech realtime model (Gemini Live, OpenAI Realtime) is one
  *tier* of this path for when quality matters, not the whole path.

This is the **highest-risk assumption in the design**. It gets built and measured
before the client cutover is committed to.

## 7. Home agent — the residual 5%

An edge data plane cannot see `192.168.x`. Today that matters for
`tomsense-docker-proxy`, backrest, KitchenSync, aistack, and the host-mounted
share. If the assistant should ever answer *"restart the backend"* or *"how's the
backup job"*, something must live at home.

The answer is **not** keeping the stack. It's one small container that:

- dials **outbound** to a Durable Object over WebSocket (no inbound ports, no
  tunnel for the app itself),
- exposes a **narrow, allow-listed** MCP tool surface,
- is the only home dependency, and its absence degrades exactly those tools and
  nothing else.

## 8. What genuinely doesn't move

1. **LAN reach** — §7.
2. **Persistent workspaces.** Containers sleep; no filesystem survives across
   days. Code mode becomes rehydrate-from-R2 → exec → write back. Workable, but
   it contradicts `tomsense-sandbox`'s assumption of a living box. Budget a
   rewrite, not a lift.
3. **Long-lived daemons.** SearXNG and Qdrant-as-a-service fight the
   request-scoped model. Replace rather than port.

## 9. Memory

Three layers, and the third is where you beat them:

1. Small **user-editable profile**, always in context.
2. **Extracted facts** with dedup and decay.
3. **Semantic search** over full history in Vectorize.

The differentiator is that **memory writes are visible and editable**. ChatGPT's
opaque memory is its single most annoying property, and fixing it is nearly free
here.

## 10. The privacy fork — decide deliberately

Self-hosting was arguably part of the point. D1/Vectorize/R2 puts conversation
history on Cloudflare's disks under Cloudflare's keys.

Because local SQLite is the source of truth, this *can* be closed: device key
encrypts bodies, D1 stores ciphertext, Vectorize stores vectors + encrypted
metadata, search returns IDs and the device decrypts. Coherent — and it needs an
on-device embedding model, which ships anyway alongside the on-device LLM.

But it is a **genuine fork, not a free win**. If the Worker can't read content,
every server-side feature that reads history dies: cron digests, scheduled
summarization, server-side titles, memory extraction.

**Decision: plaintext at the edge, with E2EE as a per-project toggle.** Sensitive
threads opt in and knowingly lose automation.

## 11. Cost and concentration

Workers Paid is $5/mo + metered. For one user, D1/Vectorize/R2 round to noise.
**Containers are the variable**, billed while running — heavy code mode is what
moves the bill. Call it $5–30/mo, which frontier tokens will dwarf regardless.

The sharper risk is **concentration**: CF Access is already the auth, so one
account problem takes auth, data, vectors, files, and compute simultaneously.
Mitigation: point backrest at periodic D1 exports plus an R2 mirror, so a
restorable copy exists outside Cloudflare.

## 12. Honest ceiling

~90–95%, winning outright on privacy, extensibility, and cost control. Two things
will not be matched:

- Gemini's **privileged system actions** on stock Android — some Assistant APIs
  simply aren't open to third parties.
- Advanced Voice **quality** without paying realtime-API rates.

Also stated plainly: the `claude -p` Pro-subscription bridge in ClaudeAssist is
ToS-gray and fragile. Fine as a personal experiment; **not a foundation** for
this. Budget real API spend on tier 3; routing traffic downward keeps it sane,
but it will not be $20/mo flat.

## 13. Build order

Voice and assistant role come *before* features — they are the reason you'd reach
for this instead of Gemini.

| # | Milestone | Proves |
|---|---|---|
| M0 | Spec (this doc) | — |
| M1 | Edge router + D1 schema + auth | BYO-key fanout and streaming work at the edge |
| M2 | **DO duplex voice** | the riskiest assumption, measured |
| M3 | Sync protocol + local store | local-first is real, airplane mode works |
| M4 | KMP client shell (Android + desktop) | parity with today's UI |
| M5 | Assistant role, wake word, QS tile, widget | the integration moat |
| M6 | Home LAN agent | the residual 5% |
| M7 | Memory layers, projects, agentic | depth |
| M8 | Code mode on Containers | the rewrite |

## 14. Layout on this branch

```
edge/            Cloudflare Worker — control + data plane
  src/index.ts     router entry, SSE streaming
  src/auth.ts      CF Access / device tokens
  src/providers/   BYO-key adapters (port of backend/app/providers.py)
  src/do/          VoiceSession, DetachedRun, HomeLink
  migrations/      D1 schema
client/          Kotlin Multiplatform
  shared/          SQLDelight store, Ktor transport, sync engine
  androidApp/      assistant role, voice, widget, tile
  desktopApp/      hotkey overlay, tray
homeagent/       the single residual container
docs/            this spec
```

Nothing under `backend/` or `frontend/` is deleted on this branch. The existing
stack stays runnable until M4 lands and the cutover is an explicit decision.
