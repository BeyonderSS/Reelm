// Episode stills, air dates and overviews from TMDB. Server side so TMDB_API_KEY stays off the browser.
export const tmdb = async (path: string) => {
  const key = process.env.TMDB_API_KEY;
  if (!key) throw new Error("TMDB not configured");
  const r = await fetch(`https://api.themoviedb.org/3${path}${path.includes("?") ? "&" : "?"}api_key=${key}`);
  if (!r.ok) throw new Error(`TMDB HTTP ${r.status}`);
  return r.json() as Promise<any>;
};
export const img = (p: string | null, w: string) => (p ? `https://image.tmdb.org/t/p/${w}${p}` : undefined);

/** IMDb ids resolve through /find; MovieBox (mb:) ids by title and year. Returns null when TMDB has no match. */
export async function episodes(id: string, season: number, title: string, year: number) {
  let tv: number | undefined;
  if (/^tt\d+$/.test(id)) tv = (await tmdb(`/find/${id}?external_source=imdb_id`)).tv_results?.[0]?.id;
  else if (title) tv = (await tmdb(`/search/tv?query=${encodeURIComponent(title)}${year ? `&first_air_date_year=${year}` : ""}`)).results?.[0]?.id;
  if (!tv) return null;
  const s = await tmdb(`/tv/${tv}/season/${season}`);
  return {
    overview: (s.overview as string) || undefined, poster: img(s.poster_path, "w342"), name: s.name as string | undefined,
    episodes: ((s.episodes ?? []) as any[]).map((e) => ({
      n: e.episode_number as number, title: e.name as string, overview: (e.overview as string) || undefined, still: img(e.still_path, "w300"),
      air: e.air_date as string | undefined, runtime: (e.runtime as number) || undefined, rating: e.vote_count > 2 ? Math.round(e.vote_average * 10) / 10 : undefined,
    })),
  };
}

// Anime: Kitsu (free, no key) has per-episode titles, synopses, air dates and thumbnails. Matched by title, then the
// Japanese title, preferring the hianime premiere year.
const kitsu = async (path: string) => {
  const r = await fetch(`https://kitsu.io/api/edge${path}`, { headers: { Accept: "application/vnd.api+json" } });
  if (!r.ok) throw new Error(`Kitsu HTTP ${r.status}`);
  return r.json() as Promise<any>;
};

/** ponytail: first 200 episodes (10 pages of 20); long runners fall back to plain tiles past that. */
export async function animeEpisodes(title: string, japanese: string, year: number) {
  let hit: any;
  for (const q of [title, japanese].filter(Boolean)) {
    const res = (await kitsu(`/anime?filter[text]=${encodeURIComponent(q)}&page[limit]=5&fields[anime]=startDate,canonicalTitle`)).data ?? [];
    hit = res.find((a: any) => year && a.attributes.startDate?.startsWith(String(year))) ?? res[0];
    if (hit) break;
  }
  if (!hit) return null;
  const pages = await Promise.all([...Array(10)].map((_, i) =>
    kitsu(`/anime/${hit.id}/episodes?page[limit]=20&page[offset]=${i * 20}&sort=number`).then((r) => r.data ?? []).catch(() => [])));
  const episodes = pages.flat().filter((e: any) => e.attributes.number).map((e: any) => {
    const a = e.attributes;
    return { n: a.number, title: a.canonicalTitle || `Episode ${a.number}`, overview: a.synopsis || undefined,
      still: a.thumbnail?.original || undefined, air: a.airdate || undefined, runtime: a.length || undefined };
  });
  return episodes.length ? { episodes } : null;
}
