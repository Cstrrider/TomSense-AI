/**
 * Server tools ported from stable (EDGE-NATIVE-MIGRATION §4b): memory,
 * document search, weather, deep research, reverse image lookup, song
 * identification and artifacts.
 *
 * Kept apart from server_tools.ts (image generation) only for size; they
 * register into the same registry and follow the same contract.
 */

import type { Env, ToolCall } from "./types";
import type { ServerToolResult, ToolContext } from "./server_tools";
import { addMemory, forgetMemory } from "./memory";
import { searchDocs } from "./rag";
import { getSecret } from "./secrets";
import { getPrefs } from "./prefs";
import { runTaskModel } from "./task_model";

type Args = Record<string, unknown>;
const str = (a: Args, k: string) => (typeof a[k] === "string" ? (a[k] as string).trim() : "");

function fn(name: string, description: string, properties: Record<string, unknown>, required: string[] = []) {
  return { type: "function", function: { name, description, parameters: { type: "object", properties, required } } };
}

// ─── memory ────────────────────────────────────────────────────────────────

export const remember = {
  schema: fn(
    "remember",
    "Save a lasting fact about the user so future conversations know it — a preference, circumstance, " +
      "relationship, or anything they ask you to remember. One short third-person sentence per call.",
    { fact: { type: "string", description: "e.g. \"Is allergic to peanuts.\"" } },
    ["fact"],
  ),
  async run(env: Env, userId: string, args: Args): Promise<ServerToolResult> {
    const fact = str(args, "fact");
    if (!fact) return { content: "Nothing to remember." };
    const m = await addMemory(env, userId, fact, { confidence: 1 });
    return { content: m ? `Remembered: ${m.text}` : "Already known." };
  },
};

export const forget = {
  schema: fn(
    "forget",
    "Delete a remembered fact when the user asks you to forget something or says it is no longer true.",
    { what: { type: "string", description: "The fact, or words from it." } },
    ["what"],
  ),
  async run(env: Env, userId: string, args: Args): Promise<ServerToolResult> {
    const gone = await forgetMemory(env, userId, str(args, "what"));
    return { content: gone.length ? `Forgot: ${gone.join("; ")}` : "No matching memory found." };
  },
};

// ─── documents ─────────────────────────────────────────────────────────────

export const searchDocsTool = {
  schema: fn(
    "search_docs",
    "Search the user's uploaded documents (PDFs, notes, manuals…). Returns the most relevant passages with " +
      "their document names. Use before answering questions about their files.",
    { query: { type: "string" } },
    ["query"],
  ),
  async run(env: Env, userId: string, args: Args): Promise<ServerToolResult> {
    const hits = await searchDocs(env, userId, str(args, "query"), 6);
    if (!hits.length) return { content: "No matching passages in the user's documents." };
    return {
      content: hits.map((h, i) => `[${i + 1}] ${h.doc}\n${h.text}`).join("\n\n---\n\n").slice(0, 16_000),
    };
  },
};

// ─── weather ───────────────────────────────────────────────────────────────

const WMO: Record<number, string> = {
  0: "clear", 1: "mostly clear", 2: "partly cloudy", 3: "overcast", 45: "fog", 48: "fog",
  51: "drizzle", 53: "drizzle", 55: "drizzle", 61: "rain", 63: "rain", 65: "heavy rain",
  71: "snow", 73: "snow", 75: "heavy snow", 80: "showers", 81: "showers", 82: "heavy showers",
  95: "thunderstorms", 96: "thunderstorms with hail", 99: "thunderstorms with hail",
};

