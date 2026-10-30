import { img, tmdb } from "./episodes";

// TMDB titles that MovieBox and Cinemeta lack (e.g. "Part 2" films). Server side: TMDB_API_KEY stays off the browser.
const year = (d?: string) => Number(d?.slice(0, 4)) || undefined;

export async function search(q: string) {
  const r = (await tmdb(`/search/multi?include_adult=false&query=${encodeURIComponent(q)}`)).results as any[];
  return r.filter((x) => x.media_type === "movie" || x.media_type === "tv").map((x) => ({
    id: `tmdb:${x.media_type}:${x.id}`, type: (x.media_type === "tv" ? "series" : "movie") as "movie" | "series", title: (x.title ?? x.name) as string,
    poster: img(x.poster_path, "w500"), year: year(x.release_date ?? x.first_air_date), rating: Math.round(x.vote_average * 10) / 10 || undefined, genres: [] as string[],
  }));
}

/** id "tmdb:movie:<n>" or "tmdb:tv:<n>"; `imdb` is the tt id when TMDB knows one. */
export async function detail(id: string) {
  const [, kind, n] = id.split(":"), tv = kind === "tv";
  const d = await tmdb(`/${tv ? "tv" : "movie"}/${n}?append_to_response=external_ids,credits`);
  const min = tv ? d.episode_run_time?.[0] : d.runtime;
  return {
    id, type: tv ? "series" : "movie", title: (d.title ?? d.name) as string, poster: img(d.poster_path, "w500"), backdrop: img(d.backdrop_path ?? d.poster_path, "w500"),
    year: year(d.release_date ?? d.first_air_date), rating: Math.round(d.vote_average * 10) / 10 || undefined, genres: ((d.genres ?? []) as any[]).map((g) => g.name as string),
    description: (d.overview as string) || undefined, runtime: min ? `${min} min` : undefined, imdb: (d.imdb_id ?? d.external_ids?.imdb_id) as string | undefined,
    seasons: tv ? ((d.seasons ?? []) as any[]).filter((s) => s.season_number > 0 && s.episode_count > 0).map((s) => ({ season: s.season_number as number, episodes: s.episode_count as number })) : [],
    cast: ((d.credits?.cast ?? []) as any[]).map((c) => c.name as string).slice(0, 12), // same shape as cinemetaDetail; the title page lists them
    country: (d.production_countries?.[0]?.name ?? d.origin_country?.[0]) as string | undefined,
    playable: true,
  };
}
