import * as mb from "@/lib/server/moviebox";
import * as misc from "@/lib/server/misc";
import * as dr from "@/lib/server/dramachi";
import * as tm from "@/lib/server/tmdb";
import { bestTitle, rank, visible } from "@/lib/search-rank";
import { bridge, helperOld, helperVega, helperVersion } from "@/lib/bridge";

// MovieBox refuses datacenter IPs (Cloudflare, Vercel: HTTP 440) but allows CORS, so the browser runs these
// from the viewer's own connection (see get() in core.ts). The API route keeps them for local dev.
const num = (v: string | null, d = 0) => Number(v) || d;

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
    const tmdb = (x: string) => opt<any>(typeof window === "undefined" ? tm.search(x) : fetch(`/api/movies/tmdb?q=${encodeURIComponent(x)}`).then((r) => r.json()));
    const [mbs, cm, tms0] = await Promise.all([opt<any>(mb.search(t)), opt<any>(misc.cinemetaSearch(t)), tmdb(t)]);
    // TMDB doesn't forgive typos: ask again with the best title the others found ("gang of wasepur" -> "Gangs of Wasseypur")
    const best = tms0.length ? "" : bestTitle(t, [...mbs.filter((i: any) => visible(i, mature)), ...cm]);
    const tms = best && best.toLowerCase() !== t.trim().toLowerCase() ? await tmdb(best) : tms0;
    return rank(t, { mb: mbs, tm: tms, cm }, mature);
  },
  "movies/tmdb": (q) => (q.get("id") ? tm.detail(q.get("id")!) : tm.search(q.get("q") ?? "")),
  "movies/detail": (q) => {
    const id = q.get("id")!;
    if (id.startsWith("tmdb:")) return typeof window === "undefined" ? tm.detail(id) : fetch(`/api/movies/tmdb?id=${encodeURIComponent(id)}`).then((r) => r.json());
    return id.startsWith("mb:") ? mb.detail(id.slice(3)) : misc.cinemetaDetail(id, q.get("type") === "series" ? "series" : "movie");
  },
  // Dramachi has no CORS, so the browser asks our server for it (below); a source that fails drops out alone.
  "movies/dramachi": (q) => dr.streams(q.get("title") ?? "", num(q.get("year")), q.get("type") === "series" ? "series" : "movie", num(q.get("s")), num(q.get("e"))),
  "movies/stream": async (q) => {
    const id = q.get("id")!;
    const mbPart = async () => {
      const sid = id.startsWith("mb:") ? id.slice(3) : await findMb(q);
      if (!sid) throw new Error("No stream source found for this title");
      return (await mb.streams(sid, num(q.get("s")), num(q.get("e")))).map((x) => ({ ...x, label: `MovieBox · ${x.label}` }));
    };
    const drPart = async () => {
      if (!q.get("title")) return []; // an "mb:" id arrives without a title
      return typeof window === "undefined" ? movieRoutes["movies/dramachi"](q) : (await fetch(`/api/movies/dramachi?${q}`)).json();
    };
    // 4KHDHub blocks datacenter IPs and has no CORS: only the Reelm Helper (1.1+) can reach it, from the viewer's browser.
    const fkPart = async () => {
      if (typeof document === "undefined" || !q.get("title") || helperOld() || !helperVersion()) return [];
      return (await import("./fourkhdhub")).streams(bridge, q.get("title")!, num(q.get("year")), q.get("type") === "series" ? "series" : "movie", num(q.get("s")), num(q.get("e")));
    };
    // Vega providers (open-source scrapers, see vega.ts): same gate, needs Helper 1.2+ (any host, Referer rules).
    const vegaPart = async () => {
      if (typeof document === "undefined" || !q.get("title") || !helperVega()) return [];
      return (await import("./vega")).streams(q.get("title")!, num(q.get("year")), q.get("type") === "series" ? "series" : "movie", num(q.get("s")), num(q.get("e")));
    };
    const r = await Promise.allSettled([mbPart(), drPart(), fkPart(), vegaPart()]);
    const list = r.flatMap((x) => (x.status === "fulfilled" ? (x.value as any[]) : []));
    if (!list.length) throw new Error((r.find((x) => x.status === "rejected") as PromiseRejectedResult | undefined)?.reason?.message ?? "No streams found");
    return list.sort((a, b) => +!!a.dolby - +!!b.dolby); // stable: Dolby releases (decoded in JS, see use-libav-audio) last, else Android order: MovieBox first, then the title sources (Dramachi, 4KHDHub), each best-first
  },
};
