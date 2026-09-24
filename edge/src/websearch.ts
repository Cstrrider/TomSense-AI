/**
 * Web search run by the EDGE itself — no home server in the path.
 *
 * Sources, all reachable from Cloudflare's network without a key (probed
 * 2026-09-24):
 *
 *  - Bing's RSS output — the one general web engine that still answers a
 *    Worker. Google, DuckDuckGo, Startpage and Mojeek block Cloudflare IPs;
 *    Brave answered once and then CAPTCHA'd them (even a real browser).
 *    Bing's RSS terms allow personal, non-commercial use only.
 *  - Wikipedia's search API — reliable facts, never blocked.
 *  - Hacker News (Algolia) — tech discussion; only kept when titles match.
 *  - arXiv and GitHub — only when the query is plainly about papers or code,
 *    so they don't crowd general answers.
 *
 * Results are merged by reciprocal-rank fusion: a page two sources agree on
 * outranks one that only one source ranked highly. Each source has its own
 * timeout and a failure just drops that source, so one slow API never
 * sinks the whole search.
 *
 * This is the TEST path selected by WEB_SEARCH_SOURCE = "edge". "home" keeps
 * the home agent's SearXNG as before.
 */

export interface SearchHit {
  title: string;
  url: string;
  snippet: string;
  sources: string[];
}

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36";
const API_UA = "TomSense/1.0 (https://github.com/Cstrrider/TomSense-AI)";

function decode(s: string): string {
  return s
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/<[^>]+>/g, "")
    .replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, "\"").replace(/&#39;|&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/\s+/g, " ")
    .trim();
}

async function get(url: string, ua: string, ms = 7_000): Promise<Response> {
  return fetch(url, {
    headers: { "user-agent": ua, accept: "application/json, application/rss+xml, text/xml, */*", "accept-language": "en-US,en;q=0.9" },
    signal: AbortSignal.timeout(ms),
  });
}

type Raw = { title: string; url: string; snippet: string };

async function bing(q: string): Promise<Raw[]> {
  const res = await get(`https://www.bing.com/search?format=rss&count=10&q=${encodeURIComponent(q)}`, BROWSER_UA);
  if (!res.ok) throw new Error(`bing ${res.status}`);
  const xml = await res.text();
  const out: Raw[] = [];
  for (const m of xml.matchAll(/<item>([\s\S]*?)<\/item>/g)) {
    const item = m[1] ?? "";
    const pick = (tag: string) => decode(item.match(new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`))?.[1] ?? "");
    const url = pick("link");
    if (url.startsWith("http")) out.push({ title: pick("title"), url, snippet: pick("description") });
  }
  return out;
}

async function wikipedia(q: string): Promise<Raw[]> {
  const res = await get(
    `https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=4&srsearch=${encodeURIComponent(q)}`,
    API_UA,
  );
  if (!res.ok) throw new Error(`wikipedia ${res.status}`);
  const j = (await res.json()) as { query?: { search?: { title: string; snippet: string }[] } };
  return (j.query?.search ?? []).map((r) => ({
    title: `${r.title} — Wikipedia`,
    url: `https://en.wikipedia.org/wiki/${encodeURIComponent(r.title.replace(/ /g, "_"))}`,
    snippet: decode(r.snippet),
  }));
}

async function hackerNews(q: string): Promise<Raw[]> {
  const res = await get(`https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=5&query=${encodeURIComponent(q)}`, API_UA);
  if (!res.ok) throw new Error(`hn ${res.status}`);
  const j = (await res.json()) as { hits: { title?: string; url?: string; objectID: string; points?: number }[] };
  // Algolia matches loosely; keep only stories whose TITLE shares a real word
  // with the query, or HN drowns general questions in tangents.
  const words = q.toLowerCase().match(/[a-z0-9]{4,}/g) ?? [];
  return j.hits
    .filter((h) => h.title && words.some((w) => h.title!.toLowerCase().includes(w)))
    .slice(0, 3)
    .map((h) => ({
      title: `${h.title} (Hacker News, ${h.points ?? 0} points)`,
      url: h.url || `https://news.ycombinator.com/item?id=${h.objectID}`,
      snippet: `Discussion: https://news.ycombinator.com/item?id=${h.objectID}`,
    }));
}

