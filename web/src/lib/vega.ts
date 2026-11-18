// Vega providers (github.com/Zenda-Cross/vega-providers): compiled CommonJS scrapers loaded from jsDelivr and run in the page. The sites send no CORS,
// so every request goes through the Reelm Helper bridge (1.2+). `f` is injectable so node can test the shims.
import { score } from "./search-rank.ts";
import { bridge, helperCors, setHeaders } from "./bridge.ts";
import { langs } from "./langs.ts";
import { loadStats, score as reliability } from "./src-rank.ts";
export type F = (url: string, o?: { headers?: Record<string, string>; probe?: boolean; method?: string; body?: string; vega?: boolean }) => Promise<{ status: number; url: string; headers: Record<string, string>; text: string }>;
const vf: F = (u, o) => bridge(u, { ...o, vega: true }, 20000);
const DEFAULT = { commit: "4ecbc98e12da870ffc06b41bd7affe4b1fe316f0", enabled: ["hdhub4u", "drive", "vega", "mod", "topmovies"], base: "" };
const NAMES: Record<string, string> = { "4khdhub": "4KHDHub", hdhub4u: "HDHub4u", drive: "Drive", vega: "VegaMovies", mod: "MoD", topmovies: "TopMovies" };
const cdn = (c: string) => `https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@${c}/dist`;

