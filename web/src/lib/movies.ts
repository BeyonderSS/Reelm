import * as mb from "@/lib/server/moviebox";
import * as misc from "@/lib/server/misc";
import * as dr from "@/lib/server/dramachi";
import * as tm from "@/lib/server/tmdb";
import { bestTitle, rank, visible } from "@/lib/search-rank";
import { bridge, helperMirror, helperOld, helperVega, helperVersion, MIRRORS, mirrorOf } from "@/lib/bridge";
import { langs } from "@/lib/langs";
import { mirrorTitle, normTitle, type Play } from "@/lib/core";

// MovieBox refuses datacenter IPs (Cloudflare, Vercel: HTTP 440) but allows CORS, so the browser runs these
// from the viewer's own connection (see get() in core.ts). The API route keeps them for local dev.
const num = (v: string | null, d = 0) => Number(v) || d;
// an error reply ({error}, 502) must reject, not come back as if it were the data
const json = (u: string) => fetch(u).then(async (r) => { const d = await r.json().catch(() => ({})); if (!r.ok) throw new Error(d.error ?? `HTTP ${r.status}`); return d; });

async function findMb(q: URLSearchParams) {
  const type = q.get("type") === "series" ? "series" : "movie", year = num(q.get("year"));
  const hits = (await mb.search(q.get("title") ?? "")).filter((h) => h.type === type);
  const norm = (t: string) => t.toLowerCase().replace(/[^a-z0-9]/g, "");
  const same = hits.filter((h) => norm(h.title) === norm(q.get("title") ?? ""));
  return (same.find((h) => !year || Math.abs((h.year ?? year) - year) <= 1) ?? same[0] ?? hits[0])?.id.slice(3);
}

export const movieRoutes: Record<string, (q: URLSearchParams) => Promise<unknown>> = {
  // MovieBox (browser only), Cinemeta and TMDB in parallel; each is optional and gives up after 6 s. Ranked and filtered in search-rank.ts.
  "movies/search": async (q) => {
    const t = q.get("q") ?? "", mature = q.get("mature") === "1";
    const opt = <T,>(p: Promise<T[]>) => Promise.race([p.catch(() => [] as T[]), new Promise<T[]>((r) => setTimeout(() => r([]), 6000))]);
    const tmdb = (x: string) => opt<any>(typeof window === "undefined" ? tm.search(x) : json(`/api/movies/tmdb?q=${encodeURIComponent(x)}`));
    // "vision quest" also asks for "visionquest" (and the other way round, the score treats both alike); each source's lists are joined
    const vs = [t, ...(/\S\s+\S/.test(t.trim()) ? [t.replace(/\s+/g, "")] : [])];
    const all = <T,>(f: (x: string) => Promise<T[]>) => Promise.all(vs.map(f)).then((l) => l.flat());
    const [mbs, cm, tms0] = await Promise.all([all((x) => opt<any>(mb.search(x))), all((x) => opt<any>(misc.cinemetaSearch(x))), all(tmdb)]);
    // TMDB doesn't forgive typos: ask again with the best title the others found ("gang of wasepur" -> "Gangs of Wasseypur")
    const best = tms0.length ? "" : bestTitle(t, [...mbs.filter((i: any) => visible(i, mature)), ...cm]);
    const tms = best && best.toLowerCase() !== t.trim().toLowerCase() ? await tmdb(best) : tms0;
    return rank(t, { mb: mbs, tm: tms, cm }, mature);
  },
  "movies/tmdb": (q) => (q.get("id") ? tm.detail(q.get("id")!) : tm.search(q.get("q") ?? "")),
  "movies/detail": async (q) => {
    const id = q.get("id")!;
    if (mirrorOf(id)) return mirrorDetail(id);
    const d: any = id.startsWith("tmdb:") ? await (typeof window === "undefined" ? tm.detail(id) : json(`/api/movies/tmdb?id=${encodeURIComponent(id)}`))
      : id.startsWith("mb:") ? await mb.detail(id.slice(3)) : await misc.cinemetaDetail(id, q.get("type") === "series" ? "series" : "movie");
    return { ...d, cast: [...new Set(d.cast ?? [])] }; // sources list a person once per role ("Tom Holland, Tom Holland")
  },
  // Audio languages live on MovieBox subjects: a Cinemeta/TMDB title borrows them from the subject Play would stream.
  "movies/audio": async (q) => {
    const id = q.get("id")!, sid = id.startsWith("mb:") ? id.slice(3) : await findMb(q);
    return sid ? (await mb.detail(sid)).audio : [];
  },
  // Dramachi has no CORS, so the browser asks our server for it (below); a source that fails drops out alone.
  "movies/dramachi": (q) => dr.streams(q.get("title") ?? "", num(q.get("year")), q.get("type") === "series" ? "series" : "movie", num(q.get("s")), num(q.get("e"))),
  "movies/stream": (q) => streamAll(q),
};

