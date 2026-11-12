// Vega providers (github.com/Zenda-Cross/vega-providers): compiled CommonJS scrapers loaded from jsDelivr and run in the page. The sites send no CORS,
// so every request goes through the Reelm Helper bridge (1.2+). `f` is injectable so node can test the shims.
import { score } from "./search-rank.ts";
import { bridge, setHeaders } from "./bridge.ts";
import { langs } from "./langs.ts";
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
    const headers: Record<string, string> = Object.fromEntries(Object.entries(c.headers ?? {}).filter(([, v]) => v != null).map(([k, v]) => [k, String(v)]));
    let body: string | undefined = undefined;
    if (c.data != null && c.method !== "GET" && c.method !== "HEAD") {
      const d = c.data;
      if (typeof FormData !== "undefined" && d instanceof FormData) { const r = new Response(d); headers["content-type"] = r.headers.get("content-type")!; body = await r.text(); }
      else if (d instanceof URLSearchParams) { body = d.toString(); headers["content-type"] ??= "application/x-www-form-urlencoded"; }
      else if (typeof d === "string") body = d;
      else { body = JSON.stringify(d); headers["content-type"] ??= "application/json"; }
    }
    const url = qs(c.url, c.params), o = { headers, method: c.method, body };
    if (c.signal?.aborted) throw Object.assign(new Error("canceled"), { code: "ERR_CANCELED" });
    const work = (async () => {
      if (c.maxRedirects === 0) { // probe (no body download, the target may be a video): a changed URL is the redirect
        const p = await f(url, { ...o, probe: true });
        if (!same(p.url, url)) return { ...p, status: 302, headers: { ...p.headers, location: p.url }, text: "" };
      }
      return f(url, o);
    })();
    const r = await (c.signal ? Promise.race([work, new Promise<never>((_, no) => c.signal.addEventListener("abort", () => no(Object.assign(new Error("canceled"), { code: "ERR_CANCELED" }))))]) : work);
    let data: any = r.text;
    if (/^\s*[{[]/.test(data)) try { data = JSON.parse(data); } catch {}
    const res = { data, status: r.status, statusText: "", headers: r.headers, config: c, request: { responseURL: r.url } };
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
  const url = String(u?.url ?? u), headers = { ...(i.headers ?? {}) };
  let body = i.body;
  if (typeof FormData !== "undefined" && body instanceof FormData) { const r = new Response(body); headers["content-type"] = r.headers.get("content-type"); body = await r.text(); }
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
const tmo = <T,>(p: Promise<T>, ms: number) => Promise.race([p, new Promise<never>((_, no) => setTimeout(() => no(new Error("timeout")), ms))]);

type Req = { title: string; year: number; type: "movie" | "series"; season: number; episode: number };
export type Stage = "load" | "search" | "meta" | "episodes" | "stream" | "done";
const context = async (f: F) => ({ axios: makeAxios(f), cheerio: await import("cheerio"), commonHeaders: { "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" },
  kvStore: (() => { const m = new Map<string, any>(); return { get: async (k: string) => m.get(k), set: async (k: string, v: any) => void m.set(k, v), delete: async (k: string) => void m.delete(k) }; })(),
  openWebView: async () => { throw new Error("unsupported"); } });

/** One provider, one title: search, meta, (episodes), stream, then probe each file the way a <video> asks. `at` reports each stage reached. */
async function resolve(base: string, p: string, r: Req, ctx: any, f: F, at: (s: Stage) => void = () => {}) {
  const { year, type, season, episode } = r, q = r.title.replace(/\s+(s\d{1,2}|season \d{1,2})\s*$/i, "").trim();
  const [posts, meta, stream, eps] = await Promise.all(["posts", "meta", "stream", "episodes"].map((n) => load(base, p, n, f).catch((e) => (n === "episodes" ? null : Promise.reject(e)))));
  const sig = new AbortController().signal, ctxBase = { providerValue: p, signal: sig, providerContext: ctx };
  at("search");
  const res = (await posts.getSearchPosts({ searchQuery: q, page: 1, ...ctxBase })) as { title: string; link: string }[];
  const found = res
    .map((x) => ({ x, s: score(q, x.title.replace(/\(.*?\)|\[.*?\]|\b(19|20)\d{2}\b.*$/g, " ")) + (year && x.title.includes(String(year)) ? 0.3 : 0) }))
    .filter((e) => e.s >= 0.6 && (type !== "series" || pickSeason([e.x], season).length)).sort((a, b) => b.s - a.s)[0]?.x; // not a post for another season
  if (!found) { console.debug(`[vega] ${p}: no post matched "${q}"`, res.slice(0, 8).map((x) => x.title)); return []; }
  at("meta");
  const m = await meta.getMeta({ link: found.link, provider: p, providerContext: ctx });
  console.debug(`[vega] ${p}: "${found.title.slice(0, 60)}" has ${m?.linkList?.length ?? 0} links`);
  const ll: Link[] = type === "series" ? pickSeason(m.linkList ?? [], season) : m.linkList ?? [];
  console.debug(`[vega] ${p}: links`, (m.linkList ?? []).map((l: Link) => `${l.title.slice(0, 50)}${l.episodesLink ? " [eps]" : ""}${l.directLinks?.length ? ` [${l.directLinks.length} dl: ${l.directLinks.slice(0, 3).map((d) => d.title).join("|")}]` : ""}`), `-> S${season}: ${ll.length}`);
  const out = await Promise.all(pickQualities(ll).map(async (l) => {
    let link = "", t = "movie";
    if (type === "series") {
      at("episodes"); // 4khdhub and autoEmbed list a season's episodes as its directLinks
      const el: { title: string; link: string }[] = l.directLinks?.length ? l.directLinks.map((d) => ({ title: d.title ?? "", link: d.link }))
        : l.episodesLink && eps ? await eps.getEpisodes({ url: l.episodesLink, providerContext: ctx }) : [];
      const ep = pickEpisode(el, episode);
      console.debug(`[vega] ${p} ${l.title.slice(0, 30)}: ${el.length} episodes`, el.slice(0, 30).map((x: any) => x.title), `-> E${episode}: ${ep?.title}`);
      if (!ep) return null;
      link = ep.link; t = "series";
    } else for (const d of l.directLinks ?? []) { link = d.link; t = d.type ?? "movie"; if (link) break; }
    if (!link) return null;
    at("stream");
    const list = ((await stream.getStream({ link, type: t, signal: sig, providerContext: ctx, isDownload: false })) as any[]) ?? [];
    console.debug(`[vega] ${p} ${l.title.slice(0, 30)}: ${list.length} files from ${link.slice(0, 80)}`, list.map((s) => `${s.server} ${String(s.link).slice(8, 50)}`));
    for (const s of list) { // m3u8: hls.js needs CORS from the host, which these never send
      if (!/^https:/.test(s.link ?? "") || s.type === "m3u8" || /\.m3u8(\?|$)/.test(s.link) || NOT_MEDIA.test(s.link)) continue;
      const hd = s.headers ?? {};
      if (Object.keys(hd).some((k) => !["referer", "origin", "user-agent"].includes(k.toLowerCase()))) continue; // can't be applied to a <video>
      if (Object.keys(hd).length) try { await setHeaders(s.link, hd); } catch { continue; }
      // ask the way a <video> does (open range): some hosts only serve bounded ranges and answer players with a 403 page
      const pr = await f(s.link, { probe: true, vega: true, headers: { ...hd, Range: "bytes=0-" } }).catch(() => null);
      if (!pr || (pr.status !== 200 && pr.status !== 206) || /text\/html|json|xml/.test(pr.headers["content-type"] ?? "")) { console.debug(`[vega] ${p}: dropped ${String(s.link).slice(8, 50)} (${pr ? pr.status + " " + pr.headers["content-type"] : "probe failed"})`); continue; }
      const ht = h(l) || +(`${s.quality ?? ""}`.match(/\d{3,4}/)?.[0] ?? 0), ac = /ddp|dd\+|dd ?[257]\.1|e-?ac-?3|\bac-?3|dts|atmos|truehd/i.test(l.title) && !/aac|opus/i.test(l.title);
      return { url: s.link as string, height: ht, label: `${NAMES[p] ?? p} · ${String(s.server ?? "").split(" · ").filter((x) => x && x !== "Unknown" && !/^\d{3,4}p$/.test(x)).join(" ") || "Direct"}${ht ? " " + ht + "p" : ""}${ac ? " · Dolby audio" : ""}`, dolby: ac,
        codec: /av1/i.test(l.title) ? "AV1" : /hevc|x265|h\.?265/i.test(l.title) ? "HEVC" : /avc|x264|h\.?264/i.test(l.title) ? "H.264" : undefined, subtitles: [] as any[],
        langs: langs(langs(l.title).length ? l.title : found.title) }; // the link names its own audio when it differs from the post (a Hindi-only 480p under a Dual Audio post)
    }
    return null;
  }).map((x) => x.catch(() => null)));
  return out.filter((x) => !!x);
}

/** `on` gets each provider's files as soon as it has them; slow ones (VegaMovies, autoEmbed: 15-25 s for a series) get up to 45 s. */
export async function streams(title: string, year: number, type: "movie" | "series", season: number, episode: number, f: F = vf, on: (x: Awaited<ReturnType<typeof resolve>>) => void = () => {}) {
  const reg = await registry(), ctx = await context(f), r = { title, year, type, season, episode };
  // ponytail: first 10 enabled run in parallel; a queue with a deadline if admins enable more and want them all
  const t0 = Date.now(), all = await Promise.all(reg.enabled.slice(0, 10).map((p) => tmo(resolve(reg.base, p, r, ctx, f), 45000)
    .then((x) => (console.debug(`[vega] ${p}: ${x.length} in ${Date.now() - t0} ms`), x.length && on(x), x), (e) => (console.debug(`[vega] ${p}: ${e?.message} after ${Date.now() - t0} ms`), [] as any[]))));
  return all.flat().sort((a, b) => b.height - a.height);
}

/** Admin "Test update": the same cases as the Android VegaEngineTest family. */
export const CHECK_CASES = { movie: { title: "Spider-Man: Into the Spider-Verse", year: 2018, type: "movie", season: 0, episode: 0 }, series: { title: "Breaking Bad", year: 2008, type: "series", season: 1, episode: 1 } } as const;
export type CheckCase = keyof typeof CHECK_CASES;
export type Check = { ok: boolean; stage: Stage; files: number; ms: number; error?: string };
const ORDER: Stage[] = ["load", "search", "meta", "episodes", "stream", "done"];
/** Run ONE provider at an explicit commit (not the registry's pin) through the player's path. `files` = playable files found (max one per picked quality). */
export async function checkProvider(commit: string, provider: string, c: CheckCase, f: F = vf, ms = 30000): Promise<Check> {
  const t0 = Date.now();
  let stage = "load" as Stage;
  try {
    const out = await tmo(resolve(cdn(commit), provider, CHECK_CASES[c], await context(f), f, (s) => { if (ORDER.indexOf(s) > ORDER.indexOf(stage)) stage = s; }), ms);
    if (out.length) return { ok: true, stage: "done", files: out.length, ms: Date.now() - t0 };
    return { ok: false, stage, files: 0, ms: Date.now() - t0, error: stage === "search" ? "no search match" : "no playable file" };
  } catch (e: any) { return { ok: false, stage, files: 0, ms: Date.now() - t0, error: String(e?.message ?? e) }; }
}
