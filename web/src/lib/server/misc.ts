const CINEMETA = "https://v3-cinemeta.strem.io";
const j = async (u: string) => { const r = await fetch(u, { headers: { "User-Agent": "Mozilla/5.0 Reelm" } }); if (!r.ok) throw new Error(`HTTP ${r.status} from ${new URL(u).host}`); return r.json() as Promise<any>; };

const meta = (m: any) => ({
  id: m.id as string, type: (m.type === "series" ? "series" : "movie") as "movie" | "series", title: m.name as string, poster: m.poster as string | undefined,
  year: Number(String(m.releaseInfo ?? m.year ?? "").slice(0, 4)) || undefined, rating: Number(m.imdbRating) || undefined, genres: (m.genres ?? []) as string[],
  backdrop: m.background as string | undefined, description: m.description as string | undefined,
});

/** key: "<type>/<catalogId>" or "<type>/<catalogId>/genre=Sci-Fi" */
export async function catalog(key: string, skip = 0) {
  const [type, id, extra] = key.split("/");
  const e = [extra, skip ? `skip=${skip}` : ""].filter(Boolean).join("&");
  return ((await j(`${CINEMETA}/catalog/${type}/${id}${e ? "/" + e : ""}.json`)).metas ?? []).map(meta);
}

export async function cinemetaSearch(q: string) {
  const r = await Promise.all(["movie", "series"].map((t) => j(`${CINEMETA}/catalog/${t}/top/search=${encodeURIComponent(q)}.json`).then((d) => (d.metas ?? []).map(meta)).catch(() => [])));
  return r.flat();
}

export async function cinemetaDetail(id: string, type: string) {
  const m = (await j(`${CINEMETA}/meta/${type}/${id}.json`)).meta;
  const seasons = new Map<number, number>();
  for (const v of (m.videos ?? []) as any[]) if (v.season > 0) seasons.set(v.season, Math.max(seasons.get(v.season) ?? 0, v.episode ?? v.number ?? 0));
  return {
    ...meta(m), description: m.description as string | undefined, backdrop: (m.background ?? m.poster) as string | undefined, runtime: m.runtime as string | undefined,
    country: m.country as string | undefined, cast: ((m.cast ?? []) as string[]).slice(0, 12), certification: m.certification as string | undefined,
    seasons: [...seasons].map(([season, episodes]) => ({ season, episodes })).sort((a, b) => a.season - b.season), playable: true,
  };
}

export type Channel = { name: string; logo?: string; group?: string; url: string };
export const IPTV = "https://iptv-org.github.io/iptv/index.m3u";
let cache: { url: string; at: number; list: Channel[] } | undefined;

export async function channels(url = IPTV) {
  if (cache?.url === url && Date.now() - cache.at < 36e5) return cache.list;
  if (!/^https?:\/\//.test(url)) throw new Error("Playlist URL must be http(s)");
  const text = await (await fetch(url)).text();
  const list: Channel[] = [];
  let cur: Omit<Channel, "url"> | undefined;
  for (const raw of text.split("\n")) {
    const l = raw.trim();
    if (l.startsWith("#EXTINF")) {
      const a = (k: string) => l.match(new RegExp(`${k}="([^"]*)"`))?.[1] || undefined;
      cur = { name: l.slice(l.lastIndexOf(",") + 1).trim() || a("tvg-name") || "Channel", logo: a("tvg-logo"), group: a("group-title") };
    } else if (cur && /^https?:\/\//.test(l)) {
      if (/\.mpd(\?|$)/.test(l)) { cur = undefined; continue; } // hls.js plays HLS only
      list.push({ ...cur, url: l }); cur = undefined;
    }
  }
  cache = { url, at: Date.now(), list };
  return list;
}

export async function aniskip(mal: string, ep: string) {
  return j(`https://api.aniskip.com/v2/skip-times/${mal}/${ep}?types[]=op&types[]=recap&types[]=ed&types[]=mixed-op&episodeLength=0`).catch(() => ({ results: [] }));
}

/** TheIntroDB (movies and series) in AniSkip's shape; endTime null = end of video. id: "tt…" or a TMDB number. */
const INTRODB_TYPES = { intro: "op", recap: "recap", credits: "ed", preview: "preview" } as const;
export async function introdb(id: string, season: string | null, episode: string | null) {
  const q = new URLSearchParams(/^tt\d+$/.test(id) ? { imdb_id: id } : { tmdb_id: id });
  if (Number(season) && Number(episode)) { q.set("season", season!); q.set("episode", episode!); }
  const r = await fetch(`https://api.theintrodb.org/v4/media?${q}`, { headers: { "User-Agent": "Mozilla/5.0 Reelm" } });
  if (r.status === 404) return { results: [] };
  if (!r.ok) throw new Error(`HTTP ${r.status} from theintrodb`);
  const d = await r.json() as Record<string, { start_ms: number | null; end_ms: number | null }[]>;
  return { results: Object.entries(INTRODB_TYPES).flatMap(([k, skipType]) => (d[k] ?? []).map((x) => ({ skipType, interval: { startTime: (x.start_ms ?? 0) / 1000, endTime: x.end_ms == null ? null : x.end_ms / 1000 } }))) };
}