async function arxiv(q: string): Promise<Raw[]> {
  const res = await get(`https://export.arxiv.org/api/query?max_results=4&search_query=all:${encodeURIComponent(q)}`, API_UA);
  if (!res.ok) throw new Error(`arxiv ${res.status}`);
  const xml = await res.text();
  return [...xml.matchAll(/<entry>([\s\S]*?)<\/entry>/g)].map((m) => {
    const e = m[1] ?? "";
    const pick = (tag: string) => decode(e.match(new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`))?.[1] ?? "");
    return { title: `${pick("title")} (arXiv)`, url: pick("id"), snippet: pick("summary").slice(0, 300) };
  });
}

async function github(q: string): Promise<Raw[]> {
  const res = await get(`https://api.github.com/search/repositories?per_page=4&q=${encodeURIComponent(q)}`, API_UA);
  if (!res.ok) throw new Error(`github ${res.status}`);
  const j = (await res.json()) as { items?: { full_name: string; html_url: string; description?: string; stargazers_count: number }[] };
  return (j.items ?? []).map((r) => ({
    title: `${r.full_name} (GitHub, ★${r.stargazers_count})`,
    url: r.html_url,
    snippet: r.description ?? "",
  }));
}

/** Same page, different spelling: scheme, www, trailing slash, tracking params. */
function normalise(url: string): string {
  try {
    const u = new URL(url);
    for (const p of [...u.searchParams.keys()]) if (/^(utm_|ref$|fbclid|gclid)/.test(p)) u.searchParams.delete(p);
    return (u.hostname.replace(/^www\./, "") + u.pathname.replace(/\/$/, "") + (u.search || "")).toLowerCase();
  } catch {
    return url;
  }
}

export async function edgeWebSearch(
  query: string,
  opts: { limit?: number } = {},
): Promise<{ results: SearchHit[]; sources: Record<string, number | string> }> {
  const q = query.trim().slice(0, 300);
  const limit = opts.limit ?? 10;

  // Short-lived cache: a model often repeats the same query within a turn,
  // and every repeat is traffic that makes a block more likely.
  const cacheKey = new Request(`https://websearch.cache/v1?q=${encodeURIComponent(q.toLowerCase())}&n=${limit}`);
  const cache = (caches as unknown as { default: Cache }).default;
  const hit = await cache.match(cacheKey).catch(() => undefined);
  if (hit) return (await hit.json()) as { results: SearchHit[]; sources: Record<string, number | string> };

  const lower = q.toLowerCase();
  const wantPapers = /\b(paper|papers|arxiv|preprint|study|studies|research on)\b/.test(lower);
  const wantCode = /\b(github|repo|repository|library|package|open[- ]source|sdk)\b/.test(lower);

  const plan: [string, Promise<Raw[]>][] = [
    ["bing", bing(q)],
    ["wikipedia", wikipedia(q)],
    ["hackernews", hackerNews(q)],
    ...(wantPapers ? [["arxiv", arxiv(q)] as [string, Promise<Raw[]>]] : []),
    ...(wantCode ? [["github", github(q)] as [string, Promise<Raw[]>]] : []),
  ];
  const settled = await Promise.allSettled(plan.map(([, p]) => p));

  // Reciprocal-rank fusion. Bing carries the most weight: it is the only
  // general web source; the rest add depth on their own ground.
  const WEIGHT: Record<string, number> = { bing: 1.0, wikipedia: 0.8, hackernews: 0.5, arxiv: 0.9, github: 0.9 };
  const merged = new Map<string, { hit: SearchHit; score: number }>();
  const sources: Record<string, number | string> = {};
  settled.forEach((r, i) => {
    const name = plan[i]![0];
    if (r.status === "rejected") {
      sources[name] = `failed: ${(r.reason as Error)?.message ?? r.reason}`;
      return;
    }
    sources[name] = r.value.length;
    r.value.forEach((raw, rank) => {
      const key = normalise(raw.url);
      const add = (WEIGHT[name] ?? 0.5) / (60 + rank);
      const cur = merged.get(key);
      if (cur) {
        cur.score += add;
        cur.hit.sources.push(name);
        if (raw.snippet.length > cur.hit.snippet.length) cur.hit.snippet = raw.snippet;
      } else {
        merged.set(key, { hit: { ...raw, sources: [name] }, score: add });
      }
    });
  });

  const results = [...merged.values()]
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map((m) => ({ ...m.hit, snippet: m.hit.snippet.slice(0, 400) }));
  const payload = { results, sources };
  if (results.length) {
    await cache
      .put(cacheKey, new Response(JSON.stringify(payload), { headers: { "cache-control": "max-age=600" } }))
      .catch(() => {});
  }
  return payload;
}

/**
 * Read a page from the edge — the fallback when the home agent's fetch_page
 * is unavailable. Workers cannot reach private networks, so the SSRF risk
 * the home version guards against does not exist here; only http(s) on
 * default ports is allowed anyway.
 */
export async function edgeFetchPage(url: string): Promise<{ url: string; text: string }> {
  const u = new URL(url);
  if (!/^https?:$/.test(u.protocol) || (u.port && u.port !== "80" && u.port !== "443")) {
    throw new Error("only http(s) URLs on default ports");
  }
  const res = await fetch(u, { headers: { "user-agent": BROWSER_UA, accept: "text/html,*/*" }, signal: AbortSignal.timeout(12_000) });
  if (!res.ok) throw new Error(`fetch returned ${res.status}`);
  const html = (await res.text()).slice(0, 2_000_000);
  const text = html
    .replace(/<(script|style|noscript|svg|nav|footer|header|form)[\s\S]*?<\/\1>/gi, " ")
    .replace(/<br\s*\/?>|<\/(p|div|li|h[1-6]|tr)>/gi, "\n")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, "\"").replace(/&#39;/g, "'")
    .replace(/[ \t]+/g, " ")
    .replace(/\n\s*\n+/g, "\n\n")
    .trim();
  return { url: u.toString(), text: text.slice(0, 12_000) };
}
