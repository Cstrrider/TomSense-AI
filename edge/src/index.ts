/**
 * TomSense edge control plane — Worker entry point.
 *
 * Stateless by design: every request authenticates, does its work against D1 /
 * Vectorize / R2 / a Durable Object, and returns. Nothing here holds session
 * state, which is why it can be globally distributed and why home being down
 * doesn't take chat with it.
 */

import type { Env, Principal, ChatMessage } from "./types";
import { authenticate, issueDeviceToken } from "./auth";
import { parseModelStr, resolveProvider, chatCompletionsUrl } from "./providers";
import { streamWithFallback } from "./stream";
import { push, pull, type PushRequest } from "./sync";

export { VoiceSession } from "./do/voice";
export { DetachedRun } from "./do/run";
export { HomeLink } from "./do/homelink";

/**
 * CF Access team domain + application AUD, from config rather than hardcoded.
 *
 * If ACCESS_AUD is unset we FAIL CLOSED — every Access-JWT request is
 * rejected. The tempting alternative (skip the aud check when unconfigured)
 * would mean a token minted for any other application in the account is
 * accepted here, which is a real privilege-escalation path, not a hypothetical.
 */
function accessConfig(env: Env): { teamDomain: string; aud: string } | null {
  if (!env.ACCESS_TEAM_DOMAIN || !env.ACCESS_AUD) return null;
  return { teamDomain: env.ACCESS_TEAM_DOMAIN, aud: env.ACCESS_AUD };
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;

    // The home agent authenticates with its own shared secret, not a user
    // credential — it acts on behalf of the deployment, not a person.
    if (path === "/homelink/agent") {
      const id = env.HOMELINK.idFromName("default");
      return env.HOMELINK.get(id).fetch(req);
    }

    // Health check is the ONLY unauthenticated route, and it deliberately
    // reveals nothing beyond liveness and whether Access is configured.
    if (path === "/health") {
      return json({ ok: true, accessConfigured: accessConfig(env) !== null });
    }

    const cfg = accessConfig(env);
    if (!cfg) {
      return json(
        { error: "ACCESS_TEAM_DOMAIN / ACCESS_AUD not configured on this Worker" },
        503,
      );
    }

    const who = await authenticate(req, env, cfg);
    if (!who) return json({ error: "authentication required" }, 401);

    try {
      if (path === "/chat" && req.method === "POST") return await chat(req, env, who);
      if (path === "/sync/push" && req.method === "POST") return await syncPush(req, env, who);
      if (path === "/sync/pull" && req.method === "GET") return await syncPull(url, env, who);
      if (path === "/auth/device" && req.method === "POST") return await registerDevice(req, env, who);
      if (path === "/voice") return await voice(req, env, who);
      if (path.startsWith("/run/")) return await run(req, env, who, path);
      if (path === "/home/tools") return await homeTools(env);
      if (path === "/home/call" && req.method === "POST") return await homeCall(req, env);
      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: (e as Error).message }, 500);
    }
  },

  async scheduled(_event: ScheduledController, _env: Env, _ctx: ExecutionContext): Promise<void> {
    // Cron: scheduled prompts + memory decay (spec §9). Not yet implemented —
    // M7. Left as an explicit stub so the trigger binding is wired and the gap
    // is visible rather than silently missing.
  },
} satisfies ExportedHandler<Env>;

