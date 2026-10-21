import * as mb from "@/lib/server/moviebox";
import * as misc from "@/lib/server/misc";
import * as dr from "@/lib/server/dramachi";
import { bridge } from "@/lib/bridge";
import { helperReady, playable, serverFetch } from "@/lib/relay";

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
  "movies/search": async (q) => {
    const t = q.get("q") ?? "";
    const m = await mb.search(t).catch(() => []);
    return m.length ? m : misc.cinemetaSearch(t);
  },
  "movies/detail": (q) => {
    const id = q.get("id")!;
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
    // 4KHDHub has no CORS and may block datacenter IPs: the Reelm Helper reaches it from the viewer's own connection when
    // installed, otherwise the Worker tries (if it is blocked there, this source just drops out).
    const fkPart = async () => {
      if (typeof document === "undefined" || !q.get("title")) return [];
      return (await import("./fourkhdhub")).streams(helperReady() ? bridge : serverFetch, q.get("title")!, num(q.get("year")), q.get("type") === "series" ? "series" : "movie", num(q.get("s")), num(q.get("e")));
    };
    const r = await Promise.allSettled([mbPart(), drPart(), fkPart()]);
    const list = r.flatMap((x) => (x.status === "fulfilled" ? (x.value as any[]) : []));
    if (!list.length) throw new Error((r.find((x) => x.status === "rejected") as PromiseRejectedResult | undefined)?.reason?.message ?? "No streams found");
    return list.map((s) => ({ ...s, url: playable(s.url) })).sort((a, b) => +!!a.dolby - +!!b.dolby); // stable: silent-in-browser Dolby releases last, else Android order: MovieBox first, then the title sources (Dramachi, 4KHDHub), each best-first
  },
};
