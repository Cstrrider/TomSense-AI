import { marked } from 'marked';
import hljs from 'highlight.js/lib/common';
import katex from 'katex';
import 'katex/dist/katex.min.css';

marked.setOptions({
  gfm: true,
  breaks: true
});

const renderer = new marked.Renderer();
const origCode = renderer.code.bind(renderer);
renderer.code = function (args: { text: string; lang?: string; escaped?: boolean }) {
  const { text, lang } = args;
  // ask_user card: keep the raw payload + a recognizable class; Message.svelte
  // turns it into an interactive question card in its post-render pass. Must
  // NOT be syntax-highlighted (that would drop the language-ask-user class the
  // decorator looks for).
  if (lang === 'ask-user') {
    const esc = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return `<pre class="ask-user-raw"><code class="language-ask-user">${esc}</code></pre>`;
  }
  if (lang && hljs.getLanguage(lang)) {
    try {
      const out = hljs.highlight(text, { language: lang, ignoreIllegals: true }).value;
      return `<pre><code class="hljs language-${lang}">${out}</code></pre>`;
    } catch {
      // fall through
    }
  }
  try {
    const out = hljs.highlightAuto(text).value;
    return `<pre><code class="hljs">${out}</code></pre>`;
  } catch {
    return origCode({ type: 'code', raw: text, text, lang, escaped: false });
  }
};

// Images: add loading="lazy" + decoding="async" so the browser doesn't block
// the main thread decoding several multi-MB generated images at once when the
// message list re-renders (e.g. on a regenerate/rerun) — that synchronous
// decode is a real mobile freeze source.
renderer.image = function (args: { href: string; title?: string | null; text?: string }) {
  const { href, title, text } = args;
  const t = title ? ` title="${title.replace(/"/g, '&quot;')}"` : '';
  const alt = (text || '').replace(/"/g, '&quot;');
  return `<img src="${href}" alt="${alt}"${t} loading="lazy" decoding="async" />`;
};

marked.use({ renderer });

// ─── LaTeX math via KaTeX ────────────────────────────────────────────────────
// marked has no native math support, so models that emit `$…$` / `$$…$$` (or
// the `\(…\)` / `\[…\]` variants) render as raw text. These two extensions
// tokenize math spans and hand them to KaTeX.
//
// The inline `$` rule is currency-safe: the opening `$` must not be followed by
// whitespace, the closing `$` must not be preceded by whitespace nor followed
// by a digit — so "it costs $5 and $10" is left untouched.

function renderKatex(tex: string, displayMode: boolean): string {
  try {
    return katex.renderToString(tex, { displayMode, throwOnError: true, strict: false });
  } catch {
    return ''; // signal failure → caller falls back to the raw source
  }
}

const blockKatex = {
  name: 'blockKatex',
  level: 'block' as const,
  start(src: string) {
    const a = src.indexOf('$$');
    const b = src.indexOf('\\[');
    const hits = [a, b].filter((i) => i >= 0);
    return hits.length ? Math.min(...hits) : undefined;
  },
  tokenizer(src: string) {
    const m = /^\$\$([\s\S]+?)\$\$/.exec(src) || /^\\\[([\s\S]+?)\\\]/.exec(src);
    if (m) return { type: 'blockKatex', raw: m[0], text: m[1].trim() };
    return undefined;
  },
  renderer(token: { raw: string; text: string }) {
    const html = renderKatex(token.text, true);
    return html ? `<div class="katex-block">${html}</div>` : token.raw;
  }
};

const inlineKatex = {
  name: 'inlineKatex',
  level: 'inline' as const,
  start(src: string) {
    const a = src.indexOf('$');
    const b = src.indexOf('\\(');
    const hits = [a, b].filter((i) => i >= 0);
    return hits.length ? Math.min(...hits) : undefined;
  },
  tokenizer(src: string) {
    let m = /^\$(?!\s)((?:\\\$|[^$\n])+?)(?<!\s)\$(?!\d)/.exec(src);
    if (!m) m = /^\\\(([\s\S]+?)\\\)/.exec(src);
    if (m) return { type: 'inlineKatex', raw: m[0], text: m[1].trim() };
    return undefined;
  },
  renderer(token: { raw: string; text: string }) {
    const html = renderKatex(token.text, false);
    return html || token.raw;
  }
};

marked.use({ extensions: [blockKatex, inlineKatex] });

/**
 * Render markdown to HTML. The backend may include <details> tool chips,
 * which marked passes through as raw HTML — we accept that (no untrusted
 * user-generated HTML reaches this path).
 *
 * Tool-chip / reasoning <details> blocks are raw HTML. CommonMark only ends
 * an HTML block at a blank line — without one, the paragraph immediately
 * after </details> is swallowed into the HTML block and its markdown
 * (**bold**, $math$, links) renders raw. Forcing a blank line around every
 * <details> boundary keeps the surrounding text parsed as markdown.
 */
export function renderMarkdown(src: string): string {
  const prepared = src
    // A <details> chip is a CommonMark HTML block, which ENDS at the first
    // blank line. A chip BODY can contain blank lines (e.g. a read_file
    // showing a file with a gap between imports and code), which terminates
    // the block early — so everything after it, including a trailing summary,
    // gets mis-parsed and vanishes. Collapse blank lines INSIDE each block so
    // it stays one uninterrupted HTML block from <details> to </details>.
    .replace(/<details[\s\S]*?<\/details>/g, (b) => b.replace(/\n[ \t]*\n+/g, '\n'))
    .replace(/\n*(<details)/g, '\n\n$1')
    .replace(/(<\/details>)\n*/g, '$1\n\n');
  return marked.parse(prepared, { async: false }) as string;
}
