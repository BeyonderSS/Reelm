import * as hi from "./hianime";
import { kids as kidsPolicy, toPlay, type CardData } from "../core";
import { blend, erotic, INDIAN, lowRated, matureOk, norm, rank, roundRobin, srvKey, type Doc, type ServerRec } from "../recs-rank";

export type Seed = { id: string; kind: "anime" | "media"; title: string; type?: string; year?: number; weight: number };
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// ponytail: per-instance memo, cleared wholesale at 3000 keys; a KV cache if instances stay cold
const memo = new Map<string, { at: number; v: Promise<any> }>();
export function cached<V>(key: string, ttl: number, fn: () => Promise<V>): Promise<V> {
  const hit = memo.get(key);
  if (hit && Date.now() - hit.at < ttl) return hit.v;
  if (memo.size > 3000) memo.clear();
  const v = fn(); memo.set(key, { at: Date.now(), v }); v.catch(() => memo.delete(key));
  return v;
}

const SEEDS = 8, ENRICH = 24, KEEP = 30;

// Jikan (MAL) asks for ≤3 requests/s: each call reserves the next 350 ms slot.
let jikanNext = 0;
const jikan = (path: string): Promise<any> => cached(`j:${path}`, 864e5, async () => {
  for (let i = 0; ; i++) {
    const at = Math.max(Date.now(), jikanNext); jikanNext = at + 350;
    await sleep(at - Date.now());
    const r = await fetch(`https://api.jikan.moe/v4${path}`, { signal: AbortSignal.timeout(8000) }).catch(() => null);
    if (r?.ok) return r.json();
    if (i || (r && r.status !== 429 && r.status < 500)) throw new Error(`Jikan HTTP ${r?.status ?? "network"}`);
    await sleep(1000);
  }
});
const hiDetail = (slug: string) => cached(`hd:${slug}`, 6 * 36e5, () => hi.detail(slug));
const hiFind = (title: string) => cached(`hs:${title}`, 864e5, async () => (await hi.search(title))[0] ?? null);
type Detail = Awaited<ReturnType<typeof hi.detail>>;
type Pick = { key: string; title: string; poster?: string; card?: hi.Card; votes: number };

async function malOf(d: Detail): Promise<number | undefined> {
  const r = ((await jikan(`/anime?q=${encodeURIComponent(d.japanese || d.title)}&limit=5`)).data ?? []) as any[];
  const want = [d.title, d.japanese].filter(Boolean).map((t) => norm(t!));
  const names = (x: any) => [x.title, x.title_english, x.title_japanese, ...(x.titles ?? []).map((t: any) => t.title)].filter(Boolean).map(norm);
  return (r.find((x) => names(x).some((n) => want.includes(n))) ?? r.find((x) => d.year && Math.abs((x.year ?? x.aired?.prop?.from?.year ?? 0) - d.year) <= 1) ?? r[0])?.mal_id;
}

/** MAL's user recommendations for a hianime title; hianime's own (generic) list when there's no MAL match. */
async function similarPicks(d: Detail): Promise<{ mal: boolean; list: Pick[] }> {
  try {
    const id = await malOf(d);
    const recs = id ? ((await jikan(`/anime/${id}/recommendations`)).data ?? []) as any[] : [];
    if (recs.length) return { mal: true, list: recs.slice(0, 12).map((r) => ({ key: `mal:${r.entry.mal_id}`, title: r.entry.title, poster: r.entry.images?.webp?.large_image_url ?? r.entry.images?.jpg?.large_image_url, votes: r.votes ?? 0 })) };
  } catch {}
  return { mal: false, list: d.recommendations.map((c) => ({ key: c.slug, title: c.title, poster: c.poster, card: c, votes: 0 })) };
}
// ponytail: first hianime search hit for the MAL title; compare jname if wrong matches show up
const toCard = async (p: Pick) => p.card ?? (await hiFind(p.title).catch(() => null)) ?? undefined;

/** "You might also like" on an anime page: MAL recommendations mapped onto hianime, filtered for kids/mature. */
export async function similarAnime(slug: string, mature: boolean, kids: boolean) {
  const { mal, list } = await similarPicks(await hiDetail(slug));
  const seen = new Set([slug]), cards = (await Promise.all(list.map(toCard))).filter((c): c is hi.Card => !!c && !seen.has(c.slug) && !!seen.add(c.slug));
  const ok = mature && !kids ? cards.map(() => true) : await Promise.all(cards.map((c) => hiDetail(c.slug).then((d) => matureOk(d.genres, mature) && (!kids || kidsPolicy.anime(d.rating, d.genres)), () => !kids)));
  return { source: mal ? "mal" : "hianime", items: cards.filter((_, i) => ok[i]) };
}

