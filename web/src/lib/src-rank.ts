// Quality-first picking: which source of a height to try first, and where to fall over to, from local per-provider reliability.
import { dv, risky, type Pickable } from "./pick-stream.ts";

export type Stat = { ok: number; fail: number; stall: number; ttff: number; t: number };
export type Stats = Record<string, Stat>;
const HALF = 7 * 864e5; // a week's half-life: a provider that recovers climbs back
const KEY = "reelm.srcStats";
/** "VegaMovies · VCloud 1080p" -> "VegaMovies", "MovieBox · 720p" -> "MovieBox" */
export const provider = (label = "") => label.split(" · ")[0].trim();
const decay = (s: Stat, now: number): Stat => { const k = 0.5 ** (Math.max(0, now - s.t) / HALF); return { ok: s.ok * k, fail: s.fail * k, stall: s.stall * k, ttff: s.ttff, t: now }; };
/** 0..1, higher is better; an unknown provider scores 0.5 */
export const score = (s: Stat | undefined, now = Date.now()) => {
  if (!s) return 0.5;
  const d = decay(s, now);
  return (d.ok + 1) / (d.ok + d.fail + d.stall / 2 + 2) - Math.min(d.ttff / 1e5, 0.2); // 10 s to first frame costs 0.1
};
export const loadStats = (): Stats => { try { return JSON.parse(localStorage.getItem(KEY) ?? "{}") ?? {}; } catch { return {}; } };
export function recordStat(label: string | undefined, e: { ok?: boolean; fail?: boolean; stall?: boolean; ttff?: number }) {
  try {
    const all = loadStats(), p = provider(label), now = Date.now(), s = decay(all[p] ?? { ok: 0, fail: 0, stall: 0, ttff: 0, t: now }, now);
    if (e.ok) s.ok++;
    if (e.fail) s.fail++;
    if (e.stall) s.stall++;
    if (e.ttff) s.ttff = s.ttff ? 0.7 * s.ttff + 0.3 * e.ttff : e.ttff;
    all[p] = s;
    localStorage.setItem(KEY, JSON.stringify(all));
  } catch {}
}

/** Indexes of `list` to try, in order: the chosen language's sources first; `height` itself, then each lower height (nearest first), then higher;
 *  within a height: what the browser can decode, then provider reliability, then list order. */
export function fallbackOrder(list: Pickable[], o: { lang?: string; height: number; stats: Stats; now?: number }): number[] {
  const now = o.now ?? Date.now(), has = (x: Pickable) => !o.lang || !!x.langs?.includes(o.lang), anyLang = list.some(has);
  const key = (i: number) => {
    const x = list[i], h = x.height ?? 0;
    return [anyLang && !has(x) ? 1 : 0, h === o.height ? 0 : h < o.height ? 1 : 2, h < o.height ? -h : h, dv(x) ? 2 : risky(x) || x.dolby ? 1 : 0, -score(o.stats[provider(x.label)], now), i];
  };
  const keys = list.map((_, i) => key(i));
  return list.map((_, i) => i).sort((a, b) => { for (let k = 0; k < keys[a].length; k++) if (keys[a][k] !== keys[b][k]) return keys[a][k] - keys[b][k]; return 0; });
}

/** Quality chips: each height (best first) among the sources carrying `lang` (all of them when none do), with how many there are. */
export function qualities(list: Pickable[], lang?: string) {
  const pool = list.filter((x) => !lang || x.langs?.includes(lang)), use = pool.length ? pool : list, n = new Map<number, number>();
  for (const x of use) if (x.height) n.set(x.height, (n.get(x.height) ?? 0) + 1);
  return [...n].sort((a, b) => b[0] - a[0]).map(([h, c]) => ({ h, n: c }));
}
