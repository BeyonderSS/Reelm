// Dramachi (Asian dramas/movies) JSON API. No CORS for browsers but it answers Cloudflare, so it runs server-side.
// Port of android .../dramachi/DramachiSource.kt. The CDN files play with no Referer (the app sends no-referrer).
import { scraperConfig } from "@/lib/scraperConfig";

// Defaults match what shipped; scrapers/scrapers.json (verified at runtime) can override them without a deploy.
const D = { base: "https://api.nodeobjects.com/", cdnPath: "https://{}/cdn/{}" };
type C = typeof D;
const api = async (c: C, q: Record<string, string>) => (await fetch(`${c.base}?${new URLSearchParams(q)}`, { signal: AbortSignal.timeout(10000) })).json() as Promise<any>;
const norm = (t: string) => t.toLowerCase().replace(/\[[^\]]*\]|\([^)]*\)/g, " ").replace(/[^\p{L}\p{N}]+/gu, " ").trim().replace(/\s+(s\d{1,2}|season \d{1,2})$/, "").trim();
const YEAR = /\s+(19|20)\d{2}$/;
const epNum = (t: string) => { // "Queen of Tears S01E16" -> 16, "Show EP 3" -> 3, "Parasite 2019 002" -> 2
  t = t.trim().replace(/\s+(\d{3,4}p|hi dub|dub|eng subbed full)$/i, "");
  const m = t.match(/(?:^|[^a-z])(?:episode|ep\.?|e)[\s._-]*(\d+)/i);
  return m ? +m[1] : Number(t.split(/\s+/).pop()) || undefined;
};

export async function streams(title: string, year: number, type: "movie" | "series", season: number, episode: number) {
  const c = await scraperConfig("dramachi", D);
  const q = title.replace(/\[[^\]]*\]|\([^)]*\)/g, " ").replace(/\s+(s\d{1,2}|season \d{1,2})\s*$/i, "").replace(/\s+/g, " ").trim();
  const hits = ((await api(c, { interface: "search", q, filter: "all", page: "1" })).data ?? []).map((o: any) => ({
    id: String(o.id), title: String(o.title ?? "").replace(YEAR, ""), year: Number(o.year) || Number(String(o.title).match(YEAR)?.[0]) || undefined,
    type: ["movie", "movies"].includes(String(o.content).toLowerCase()) ? "movie" : "series",
  }));
  const m = hits.find((h: any) => h.type === type && norm(h.title) === norm(title) && (type === "series" || !year || !h.year || Math.abs(h.year - year) <= 1));
  if (!m) return [];
  const seasons = (await api(c, { interface: "title_v2", id: m.id })).seasons ?? {};
  const group = seasons[`Season ${String(season).padStart(2, "0")}`] ?? (season <= 1 ? Object.values(seasons)[0] : undefined);
  const rip = (group as any)?.versions?.[0]?.rip;
  if (!rip) return [];
  const all = (((await api(c, { interface: "eplist", season: rip, id: m.id })).episode_list ?? []) as any[])
    .map((o) => ({ n: epNum(o.f_title ?? ""), fid: o.fid, disk: o.disk, height: +(o.quality?.match(/(\d{3,4})p/)?.[1] ?? 0) }))
    .sort((a, b) => (a.n ?? 1e9) - (b.n ?? 1e9));
  const hit = all.filter((e) => e.n === episode);
  const eps = !season && !episode ? all : hit.length ? hit : all.slice(episode - 1, episode);
  const out = await Promise.all(eps.map(async (e, i) => {
    try {
      const f = await api(c, { interface: "getFile", fid: e.fid, findex: e.disk });
      const url = c.cdnPath.replace("{}", String(f.hostInfo.host).replace(/\/$/, "")).replace("{}", String(f.fileInfo[0].url).replace(/^\//, ""));
      const l = e.height ? `${e.height}p` : "Auto";
      return { url, height: e.height, label: `Dramachi · ${eps.length > 1 && !season ? `${l} Part ${i + 1}` : l}`, codec: undefined as string | undefined, subtitles: [] as any[] };
    } catch { return null; }
  }));
  return out.filter((x) => x !== null).sort((a, b) => b.height - a.height);
}