export type Rec = CardData & { by: string[] };
type Opts = { seen: Set<string>; titles: Set<string>; kids: boolean; mature: boolean; lang: string; srv: ServerRec[]; conf: number };
/** Card line and `by` for a pick; server-only picks without a local seed fall back to the server's `becauseTitle`. */
const why = (seeds: Seed[], by: number[], t?: string | null) => { const b = by.length ? by.map((i) => seeds[i].title) : t ? [t] : []; return { sub: b[0] ? `Because you watched ${b[0]}` : "Watched by people like you", by: b }; };

async function animePicks(seeds: Seed[], o: Opts): Promise<Rec[]> {
  if (!seeds.length) return [];
  const det = await Promise.all(seeds.map((s) => hiDetail(s.id).catch(() => null)));
  const lists = await Promise.all(det.map((d) => d ? similarPicks(d).then((x) => x.list, () => []) : []));
  const agg = new Map<string, { p: Pick; by: number[]; card?: hi.Card; d?: Detail | null; why?: string | null }>();
  lists.forEach((l, i) => l.forEach((p) => {
    if (o.titles.has(norm(p.title))) return;
    const a = agg.get(p.key) ?? agg.set(p.key, { p, by: [] }).get(p.key)!;
    if (!a.by.includes(i)) a.by.push(i);
    a.p.votes = Math.max(a.p.votes, p.votes);
  }));
  const top = roundRobin([...agg.values()].sort((a, b) => b.by.length - a.by.length || b.p.votes - a.p.votes), (a) => a.by[0]).slice(0, ENRICH);
  await Promise.all(top.map(async (a) => { a.card = await toCard(a.p); a.d = a.card ? await hiDetail(a.card.slug).catch(() => null) : null; }));
  const doc = (d: any, fb: string): Doc => ({ genres: d?.genres ?? [], text: d?.synopsis ?? fb });
  const slugs = new Set([...o.seen, ...seeds.map((s) => s.id)]);
  const ok = (a: { card?: hi.Card; d?: Detail | null }) => a.card && !slugs.has(a.card.slug) && !o.titles.has(norm(a.card.title)) && slugs.add(a.card.slug) && matureOk(a.d?.genres, o.mature) && (!o.kids || (a.d && kidsPolicy.anime(a.d.rating, a.d.genres)));
  const cand = (a: typeof top[number]) => ({ item: a, doc: doc(a.d, a.p.title), rating: Number(a.d?.score) || undefined, year: a.d?.year, by: a.by });
  // server-only anime picks get the same detail fetch (genres for the mature/kids checks and the taste match); top 10 only
  const srv = o.conf > 0 ? o.srv.filter((r) => r.item.startsWith("anime:")).slice(0, 10) : [];
  const sd = new Map(await Promise.all(srv.map(async (r) => [r.item, await hiDetail(r.item.slice(6)).catch(() => null)] as const)));
  const cands = blend(top.filter(ok).map(cand), (c) => `anime:${c.item.card!.slug}`, srv, o.conf, (r) => {
    const p = toPlay(r.payload), d = sd.get(r.item), i = seeds.findIndex((s) => r.because === `anime:${s.id}`);
    if (p?.kind !== "anime") return null;
    const a = { p: { key: p.id, title: d?.title || p.title, poster: p.poster, votes: 0 }, by: i < 0 ? [] : [i], card: { slug: p.id, title: d?.title || p.title, poster: p.poster ?? d?.poster }, d, why: r.becauseTitle };
    return ok(a) ? cand(a) : null;
  });
  return rank(seeds.map((s, i) => ({ doc: doc(det[i], s.title), weight: s.weight })), cands).slice(0, KEEP).map(({ item: a }) => ({
    href: `/anime/${a.card!.slug}`, title: a.d?.title || a.card!.title, poster: a.card!.poster ?? a.d?.poster ?? a.p.poster, ...why(seeds, a.by, a.why),
    rating: a.card!.rating, kind: "anime" as const, genres: a.d?.genres,
  }));
}