export const getWeather = {
  schema: fn(
    "get_weather",
    "Current conditions and a 3-day forecast for a place (Open-Meteo). Give a place name, or latitude and " +
      "longitude (from get_location for \"here\"). Choose units from the user's country: fahrenheit for the " +
      "US, celsius almost everywhere else.",
    {
      place: { type: "string", description: "City or place name" },
      latitude: { type: "number" },
      longitude: { type: "number" },
      units: { type: "string", enum: ["celsius", "fahrenheit"] },
    },
  ),
  async run(_env: Env, _userId: string, args: Args): Promise<ServerToolResult> {
    let lat = Number(args["latitude"]);
    let lon = Number(args["longitude"]);
    let label = str(args, "place");
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
      if (!label) return { content: "Need a place name or coordinates." };
      const g = (await (await fetch(
        `https://geocoding-api.open-meteo.com/v1/search?count=1&name=${encodeURIComponent(label)}`,
      )).json()) as { results?: { latitude: number; longitude: number; name: string; admin1?: string; country?: string }[] };
      const hit = g.results?.[0];
      if (!hit) return { content: `Couldn't find a place called "${label}".` };
      lat = hit.latitude;
      lon = hit.longitude;
      label = [hit.name, hit.admin1, hit.country].filter(Boolean).join(", ");
    }
    const f = str(args, "units") === "fahrenheit";
    const u = new URL("https://api.open-meteo.com/v1/forecast");
    u.searchParams.set("latitude", String(lat));
    u.searchParams.set("longitude", String(lon));
    u.searchParams.set("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m");
    u.searchParams.set("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max");
    u.searchParams.set("timezone", "auto");
    u.searchParams.set("forecast_days", "3");
    if (f) {
      u.searchParams.set("temperature_unit", "fahrenheit");
      u.searchParams.set("wind_speed_unit", "mph");
    }
    const w = (await (await fetch(u)).json()) as {
      current?: Record<string, number>;
      daily?: Record<string, (number | string)[]>;
    };
    const deg = f ? "°F" : "°C";
    const c = w.current ?? {};
    const lines = [
      `Weather for ${label || `${lat.toFixed(2)}, ${lon.toFixed(2)}`}:`,
      `Now: ${c["temperature_2m"]}${deg} (feels ${c["apparent_temperature"]}${deg}), ${WMO[c["weather_code"] ?? -1] ?? "—"}, ` +
        `wind ${c["wind_speed_10m"]} ${f ? "mph" : "km/h"}, humidity ${c["relative_humidity_2m"]}%`,
    ];
    const d = w.daily ?? {};
    (d["time"] ?? []).forEach((day, i) => {
      lines.push(
        `${day}: ${WMO[Number(d["weather_code"]?.[i])] ?? "—"}, ${d["temperature_2m_min"]?.[i]}–${d["temperature_2m_max"]?.[i]}${deg}, ` +
          `rain ${d["precipitation_probability_max"]?.[i]}%, UV ${d["uv_index_max"]?.[i]}, ` +
          `sunrise ${String(d["sunrise"]?.[i]).slice(11)}, sunset ${String(d["sunset"]?.[i]).slice(11)}`,
      );
    });
    return { content: lines.join("\n") };
  },
};

// ─── deep research ─────────────────────────────────────────────────────────

