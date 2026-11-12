import * as mb from "@/lib/server/moviebox";
import * as misc from "@/lib/server/misc";
import * as dr from "@/lib/server/dramachi";
import * as tm from "@/lib/server/tmdb";
import { bestTitle, rank, visible } from "@/lib/search-rank";
import { bridge, helperOld, helperVega, helperVersion } from "@/lib/bridge";
import { langs } from "@/lib/langs";

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
    const [mbs, cm, tms0] = await Promise.all([opt<any>(mb.search(t)), opt<any>(misc.cinemetaSearch(t)), tmdb(t)]);
    // TMDB doesn't forgive typos: ask again with the best title the others found ("gang of wasepur" -> "Gangs of Wasseypur")
    const best = tms0.length ? "" : bestTitle(t, [...mbs.filter((i: any) => visible(i, mature)), ...cm]);
    const tms = best && best.toLowerCase() !== t.trim().toLowerCase() ? await tmdb(best) : tms0;
    return rank(t, { mb: mbs, tm: tms, cm }, mature);
  },
  "movies/tmdb": (q) => (q.get("id") ? tm.detail(q.get("id")!) : tm.search(q.get("q") ?? "")),
  "movies/detail": async (q) => {
    const id = q.get("id")!;
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
    return (await import("./vega")).streams(q.get("title")!, num(q.get("year")), q.get("type") === "series" ? "series" : "movie", num(q.get("s")), num(q.get("e")), undefined, on);
  };
  const t0 = Date.now(), log = (n: string) => <T,>(p: Promise<T>) => p.then((x: any) => (console.debug(`[stream] ${n}: ${x?.length} in ${Date.now() - t0} ms`), x), (e) => { console.debug(`[stream] ${n}: ${e?.message} after ${Date.now() - t0} ms`); throw e; });
  const r = await Promise.allSettled([log("moviebox")(mbPart().then(emit)), log("dramachi")(drPart().then(emit)), log("4khdhub")(fkPart().then(emit)), log("vega")(vegaPart())]);
  const list = r.flatMap((x) => (x.status === "fulfilled" ? (x.value as any[]) : []));
  if (!list.length) throw new Error((r.find((x) => x.status === "rejected") as PromiseRejectedResult | undefined)?.reason?.message ?? "No streams found");
  return list.sort((a, b) => +!!a.dolby - +!!b.dolby); // stable: Dolby releases (decoded in JS, see use-libav-audio) last, else Android order: MovieBox first, then the title sources (Dramachi, 4KHDHub), each best-first
}