export const GENRES: Record<number, string> = { 28: "Action", 12: "Adventure", 16: "Animation", 35: "Comedy", 80: "Crime", 99: "Documentary", 18: "Drama", 10751: "Family", 14: "Fantasy", 36: "History", 27: "Horror", 10402: "Music", 9648: "Mystery", 10749: "Romance", 878: "Sci-Fi", 10770: "TV Movie", 53: "Thriller", 10752: "War", 37: "Western", 10759: "Action", 10762: "Kids", 10763: "News", 10764: "Reality", 10765: "Sci-Fi", 10766: "Soap", 10767: "Talk", 10768: "War" };
type T = { type: "movie" | "tv"; id: number; title: string; poster?: string; year?: number; genres: string[]; gids: number[]; overview: string; rating?: number; votes?: number; lang?: string; adult?: boolean };
// At most 4 TMDB requests in flight, one retry: ~36 at once lost about half to ECONNRESET. Responses are memoised for 6 h.
let busy = 0;
const waiting: (() => void)[] = [];
export const tmdb = (path: string): Promise<any> => cached(`t:${path}`, 6 * 36e5, async () => {
  const key = process.env.TMDB_API_KEY;
  if (!key) throw new Error("TMDB not configured");
  while (busy >= 4) await new Promise<void>((r) => waiting.push(r));
  busy++;
  try {
    for (let i = 0; ; i++) {
      const r = await fetch(`https://api.themoviedb.org/3${path}${path.includes("?") ? "&" : "?"}api_key=${key}`).catch(() => null);
      const j = r?.ok ? await r.json().catch(() => null) : null; // the body can reset mid-read too
      if (j) return j;
      if (i || (r && !r.ok && r.status !== 429 && r.status < 500)) throw new Error(`TMDB HTTP ${r?.status ?? "network"}`);
      await sleep(500);
    }
  } finally { busy--; waiting.shift()?.(); }
});
const toT = (o: any, type: "movie" | "tv"): T | null => {
  const t = o.media_type === "tv" || o.media_type === "movie" ? o.media_type : type, title = o.title ?? o.name;
  return o.id && title ? { type: t, id: o.id, title, poster: o.poster_path ? `https://image.tmdb.org/t/p/w342${o.poster_path}` : undefined, year: Number((o.release_date ?? o.first_air_date ?? "").slice(0, 4)) || undefined,
    genres: (o.genre_ids ?? []).map((g: number) => GENRES[g]).filter(Boolean), gids: o.genre_ids ?? [], overview: o.overview ?? "", rating: o.vote_average > 0 ? o.vote_average : undefined, votes: o.vote_count ?? 0,
    lang: o.original_language || undefined, adult: !!o.adult } : null;
};

/** The TMDB entry for a history seed: by IMDb id (/find) or tmdb: id when we have one, else by title (+year). */
async function findSeed(s: Seed): Promise<T | null> {
  const want = s.type === "series" ? "tv" : "movie", m = s.id.match(/^tmdb:(movie|tv):(\d+)$/);
  if (m) { const d = await tmdb(`/${m[1]}/${m[2]}`).catch(() => null); if (d) return toT({ ...d, genre_ids: (d.genres ?? []).map((g: any) => g.id) }, m[1] as "movie" | "tv"); }
  if (/^tt\d+$/.test(s.id)) {
    const f = await tmdb(`/find/${s.id}?external_source=imdb_id`).catch(() => null), mv = f?.movie_results?.[0], tv = f?.tv_results?.[0];
    const hit = want === "tv" ? tv ?? mv : mv ?? tv;
    if (hit) return toT(hit, hit === tv ? "tv" : "movie");
  }
  const res = ((await tmdb(`/search/${want}?query=${encodeURIComponent(s.title)}`).catch(() => null))?.results ?? []) as any[];
  const hit = res.find((r) => !s.year || Math.abs(Number((r.release_date ?? r.first_air_date ?? "").slice(0, 4)) - s.year) <= 1) ?? res[0];
  return hit ? toT(hit, want) : null;
}

