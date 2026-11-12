"use client";
// The account hub (/v1/hub): one WebSocket per tab, shared by every component through useHub().
import { useEffect, useSyncExternalStore } from "react";
import { deviceId, wire, WS, type Play } from "./core";
import { useSession } from "./session";

export type Now = { title: string; subtitle?: string; poster?: string; playing: boolean; positionMs: number; durationMs: number; hasNext: boolean; hasPrev: boolean; at: number };
export type Device = { deviceId: string; deviceName: string; kind: string; now?: Now | null };
export type Action = "play" | "pause" | "toggle" | "seekTo" | "seekBy" | "next" | "prev" | "stop";
type Snap = { devices: Device[]; remoteHere: string | null };

let snap: Snap = { devices: [], remoteHere: null }, ws: WebSocket | null = null, token = "", users = 0, retry = 0, timer: ReturnType<typeof setTimeout> | undefined;
const subs = new Set<() => void>();
const set = (n: Partial<Snap>) => { snap = { ...snap, ...n }; subs.forEach((f) => f()); };

function connect() {
  clearTimeout(timer);
  if (!token || !users) return;
  const w = (ws = new WebSocket(`${WS}/v1/hub?token=${token}`));
  w.onopen = () => { retry = 0; w.send(JSON.stringify({ type: "hello", deviceId: deviceId(), deviceName: "Reelm Web", kind: "phone" })); };
  w.onmessage = (ev) => {
    let m: any;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === "devices") set({ devices: m.devices ?? [] });
    else if (m.type === "state") set({ devices: snap.devices.map((d) => (d.deviceId === m.from ? { ...d, now: m.now } : d)) });
  };
  w.onclose = () => {
    if (ws !== w) return;
    ws = null; set({ devices: [] });
    timer = setTimeout(connect, Math.min(30_000, 1000 * 2 ** retry++));
  };
}
const close = () => { clearTimeout(timer); const w = ws; ws = null; w?.close(); };

export const send = (m: object) => { if (ws?.readyState === 1) { ws.send(JSON.stringify(m)); return true; } return false; };
export const control = (to: string, action: Action, value?: number) => send({ type: "control", to, action, value });
/** Mark which TV this page is already showing a remote for (the global bar hides for it). */
export const setRemoteHere = (id: string | null) => set({ remoteHere: id });

/** TVs on the account, a shared name made unique with the deviceId's last 4. */
export const tvsOf = (all: Device[]) => {
  const tvs = all.filter((d) => d.kind === "tv");
  return tvs.map((d) => ({ ...d, name: tvs.some((o) => o !== d && o.deviceName === d.deviceName) ? `${d.deviceName} · ${d.deviceId.slice(-4)}` : d.deviceName || "TV" }));
};
export type Tv = ReturnType<typeof tvsOf>[number];
/** Extrapolated TV position: positionMs + (playing ? elapsed : 0) */
export const posOf = (n: Now) => Math.min(n.durationMs || Infinity, n.positionMs + (n.playing ? Date.now() - n.at : 0));

const LAST = "reelm.lastTv";
export const lastTv = () => { try { return localStorage.getItem(LAST) ?? ""; } catch { return ""; } };
// what was handed to each TV, so "Play here" from the global bar knows the title to open
const sent = (id: string) => `reelm.handoff.${id}`;
export const handedOff = (id: string): Play | null => { try { return JSON.parse(localStorage.getItem(sent(id)) ?? "null"); } catch { return null; } };

/** The apps read the payload as a history row (slug/mediaId/kind "media"), so it goes out in that shape. */
export function handoff(to: string, profileId: string, payload: Play, positionMs: number, source?: string) {
  try { localStorage.setItem(LAST, to); localStorage.setItem(sent(to), JSON.stringify(payload)); } catch {}
  return send({ type: "handoff", to, profileId, playback: { payload: wire(payload, 0, 0), positionMs: Math.max(0, Math.round(positionMs)), autoplay: true, ...(source ? { source } : {}) } });
}

export function useHub() {
  const { s } = useSession();
  useEffect(() => {
    users++;
    if (s.token && s.token !== token) { close(); token = s.token; connect(); } else if (!s.token && token) { token = ""; close(); set({ devices: [] }); } else if (!ws) connect();
    return () => { if (!--users) setTimeout(() => { if (!users) { token = ""; close(); } }, 1000); }; // a route change remounts users; keep the socket across it
  }, [s.token]);
  const v = useSyncExternalStore((f) => (subs.add(f), () => subs.delete(f)), () => snap, () => snap);
  const tvs = tvsOf(v.devices), last = lastTv();
  return { ...v, tvs: [...tvs].sort((a, b) => Number(b.deviceId === last) - Number(a.deviceId === last)) };
}
/** A history row's position in ms. Web rows hold seconds; app rows hold permille of 1000, so those start at 0.
 *  ponytail: same 1000-guess as timeLeft(); send the real position once synced rows carry seconds. */
export const histMs = (e?: { position: number; duration: number } | null) => (e && e.duration && e.duration !== 1000 && e.position < e.duration * 0.95 ? e.position * 1000 : 0);
