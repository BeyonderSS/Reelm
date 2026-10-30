// Movies & TV search: merge MovieBox, TMDB and Cinemeta results, drop junk, rank by how well the title matches the query.
export type R = { id: string; title: string; year?: number; genres?: string[] };
const ADULT = ["erotic", "hot", "adult", "18+"];
// MovieBox tags some explicit titles with an ordinary genre ("Gang Rape" is "Crime"), so the title is checked too
const EXPLICIT = /\b(rape|porn|porno|xxx|nude|erotic|hentai|orgy|gang ?bang)\b/i;
const FILM = ["action", "adventure", "animation", "anime", "biography", "comedy", "crime", "documentary", "drama", "family", "fantasy", "history", "horror", "music", "musical", "mystery", "romance", "sci-fi", "science fiction", "sport", "thriller", "war", "western", "reality", "reality-tv", "talk-show", "news", "kids", "short"];

/** Hide MovieBox adult titles and non-film clips ("Modern Drama" shorts, wrestling, pop) unless the profile opted in. No genres: stays. */
export function visible(i: R, mature: boolean) {
  const g = (i.genres ?? []).map((x) => x.trim().toLowerCase());
  if (mature || !i.id.startsWith("mb:")) return true;
  if (EXPLICIT.test(i.title)) return false;
  return !g.length || (!g.some((x) => ADULT.includes(x)) && g.some((x) => FILM.includes(x)));
}

const words = (t: string) => t.toLowerCase().replace(/\[[^\]]*\]/g, " ").replace(/[^a-z0-9]+/g, " ").trim().split(" ").filter(Boolean);
const tokens = (t: string) => { const w = words(t).filter((x) => x !== "part"); return w[0] === "the" ? w.slice(1) : w; };
/** "Part 1" is the film itself ("Gangs of Wasseypur [Hindi]" = "... - Part 1"); "Part 2" is another title. */
const key = (t: string) => tokens(t.replace(/\bpart\s+1\b/i, " ")).join(" ");

function lev(a: string, b: string) {
  let p = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const c = [i];
    for (let j = 1; j <= b.length; j++) c[j] = Math.min(p[j] + 1, c[j - 1] + 1, p[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    p = c;
  }
  return p[b.length];
}
const sim = (a: string, b: string) => 1 - lev(a, b) / Math.max(a.length, b.length);

/** Mean over query tokens of the best similarity to a title token, plus small bonuses for an exact or leading match. */
export function score(q: string, title: string) {
  const qt = tokens(q), tt = tokens(title);
  if (!qt.length || !tt.length) return 0;
  const s = qt.reduce((n, a) => n + Math.max(...tt.map((b) => sim(a, b))), 0) / qt.length;
  const a = qt.join(" "), b = tt.join(" ");
  return s + (a === b ? 0.2 : b.startsWith(a) ? 0.1 : 0);
}

/** The best-matching title among the sources, cleaned of "[Hindi]" tags: what TMDB is asked again when the typo got it nothing. */
export const bestTitle = (q: string, l: R[]) => l.map((i) => [i, score(q, i.title)] as const).sort((x, y) => y[1] - x[1])[0]?.[0].title.replace(/\[[^\]]*\]/g, "").trim();

/** Lists in source order (MovieBox, TMDB, Cinemeta). One item per title+year, preferring the one that can stream (MovieBox, then Cinemeta, then TMDB). */
export function rank<T extends R>(q: string, src: { mb: T[]; tm: T[]; cm: T[] }, mature: boolean): T[] {
  const all = [...src.mb.filter((i) => visible(i, mature)), ...src.tm, ...src.cm].map((it, o) => ({ it, o, s: score(q, it.title), p: it.id.startsWith("mb:") ? 0 : it.id.startsWith("tmdb:") ? 2 : 1 })).filter((x) => x.s >= 0.6);
  const kept: typeof all = [];
  for (const x of [...all].sort((a, b) => a.p - b.p || a.o - b.o))
    if (!kept.some((k) => key(k.it.title) === key(x.it.title) && (!k.it.year || !x.it.year || Math.abs(k.it.year - x.it.year) <= 1))) kept.push(x);
  return kept.sort((a, b) => b.s - a.s || a.o - b.o).map((x) => x.it);
}