async function mediaPicks(seeds: Seed[], o: Opts): Promise<Rec[]> {
  if (!seeds.length) return [];
  const found = await Promise.all(seeds.map((s) => findSeed(s).catch(() => null)));
  // Indian-language seeds also pull popular titles in that language and genre; TMDB's recommendations lean Hollywood
  const recs = await Promise.all(found.map(async (f) => !f ? [] : (await Promise.all([`/${f.type}/${f.id}/recommendations`,
    ...(f.lang && INDIAN.includes(f.lang) ? [`/discover/${f.type}?with_original_language=${f.lang}&with_genres=${f.gids.slice(0, 2).join("|")}&sort_by=popularity.desc&vote_count.gte=${f.type === "tv" ? 10 : 50}`] : [])]
    .map((p) => tmdb(p).then((d) => (d.results ?? []) as any[], () => [])))).flat().map((x) => toT(x, f.type)).filter((x): x is T => !!x)));
  const seedKeys = new Set(found.filter(Boolean).map((f) => `${f!.type}:${f!.id}`));
  const agg = new Map<string, { t: T; by: number[]; href?: string; mid?: string; why?: string | null }>();
  recs.forEach((l, i) => l.forEach((t) => {
    const k = `${t.type}:${t.id}`;
    if (seedKeys.has(k) || o.seen.has(`tmdb:${k}`) || o.titles.has(norm(t.title)) || lowRated(t.rating, t.votes) || t.adult || (!o.mature && erotic(t)) || (o.kids && !kidsPolicy.media(t.genres))) return;
    const a = agg.get(k) ?? agg.set(k, { t, by: [] }).get(k)!;
    if (!a.by.includes(i)) a.by.push(i);
  }));
  // server-only picks get their TMDB entry (genres, synopsis, language, rating) so they rank like any candidate; top 10 only
  const srv = o.conf > 0 ? o.srv.filter((r) => r.item.startsWith("media:")).slice(0, 10) : [];
  const sd = new Map(await Promise.all(srv.map(async (r) => { const p = toPlay(r.payload);
    return [r.item, p?.kind === "movie" ? await findSeed({ id: p.id, kind: "media", title: p.title, type: p.type, year: p.year, weight: 1 }).catch(() => null) : null] as const; })));
  const cands = blend([...agg.values()].map((a) => ({ item: a, doc: { genres: a.t.genres, text: a.t.overview }, rating: a.t.rating, year: a.t.year, lang: a.t.lang, by: a.by })),
    (c) => `media:${norm(c.item.t.title)}`, srv, o.conf, (r) => {
      const p = toPlay(r.payload), i = seeds.findIndex((s) => srvKey(r.because ?? "") === `media:${norm(s.title)}`);
      if (p?.kind !== "movie" || o.seen.has(p.id) || o.titles.has(norm(p.title)) || (!o.mature && erotic({ title: p.title }))) return null;
      const f = sd.get(r.item), t: T = { ...(f ?? { id: 0, genres: [], gids: [], overview: "" }), type: p.type === "series" ? "tv" : "movie", title: p.title, poster: f?.poster ?? p.poster, year: p.year ?? f?.year };
      if (t.adult || lowRated(t.rating, t.votes) || (!o.mature && erotic(t)) || (o.kids && !kidsPolicy.media(t.genres))) return null;
      const by = i < 0 ? [] : [i];
      return { item: { t, by, href: `/title/${encodeURIComponent(p.id)}?type=${p.type}`, mid: p.id, why: r.becauseTitle }, doc: { genres: t.genres, text: t.overview || p.title }, rating: t.rating, year: t.year, lang: t.lang, by };
    });
  const ranked = rank(seeds.map((s, i) => ({ doc: { genres: found[i]?.genres ?? [], text: found[i]?.overview ?? s.title }, weight: s.weight, lang: found[i]?.lang })), cands, o.lang).slice(0, KEEP);
  // IMDb ids only for what is shown, so the card opens the regular /title/tt… page (tmdb: id when TMDB has none)
  const ids = await Promise.all(ranked.map(async (c) => c.item.href ? undefined : (await tmdb(`/${c.item.t.type}/${c.item.t.id}/external_ids`).catch(() => null))?.imdb_id as string | undefined));
  return ranked.flatMap((c, i) => (ids[i] && o.seen.has(ids[i])) || (c.item.mid && ids.includes(c.item.mid)) ? [] : [{
    href: c.item.href ?? `/title/${ids[i]?.startsWith("tt") ? ids[i] : encodeURIComponent(`tmdb:${c.item.t.type}:${c.item.t.id}`)}?type=${c.item.t.type === "tv" ? "series" : "movie"}`, title: c.item.t.title, poster: c.item.t.poster,
    ...why(seeds, c.item.by, c.item.why), rating: c.item.t.rating?.toFixed(1), kind: "media" as const, genres: c.item.t.genres,
  }]);
}

