# beta/edge-native — session handoff

Last worked: **2026-09-19**. Branch `beta/edge-native`, 4 commits ahead of `main`.

Read `EDGE-NATIVE-SPEC.md` first for the *why*. This file is only the state of
play and what to do next.

---

## Where things stand

| Milestone | State |
|---|---|
| M0 spec | done — `docs/EDGE-NATIVE-SPEC.md` |
| M1 edge router + D1 + auth | done, **deployed** |
| M2 duplex voice DO | code complete, **never executed** |
| M3 sync protocol | done both halves (edge + client) |
| M4 client shells | Android APK + desktop both build |
| M5 assistant role / wake word / real voice | **not started** |
| M6 home LAN agent | code complete, **not deployed** |
| M7 memory layers, cron | stubs only |
| M8 code mode on Containers | not started |

### Deployed (live)

- Worker `tomsense-edge` → `https://tomsense-edge.tdisarro.workers.dev`
- D1 `tomsense`, id `dab300a7-1f71-4eef-ac8a-cdb91a8b8e7a`, migration `0001` applied
- R2 `tomsense-files`
- DOs bound: `VoiceSession`, `DetachedRun`, `HomeLink`
- Cron `*/15` registered (handler is an intentional stub)

### Verified builds

```
edge/       npx tsc --noEmit                  clean
homeagent/  npx tsc --noEmit                  clean
client/     :androidApp:assembleDebug         APK, ~74 MB debug
client/     :desktopApp:compileKotlin         clean
```

---

## BLOCKERS — both need the owner

### 1. `ACCESS_AUD` is empty → the Worker rejects everything

`/health` returns `{"ok":true,"accessConfigured":false}`; every other route
returns **503**. This is deliberate (see `accessConfig()` in `edge/src/index.ts`):
if the aud check were skipped when unconfigured, an Access JWT minted for any
other application in the account would authenticate here.

To fix: Zero Trust → Access → Applications → the app fronting this Worker →
copy **Application Audience (AUD) Tag**, then either set `ACCESS_AUD` in
`edge/wrangler.toml` and redeploy, or `wrangler secret put ACCESS_AUD`.

The deploy API token has **no Access:Read**, so this cannot be fetched
programmatically with current credentials.

### 2. Vectorize permission missing

`/vectorize/v2/indexes` returns **403** with the token in
`/workspace/aistack/.env`. This is the *same* token used for news-worker —
news-worker simply never needed Vectorize (it stores embeddings as D1 BLOBs).
Needs **Account · Vectorize · Edit** added in the dashboard.

Only blocks M7. The binding is commented out in `wrangler.toml` and nothing
references `env.VECTORS`.

---

## Secrets

`edge/.secrets/deployed.env` is **gitignored** and holds the plaintext of
`KEY_ENC_SECRET` and `HOME_AGENT_TOKEN`. Cloudflare secrets are write-only, so
that file is the only readable copy.

**If the container resets and that file is lost, nothing breaks — re-roll both.**
That is true *right now* specifically because:

- `KEY_ENC_SECRET` wraps provider API keys at rest in D1, and the `providers`
  table is **empty**. Once real BYO keys are stored, losing this secret makes
  them permanently undecryptable — at that point it must be backed up properly.
- `HOME_AGENT_TOKEN` is only consumed by the home agent, which is not deployed
  yet. Re-roll with `wrangler secret put` and set the same value in the agent's
  environment.

---

## Next session — suggested order

1. **Set `ACCESS_AUD`, redeploy, and verify auth end-to-end.** Until this is
   done nothing can be exercised against the live Worker. Confirm a forged
   `Cf-Access-Authenticated-User-Email` header returns **401** (today it
   returns 503 for the unrelated reason above, so the test is not yet
   meaningful).
2. **Prove the voice path (M2/M5).** This is still the highest-risk assumption
   in the design and it has never run. Wire `VoiceService` to the `/voice`
   WebSocket, stream mic PCM, and measure *perceived* latency on a real phone
   against the ~500 ms budget in spec §6. If it can't get near that, revisit
   the premise before building more on top of it.
3. Deploy the home agent (M6) — needs `EDGE_URL`, `HOME_AGENT_TOKEN`, and
   `DOCKER_GID` from the host.
4. Then M5 proper: assistant role end-to-end, wake word, QS tile action.

---

## Recovering this branch after a container reset

`/workspace/tomsense-ai` is container-local, so nothing here survives a reset.
The branch itself does — it is **pushed to the public remote**:

```bash
git clone -b beta/edge-native https://github.com/Cstrrider/TomSense-AI.git
```

That is the primary recovery path. Note what going public disclosed, since it
is not on `main` and is worth remembering before adding more: the CF Access
team domain, the Worker URL, the D1 database id, and the home-agent container
allow-list. No credentials — the D1 id is inert without the API token, and both
endpoints fail closed. **Keep it that way: never commit anything from
`edge/.secrets/`.**

A verified git bundle of the full history also lives in R2. It is now a
*secondary* copy rather than the only one, kept because it sits outside the
Cloudflare control plane and is the concentration hedge spec §11 argues for:

```
bucket tomsense-files
key    backups/tomsense-edge-native-20260919.bundle
```

Restore:

```bash
set -a && . /workspace/aistack/.env && set +a
export CLOUDFLARE_API_TOKEN="$CF_API_TOKEN" CLOUDFLARE_ACCOUNT_ID="$CF_ACCOUNT_ID"
npx wrangler r2 object get \
  tomsense-files/backups/tomsense-edge-native-20260919.bundle \
  --file /tmp/ts.bundle --remote
git clone --branch beta/edge-native /tmp/ts.bundle tomsense-ai
```

This round trip was tested on 2026-09-19: re-downloaded, sha256 matched, cloned
clean. The bundle is only as current as the last upload, so prefer the git
clone above unless you specifically want an off-Cloudflare copy.

One credential limit worth knowing before planning anything around it: the PAT
in `~/.git-credentials` is a **fine-grained token that cannot create
repositories** (`Resource not accessible by personal access token`). It can
push to existing ones. Creating a new repo needs the owner, or
*Administration: read & write* added to the token.

## Environment notes for a fresh container

- **`/workspace/tomsense-ai` is container-local.** The host copy is canonical
  for the deployed stack; this branch lives in git — re-clone from `origin`
  (see the recovery section above).
- Gradle: there is **no wrapper jar**. Use the cached distribution:
  ```
  source /home/node/android-toolchain/env.sh
  GRADLE_BIN=$(ls -d ~/.gradle/wrapper/dists/gradle-8.11.1-all/*/gradle-8.11.1/bin/gradle | head -1)
  ```
  Offline mode fails — the KMP/Compose plugins are not in the local cache.
- Cloudflare creds: `/workspace/aistack/.env` → export as
  `CLOUDFLARE_API_TOKEN` / `CLOUDFLARE_ACCOUNT_ID`.
- Inline `git` commands trip a harness bug; write the git sequence to a
  `/tmp/*.sh` script and run that instead.
