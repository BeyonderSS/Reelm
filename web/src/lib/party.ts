// Watch party: the pure bits of room sync and the call layer (checked by party.check.ts).
// Contract: docs/superpowers/specs/2026-11-21-watch-party-design.md
import { provider } from "./src-rank.ts";

/** Room positions are integer milliseconds; the <video> speaks seconds. */
export const toMs = (sec: number) => Math.round((Number.isFinite(sec) ? sec : 0) * 1000);
export const fromMs = (ms: number) => (Number.isFinite(ms) ? ms : 0) / 1000;

/** Where the room's playhead is now (ms): `at` is the server time the position was stamped; a lights-down start lies in the future. */
export const roomPos = (s: { playing: boolean; position: number; at: number }, serverNow: number) =>
  s.playing ? s.position + Math.max(0, serverNow - s.at) : s.position;

/** Server clock offset (server - local, ms) from a stamped message: the max sample is the least delayed one. */
export const nextOffset = (prev: number | null, at: number, now: number) => (prev === null ? at - now : Math.max(prev, at - now));

/** Echo guard: nothing is broadcast before the room's state reached a ready player, during the first 2 s after join,
 *  or while a remote event is being applied (its play/pause/seeked events would bounce back). */
export type Guard = { joined: number; ready: boolean; until: number; quietSeeks: number; quietUntil: number };
export const JOIN_QUIET_MS = 2000, APPLY_MS = 800, SEEK_MS = 5000;
export const guard = (now: number): Guard => ({ joined: now, ready: false, until: 0, quietSeeks: 0, quietUntil: 0 });
export const canSend = (g: Guard, now: number) => g.ready && now - g.joined >= JOIN_QUIET_MS && now >= g.until;
/** a remote event is being applied: hold sends, and swallow the `seeked` it causes even if the seek outlasts the hold */
export const hold = (g: Guard, now: number, seeked = false) => { g.until = Math.max(g.until, now + APPLY_MS); if (seeked) { g.quietSeeks++; g.quietUntil = now + SEEK_MS; } };
/** call from `seeked`: true when this seek was the user's own (two seeks set before one `seeked` fire it once: the count expires) */
export const ownSeek = (g: Guard, now: number) => { if (g.quietSeeks > 0 && now < g.quietUntil) { g.quietSeeks--; return false; } g.quietSeeks = 0; return canSend(g, now); };
/** seeking only when off by more than this (a smaller fix stutters more than it helps) */
export const DRIFT_MS = 1500;

/** Film ducking: any remote voice above THRESH for >150 ms ducks the film to 35 %; it comes back over 400 ms of silence. */
export type Duck = { loudSince: number; quietSince: number; ducked: boolean };
export const THRESH = 0.04, DUCK = 0.35, DUCK_IN_MS = 150, DUCK_OUT_MS = 400;
export const duck0 = (): Duck => ({ loudSince: -1, quietSince: -1, ducked: false });
export function duckStep(d: Duck, level: number, now: number): Duck {
  if (level > THRESH) {
    const loudSince = d.loudSince < 0 ? now : d.loudSince;
    return { loudSince, quietSince: -1, ducked: d.ducked || now - loudSince > DUCK_IN_MS };
  }
  const quietSince = d.quietSince < 0 ? now : d.quietSince;
  return { loudSince: -1, quietSince, ducked: d.ducked && now - quietSince < DUCK_OUT_MS };
}
/** the film's gain: 0.35 while ducked, then a ramp back to 1 across the 400 ms after the voice stops */
export const duckGain = (d: Duck, now: number) =>
  d.ducked ? (d.quietSince < 0 ? DUCK : DUCK + (1 - DUCK) * Math.min(1, (now - d.quietSince) / DUCK_OUT_MS)) : 1;

/** The room's `source` descriptor: which copy of the film plays, so joiners pick the same cut. */
export type SourceDesc = { provider: string; quality: string; host?: string; label?: string };
type Src = { src: string; label: string; height?: number };
export const hostOf = (url: string) => {
  try { const u = new URL(url, "http://x"); return new URL(u.searchParams.get("u") ?? u.href).host; } catch { return ""; }
};
const quality = (x: Src) => (x.height ? `${x.height}p` : x.label.split(" · ").find((t) => /^(\d{3,4}p|auto|4k)$/i.test(t)) ?? "");
export const describe = (x: Src): SourceDesc => ({ provider: provider(x.label), quality: quality(x), host: hostOf(x.src) || undefined, label: x.label });
/** best match for the room's copy: same label, else same provider + quality + host, else same provider + quality; -1 = none */
export function matchSource(list: Src[], d: SourceDesc | null | undefined): number {
  if (!d) return -1;
  const tests = [
    (x: Src) => !!d.label && x.label === d.label,
    (x: Src) => provider(x.label) === d.provider && quality(x) === d.quality && (!d.host || hostOf(x.src) === d.host),
    (x: Src) => provider(x.label) === d.provider && quality(x) === d.quality,
  ];
  for (const t of tests) { const i = list.findIndex(t); if (i >= 0) return i; }
  return -1;
}
export const sameCopy = (x: Src | undefined, d: SourceDesc | null | undefined) => !d || (!!x && matchSource([x], d) === 0);

export const REACTS = { heart: "❤️", laugh: "😂", gasp: "😮", cry: "😢" } as const;
export type ReactKind = keyof typeof REACTS;
export const VIDEO_SEATS = 4;

/** 3723000 ms -> "1:02:03", 62000 -> "1:02" */
export const clock = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = String(s % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${r}` : `${m}:${r}`;
};