/** One interleaved list of anime and movie/series picks; `by` = the seed titles behind each. `seen` = slugs/ids already watched or saved. */
export async function recommend(body: { seeds: Seed[]; seen: string[]; seenTitles: string[]; kids?: boolean; mature?: boolean; lang?: string; server?: { items?: ServerRec[]; confidence?: number } }): Promise<Rec[]> {
  const seeds = [...body.seeds].sort((a, b) => b.weight - a.weight);
  const o: Opts = { seen: new Set(body.seen), titles: new Set([...body.seenTitles, ...seeds.map((s) => s.title)].map(norm)), kids: !!body.kids, mature: !!body.mature && !body.kids, lang: body.lang ?? "",
    srv: (Array.isArray(body.server?.items) ? body.server.items : []).filter((r) => typeof r?.item === "string" && r.score > 0 && r.payload), conf: Math.min(1, Number(body.server?.confidence) || 0) };
  const [a, m] = await Promise.all([
    animePicks(seeds.filter((s) => s.kind === "anime").slice(0, SEEDS), o).catch(() => []),
    mediaPicks(seeds.filter((s) => s.kind === "media").slice(0, SEEDS), o).catch(() => []),
  ]);
  const out: Rec[] = [];
  for (let i = 0; i < Math.max(a.length, m.length); i++) { if (m[i]) out.push(m[i]); if (a[i]) out.push(a[i]); }
  return out.filter((r) => r.poster); // a history payload can lack a poster and the lookup can miss: no blank cards
}

/** Posters for /v1/recs payloads that carry none (history rows can lack one), by the same TMDB / hianime lookup the picks use. */
export async function posters(items: ServerRec[]): Promise<Record<string, string>> {
  const got = await Promise.all(items.slice(0, 20).map(async (r) => {
    const p = toPlay(r.payload);
    const poster = p?.poster || (p?.kind === "anime" ? (await hiDetail(p.id).catch(() => null))?.poster
      : p?.kind === "movie" ? (await findSeed({ id: p.id, kind: "media", title: p.title, type: p.type, year: p.year, weight: 1 }).catch(() => null))?.poster : undefined);
    return [r.item, poster] as const;
  }));
  return Object.fromEntries(got.filter((x): x is [string, string] => !!x[1]));
}

const tmdbMeta = (o: any, type: "movie" | "tv") => ({
  id: `tmdb:${type}:${o.id}`, type: type === "tv" ? "series" : "movie", title: (o.title ?? o.name) as string,
  poster: o.poster_path ? `https://image.tmdb.org/t/p/w342${o.poster_path}` : undefined, backdrop: o.backdrop_path ? `https://image.tmdb.org/t/p/w780${o.backdrop_path}` : undefined,
  year: Number((o.release_date ?? o.first_air_date ?? "").slice(0, 4)) || undefined, rating: Math.round(o.vote_average * 10) / 10 || undefined,
  genres: ((o.genre_ids ?? []) as number[]).map((g) => GENRES[g]).filter(Boolean), description: (o.overview as string) || undefined,
});
const okMeta = (o: any) => o.id && (o.title ?? o.name) && o.poster_path && !o.adult && !erotic(o);

// TMDB's trending ignores region, so "New in India" is now_playing?region=IN.
const INDIA = [
  ["Bollywood", "/discover/movie?with_original_language=hi&region=IN&sort_by=popularity.desc&vote_count.gte=50"],
  ["South Indian hits", "/discover/movie?with_original_language=te|ta|ml|kn&region=IN&sort_by=popularity.desc&vote_count.gte=50"],
  ["Indian series", "/discover/tv?with_origin_country=IN&with_genres=18|80|10759|9648&without_genres=10766|10767|10764&first_air_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=15"],
  ["New in India", "/movie/now_playing?region=IN"],
] as const;
/** Indian rows for Home, in the Cinemeta catalog shape (ids "tmdb:<type>:<n>"). */
export async function india() {
  return (await Promise.all(INDIA.map(async ([title, path]) => ({
    title, items: (((await tmdb(`${path}&include_adult=false`).catch(() => null))?.results ?? []) as any[]).filter(okMeta).map((o) => tmdbMeta(o, path.includes("/tv") ? "tv" : "movie")),
  })))).filter((r) => r.items.length);
}

/** "More like this" on a movie/series page: TMDB recommendations for a tt…/tmdb: id, else found by title. */
export async function similarMedia(id: string, type: string, title = "", year = 0) {
  const f = await findSeed({ id, kind: "media", title, type, year, weight: 1 });
  if (!f) return [];
  return (((await tmdb(`/${f.type}/${f.id}/recommendations`)).results ?? []) as any[]).filter(okMeta).map((o) => tmdbMeta(o, o.media_type === "tv" || o.media_type === "movie" ? o.media_type : f.type));
}
