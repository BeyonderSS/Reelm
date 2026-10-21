import { API, proxied } from "@/lib/core";
import { helperOld, helperVersion } from "@/lib/bridge";
import type { F } from "@/lib/fourkhdhub";

// The Reelm Helper extension is optional now. With it (1.1+) the browser reaches these hosts directly from the viewer's own
// connection; without it the Reelm Worker does the same job (server/src/index.ts: /v1/proxy and /v1/probe).
export const helperReady = () => !!helperVersion() && !helperOld();

// Hosts a plain <video> or fetch can't use: MovieBox's CDN wants an app User-Agent, Pixeldrain refuses cross-site hotlinks
// (Sec-Fetch-Site), and neither sends CORS headers, which also locks the libav.js audio decoder out of their bytes.
const NEEDS_RELAY = /(^|\.)(hakunaymatata\.com|pixeldrain\.(com|dev))$/;

/** A stream URL the <video> element can open: unchanged when it already works, through the Worker when it needs headers. */
export function playable(url: string): string {
  if (helperReady()) return url;
  try { return NEEDS_RELAY.test(new URL(url).hostname) ? proxied(url) : url; } catch { return url; }
}

/** Same contract as the extension bridge (lib/bridge.ts), served by the Worker for viewers without the extension. */
export const serverFetch: F = async (url, o = {}) => {
  const u = encodeURIComponent(url);
  if (o.probe) {
    const j = await (await fetch(`${API}/v1/probe?u=${u}`)).json().catch(() => ({}));
    return { status: j.status ?? 0, url: j.url ?? url, headers: { "content-type": j.type ?? "" }, text: "" };
  }
  const r = await fetch(`${API}/v1/proxy?u=${u}`);
  return { status: r.status, url: r.headers.get("x-final-url") ?? url, headers: { "content-type": (r.headers.get("content-type") ?? "").toLowerCase() }, text: await r.text() };
};