export type MirrorRow = { title: string; items: { id: string; type: "movie" | "series"; title: string; poster?: string; year?: number; rating?: number; genres?: string[] }[] };
/** Mirror mode: a mirror's rows. `on` gets them as soon as the mirror answers (its own images), then again as each row
 *  borrows Cinemeta's art (a Cinemeta title with exactly its name), so nothing waits for the hundreds of title lookups. */
export async function mirrorHome(p: string, on: (rows: MirrorRow[]) => void) {
  const t0 = Date.now(), rows = await (await import("./vega")).mirrorRows(p);
  console.debug(`[vega] ${p}: ${rows.length} rows in ${Date.now() - t0} ms`);
  if (!rows.length) throw new Error(`The ${MIRRORS[p]} mirror didn't answer`);
  const own = (x: { title: string; link: string; image?: string }) => ({ id: `nm:${p}:${x.link}`, type: "movie" as const, title: x.title, poster: x.image });
  let cur: MirrorRow[] = rows.map((r) => ({ title: r.title, items: r.items.map(own) }));
  on(cur);
  await Promise.all(rows.map(async (r, i) => {
    const items = await Promise.all(r.items.map(async (x) => {
      const h = await byTitle(x.title);
      return h ? { ...own(x), type: h.type, poster: h.poster ?? x.image, year: h.year, rating: h.rating, genres: h.genres } : own(x);
    }));
    cur = cur.map((c, k) => (k === i ? { ...c, items } : c));
    on(cur);
  }));
}

/** Cinemeta's title with exactly this name, newest first when a film and a series share it (Android's cinemetaByTitle).
 *  ponytail: name only, so a same-named older title can lend its art; play still goes to the mirror's own id. */
const titles = new Map<string, Promise<Awaited<ReturnType<typeof misc.cinemetaSearch>>[number] | undefined>>();
const byTitle = (t: string) => {
  const n = normTitle(t);
  if (!titles.has(n)) titles.set(n, misc.cinemetaSearch(t).then((l) => l.filter((x) => normTitle(x.title) === n).sort((a, b) => (b.year ?? 0) - (a.year ?? 0))[0], () => (titles.delete(n), undefined)));
  return titles.get(n)!;
};
/** A mirror title's page: Cinemeta's detail when the name matched (fast, has seasons), else the mirror's own meta (may wait for its unlock). */
async function mirrorDetail(id: string) {
  const p = mirrorOf(id)!, title = mirrorTitle(id), h = await byTitle(title);
  if (h) return { ...(await misc.cinemetaDetail(h.id, h.type)), id };
  if (!helperMirror()) throw new Error("This title needs Reelm Helper 1.4");
  const m = await (await import("./vega")).mirrorMeta(p, id.slice(`nm:${p}:`.length)), series = m.type === "series";
  const tags: string[] = (m.tags ?? []).map(String);
  return { id, type: series ? "series" : "movie", title: m.title || title, description: m.synopsis, poster: m.image, backdrop: m.image, playable: true,
    year: tags.map(Number).find((y) => y > 1900 && y < 2100), genres: tags.filter((t) => isNaN(+t) && !t.includes("IMDb")), cast: (m.cast ?? []).map(String),
    seasons: series ? (m.linkList ?? []).map((l: { title?: string }, i: number) => ({ season: +String(l.title ?? "").replace(/\D/g, "") || i + 1, episodes: m.counts[i] ?? 0 })) : [] };
}

