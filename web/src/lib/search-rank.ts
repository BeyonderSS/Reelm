// Movies & TV search: merge MovieBox, TMDB and Cinemeta results, drop junk, rank by how well the title matches the query.
export type R = { id: string; title: string; year?: number; genres?: string[]; rating?: number | string };
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

const TAG = /\[[^\]]*\]/;
const words = (t: string) => t.toLowerCase().replace(/\[[^\]]*\]/g, " ").replace(/[^a-z0-9]+/g, " ").trim().split(" ").filter(Boolean);
const tokens = (t: string) => { const w = words(t).filter((x) => x !== "part"); return w[0] === "the" ? w.slice(1) : w; };
/** "Part 1" is the film itself ("Gangs of Wasseypur [Hindi]" = "... - Part 1"); "Part 2" is another title. */
const key = (t: string) => tokens(t.replace(/\bpart\s+1\b/i, " ")).join(" ");

/** Edit distance where swapping two neighbouring letters is one typo ("visoin" -> "vision"), not two. */
function lev(a: string, b: string) {
  let pp: number[] = [], p = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const c = [i];
    for (let j = 1; j <= b.length; j++) {
      c[j] = Math.min(p[j] + 1, c[j - 1] + 1, p[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) c[j] = Math.min(c[j], pp[j - 2] + 1);
    }
    pp = p; p = c;
  }
  return p[b.length];
}
const sim = (a: string, b: string) => 1 - lev(a, b) / Math.max(a.length, b.length);

/** Spacing, case, accents and punctuation don't count: "vision quest" = "VisionQuest", "Pokémon" = "pokemon", "&" = "and". */
export const compact = (t: string) => t.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/\[[^\]]*\]/g, " ").replace(/&/g, "and").replace(/[^a-z0-9]+/g, "");

/** Mean over query tokens of the best similarity to a title token, plus small bonuses for an exact or leading match, less a little per extra
 *  title word ("Interstellar" above "Interstellar Wars"). The space-free forms are compared too, so a split or joined word still matches. */
export function score(q: string, title: string) {
  const qt = tokens(q), tt = tokens(title), qc = compact(q), tc = compact(title);
  if (!qt.length || !tt.length || !qc || !tc) return 0;
  if (qc === tc) return 1.2;
  const s = qt.reduce((n, a) => n + Math.max(...tt.map((b) => sim(a, b))), 0) / qt.length;
  const a = qt.join(" "), b = tt.join(" ");
  const t = s + (a === b ? 0.2 : b.startsWith(a) ? 0.1 : 0) - Math.min(0.3, 0.05 * Math.max(0, tt.length - qt.length));
  return Math.max(t, sim(qc, tc) + (tc.startsWith(qc) ? 0.1 : 0));
}

/** Scores within 0.05 count as the same match; there the rated, then the newer title goes first ("VisionQuest" 2026, 8.3 over the 1985 film). */
export const band = (s: number) => Math.round(s * 20);
const stars = (i: R) => Number(i.rating) || 0;

/** The best-matching title among the sources, cleaned of "[Hindi]" tags: what TMDB is asked again when the typo got it nothing. */
export const bestTitle = (q: string, l: R[]) => l.map((i) => [i, score(q, i.title)] as const).sort((x, y) => y[1] - x[1])[0]?.[0].title.replace(/\[[^\]]*\]/g, "").trim();

/** Lists in source order (MovieBox, TMDB, Cinemeta). One item per title+year, preferring the one that can stream (MovieBox, then Cinemeta, then TMDB). */
export function rank<T extends R>(q: string, src: { mb: T[]; tm: T[]; cm: T[] }, mature: boolean): T[] {
  const all = [...src.mb.filter((i) => visible(i, mature)), ...src.tm, ...src.cm].map((it, o) => ({ it, o, s: score(q, it.title), p: it.id.startsWith("mb:") ? 0 : it.id.startsWith("tmdb:") ? 2 : 1 })).filter((x) => x.s >= 0.6);
  const kept: typeof all = [];
  for (const x of [...all].sort((a, b) => a.p - b.p || a.o - b.o))
  {
    const k = kept.find((k) => key(k.it.title) === key(x.it.title) && (!k.it.year || !x.it.year || Math.abs(k.it.year - x.it.year) <= 1));
    if (!k) kept.push(x);
    else if (TAG.test(k.it.title) && !TAG.test(x.it.title)) k.it = { ...k.it, title: x.it.title }; // the streamable MovieBox copy is "Inception [Hindi]"; show the canonical title
  }
  return kept.sort((a, b) => band(b.s) - band(a.s) || stars(b.it) - stars(a.it) || (b.it.year ?? 0) - (a.it.year ?? 0) || a.o - b.o).map((x) => x.it);
}