export const deepResearch = {
  schema: fn(
    "deep_research",
    "Research a question thoroughly: runs several web searches, reads the best pages in full, and returns " +
      "sourced excerpts to write a well-cited answer from. Slower than web_search — use for questions that " +
      "need several sources or depth, not single facts.",
    { question: { type: "string" } },
    ["question"],
  ),
  async run(env: Env, userId: string, args: Args, ctx?: ToolContext): Promise<ServerToolResult> {
    const question = str(args, "question");
    if (!ctx?.callHome) {
      return { content: "Web access is unavailable right now (the home agent is offline).", error: "deep_research: home agent offline" };
    }
    const prefs = await getPrefs(env, userId);
    const planned = await runTaskModel(env, { userId, email: "", deviceId: "edge" }, {
      purpose: "summary",
      prompt:
        "Write 3 different web search queries that together would answer this question well. " +
        "One per line, no numbering, no commentary.\n\nQuestion: " + question,
      slots: prefs.tool_models,
      maxTokens: 80,
    });
    const queries = [question, ...(planned ?? "").split("\n").map((q) => q.replace(/^[-*\d.)\s"]+|"$/g, "").trim())]
      .filter((q) => q.length > 3)
      .slice(0, 4);

    const seen = new Map<string, { title: string; snippet: string }>();
    for (const q of queries) {
      const r = await ctx.callHome("web_search", { query: q });
      if (r.error) continue;
      try {
        const results = JSON.parse(r.content) as { title: string; url: string; snippet: string }[];
        for (const x of results.slice(0, 5)) if (!seen.has(x.url)) seen.set(x.url, { title: x.title, snippet: x.snippet });
      } catch {
        // skip unparsable result sets
      }
    }
    if (!seen.size) return { content: "Web search returned nothing for this question.", error: "deep_research: no search results" };

    // Read the top pages in full — snippets are what makes shallow research shallow.
    const urls = [...seen.keys()].slice(0, 6);
    const pages = await Promise.all(
      urls.map(async (url) => {
        const r = await ctx.callHome!("fetch_page", { url });
        if (r.error) return null;
        try {
          const p = JSON.parse(r.content) as { text?: string };
          return { url, title: seen.get(url)!.title, text: (p.text ?? "").slice(0, 3_500) };
        } catch {
          return null;
        }
      }),
    );
    const read = pages.filter((p): p is { url: string; title: string; text: string } => !!p && p.text.length > 200);
    const unread = urls.filter((u) => !read.some((p) => p.url === u)).map((u) => ({ url: u, ...seen.get(u)! }));

    const out = [
      `Research bundle for: ${question}`,
      `Queries run: ${queries.join(" | ")}`,
      "Write a thorough answer from these sources, citing them inline as [1], [2]… and listing the URLs at the end. " +
        "Say where sources disagree or where the evidence is thin.",
      ...read.map((p, i) => `\n[${i + 1}] ${p.title}\n${p.url}\n${p.text}`),
      ...unread.map((p, i) => `\n[${read.length + i + 1}] ${p.title} (snippet only)\n${p.url}\n${p.snippet}`),
    ];
    return { content: out.join("\n").slice(0, 26_000) };
  },
};

// ─── reverse image lookup ──────────────────────────────────────────────────

function b64(bytes: ArrayBuffer): string {
  let s = "";
  const u = new Uint8Array(bytes);
  for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode(...u.subarray(i, i + 0x8000));
  return btoa(s);
}

