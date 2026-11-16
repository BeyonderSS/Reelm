"use client";
// Trailer previews (TRAILER_CONTRACT §6): the /v1/trailer lookup, the "Preview trailers" setting, and when a preview may play.
import { createContext, useEffect, useSyncExternalStore } from "react";
import { reelm, type CardData } from "./core";
import { useHub } from "./hub";

export type Trailer = { ytIds: string[]; logo: string | null; backdrops: string[]; startAt: number; imdb: string | null };
export type TKey = { imdb?: string; title?: string; year?: number; type: "movie" | "series" };

/** The phone's trailerKeyFor: an IMDb id with its type (a series asked as a movie gets another title's trailer), else title + year; anime is a series by title. */
export function keyOf(c: Pick<CardData, "href" | "title" | "sub">): TKey {
  const m = c.href.match(/^\/title\/([^?]+)\?type=(\w+)/), year = Number(c.sub?.match(/\b(19|20)\d{2}\b/)?.[0]) || undefined;
  if (!m) return { title: c.title, year, type: "series" };
  const id = decodeURIComponent(m[1]), type = m[2] === "series" ? "series" : "movie";
  return id.startsWith("tt") ? { imdb: id.split(":")[0], type } : { title: c.title, year, type };
}

const memo = new Map<string, Promise<Trailer | null>>();
/** null = API unreachable; ytIds [] = no trailer (show the art). One request per key per page load. */
export function trailerOf(k: TKey) {
  const q = new URLSearchParams(Object.entries(k).flatMap(([a, b]) => (b == null || b === "" ? [] : [[a, String(b)]]))).toString();
  let p = memo.get(q);
  if (!p) memo.set(q, (p = reelm<Trailer>(`/v1/trailer?${q}`).catch(() => null)));
  return p;
}

// ---- the setting: On / Wi-Fi only / Off, per browser, default On ----
export type PreviewMode = "on" | "wifi" | "off";
const PK = "reelm.previewTrailers", subs = new Set<() => void>();
const read = (): PreviewMode => { try { const v = localStorage.getItem(PK); return v === "wifi" || v === "off" ? v : "on"; } catch { return "on"; } };
export const setPreviewMode = (m: PreviewMode) => { try { localStorage.setItem(PK, m); } catch {} subs.forEach((f) => f()); };
export const usePreviewMode = () => useSyncExternalStore((f) => (subs.add(f), () => subs.delete(f)), read, () => "on" as PreviewMode);

/** `auto`: may start on its own (hero dwell, hover). `demand`: the viewer asked (hold, reel). Neither while a TV on the account is playing (this tab is its remote). */
export function usePreviewGate() {
  const mode = usePreviewMode(), { devices } = useHub();
  const tv = devices.some((d) => d.kind === "tv" && d.now?.playing);
  if (typeof window === "undefined") return { auto: false, demand: false };
  const c = (navigator as any).connection, metered = !!c && (c.saveData || c.type === "cellular"); // unknown counts as Wi-Fi
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  return { auto: !tv && !reduced && (mode === "on" || (mode === "wifi" && !metered)), demand: !tv && mode !== "off" };
}

let overlays = 0;
const osubs = new Set<() => void>(), bump = (d: number) => { overlays += d; osubs.forEach((f) => f()); };
/** A trailer card or the reel feed is open: the hero's trailer waits until it closes. */
export const useOverlayHold = (on: boolean) => useEffect(() => { if (!on) return; bump(1); return () => bump(-1); }, [on]);
export const useOverlayOpen = () => useSyncExternalStore((f) => (osubs.add(f), () => osubs.delete(f)), () => overlays > 0, () => false);

/** Set by Home: hovering a poster 600 ms (mouse) or holding it 380 ms (touch) lifts it into its trailer card. */
export const PeekCtx = createContext<((c: CardData, el: HTMLElement, hold: boolean) => void) | null>(null);

let yt: Promise<any> | undefined;
/** The YouTube IFrame Player API, loaded once. */
export const loadYT = () => (yt ??= new Promise((res) => {
  const w = window as any;
  if (w.YT?.Player) return res(w.YT);
  const prev = w.onYouTubeIframeAPIReady;
  w.onYouTubeIframeAPIReady = () => { prev?.(); res(w.YT); };
  const s = document.createElement("script");
  s.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(s);
}));
