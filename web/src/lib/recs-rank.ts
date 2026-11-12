// Pure ranking for /api/recs (server/recs.ts); no imports so `node` can run recs-rank.check.ts directly.
// Port of android core/media Recommendations.kt: TF-IDF taste vector from the seeds, candidates scored by
// cosine similarity (0.6) + share of seeds that recommended them (0.3) + rating (0.1) + 0.15 for a matching original language,
// + the server's co-watch bonus (blend), then dealt round-robin across seeds so one title can't fill the row.
export type Doc = { genres: string[]; text: string };
export type Cand<T> = { item: T; doc: Doc; rating?: number; year?: number; lang?: string; by: number[]; bonus?: number };
export const norm = (t: string) => t.toLowerCase().replace(/&/g, "and").replace(/^\s*the\b/, "").replace(/[^\p{L}\p{N}]/gu, "");
export const INDIAN = ["hi", "te", "ta", "ml", "kn", "bn", "mr"];

/** Non-mature profiles never get ecchi/hentai anime. */
export const matureOk = (genres: string[] = [], mature?: boolean) => !!mature || !genres.some((g) => /^(ecchi|hentai)$/i.test(g.trim()));
// TMDB leaves some Indian erotic web series untagged (adult=false), e.g. "Gandii Baat".
// ponytail: title denylist + overview keywords; a per-network filter (ULLU, ALTBalaji bold) if more slip through
const EROTIC_TITLES = /gandii baat|gandi baat|xxx|charmsukh|palang tod|kavita bhabhi|rasbhari|virgin bhasskar|mastram|hello mini|ragini mms|dirty hindi/i;
export const erotic = (o: { title?: string; name?: string; overview?: string }) =>
  EROTIC_TITLES.test(o.title ?? o.name ?? "") || /\b(erotic|bold web series|steamy)\b/i.test(o.overview ?? "");

/** TMDB titles most voters disliked (< 5.5 from 50+ votes); unrated and barely-voted new titles stay. */
export const lowRated = (rating?: number, votes = 0) => rating != null && rating < 5.5 && votes >= 50;

/** One /v1/recs pick (server/API.md): `item` is the history key, `anime:<slug>` / `media:<movie|series>:<normtitle>`. */
export type ServerRec = { item: string; score: number; because?: string | null; becauseTitle?: string | null; payload: any };
/** Type-agnostic match key: TMDB and the apps don't always agree on movie vs series. */
export const srvKey = (item: string) => item.replace(/^media:(movie|series):/, "media:");
/**
 * Reelm's co-watch picks as extra support: a candidate the server also picked gets 0.3 × confidence × score / best score;
 * server picks no candidate covers join via `make` (null skips) with that bonus. Confidence 0 changes nothing.
 */
export function blend<T>(cands: Cand<T>[], keyOf: (c: Cand<T>) => string, srv: ServerRec[], confidence: number, make: (r: ServerRec) => Cand<T> | null): Cand<T>[] {
  const top = Math.max(0, ...srv.map((r) => r.score));
  if (!(confidence > 0 && top > 0)) return cands;
  const bonus = new Map(srv.map((r) => [srvKey(r.item), (0.3 * Math.min(1, confidence) * r.score) / top]));
  const have = new Set(cands.map(keyOf)), out = cands.map((c) => ({ ...c, bonus: bonus.get(keyOf(c)) }));
  for (const r of srv) {
    const k = srvKey(r.item), c = !have.has(k) && have.add(k) && make(r);
    if (c) out.push({ ...c, bonus: bonus.get(k) });
  }
  return out;
}

const STOP = new Set("with that this from they their them have will into when where which while after before about what your there been were who whose only also more most than then some such over just life story find finds must each other himself herself becomes being world years year these those".split(" "));
const tokens = (d: Doc) => [...d.genres.flatMap((g) => [`g:${g.toLowerCase()}`, `g:${g.toLowerCase()}`]), ...(d.text.toLowerCase().match(/[a-z]+/g) ?? []).filter((w) => w.length > 3 && !STOP.has(w))];
const cosine = (a: Map<string, number>, b: Map<string, number>) => {
  let dot = 0, na = 0, nb = 0;
  for (const [k, v] of b) { dot += v * (a.get(k) ?? 0); nb += v * v; }
  for (const v of a.values()) na += v * v;
  return na && nb ? dot / Math.sqrt(na * nb) : 0;
};

/** Deals the list out one per key in turn (keys in first-seen order), keeping each key's own order. */
export function roundRobin<X>(xs: X[], key: (x: X) => unknown): X[] {
  const g = new Map<unknown, X[]>();
  xs.forEach((x) => (g.get(key(x)) ?? g.set(key(x), []).get(key(x))!).push(x));
  const lists = [...g.values()], out: X[] = [];
  for (let i = 0; out.length < xs.length; i++) lists.forEach((l) => l[i] !== undefined && out.push(l[i]));
  return out;
}

export function rank<T>(seeds: { doc: Doc; weight: number; lang?: string }[], cands: Cand<T>[], prefLang = ""): Cand<T>[] {
  cands = cands.filter((c) => !c.year || c.year >= 1990 || (c.rating ?? 0) >= 8);
  if (!cands.length) return [];
  const docs = [...seeds.map((s) => tokens(s.doc)), ...cands.map((c) => tokens(c.doc))];
  const df = new Map<string, number>();
  docs.forEach((d) => new Set(d).forEach((t) => df.set(t, (df.get(t) ?? 0) + 1)));
  const n = docs.length;
  const tfidf = (t: string[]) => { const m = new Map<string, number>(); t.forEach((w) => m.set(w, (m.get(w) ?? 0) + 1)); for (const [w, c] of m) m.set(w, (c / t.length) * (Math.log((n + 1) / (df.get(w)! + 1)) + 1)); return m; };
  const taste = new Map<string, number>();
  seeds.forEach((s, i) => { for (const [t, v] of tfidf(docs[i])) taste.set(t, (taste.get(t) ?? 0) + v * s.weight); });
  const langOk = (c: Cand<T>) => !!c.lang && (c.lang === prefLang || c.by.some((i) => seeds[i]?.lang === c.lang));
  const sorted = cands.map((c, i) => ({ c, s: 0.6 * cosine(taste, tfidf(docs[seeds.length + i])) + 0.3 * (new Set(c.by).size / seeds.length) + 0.1 * Math.min(1, Math.max(0, (c.rating ?? 0) / 10)) + (langOk(c) ? 0.15 : 0) + (c.bonus ?? 0) }))
    .sort((a, b) => b.s - a.s).map((x) => x.c);
  return roundRobin(sorted, (c) => c.by[0]);
}
