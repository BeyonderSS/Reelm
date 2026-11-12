import * as hi from "@/lib/server/hianime";
import { movieRoutes } from "@/lib/movies";
import * as misc from "@/lib/server/misc";
import * as recs from "@/lib/server/recs";
import { episodes, animeEpisodes } from "@/lib/server/episodes";

// One catch-all so the server side of every feature is a flat table.
const num = (v: string | null, d = 0) => Number(v) || d;
const KIDS_CH = ["kids", "animation", "family", "education", "cartoon", "children"];

const routes: Record<string, (q: URLSearchParams) => Promise<unknown>> = {
  "anime/home": () => hi.home(),
  "anime/search": (q) => hi.search(q.get("q") ?? "", num(q.get("page"), 1)),
  "anime/browse": (q) => hi.browse(q.get("path") ?? "top-airing", num(q.get("page"), 1)),
  "anime/detail": (q) => hi.detail(q.get("slug")!),
  "anime/episodes": (q) => hi.episodes(q.get("slug")!),
  "anime/stream": (q) => hi.stream(num(q.get("ep")), q.get("lang") ?? "sub"),
  "anime/schedule": () => hi.schedule(),
  "aniskip": (q) => misc.aniskip(q.get("mal")!, q.get("ep")!),
  "introdb": (q) => misc.introdb(q.get("id")!, q.get("s"), q.get("e")),
  "anime/similar": (q) => recs.similarAnime(q.get("slug")!, q.get("mature") === "1", q.get("kids") === "1"),
  "movies/similar": (q) => recs.similarMedia(q.get("id")!, q.get("type") ?? "movie", q.get("title") ?? "", num(q.get("year"))),
  "movies/catalog": (q) => misc.catalog(q.get("key") ?? "movie/top", num(q.get("skip"))),
  "anime/episodes-info": (q) => animeEpisodes(q.get("title") ?? "", q.get("jp") ?? "", num(q.get("year"))),
  "movies/episodes": (q) => episodes(q.get("id")!, num(q.get("season"), 1), q.get("title") ?? "", num(q.get("year"))),
  ...movieRoutes,
  "live": async (q) => {
    const kids = q.get("kids") === "1", g = (q.get("group") ?? "").toLowerCase(), s = (q.get("q") ?? "").toLowerCase();
    const all = await misc.channels(q.get("url") ?? undefined);
    const list = all.filter((c) => (!kids || KIDS_CH.some((k) => c.group?.toLowerCase().includes(k))) && (!g || c.group?.toLowerCase() === g) && (!s || c.name.toLowerCase().includes(s)));
    const groups = [...new Set(all.map((c) => c.group).filter(Boolean))].sort();
    return { channels: list.slice(num(q.get("skip")), num(q.get("skip")) + 120), total: list.length, groups: q.get("skip") ? undefined : groups };
  },
};

export async function GET(req: Request, ctx: { params: Promise<{ p: string[] }> }) {
  const key = (await ctx.params).p.join("/");
  if (key === "sub") { // subtitles as WebVTT (browsers can't show .srt)
    try {
      const r = await fetch(new URL(req.url).searchParams.get("u")!, { headers: { "User-Agent": "Mozilla/5.0" } });
      let t = (await r.text()).replace(/^\uFEFF/, "");
      if (!t.startsWith("WEBVTT")) t = "WEBVTT\n\n" + t.replace(/(\d\d:\d\d:\d\d),(\d{3})/g, "$1.$2");
      return new Response(t, { headers: { "content-type": "text/vtt; charset=utf-8" } });
    } catch { return new Response("WEBVTT\n", { headers: { "content-type": "text/vtt" } }); }
  }
  const fn = routes[key];
  if (!fn) return Response.json({ error: "not found" }, { status: 404 });
  try {
    return Response.json(await fn(new URL(req.url).searchParams), { headers: { "cache-control": key === "movies/episodes" || key === "introdb" ? "public, max-age=3600, s-maxage=86400" : "no-store" } });
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
