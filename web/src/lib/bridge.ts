// Talks to the Reelm Helper extension's content script, which fetches for us from the viewer's own IP (no CORS, no datacenter block).
export type BridgeRes = { status: number; url: string; headers: Record<string, string>; text: string };
let n = 0;
export const bridge = (url: string, o: { headers?: Record<string, string>; probe?: boolean; method?: string; body?: string; vega?: boolean; type?: string } = {}, ms = 15000) =>
  new Promise<BridgeRes>((ok, no) => {
    const id = ++n, done = (f: () => void) => { clearTimeout(t); removeEventListener("message", on); f(); };
    const on = (e: MessageEvent) => {
      if (e.source !== window || e.data?.reelm !== "res" || e.data.id !== id) return;
      done(() => (e.data.error ? no(new Error(e.data.error)) : ok(e.data.res)));
    };
    const t = setTimeout(() => done(() => no(new Error("helper timeout"))), ms);
    addEventListener("message", on);
    postMessage({ reelm: "req", id, msg: { type: "fetch", url, ...o } }, location.origin);
  });
// dataset value is the helper version ("1" from the first releases, which can't bridge)
export const helperVersion = () => (typeof document === "undefined" ? "" : document.documentElement.dataset.reelmHelper ?? "");
export const helperOld = () => { const [a = 0, b = 0] = helperVersion().split(".").map(Number); return !!helperVersion() && (a < 1 || (a === 1 && b < 1)); };
// Helper 1.2+ (vega: any https host, Referer/Origin via a per-host header rule, also used for <video> playback)
export const helperVega = () => { const [a = 0, b = 0] = helperVersion().split(".").map(Number); return a > 1 || (a === 1 && b >= 2); };
export const setHeaders = (url: string, headers: Record<string, string>) => bridge(url, { type: "hdr", headers });