/** Every source for a title. `on` gets each part (MovieBox, Dramachi, 4KHDHub, each Vega provider) as it lands, so the player can start before the slow ones finish. */
export async function streamAll(q: URLSearchParams, on: (part: any[]) => void = () => {}) {
  const id = q.get("id")!, emit = <T,>(x: T) => ((x as any[])?.length && on(x as any[]), x);
  const mbPart = async () => {
    const sid = id.startsWith("mb:") ? id.slice(3) : await findMb(q);
    if (!sid) throw new Error("No stream source found for this title");
    // each dub is its own subject: play the dubs too, tagged with their language ("Original" stays untagged: we don't know it)
    const one = async (id: string, label = "Original") => (await mb.streams(id, num(q.get("s")), num(q.get("e"))))
      .map((x) => ({ ...x, label: `MovieBox · ${x.label}`, langs: label === "Original" ? [] : langs(label) }));
    const audio = mb.detail(sid).then((d) => d.audio).catch(() => [] as Awaited<ReturnType<typeof mb.detail>>["audio"]);
    const [main, dubs] = await Promise.all([audio.then((a) => one(sid, a.find((x) => x.current)?.label)),
      // ponytail: first 6 dubs with a known language; a dub subject often serves only a notice video, which streams() drops
      audio.then((a) => Promise.all(a.filter((x) => !x.current && langs(x.label).length).slice(0, 6).map((x) => one(x.id.slice(3), x.label).catch(() => []))))]);
    return [...main, ...dubs.flat()];
  };
  const drPart = async () => {
    if (!q.get("title")) return []; // an "mb:" id arrives without a title
    return typeof window === "undefined" ? movieRoutes["movies/dramachi"](q) : json(`/api/movies/dramachi?${q}`);
  };
  // 4KHDHub blocks datacenter IPs and has no CORS: only the Reelm Helper (1.1+) can reach it, from the viewer's browser.
  const fkPart = async () => {
    if (typeof document === "undefined" || !q.get("title") || helperOld() || !helperVersion()) return [];
    // its mirror chain moves to new hosts the Helper's fixed list lacks; Helper 1.2+ lets vega requests reach any public https host
    const f: typeof bridge = helperVega() ? (u, o) => bridge(u, { ...o, vega: true }) : bridge;
    return (await import("./fourkhdhub")).streams(f, q.get("title")!, num(q.get("year")), q.get("type") === "series" ? "series" : "movie", num(q.get("s")), num(q.get("e")));
  };
  // Vega providers (open-source scrapers, see vega.ts): same gate, needs Helper 1.2+ (any host, Referer rules).
  const vegaPart = async () => {
    if (typeof document === "undefined" || !q.get("title") || !helperVega()) return [];
    return (await import("./vega")).streams(q.get("title")!, num(q.get("year")), q.get("type") === "series" ? "series" : "movie", num(q.get("s")), num(q.get("e")), undefined, on, mirrorOf(id)); // a mirror title asks its own mirror first, by its id (the watch page)
  };
  const t0 = Date.now(), log = (n: string) => <T,>(p: Promise<T>) => p.then((x: any) => (console.debug(`[stream] ${n}: ${x?.length} in ${Date.now() - t0} ms`), x), (e) => { console.debug(`[stream] ${n}: ${e?.message} after ${Date.now() - t0} ms`); throw e; });
  const r = await Promise.allSettled([log("moviebox")(mbPart().then(emit)), log("dramachi")(drPart().then(emit)), log("4khdhub")(fkPart().then(emit)), log("vega")(vegaPart())]);
  const list = r.flatMap((x) => (x.status === "fulfilled" ? (x.value as any[]) : []));
  if (!list.length) throw new Error((r.find((x) => x.status === "rejected") as PromiseRejectedResult | undefined)?.reason?.message ?? "No streams found");
  return list.sort((a, b) => +!!a.dolby - +!!b.dolby); // stable: Dolby releases (decoded in JS, see use-libav-audio) last, else Android order: MovieBox first, then the title sources (Dramachi, 4KHDHub), each best-first
}

// ---- start early, remember ----
/** The lookup a Play payload starts; the watch page and prefetches build it here so they share one key. */
export const streamQuery = (p: Play) => new URLSearchParams({ id: p.id, type: p.type ?? "movie", title: p.title, year: String(p.year ?? ""), s: String(p.season ?? 0), e: String(p.episode ?? 0) });
type Run = { at: number; parts: any[][]; ons: Set<(part: any[]) => void>; done: Promise<any[]> };
const runs = new Map<string, Run>(), TTL = 4 * 60e3; // stream URLs carry expiring tokens: never longer
let prefetching = 0;
/** streamAll, shared: a prefetch and the player asking for the same title run ONE lookup. `on` gets every part found so far (as one), then the late ones. `fresh` (Retry) starts over. */
export function streamShared(q: URLSearchParams, on: (part: any[]) => void = () => {}, fresh = false) {
  const k = q.toString(), now = Date.now();
  let run = runs.get(k);
  if (fresh || !run || now - run.at > TTL) {
    for (const [x, r] of runs) if (now - r.at > TTL) runs.delete(x);
    const r: Run = { at: now, parts: [], ons: new Set(), done: Promise.resolve([]) };
    r.done = streamAll(q, (part) => { r.parts.push(part); r.ons.forEach((f) => f(part)); });
    r.done.catch(() => runs.get(k) === r && runs.delete(k)); // a failed lookup isn't remembered
    runs.set(k, (run = r));
  }
  const r = run;
  if (r.parts.length) on(r.parts.flat());
  r.ons.add(on);
  return r.done.finally(() => r.ons.delete(on));
}
/** Start the lookup Play would start (a title's page, an episode's credits). Movies and series only, with the Helper; at most 2 at a time, the rest are dropped. */
export function prefetchStream(p: Play) {
  if (p.kind !== "movie" || !p.title || typeof document === "undefined" || !document.documentElement.dataset.reelmHelper || prefetching >= 2) return;
  const q = streamQuery(p), r = runs.get(q.toString());
  if (r && Date.now() - r.at < TTL) return;
  prefetching++;
  streamShared(q).catch(() => {}).finally(() => prefetching--);
}
