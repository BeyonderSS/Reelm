import * as hi from "./hianime";
import { kids as kidsPolicy, type CardData } from "../core";

// Port of android core/media Recommendations.kt: TF-IDF taste vector from the seeds, candidates scored by
// cosine similarity (0.6) + how strongly the seeds recommended them (0.3) + rating (0.1), then diversified.
export type Seed = { id: string; kind: "anime" | "media"; title: string; type?: string; year?: number; weight: number };
type Doc = { genres: string[]; text: string };
type Cand<T> = { item: T; doc: Doc; rating?: number; seedWeights: number[] };

const STOP = new Set("with that this from they their them have will into when where which while after before about what your there been were who whose only also more most than then some such over just life story find finds must each other himself herself becomes being world years year these those".split(" "));
const tokens = (d: Doc) => [...d.genres.flatMap((g) => [`g:${g.toLowerCase()}`, `g:${g.toLowerCase()}`]), ...(d.text.toLowerCase().match(/[a-z]+/g) ?? []).filter((w) => w.length > 3 && !STOP.has(w))];
const cosine = (a: Map<string, number>, b: Map<string, number>) => {
  let dot = 0, na = 0, nb = 0;
  for (const [k, v] of b) { dot += v * (a.get(k) ?? 0); nb += v * v; }
  for (const v of a.values()) na += v * v;
  return na && nb ? dot / Math.sqrt(na * nb) : 0;
};

function rank<T>(seeds: { doc: Doc; weight: number }[], cands: Cand<T>[], firstPage = 10, perGenre = 3): Cand<T>[] {
  if (!cands.length) return [];
  const docs = [...seeds.map((s) => tokens(s.doc)), ...cands.map((c) => tokens(c.doc))];
  const df = new Map<string, number>();
  docs.forEach((d) => new Set(d).forEach((t) => df.set(t, (df.get(t) ?? 0) + 1)));
  const n = docs.length;
  const tfidf = (t: string[]) => { const m = new Map<string, number>(); t.forEach((w) => m.set(w, (m.get(w) ?? 0) + 1)); for (const [w, c] of m) m.set(w, (c / t.length) * (Math.log((n + 1) / (df.get(w)! + 1)) + 1)); return m; };
  const taste = new Map<string, number>();
  seeds.forEach((s, i) => { for (const [t, v] of tfidf(docs[i])) taste.set(t, (taste.get(t) ?? 0) + v * s.weight); });
  const sum = (c: Cand<T>) => c.seedWeights.reduce((a, b) => a + b, 0);
  const maxSupport = Math.max(...cands.map(sum)) || 1;
  const sorted = cands.map((c, i) => ({ c, s: 0.6 * cosine(taste, tfidf(docs[seeds.length + i])) + 0.3 * (sum(c) / maxSupport) + 0.1 * Math.min(1, Math.max(0, (c.rating ?? 0) / 10)) }))
    .sort((a, b) => b.s - a.s).map((x) => x.c);
  const head: Cand<T>[] = [], tail: Cand<T>[] = [], counts = new Map<string, number>();
  for (const c of sorted) {
    const g = c.doc.genres[0];
    if (head.length >= firstPage || (g && (counts.get(g) ?? 0) >= perGenre)) tail.push(c);
    else { head.push(c); if (g) counts.set(g, (counts.get(g) ?? 0) + 1); }
  }
  return [...head, ...tail];
}

const SEEDS = 8, ENRICH = 15, KEEP = 20;

async function animePicks(seeds: Seed[], seen: Set<string>, kids: boolean): Promise<CardData[]> {
  if (!seeds.length) return [];
  const det = await Promise.all(seeds.map((s) => hi.detail(s.id).catch(() => null)));
  const agg = new Map<string, { card: hi.Card; by: number[]; d?: Awaited<ReturnType<typeof hi.detail>> | null }>();
  det.forEach((d, i) => d?.recommendations.forEach((r) => { if (!seen.has(r.slug)) (agg.get(r.slug) ?? agg.set(r.slug, { card: r, by: [] }).get(r.slug)!).by.push(i); }));
  const top = [...agg.values()].sort((a, b) => b.by.reduce((x, i) => x + seeds[i].weight, 0) - a.by.reduce((x, i) => x + seeds[i].weight, 0)).slice(0, ENRICH);
  await Promise.all(top.map(async (a) => { a.d = await hi.detail(a.card.slug).catch(() => null); }));
  const doc = (d: any, fb: string): Doc => ({ genres: d?.genres ?? [], text: d?.synopsis ?? fb });
  const cands = top.filter((a) => !kids || (a.d && kidsPolicy.anime(a.d.rating, a.d.genres)))
    .map((a) => ({ item: a, doc: doc(a.d, a.card.title), rating: Number(a.d?.score) || undefined, seedWeights: a.by.map((i) => seeds[i].weight) }));
  return rank(seeds.map((s, i) => ({ doc: doc(det[i], s.title), weight: s.weight })), cands).slice(0, KEEP).map(({ item: a }) => ({
    href: `/anime/${a.card.slug}`, title: a.d?.title || a.card.title, poster: a.card.poster ?? a.d?.poster, sub: `Because you watched ${seeds[a.by[0]].title}`,
    rating: a.card.rating, kind: "anime" as const, genres: a.d?.genres,
  }));
}