export const reverseImageLookup = {
  schema: fn(
    "reverse_image_lookup",
    "Google Lens-style lookup of the user's attached photo: identifies landmarks, products, artworks and " +
      "finds web pages containing the image. Use when the user asks what or where something in a photo is.",
    { hint: { type: "string", description: "Optional: what the user wants identified." } },
  ),
  async run(env: Env, userId: string, _args: Args, ctx?: ToolContext): Promise<ServerToolResult> {
    const key = ctx?.recentImageKeys?.at(-1);
    if (!key) return { content: "Reverse image lookup needs an image — ask the user to attach the photo." };
    const apiKey = await getSecret(env, userId, "GOOGLE_VISION_API_KEY");
    if (!apiKey) {
      return {
        content:
          "Reverse image lookup is not set up. Tell the user: add a Google Cloud Vision API key in " +
          "Settings → API keys (console.cloud.google.com → enable Cloud Vision API → Credentials; 1,000 free " +
          "lookups a month). Meanwhile answer from what you can see.",
      };
    }
    const obj = await env.FILES.get(key);
    if (!obj) return { content: "Couldn't read the attached image." };
    const res = await fetch(`https://vision.googleapis.com/v1/images:annotate?key=${apiKey}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        requests: [{
          image: { content: b64(await obj.arrayBuffer()) },
          features: [{ type: "LANDMARK_DETECTION", maxResults: 5 }, { type: "WEB_DETECTION", maxResults: 10 }],
        }],
      }),
    });
    if (!res.ok) return { content: `Lookup failed: HTTP ${res.status}`, error: `reverse_image_lookup: HTTP ${res.status}` };
    const r = ((await res.json()) as { responses?: Record<string, any>[] }).responses?.[0] ?? {};
    if (r["error"]) return { content: `Lookup failed: ${r["error"].message}`, error: `reverse_image_lookup: ${r["error"].message}` };
    const out = ["Reverse image lookup (Google Cloud Vision):"];
    for (const lm of (r["landmarkAnnotations"] ?? []).slice(0, 5)) {
      const ll = lm.locations?.[0]?.latLng;
      out.push(`LANDMARK: ${lm.description} (confidence ${Number(lm.score ?? 0).toFixed(2)})${ll ? ` at ${ll.latitude?.toFixed(4)}, ${ll.longitude?.toFixed(4)}` : ""}`);
    }
    const web = r["webDetection"] ?? {};
    const guesses = (web.bestGuessLabels ?? []).map((g: { label: string }) => g.label).filter(Boolean);
    if (guesses.length) out.push(`BEST GUESS: ${guesses.join("; ")}`);
    for (const e of (web.webEntities ?? []).filter((e: { description?: string }) => e.description).slice(0, 8)) {
      out.push(`ENTITY: ${e.description} (${Number(e.score ?? 0).toFixed(2)})`);
    }
    for (const p of (web.pagesWithMatchingImages ?? []).slice(0, 6)) out.push(`PAGE: ${p.pageTitle ?? ""} ${p.url}`.trim());
    return { content: out.length > 1 ? out.join("\n") : "No matches found for this image." };
  },
};

// ─── identify song ─────────────────────────────────────────────────────────

export const identifySong = {
  schema: fn(
    "identify_song",
    "Identify the song in the user's attached audio clip (acoustic fingerprinting via AudD).",
    {},
  ),
  async run(env: Env, userId: string, _args: Args, ctx?: ToolContext): Promise<ServerToolResult> {
    let audio: R2ObjectBody | null = null;
    for (const key of [...(ctx?.attachmentKeys ?? [])].reverse()) {
      const obj = await env.FILES.get(key);
      if (obj && (obj.httpMetadata?.contentType ?? "").startsWith("audio/")) {
        audio = obj;
        break;
      }
    }
    if (!audio) return { content: "Song identification needs an audio clip — ask the user to attach 5–15 seconds of the music." };
    const token = await getSecret(env, userId, "AUDD_API_KEY");
    if (!token) {
      return {
        content:
          "Song identification is not set up. Tell the user: add an AudD API token in Settings → API keys " +
          "(get one at audd.io).",
      };
    }
    if (audio.size > 15 * 1024 * 1024) return { content: "That clip is too large — 10 seconds of the song is enough." };
    const form = new FormData();
    form.set("api_token", token);
    form.set("return", "apple_music,spotify");
    form.set("file", new Blob([await audio.arrayBuffer()], { type: audio.httpMetadata?.contentType }), "clip");
    const data = (await (await fetch("https://api.audd.io/", { method: "POST", body: form })).json()) as {
      status?: string;
      error?: { error_message?: string };
      result?: { artist?: string; title?: string; album?: string; release_date?: string; song_link?: string; spotify?: { external_urls?: { spotify?: string } } } | null;
    };
    if (data.status !== "success") {
      const why = data.error?.error_message ?? "unknown error";
      return { content: `Song identification failed: ${why}`, error: `identify_song: ${why}` };
    }
    const r = data.result;
    if (!r) return { content: "No match — the clip may be too short, too quiet, or a live/cover version." };
    return {
      content: [
        `${r.title} — ${r.artist}`,
        r.album ? `Album: ${r.album}` : "",
        r.release_date ? `Released: ${r.release_date}` : "",
        r.song_link ? `Link: ${r.song_link}` : "",
        r.spotify?.external_urls?.spotify ? `Spotify: ${r.spotify.external_urls.spotify}` : "",
      ].filter(Boolean).join("\n"),
    };
  },
};

// ─── artifacts ─────────────────────────────────────────────────────────────

export const createArtifact = {
  schema: fn(
    "create_artifact",
    "Create a standalone document, program, or page the user will want to keep, copy, or iterate on " +
      "(an essay, a script, a README, an HTML page, a plan). The user sees it as a card they can open. " +
      "Prefer this over a long code block for anything over ~30 lines or that will be revised.",
    {
      title: { type: "string" },
      kind: { type: "string", enum: ["markdown", "code", "html", "text"] },
      language: { type: "string", description: "For code: the language, e.g. python" },
      content: { type: "string" },
    },
    ["title", "content"],
  ),
  async run(env: Env, userId: string, args: Args, ctx?: ToolContext): Promise<ServerToolResult> {
    const id = crypto.randomUUID().slice(0, 8);
    const now = Date.now();
    await env.DB.prepare(
      `INSERT INTO artifacts (id, user_id, conv_id, title, kind, language, content, version, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
    )
      .bind(id, userId, ctx?.convId ?? null, str(args, "title") || "Untitled", str(args, "kind") || "markdown",
        str(args, "language") || null, String(args["content"] ?? ""), now, now)
      .run();
    return {
      content: `Created artifact ${id} (v1). The user can open it from the card — don't repeat its content in your reply.`,
      attachmentKey: `artifact:${id}`,
      attachmentMime: "application/x-tomsense-artifact",
    };
  },
};

export const updateArtifact = {
  schema: fn(
    "update_artifact",
    "Revise an existing artifact. Either replace its whole content, or give find/replace edits for small " +
      "changes (cheaper and safer for long documents).",
    {
      id: { type: "string" },
      title: { type: "string" },
      content: { type: "string", description: "Full new content (omit when using edits)" },
      edits: {
        type: "array",
        items: { type: "object", properties: { find: { type: "string" }, replace: { type: "string" } }, required: ["find", "replace"] },
      },
    },
    ["id"],
  ),
  async run(env: Env, userId: string, args: Args): Promise<ServerToolResult> {
    const id = str(args, "id");
    const cur = await env.DB.prepare(`SELECT title, content, version FROM artifacts WHERE id = ? AND user_id = ?`)
      .bind(id, userId)
      .first<{ title: string; content: string; version: number }>();
    if (!cur) return { content: `No artifact ${id}.` };
    let content = typeof args["content"] === "string" ? (args["content"] as string) : cur.content;
    const missed: string[] = [];
    for (const e of (Array.isArray(args["edits"]) ? args["edits"] : []) as { find?: string; replace?: string }[]) {
      if (!e.find) continue;
      if (content.includes(e.find)) content = content.replace(e.find, e.replace ?? "");
      else missed.push(e.find.slice(0, 40));
    }
    const version = cur.version + 1;
    await env.DB.prepare(`UPDATE artifacts SET content = ?, title = ?, version = ?, updated_at = ? WHERE id = ? AND user_id = ?`)
      .bind(content, str(args, "title") || cur.title, version, Date.now(), id, userId)
      .run();
    return {
      content: `Updated artifact ${id} to v${version}.` + (missed.length ? ` These edits did not match and were skipped: ${missed.join(" | ")}` : ""),
      attachmentKey: `artifact:${id}`,
      attachmentMime: "application/x-tomsense-artifact",
    };
  },
};

export const EXTRA_TOOLS = {
  remember,
  forget,
  search_docs: searchDocsTool,
  get_weather: getWeather,
  deep_research: deepResearch,
  reverse_image_lookup: reverseImageLookup,
  identify_song: identifySong,
  create_artifact: createArtifact,
  update_artifact: updateArtifact,
};

export type { ToolCall };