/** POST /chat — streams the text/reasoning/heartbeat/done contract as SSE. */
async function chat(req: Request, env: Env, who: Principal): Promise<Response> {
  const body = (await req.json()) as {
    conversationId: string;
    messages: ChatMessage[];
    model?: string;
    tools?: unknown[];
  };

  const { providerId, modelId } = parseModelStr(body.model, env.TIER2_MODEL);
  const provider = await resolveProvider(env, who.userId, providerId);
  if (!provider) return json({ error: `unknown provider ${providerId}` }, 400);

  // Tier-1 is the fallback for a stalled tier-2/3 model: cheap, fast, and
  // almost never the thing that stalls.
  const fb = parseModelStr(env.TIER1_MODEL, env.TIER1_MODEL);
  const fbProvider = await resolveProvider(env, who.userId, fb.providerId);

  const events = streamWithFallback(
    {
      provider,
      modelId,
      messages: body.messages,
      tools: body.tools,
      fallback: fbProvider ? { provider: fbProvider, modelId: fb.modelId } : undefined,
    },
    (p) => chatCompletionsUrl(p),
  );

  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    async start(controller) {
      try {
        for await (const ev of events) {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(ev)}\n\n`));
          if (ev.type === "done") {
            await recordUsage(env, who, providerId, modelId, ev.usage);
          }
        }
      } catch (e) {
        const msg = { type: "text", text: `\n\n[edge error: ${(e as Error).message}]` };
        controller.enqueue(encoder.encode(`data: ${JSON.stringify(msg)}\n\n`));
      } finally {
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      }
    },
  });

  return new Response(stream, {
    headers: {
      "content-type": "text/event-stream",
      "cache-control": "no-cache",
      connection: "keep-alive",
    },
  });
}

async function recordUsage(
  env: Env,
  who: Principal,
  providerId: string,
  modelId: string,
  usage: { in: number; out: number; cache_read: number; cache_write: number },
): Promise<void> {
  const day = new Date().toISOString().slice(0, 10);
  await env.DB.prepare(
    `INSERT INTO usage_daily
       (user_id, day, provider_id, model_id, tokens_in, tokens_out, cache_read, cache_write, requests)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
     ON CONFLICT(user_id, day, provider_id, model_id) DO UPDATE SET
       tokens_in   = tokens_in   + excluded.tokens_in,
       tokens_out  = tokens_out  + excluded.tokens_out,
       cache_read  = cache_read  + excluded.cache_read,
       cache_write = cache_write + excluded.cache_write,
       requests    = requests    + 1`,
  )
    .bind(who.userId, day, providerId, modelId, usage.in, usage.out, usage.cache_read, usage.cache_write)
    .run();
}

async function syncPush(req: Request, env: Env, who: Principal): Promise<Response> {
  const body = (await req.json()) as PushRequest;
  return json(await push(env, who, body));
}

async function syncPull(url: URL, env: Env, who: Principal): Promise<Response> {
  const cursor = Number(url.searchParams.get("cursor") ?? 0);
  return json(await pull(env, who, Number.isFinite(cursor) ? cursor : 0));
}

async function registerDevice(req: Request, env: Env, who: Principal): Promise<Response> {
  const body = (await req.json()) as { name: string; platform: string };
  const deviceId = crypto.randomUUID();
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO devices (id, user_id, name, platform, created_at, last_seen_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  )
    .bind(deviceId, who.userId, body.name, body.platform, now, now)
    .run();

  // Returned exactly once — only the hash is retained.
  const token = await issueDeviceToken(env, who.userId, deviceId);
  return json({ deviceId, token });
}

async function voice(req: Request, env: Env, who: Principal): Promise<Response> {
  // One session per device, so a second device doesn't hijack an active call.
  const id = env.VOICE.idFromName(`${who.userId}:${who.deviceId}`);
  // Forward the authenticated principal. The DO needs it to resolve BYO-key
  // providers, and must not take it from the client's own payload.
  const fwd = new Request(req);
  fwd.headers.set("x-tomsense-user", who.userId);
  return env.VOICE.get(id).fetch(fwd);
}

async function run(req: Request, env: Env, who: Principal, path: string): Promise<Response> {
  const runId = path.split("/")[2];
  if (!runId) return json({ error: "missing run id" }, 400);

  // Verify ownership before addressing the DO — run ids are guessable enough
  // that reaching one directly should not be sufficient to read it.
  const owned = await env.DB.prepare(`SELECT 1 FROM runs WHERE id = ? AND user_id = ?`)
    .bind(runId, who.userId)
    .first();
  if (!owned) return json({ error: "not found" }, 404);

  return env.RUN.get(env.RUN.idFromName(runId)).fetch(req);
}

async function homeTools(env: Env): Promise<Response> {
  const id = env.HOMELINK.idFromName("default");
  return env.HOMELINK.get(id).fetch(new Request("https://do/tools"));
}

async function homeCall(req: Request, env: Env): Promise<Response> {
  const id = env.HOMELINK.idFromName("default");
  return env.HOMELINK.get(id).fetch(
    new Request("https://do/call", { method: "POST", body: await req.text() }),
  );
}