const GENRES: Record<number, string> = { 28: "Action", 12: "Adventure", 16: "Animation", 35: "Comedy", 80: "Crime", 99: "Documentary", 18: "Drama", 10751: "Family", 14: "Fantasy", 36: "History", 27: "Horror", 10402: "Music", 9648: "Mystery", 10749: "Romance", 878: "Sci-Fi", 10770: "TV Movie", 53: "Thriller", 10752: "War", 37: "Western", 10759: "Action", 10762: "Kids", 10763: "News", 10764: "Reality", 10765: "Sci-Fi", 10766: "Soap", 10767: "Talk", 10768: "War" };
type T = { type: "movie" | "tv"; id: number; title: string; poster?: string; year?: number; genres: string[]; overview: string; rating?: number };
const tmdb = async (path: string) => {
  const key = process.env.TMDB_API_KEY;
  if (!key) throw new Error("TMDB not configured");
  const r = await fetch(`https://api.themoviedb.org/3${path}${path.includes("?") ? "&" : "?"}api_key=${key}`);
  if (!r.ok) throw new Error(`TMDB HTTP ${r.status}`);
  return r.json() as Promise<any>;
};
const toT = (o: any, type: "movie" | "tv"): T | null => {
  const t = o.media_type === "tv" || o.media_type === "movie" ? o.media_type : type, title = o.title ?? o.name;
  return o.id && title ? { type: t, id: o.id, title, poster: o.poster_path ? `https://image.tmdb.org/t/p/w342${o.poster_path}` : undefined, year: Number((o.release_date ?? o.first_air_date ?? "").slice(0, 4)) || undefined,
    genres: (o.genre_ids ?? []).map((g: number) => GENRES[g]).filter(Boolean), overview: o.overview ?? "", rating: o.vote_average > 0 ? o.vote_average : undefined } : null;
};

async function mediaPicks(seeds: Seed[], seenTitles: Set<string>, kids: boolean): Promise<CardData[]> {
  if (!seeds.length) return [];
  const found = await Promise.all(seeds.map(async (s) => {
    const want = s.type === "series" ? "tv" : "movie";
    const res = ((await tmdb(`/search/${want}?query=${encodeURIComponent(s.title)}`).catch(() => null))?.results ?? []) as any[];
    const hit = res.find((r) => !s.year || Math.abs(Number((r.release_date ?? r.first_air_date ?? "").slice(0, 4)) - s.year) <= 1) ?? res[0];
    return hit ? toT(hit, want) : null;
  }));
  const recs = await Promise.all(found.map(async (f) => f ? (((await tmdb(`/${f.type}/${f.id}/recommendations`).catch(() => null))?.results ?? []) as any[]).map((o) => toT(o, f.type)).filter((x): x is T => !!x) : []));
  const seedKeys = new Set(found.filter(Boolean).map((f) => `${f!.type}:${f!.id}`));
  const agg = new Map<string, { t: T; by: number[] }>();
  recs.forEach((l, i) => l.forEach((t) => {
    const k = `${t.type}:${t.id}`;
    if (!seedKeys.has(k) && !seenTitles.has(t.title.toLowerCase()) && (!kids || kidsPolicy.media(t.genres))) (agg.get(k) ?? agg.set(k, { t, by: [] }).get(k)!).by.push(i);
  }));
  const cands = [...agg.values()].map((a) => ({ item: a, doc: { genres: a.t.genres, text: a.t.overview }, rating: a.t.rating, seedWeights: a.by.map((i) => seeds[i].weight) }));
  const ranked = rank(seeds.map((s, i) => ({ doc: { genres: found[i]?.genres ?? [], text: found[i]?.overview ?? s.title }, weight: s.weight })), cands).slice(0, KEEP);
  // IMDb ids only for what is shown, so the card opens the regular /title/tt… page
  const ids = await Promise.all(ranked.map(async (c) => (await tmdb(`/${c.item.t.type}/${c.item.t.id}/external_ids`).catch(() => null))?.imdb_id as string | undefined));
  return ranked.flatMap((c, i) => !ids[i]?.startsWith("tt") ? [] : [{
    href: `/title/${ids[i]}?type=${c.item.t.type === "tv" ? "series" : "movie"}`, title: c.item.t.title, poster: c.item.t.poster,
    sub: `Because you watched ${seeds[c.item.by[0]].title}`, rating: c.item.t.rating?.toFixed(1), kind: "media" as const, genres: c.item.t.genres,
  }]);
}

/** One interleaved list of anime and movie/series picks. `seen` = slugs/ids already watched, `seenTitles` lower-case. */
export async function recommend(body: { seeds: Seed[]; seen: string[]; seenTitles: string[]; kids?: boolean }): Promise<CardData[]> {
  const seeds = [...body.seeds].sort((a, b) => b.weight - a.weight), seen = new Set(body.seen), titles = new Set(body.seenTitles.map((t) => t.toLowerCase())), kids = !!body.kids;
  const [a, m] = await Promise.all([
    animePicks(seeds.filter((s) => s.kind === "anime").slice(0, SEEDS), seen, kids).catch(() => []),
    mediaPicks(seeds.filter((s) => s.kind === "media").slice(0, SEEDS), titles, kids).catch(() => []),
  ]);
  const out: CardData[] = [];
  for (let i = 0; i < Math.max(a.length, m.length); i++) { if (m[i]) out.push(m[i]); if (a[i]) out.push(a[i]); }
  return out;
}
