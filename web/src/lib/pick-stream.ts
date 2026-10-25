export type Pickable = { height?: number; hls?: boolean; codec?: string; dolby?: boolean; label?: string; url?: string };
const MBPS: [number, number][] = [[2160, 16], [1440, 9], [1080, 5], [720, 2.5], [480, 1.2], [0, 0.7]];
/** Bits/s a stream needs; HLS adapts, so it fits anywhere. Sizes and durations aren't in the stream objects, so height stands in. */
export const bitrate = (x: Pickable) => x.hls ? 0 : (MBPS.find(([h]) => (x.height ?? 0) >= h)![1]) * 1e6;
const dv = (x: Pickable) => /dolby vision|\bdovi\b|\bdv\b/i.test(`${x.label} ${x.codec}`);
const risky = (x: Pickable) => /hevc|x265|h\.?265/i.test(x.codec ?? "") || /^Dramachi/.test(x.label ?? "") || /\.mkv(\?|$)/i.test(x.url ?? ""); // may not play in the browser

/** Index of the stream to start with: the pinned height (else the closest lower) if any, else the highest that fits 70% of the bandwidth. */
export function pickStream(list: Pickable[], o: { bandwidthBps: number; pinnedHeight?: number }): number {
  const ok = list.map((x, i) => ({ x, i })).filter((e) => !dv(e.x));
  const safe = ok.filter((e) => !risky(e.x)), pool = safe.length ? safe : ok;
  if (!pool.length) return 0;
  const h = (e: { x: Pickable }) => e.x.height ?? 0;
  const rank = [...pool].sort((a, b) => h(b) - h(a) || +!!a.x.dolby - +!!b.x.dolby || a.i - b.i);
  const pin = o.pinnedHeight;
  if (pin) { const e = rank.find((e) => h(e) === pin) ?? rank.find((e) => h(e) < pin); if (e) return e.i; }
  const fit = rank.find((e) => bitrate(e.x) <= 0.7 * o.bandwidthBps);
  return (fit ?? [...pool].sort((a, b) => bitrate(a.x) - bitrate(b.x) || a.i - b.i)[0]).i;
}

export const pinKey = (showKey: string) => `reelm.pin.${showKey}`;
export const getPin = (showKey: string) => { try { return Number(localStorage.getItem(pinKey(showKey))) || undefined; } catch { return undefined; } };
export const setPin = (showKey: string, height: number) => { try { if (height) localStorage.setItem(pinKey(showKey), String(height)); } catch {} };
/** bits/s: measured ("reelm.bw"), else the browser's downlink hint, else 10 Mbps */
export const getBandwidth = () => {
  try { const m = Number(localStorage.getItem("reelm.bw")); if (m > 0) return m; } catch {}
  const d = (navigator as any).connection?.downlink;
  return d > 0 ? d * 1e6 : 10e6;
};
export const saveBandwidth = (est: number, lowerBound: boolean) => {
  try {
    const old = Number(localStorage.getItem("reelm.bw")) || getBandwidth();
    if (!lowerBound || est > old) localStorage.setItem("reelm.bw", String(Math.round(0.7 * old + 0.3 * est)));
  } catch {}
};