const qs = (u: string, p?: Record<string, unknown>) => { if (!p) return u; const x = new URL(u); for (const [k, v] of Object.entries(p)) if (v != null) x.searchParams.set(k, String(v)); return x.toString(); };
const same = (a: string, b: string) => { try { return new URL(a).href === new URL(b).href; } catch { return a === b; } };
/** axios over the bridge: callable, .get/.post/.head, headers/params/data/signal/validateStatus/maxRedirects:0. The bridge can't see Location (fetch follows), so a redirect is read off the final URL. */
export const makeAxios = (f: F) => {
  async function req(c: any): Promise<any> {
    // header names are case-insensitive: a provider's "Content-Type" plus our own "content-type" went out as "application/json, application/json" (415/500 on kickAssAnime, castle)
    const headers: Record<string, string> = {};
    for (const [k, v] of Object.entries(c.headers ?? {})) if (v != null) headers[k.toLowerCase()] = String(v);
    let body: string | undefined = undefined;
    if (c.data != null && c.method !== "GET" && c.method !== "HEAD") {
      const d = c.data;
      if (typeof FormData !== "undefined" && d instanceof FormData) { const r = new Response(d); headers["content-type"] = r.headers.get("content-type")!; body = await r.text(); }
      else if (d instanceof URLSearchParams) { body = d.toString(); headers["content-type"] ??= "application/x-www-form-urlencoded"; }
      else if (typeof d === "string") body = d;
      else { body = JSON.stringify(d); headers["content-type"] ??= "application/json"; }
    }
    const url = qs(c.baseURL && !/^https?:/i.test(c.url) ? c.baseURL.replace(/\/+$/, "") + "/" + c.url.replace(/^\/+/, "") : c.url, c.params), o = { headers, method: c.method, body };
    if (c.signal?.aborted) throw Object.assign(new Error("canceled"), { code: "ERR_CANCELED" });
    const work = (async () => {
      if (c.maxRedirects === 0) { // probe (no body download, the target may be a video): a changed URL is the redirect
        const p = await f(url, { ...o, probe: true });
        if (!same(p.url, url)) return { ...p, status: 302, headers: { ...p.headers, location: p.url }, text: "" };
      }
      return f(url, o);
    })();
    const stops: Promise<never>[] = [];
    if (c.signal) stops.push(new Promise<never>((_, no) => c.signal.addEventListener("abort", () => no(Object.assign(new Error("canceled"), { code: "ERR_CANCELED" })))));
    if (c.timeout > 0) stops.push(new Promise<never>((_, no) => setTimeout(() => no(Object.assign(new Error(`timeout of ${c.timeout}ms exceeded`), { code: "ECONNABORTED" })), c.timeout)));
    const r = await (stops.length ? Promise.race([work, ...stops]) : work);
    let data: any = r.text;
    const raw = c.responseType === "text" || c.transformResponse; // iqst passes an identity transformResponse
    if (c.responseType === "arraybuffer") data = new TextEncoder().encode(data).buffer; // ponytail: the bridge carries text, so binary bodies are lossy; the providers use it for JSON/base64
    else if (!raw && /^\s*[{[]/.test(data)) try { data = JSON.parse(data); } catch {}
    // uhd/mod/topmovies read the final URL off request.res.responseUrl (Node axios), others off request.responseURL (XHR)
    const res = { data, status: r.status, statusText: "", headers: r.headers, config: c, request: { responseURL: r.url, res: { responseUrl: r.url } } };
    if (!(c.validateStatus ?? ((s: number) => s >= 200 && s < 300))(r.status)) throw Object.assign(new Error(`Request failed with status code ${r.status}`), { response: res, config: c, isAxiosError: true });
    return res;
  }
  const ax: any = (u: any, c: any = {}) => req({ method: "GET", ...(typeof u === "string" ? { url: u, ...c } : { ...u, method: (u.method ?? "GET").toUpperCase() }) });
  ax.get = (url: string, c?: any) => req({ ...c, url, method: "GET" });
  ax.head = (url: string, c?: any) => req({ ...c, url, method: "HEAD" });
  ax.post = (url: string, data?: any, c?: any) => req({ ...c, url, data, method: "POST" });
  return ax;
};

/** fetch over the bridge, as a real Response (url, headers, text(), json() all work) */
export const makeFetch = (f: F) => async (u: any, i: any = {}) => {
  const url = String(u?.url ?? u), headers: Record<string, string> = Object.fromEntries(new Headers(i.headers ?? {})); // a Headers instance spread to {} and lost every header
  let body = i.body;
  if (typeof FormData !== "undefined" && body instanceof FormData) { const r = new Response(body); headers["content-type"] = r.headers.get("content-type")!; body = await r.text(); }
  else if (body instanceof URLSearchParams) body = body.toString();
  if (i.signal?.aborted) throw new DOMException("aborted", "AbortError");
  const r = await f(url, { headers, method: i.method, body });
  const res = new Response([204, 205, 304].includes(r.status) ? null : r.text, { status: r.status || 200, headers: Object.fromEntries(Object.entries(r.headers).filter(([k]) => k !== "content-encoding" && k !== "content-length")) });
  Object.defineProperty(res, "url", { value: r.url });
  return res;
};

// ---- picking ----
type Link = { title: string; quality?: string; directLinks?: { link: string; title?: string; type?: string }[]; episodesLink?: string };
const h = (l: Link) => +(`${l.quality ?? ""} ${l.title}`.match(/\b(2160|1440|1080|720|576|480|360)p?\b/)?.[1] ?? (/\b(4k|uhd)\b/i.test(l.title) ? 2160 : 0));
const mb = (l: Link) => { const s = l.title.match(/(\d+(?:\.\d+)?)\s*(GB|MB)/i); return s ? +s[1] * (s[2].toUpperCase() === "GB" ? 1024 : 1) : Infinity; };
const heavy = (l: Link) => +/10.?bit|hdr|\bdv\b|dolby vision|hevc|x265/i.test(l.title); // plain 8-bit H.264 plays everywhere
/** Season's pack entries: "S02" / "Season 2" / a range ("Season 1-3"); a meta with no season tags at all (a one-season show) is taken whole. */
export function pickSeason(ll: Link[], s: number) {
  const tag = (t: string) => { const r = t.match(/\bseasons?\s*0*(\d+)\s*(?:-|–|to)\s*0*(\d+)/i) ?? t.match(/\bS0*(\d+)\s*(?:-|–|to)\s*S?0*(\d+)\b/i); if (r) return [+r[1], +r[2]]; const m = t.match(/\bseason\s*0*(\d+)|\bS0*(\d+)(?!\d)/i); return m ? [+(m[1] ?? m[2]), +(m[1] ?? m[2])] : null; };
  const tagged = ll.map((l) => ({ l, t: tag(l.title) }));
  return tagged.some((x) => x.t) ? tagged.filter((x) => x.t && x.t[0] <= s && s <= x.t[1]).map((x) => x.l) : ll;
}
/** Up to 2 qualities: the best, plus the one nearest 720p. One entry per height: lightest codec, then smallest. */
export function pickQualities(ll: Link[]) {
  const by = new Map<number, Link>();
  for (const l of [...ll].sort((a, b) => heavy(a) - heavy(b) || mb(a) - mb(b))) if (!by.has(h(l))) by.set(h(l), l);
  const hs = [...by.keys()].sort((a, b) => b - a), top = hs[0], low = hs.filter((x) => x !== top).sort((a, b) => Math.abs(a - 720) - Math.abs(b - 720))[0];
  return [top, low].filter((x) => x !== undefined).map((x) => by.get(x)!);
}
/** Provider "streams" that are really player configs or pages (autoEmbed hands back config-*.json); some hosts serve them as octet-stream. */
export const NOT_MEDIA = /\.(json|txt|xml)(\?|#|$)/i;
/** Trial clips a provider labels itself (castle: "… - 1080p - preview"): a few minutes, not the film. Same as Android's VegaPick. */
export const TRIAL = /\b(preview|sample)\b/i;
/** A series post's year is its (latest) season's: never before the show began, and a season-1 post's is the show's own (a "Season 01-03" pack's is season 3's).
 * "Squid Game" (2021) S1 had picked "Squid Game: The Challenge (2023)". */
export const seriesYear = (t: string, year: number, season: number) => {
  const y = +(t.match(/\b(19|20)\d{2}\b/)?.[0] ?? 0), pack = /\bseasons?\s*0*\d+\s*(?:-|–|to)\s*0*\d+|\bS0*\d+\s*(?:-|–|to)\s*S?0*\d+\b/i.test(t);
  return !y || !year || (y >= year - 1 && (season !== 1 || pack || y <= year + 1));
};
export const pickEpisode = (eps: { title: string; link: string }[], e: number) =>
  eps.find((x) => { const m = x.title.match(/(?:^|[^a-z])E0*(\d+)\b|\bepisode[\s._-]*0*(\d+)\b|^\s*0*(\d+)\b/i); return m && +(m[1] ?? m[2] ?? m[3]) === e; }) ?? eps.filter((x) => !/\bpack\b/i.test(x.title))[e - 1]; // "Episode-01 2.9 GB" (4khdhub); a lone "Season Pack" is no episode

// ---- loading ----
const mods = new Map<string, Promise<any>>();
const load = (base: string, p: string, name: string, f: F) => {
  const k = `${base}/${p}/${name}`;
  if (!mods.has(k)) mods.set(k, (async () => {
    const r = await fetch(`${base}/${p}/${name}.js`);
    if (!r.ok) throw new Error(`vega ${p}/${name}: HTTP ${r.status}`);
    const m: any = { exports: {} };
    new Function("exports", "module", "fetch", await r.text())(m.exports, m, makeFetch(f)); // ponytail: globalThis.fetch inside is the real one (only used for CORS-safe provider caches)
    return m.exports;
  })().catch((e) => { mods.delete(k); throw e; }));
  return mods.get(k)!;
};
const registry = async () => {
  try {
    const { API } = await import("./core");
    const v = (await (await fetch(`${API}/v1/sources`)).json()).vega;
    if (v?.commit && Array.isArray(v.enabled)) return { commit: v.commit as string, enabled: v.enabled as string[], base: (v.base as string) || cdn(v.commit) };
  } catch {}
  return { ...DEFAULT, base: cdn(DEFAULT.commit) };
};
/** Search results, metas and episode lists per provider: 1 h (they hold page links, not expiring stream URLs). Failures and empty searches aren't kept. */
const memo = new Map<string, { t: number; v: Promise<any> }>(), HOUR = 36e5;
export const remember = <T,>(k: string, on: boolean, f: () => Promise<T>): Promise<T> => {
  const now = Date.now(), e = memo.get(k);
  if (!on) return f();
  if (e && now - e.t < HOUR) return e.v;
  for (const [x, y] of memo) if (now - y.t >= HOUR) memo.delete(x);
  const v = f();
  memo.set(k, { t: now, v });
  v.then((x) => { if (Array.isArray(x) && !x.length) memo.delete(k); }, () => memo.delete(k));
  return v;
};
const tmo = <T,>(p: Promise<T>, ms: number) => Promise.race([p, new Promise<never>((_, no) => setTimeout(() => no(new Error("timeout")), ms))]);

type Req = { title: string; year: number; type: "movie" | "series"; season: number; episode: number };
export type Stage = "load" | "search" | "meta" | "episodes" | "stream" | "done";
const URLS = "https://raw.githubusercontent.com/Zenda-Cross/vega-providers/refs/heads/main/urls.json";
let urls: Promise<Record<string, { url: string }>> | undefined;
/** What the Vega app hands every provider (vega-app sandboxWorker.ts): getBaseUrl too (dramacool asks it first). */
const context = async (f: F, p = "") => ({ axios: makeAxios(f), cheerio: await import("cheerio"), commonHeaders: { "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" },
  getBaseUrl: async (v: string) => ((await (urls ??= makeFetch(f)(URLS).then((r) => r.json()).catch((e) => { urls = undefined; throw e; })))[v]?.url ?? ""),
  // TheIntroDB skip times per episode (1.4-2.7 s per series lookup): off, the player asks TheIntroDB/AniSkip itself
  kvStore: (() => { const m = new Map<string, any>([[`${p}_skipTimings`, false]]); return { get: async (k: string) => m.get(k), set: async (k: string, v: any) => void m.set(k, v), delete: async (k: string) => m.delete(k), keys: async () => [...m.keys()], clear: async () => m.clear() }; })(),
  openWebView: async () => { throw new Error("unsupported"); } });

/** hls.js fetches the playlists and segments itself, so every host they live on needs CORS (Helper 1.3 adds it for Reelm's own requests) and the stream's Referer. */
async function hlsReady(url: string, hd: Record<string, string>, f: F) {
  const hosts = new Set<string>();
  for (let u = url, i = 0; i < 2; i++) { // master, then its first variant
    const r = await f(u, { vega: true, headers: hd }).catch(() => null);
    if (!r || r.status !== 200 || !/^\s*#EXTM3U/.test(r.text)) return false;
    const lines = r.text.split("\n").map((l) => l.trim()), uris = lines.filter((l) => l && !l.startsWith("#"));
    const refs = [r.url, ...uris, ...lines.flatMap((l) => [...l.matchAll(/URI="([^"]+)"/g)].map((m) => m[1]))].map((x) => new URL(x, r.url));
    if (refs.some((x) => x.protocol !== "https:")) return false; // mixed content
    refs.forEach((x) => hosts.add(x.hostname)); // ponytail: hosts of the first variant only; a CDN rotating hosts mid-stream would need a wildcard rule
    if (!lines.some((l) => l.startsWith("#EXT-X-STREAM-INF")) || !uris[0]) break;
    u = new URL(uris[0], r.url).href;
  }
  await Promise.all([...hosts].map((h) => Promise.all([bridge(`https://${h}/`, { type: "cors" }), Object.keys(hd).length ? setHeaders(`https://${h}/`, hd) : null])));
  return true;
}

/** One provider, one title: search, meta, (episodes), stream, then probe each file the way a <video> asks. `at` reports each stage reached. */
async function resolve(base: string, p: string, r: Req, ctx: any, f: F, at: (s: Stage) => void = () => {}, onFile: (x: VegaFile) => void = () => {}, cache = true) {
  const { year, type, season, episode } = r, q = r.title.replace(/\s+(s\d{1,2}|season \d{1,2})\s*$/i, "").trim();
  const [posts, meta, stream, eps] = await Promise.all(["posts", "meta", "stream", "episodes"].map((n) => load(base, p, n, f).catch((e) => (n === "episodes" ? null : Promise.reject(e)))));
  const sig = new AbortController().signal, ctxBase = { providerValue: p, signal: sig, providerContext: ctx };
  at("search");
  const res = (await remember(`${base}|${p}|search|${q}`, cache, () => posts.getSearchPosts({ searchQuery: q, page: 1, ...ctxBase }))) as { title: string; link: string }[];
  // "Download Breaking Bad (Season 1 – 5) Dual Audio…" → "Breaking Bad" (Android VegaPick.cleanTitle)
  const clean = (t: string) => { const x = t.replace(/^\s*(download\s+|\[[^\]]*\]\s*)+/i, ""), i = x.search(/[([{|]|\b(19|20)\d{2}\b|\bseason\b|\bS\d{1,2}\b/i); return (i < 0 ? x : x.slice(0, i)).trim(); };
  const found = res // both ways, as Android does: one way, "Breaking Bad" matched "Bad Cop" (0.63)
    .map((x) => ({ x, s: Math.min(score(q, clean(x.title)), score(clean(x.title), q)) + (year && x.title.includes(String(year)) ? 0.3 : 0) }))
    .filter((e) => e.s >= 0.6 && (type !== "series" || (pickSeason([e.x], season).length && seriesYear(e.x.title, year, season)))).sort((a, b) => b.s - a.s)[0]?.x; // not a post for another season
  if (!found) { console.debug(`[vega] ${p}: no post matched "${q}"`, res.slice(0, 8).map((x) => x.title)); return []; }
  at("meta");
  const m: any = await remember(`${base}|${p}|meta|${found.link}`, cache, () => meta.getMeta({ link: found.link, provider: p, providerContext: ctx }));
  console.debug(`[vega] ${p}: "${found.title.slice(0, 60)}" has ${m?.linkList?.length ?? 0} links`);
  const ll: Link[] = type === "series" ? pickSeason(m.linkList ?? [], season) : m.linkList ?? [];
  console.debug(`[vega] ${p}: links`, (m.linkList ?? []).map((l: Link) => `${l.title.slice(0, 50)}${l.episodesLink ? " [eps]" : ""}${l.directLinks?.length ? ` [${l.directLinks.length} dl: ${l.directLinks.slice(0, 3).map((d) => d.title).join("|")}]` : ""}`), `-> S${season}: ${ll.length}`);
  const out = await Promise.all(pickQualities(ll).map(async (l) => {
    let link = "", t = "movie";
    if (type === "series") {
      at("episodes"); // 4khdhub and autoEmbed list a season's episodes as its directLinks
      const el: { title: string; link: string }[] = l.directLinks?.length ? l.directLinks.map((d) => ({ title: d.title ?? "", link: d.link }))
        : l.episodesLink && eps ? await remember(`${base}|${p}|eps|${l.episodesLink}`, cache, () => eps.getEpisodes({ url: l.episodesLink, providerContext: ctx })) : [];
      const ep = pickEpisode(el, episode);
      console.debug(`[vega] ${p} ${l.title.slice(0, 30)}: ${el.length} episodes`, el.slice(0, 30).map((x: any) => x.title), `-> E${episode}: ${ep?.title}`);
      if (!ep) return null;
      link = ep.link; t = "series";
    } else {
      for (const d of l.directLinks ?? []) { link = d.link; t = d.type ?? "movie"; if (link) break; }
      if (!link && l.episodesLink && eps) { at("episodes"); link = (await eps.getEpisodes({ url: l.episodesLink, providerContext: ctx }))?.[0]?.link ?? ""; } // showbox, animeworld: a movie's file sits behind an episode list (Android does the same)
    }
    if (!link) return null;
    at("stream");
    // the Vega app passes the meta's own type (its player route carries info.type), not ours
    const list = ((await stream.getStream({ link, type: m?.type ?? t, signal: sig, providerContext: ctx, isDownload: false })) as any[]) ?? [];
    console.debug(`[vega] ${p} ${l.title.slice(0, 30)}: ${list.length} files from ${link.slice(0, 80)}`, list.map((s) => `${s.server} ${String(s.link).slice(8, 50)}`));
    for (const s of list) {
      if (!/^https:/.test(s.link ?? "") || NOT_MEDIA.test(s.link) || TRIAL.test(String(s.server ?? ""))) continue;
      const hls = s.type === "m3u8" || /\.m3u8(\?|$)/.test(s.link);
      if (hls && !helperCors()) continue; // hls.js needs CORS from every host: Helper 1.3+
      const hd = s.headers ?? {};
      if (Object.keys(hd).some((k) => !["referer", "origin", "user-agent"].includes(k.toLowerCase()))) continue; // can't be applied to a <video>
      if (Object.keys(hd).length) try { await setHeaders(s.link, hd); } catch { continue; }
      if (hls) { if (!(await hlsReady(s.link, hd, f).catch(() => false))) { console.debug(`[vega] ${p}: dropped HLS ${String(s.link).slice(8, 50)}`); continue; } }
      else { // ask the way a <video> does (open range): some hosts only serve bounded ranges and answer players with a 403 page
        const pr = await f(s.link, { probe: true, vega: true, headers: { ...hd, Range: "bytes=0-" } }).catch(() => null);
        if (!pr || (pr.status !== 200 && pr.status !== 206) || /text\/html|json|xml/.test(pr.headers["content-type"] ?? "")) { console.debug(`[vega] ${p}: dropped ${String(s.link).slice(8, 50)} (${pr ? pr.status + " " + pr.headers["content-type"] : "probe failed"})`); continue; }
      }
      const ht = h(l) || +(`${s.quality ?? ""}`.match(/\d{3,4}/)?.[0] ?? 0), ac = /ddp|dd\+|dd ?[257]\.1|e-?ac-?3|\bac-?3|dts|atmos|truehd/i.test(l.title) && !/aac|opus/i.test(l.title);
      const file: VegaFile = { url: s.link as string, hls, height: ht, label: `${NAMES[p] ?? p} · ${String(s.server ?? "").split(" · ").filter((x) => x && x !== "Unknown" && !/^\d{3,4}p$/.test(x)).join(" ") || "Direct"}${ht ? " " + ht + "p" : ""}${ac ? " · Dolby audio" : ""}`, dolby: ac,
        codec: /av1/i.test(l.title) ? "AV1" : /hevc|x265|h\.?265/i.test(l.title) ? "HEVC" : /avc|x264|h\.?264/i.test(l.title) ? "H.264" : undefined,
        // the provider's own subtitle files (watch page shows them through /api/sub as WebVTT)
        subtitles: ((s.subtitles ?? []) as any[]).filter((t) => /^https?:/.test(t?.uri ?? "")).map((t) => ({ url: t.uri as string, label: String(t.title || t.language || "Subtitles"), lang: String(t.language ?? "") })),
        langs: langs(langs(l.title).length ? l.title : found.title) }; // the link names its own audio when it differs from the post (a Hindi-only 480p under a Dual Audio post)
      onFile(file); // don't wait for the other quality
      return file;
    }
    return null;
  }).map((x) => x.catch(() => null)));
  return out.filter((x) => !!x);
}

export type VegaFile = { url: string; hls: boolean; height: number; label: string; dolby: boolean; codec?: string; subtitles: { url: string; label: string; lang: string }[]; langs: string[] };

// ---- what each provider carries ----
/** The manifest's `type`, corrected where it says "global" for a niche catalog. */
const KIND_FIX: Record<string, string> = { dramacool: "kdrama", kissKh: "kdrama", durbinLive: "live", liveTgTv: "live", youtube: "video" };
export const kindOf = (p: string, type?: string) => KIND_FIX[p] ?? type ?? "global";
/** Never asked for a movie or series: anime (anime titles have their own sources), live TV and YouTube (no title catalog). */
const OFF_TITLE = ["anime", "live", "video"];
/** Providers with no movies (kissKh is K-drama episodes only): asked for series alone. */
const SERIES_ONLY = ["kissKh"];
/** Need to read Set-Cookie and send Cookie (NetMirror's ad-verify session), which browser fetch can't; they run on Android only. */
export const WEB_SKIP = ["netflixMirror", "primeMirror", "disneyMirror"];
const types = new Map<string, Promise<Record<string, string>>>();
/** provider → manifest type at the commit `base` points at (…/dist). */
export const manifestTypes = (base: string) => {
  if (!types.has(base)) types.set(base, fetch(base.replace(/\/dist\/?$/, "/manifest.json")).then((r) => r.json())
    .then((a: { value: string; type?: string }[]) => Object.fromEntries(a.map((x) => [x.value, x.type ?? "global"])))
    .catch(() => (types.delete(base), {})));
  return types.get(base)!;
};

// ---- asking many providers ----
/** Most reliable here first (src-rank stats, keyed by the label's provider name); the admin order breaks ties. */
export const order = (ps: string[], rel: (p: string) => number) => ps.map((p, i) => ({ p, i, s: rel(p) })).sort((a, b) => b.s - a.s || a.i - b.i).map((x) => x.p);
/** Enough to stop asking at full speed: 3 sources at the target height or better, or 5 of any. */
export const enough = (got: { height: number }[], target = 1080) => got.filter((x) => x.height >= target).length >= 3 || got.length >= 5;
/** Circuit breaker, one entry "fails,until" per provider: 3 failed searches in a row (network, DNS, 4xx) skip it for 6 h; a working search resets it. */
const TRIP = 3, COOL = 6 * 36e5, BKEY = "reelm.vegaBreaker";
export const tripped = (e: string | undefined, now: number) => +(e?.split(",")[1] ?? 0) > now;
export const nextBreaker = (e: string | undefined, failed: boolean, now: number) => { if (!failed) return undefined; const n = +(e?.split(",")[0] ?? 0) + 1; return `${n},${n >= TRIP ? now + COOL : 0}`; };
const breakers = (): Record<string, string> => { try { return JSON.parse(localStorage.getItem(BKEY) ?? "{}") ?? {}; } catch { return {}; } };
const breaker = (p: string, failed: boolean) => { try { const b = breakers(), n = nextBreaker(b[p], failed, Date.now()); if (n === b[p]) return; if (n) b[p] = n; else delete b[p]; localStorage.setItem(BKEY, JSON.stringify(b)); } catch {} };
/** Runs `ps` at most `limit` at a time until all are done or `deadline` ms pass; once `enough` holds for what's in, only `after` keep going. Each result goes to `on` as it lands. */
export async function fanOut<T>(ps: string[], one: (p: string) => Promise<T[]>, o: { limit: number; after: number; deadline: number; enough: (got: T[]) => boolean; on: (x: T[]) => void }) {
  const q = [...ps], got: T[] = [], t0 = Date.now();
  let stop = false;
  const worker = async (w: number) => {
    for (let p; !(stop && w >= o.after) && (p = q.shift()) !== undefined;) {
      let t: ReturnType<typeof setTimeout> | undefined, out = false;
      const x = await Promise.race([one(p), new Promise<T[]>((ok) => (t = setTimeout(() => { out = true; ok([]); }, o.deadline - (Date.now() - t0))))]).finally(() => clearTimeout(t));
      if (out || Date.now() - t0 >= o.deadline) return; // the timer can fire a ms before Date.now() shows the deadline
      if (x.length) { got.push(...x); o.on(x); }
      stop ||= o.enough(got);
    }
  };
  await Promise.all(Array.from({ length: o.limit }, (_, w) => worker(w)));
  return got;
}

/** Every title provider, most reliable first, 5 at a time (2 once there's enough), each up to 45 s, all within 60 s. `on` gets each file as soon as it's verified. */
export async function streams(title: string, year: number, type: "movie" | "series", season: number, episode: number, f: F = vf, on: (x: Awaited<ReturnType<typeof resolve>>) => void = () => {}) {
  const reg = await registry(), kinds = await manifestTypes(reg.base), r = { title, year, type, season, episode }, stats = loadStats(), br = breakers(), now = Date.now();
  const ps = order(reg.enabled.filter((p) => !WEB_SKIP.includes(p) && !OFF_TITLE.includes(kindOf(p, kinds[p])) && !(type === "movie" && SERIES_ONLY.includes(p)) && !tripped(br[p], now)), (p) => reliability(stats[NAMES[p] ?? p], now));
  const t0 = Date.now(), all: VegaFile[] = [], add = (x: VegaFile) => { if (Date.now() - t0 < 60000) { all.push(x); on([x]); } };
  const one = async (p: string) => {
    // the provider's own fetches, so a search whose requests failed can be told from one that found nothing
    let stage = "load" as Stage, fails = 0;
    const g: F = (u, o) => stage !== "search" ? f(u, o) : f(u, o).then((x) => { if (x.status >= 400) fails++; return x; }, (e) => { fails++; throw e; });
    try {
      const x = await tmo(resolve(reg.base, p, r, await context(g, p), g, (s) => { stage = s; }, add), 45000);
      console.debug(`[vega] ${p}: ${x.length} in ${Date.now() - t0} ms`);
      if (stage !== "load") breaker(p, stage === "search" && !x.length && fails > 0);
      return x;
    } catch (e: any) {
      console.debug(`[vega] ${p}: ${e?.message} after ${Date.now() - t0} ms`);
      if (stage === "search" && e?.message !== "timeout") breaker(p, true); else if (stage !== "load" && stage !== "search") breaker(p, false);
      return [];
    }
  };
  await fanOut(ps, one, { limit: 5, after: 2, deadline: 60000, enough, on: () => {} });
  return all.sort((a, b) => b.height - a.height);
}

// ---- admin "Test update" ----
const SPIDER: Req = { title: "Spider-Man: Into the Spider-Verse", year: 2018, type: "movie", season: 0, episode: 0 }, BB: Req = { title: "Breaking Bad", year: 2008, type: "series", season: 1, episode: 1 };
/** Titles each kind of provider actually carries (an anime site has no Breaking Bad). Global ones: the Android VegaEngineTest cases. */
export const CHECK_CASES: Record<string, { movie: Req; series: Req }> = {
  global: { movie: SPIDER, series: BB },
  india: { movie: { title: "Pushpa: The Rise", year: 2021, type: "movie", season: 0, episode: 0 }, series: { title: "Aashram", year: 2020, type: "series", season: 1, episode: 1 } },
  anime: { movie: { title: "Jujutsu Kaisen 0", year: 2021, type: "movie", season: 0, episode: 0 }, series: { title: "Jujutsu Kaisen", year: 2020, type: "series", season: 1, episode: 1 } },
  kdrama: { movie: { title: "Parasite", year: 2019, type: "movie", season: 0, episode: 0 }, series: { title: "Crash Landing on You", year: 2019, type: "series", season: 1, episode: 1 } },
};
export const casesFor = (kind: string) => (kind === "live" || kind === "video" ? null : CHECK_CASES[kind] ?? CHECK_CASES.global);
export type CheckCase = "movie" | "series";
export type Check = { ok: boolean; stage: Stage; files: number; ms: number; error?: string; skipped?: boolean; title?: string };
const ORDER: Stage[] = ["load", "search", "meta", "episodes", "stream", "done"];
/** Run ONE provider at an explicit commit (not the registry's pin) through the player's path, on a title of its kind. `files` = playable files found (max one per picked quality). */
export async function checkProvider(commit: string, provider: string, c: CheckCase, f: F = vf, ms = 30000, kind = "global"): Promise<Check> {
  const t0 = Date.now(), req = casesFor(kind)?.[c];
  if (WEB_SKIP.includes(provider)) return { ok: false, skipped: true, stage: "load", files: 0, ms: 0, error: "Android only: the browser can't read or send the session cookie this site needs" };
  if (!req) return { ok: false, skipped: true, stage: "load", files: 0, ms: 0, error: kind === "live" ? "Live TV: nothing to search, not testable here" : "Not a movie or series catalog, not testable here" };
  const title = `${req.title}${req.type === "series" ? ` S${req.season}E${req.episode}` : ` (${req.year})`}`;
  let stage = "load" as Stage;
  try {
    const out = await tmo(resolve(cdn(commit), provider, req, await context(f, provider), f, (s) => { if (ORDER.indexOf(s) > ORDER.indexOf(stage)) stage = s; }, undefined, false), ms);
    if (out.length) return { ok: true, stage: "done", files: out.length, ms: Date.now() - t0, title };
    return { ok: false, stage, files: 0, ms: Date.now() - t0, error: stage === "search" ? "no search match" : "no playable file", title };
  } catch (e: any) { return { ok: false, stage, files: 0, ms: Date.now() - t0, error: String(e?.message ?? e), title }; }
}
