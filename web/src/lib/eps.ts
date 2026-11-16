/** Per-episode progress carried in the history payload as `eps` (same wire shape and keys as Android's Eps in domain/model/WatchHistory.kt). */
export type Eps = Record<string, { p: number; at: number }>;
/** An episode at or past this fraction counts as watched. */
export const DONE = 0.9;

/** `e12` for anime, `s5e1` for series, "" for movies. */
export const epsKey = (p: { kind: string; ep?: number; season?: number; episode?: number }) =>
  p.kind === "anime" ? (p.ep ? `e${p.ep}` : "") : p.season && p.episode ? `s${p.season}e${p.episode}` : "";

/** Per episode the newer `at` wins; a tie keeps `b` (the incoming one). Invalid entries are dropped. */
export function mergeEps(a?: Eps, b?: Eps): Eps {
  const out: Eps = {};
  for (const m of [a, b]) for (const [k, v] of Object.entries(m ?? {})) {
    if (!v || !Number.isFinite(v.p) || !Number.isFinite(v.at)) continue;
    if (!out[k] || out[k].at <= v.at) out[k] = { p: Math.min(1, Math.max(0, v.p)), at: v.at };
  }
  return out;
}

/** Progress as a 0..1 fraction. Entries hold seconds (web) or permille of 1000 (apps); only the ratio is meaningful. */
export const frac = (position: number, duration: number) => (duration > 0 ? Math.min(1, Math.max(0, position / duration)) : 0);

/**
 * Done watching: 95%, or for a film 90% with ≤ 10 min left (long end credits left The Dark Knight at 94.96%).
 * Same rule as the apps' WatchHistoryDao. Durations ≤ 20 min skip the minutes test (older rows hold permille of 1000).
 */
export const finished = (pos: number, dur: number, film = false) =>
  frac(pos, dur) >= 0.95 || (film && dur > 1200 && frac(pos, dur) >= 0.9 && dur - pos <= 600);
