// 4KHDHub (4khdhub.one) via the Reelm Helper bridge. Port of android .../fourkhdhub/FourKHdHubSource.kt (release links go
// GreenMotors -> HubDrive -> HubCloud -> resolver page -> file CDN). `f` is the bridge (injectable so node can test it).
export type F = (url: string, o?: { headers?: Record<string, string>; probe?: boolean }) => Promise<{ status: number; url: string; headers: Record<string, string>; text: string }>;
import { langs } from "./langs.ts";
const BASE = "https://4khdhub.one";
const unesc = (s: string) => s.replace(/&#(\d+);/g, (_, d) => String.fromCharCode(+d)).replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&");
const b64 = (s: string) => new TextDecoder().decode(Uint8Array.from(atob(s.trim()), (c) => c.charCodeAt(0)));
const rot13 = (s: string) => s.replace(/[a-z]/gi, (c) => String.fromCharCode(((c.charCodeAt(0) & 95) < 78 ? c.charCodeAt(0) + 13 : c.charCodeAt(0) - 13)));
const BR = /\[[^\]]*\]|\([^)]*\)/g;
const norm = (t: string) => t.toLowerCase().replace(BR, " ").replace(/[^\p{L}\p{N}]+/gu, " ").trim().replace(/\s+(s\d{1,2}|season \d{1,2})$/, "").trim();
const height = (t: string) => +(t.match(/\b(2160|1440|1080|720|576|480|360|240)p\b/i)?.[1] ?? (/\b(4k|uhd)\b/i.test(t) ? 2160 : 0));
const MIRRORS = ["hubcloud.", "hubdrive.", "greenmotors.", "greenmountmotors.", "pixeldrain."];
const PIXEL = /https:\/\/(pixeldrain\.(?:dev|com))\/(?:u|api\/file)\/([A-Za-z0-9_-]+)/;
const pixelApi = (u: string) => { const m = u.match(PIXEL); return m && `https://${m[1]}/api/file/${m[2]}?download`; };

export const score = (url: string, label: string) => {
  const v = `${url} ${label}`.toLowerCase(), has = (...a: string[]) => a.some((x) => v.includes(x));
  return has("pixel.hubcloud.", "googleusercontent.com", "googlevideo.com", "cloudflarestorage.com", "fsl server", "10gbps", "r2.dev", "watch online") ? 0
    : has("storage.googleapis.com", "hubcloud.cx/re/", "hubcloud.fans/re/") ? 1 : v.includes("pixeldrain") ? 2
    : has("testzip.php", "vcloud.php", "drive.php", "gpdl.") ? 3 : 4;
};
const host = (u: string) => { try { return new URL(u).hostname.toLowerCase(); } catch { return ""; } };
const playbackUrl = (raw: string) => {
  let u: URL; try { u = new URL(raw); } catch { return null; }
  const h = u.hostname.toLowerCase(), p = u.pathname.toLowerCase();
  const bad = u.protocol !== "https:" || h === "localhost" || h.endsWith(".local") || /^[\d.]+$/.test(h) || h.includes(":") || p.endsWith(".zip") ||
    p.includes("login.php") || p.includes("logout") || h.includes("greenmotors.") || h.includes("greenmountmotors.") || p.includes("/drive/admin");
  return bad ? null : u.toString();
};
const isMedia = (ct: string) => ["video/", "mpegurl", "octet-stream", "matroska", "binary/"].some((x) => ct.includes(x));

export function parseSearch(html: string) {
  return [...html.matchAll(/<a href="(\/[^"]+)" class="movie-card"[^>]*>([\s\S]*?)<\/a>/g)].flatMap((m) => {
    const title = m[2].match(/class="movie-card-title">([^<]*)</)?.[1];
    return title?.trim() ? [{ id: m[1], series: m[1].includes("-series-"), title: unesc(title).trim(), year: +(m[2].match(/class="movie-card-meta">([^<]*)</)?.[1].match(/\d{4}/)?.[0] ?? 0) || undefined }] : [];
  });
}

export function parseReleases(html: string, season: number, episode: number) {
  const items = season > 0 ? html.split('id="episodes"').slice(1).join("").split('class="episode-download-item"').slice(1) : html.split('class="download-item ').slice(1);
  const seen = new Set<string>();
  return items.flatMap((c) => {
    const title = c.match(/class="(?:episode-)?file-title">\s*([^<]*?)\s*</)?.[1];
    if (!title) return [];
    const t = unesc(title), l = t.toLowerCase();
    if (l.endsWith(".zip") || l.includes("complete season") || l.includes("season pack")) return [];
    const se = t.match(/S(\d{1,2})E(\d{1,3})/i);
    if (season > 0 && !(se && +se[1] === season && +se[2] === episode)) return [];
    const links = [...new Set([...c.matchAll(/href="(https:\/\/[^"]+)"/g)].map((m) => unesc(m[1])).filter((u) => !u.includes("logout") && MIRRORS.some((x) => u.includes(x))))];
    const k = t.toLowerCase().replace(/[^a-z0-9]/g, "");
    if (!links.length || seen.has(k)) return [];
    seen.add(k);
    const s = c.match(/(\d+(?:\.\d+)?)\s*(GB|MB)\b/);
    return [{ title: t, height: height(t), size: s ? +s[1] * (s[2] === "GB" ? 1024 : 1) : Infinity, links }];
  }).sort((a, b) => b.height - a.height || a.size - b.size);
}

const greenTarget = (html: string) => {
  try {
    const p = html.match(/s\(\s*['"]o['"]\s*,\s*['"]([^'"]+)['"]/)![1];
    const t = b64(JSON.parse(b64(rot13(b64(b64(p))))).o);
    return t.startsWith("https://") ? t : null;
  } catch { return null; }
};
const hubDriveTarget = (html: string) => [...html.matchAll(/href="(https:\/\/[^"]+)"/g)].map((m) => unesc(m[1])).find((u) => /^https:\/\/[^/]*hubcloud\.[^/]+\/drive\//.test(u));
const hubResolver = (html: string) => {
  const a = [...html.matchAll(/<a\s([^>]*)>/g)].find((m) => m[1].includes('id="download"'));
  const h = a?.[1].match(/href="(https:\/\/[^"]+)"/)?.[1] ?? html.match(/https:\/\/[^"'\s]*hubcloud\.php[^"'\s]*/)?.[0];
  return h && unesc(h);
};
const watchOnline = (u: string) => {
  try { const x = new URL(u); const q = x.hostname.includes("pages.dev") && x.searchParams.get("u"); const d = q && b64(q); return d && d.startsWith("https://") ? d : null; } catch { return null; }
};
export function candidates(html: string): [string, string][] {
  const out: [string, string][] = [...html.matchAll(new RegExp(PIXEL, "g"))].map((m) => [`https://${m[1]}/api/file/${m[2]}?download`, "PixelDrain"]);
  for (const m of html.matchAll(/<a\s([^>]*)>([\s\S]*?)<\/a>/g)) {
    if (!/class="[^"]*\bbtn/.test(m[1])) continue;
    const href = m[1].match(/href="([^"]+)"/)?.[1];
    if (!href) continue;
    const label = unesc(m[2].replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim() || "Direct";
    const u = playbackUrl(watchOnline(unesc(href)) ?? pixelApi(unesc(href)) ?? unesc(href));
    if (u) out.push([u, label]);
  }
  return [...new Map(out.map((x) => [x[0], x])).values()].sort((a, b) => score(...a) - score(...b));
}

export async function streams(f: F, title: string, year: number, type: "movie" | "series", season: number, episode: number) {
  const get = async (u: string) => (await f(u)).text;
  const q = title.replace(BR, " ").replace(/\s+(s\d{1,2}|season \d{1,2})\s*$/i, "").replace(/\s+/g, " ").trim();
  // ponytail: series match ignores the year (a per-season subject carries that season's year)
  const hits = parseSearch(await get(`${BASE}/?s=${encodeURIComponent(q)}`));
  console.debug(`[4khdhub] search "${q}"`, hits.map((h) => `${h.title} ${h.year} ${h.series ? "series" : "movie"}`));
  const m = hits.filter((h) => h.series === (type === "series") && norm(h.title) === norm(title) && (type === "series" || !year || !h.year || Math.abs(h.year - year) <= 1))
    .sort((a, b) => +/\[[^\]]*\]/.test(a.title) - +/\[[^\]]*\]/.test(b.title))[0];
  if (!m) return [];
  const rel = parseReleases(await get(BASE + m.id), season, episode), per: Record<number, number> = {};
  console.debug(`[4khdhub] ${m.id}: ${rel.length} releases`, rel.map((r) => r.title));
  const picked = rel.filter((r) => (per[r.height] = (per[r.height] ?? 0) + 1) <= 2).slice(0, 4);
  const mirror = async (u: string, d = 0): Promise<[string, string][]> => {
    if (d > 3) return [];
    const h = host(u);
    if (h.includes("greenmotors.") || h.includes("greenmountmotors.")) { const t = greenTarget(await get(u)); return t ? mirror(t, d + 1) : []; }
    if (h.includes("hubdrive.")) { const t = hubDriveTarget(await get(u)); return t ? mirror(t, d + 1) : []; }
    if (h.includes("hubcloud.") && u.includes("/drive/")) { const r = hubResolver(await get(u)); return r ? candidates(await get(r)) : []; }
    const p = playbackUrl(u); return p ? [[p, "Direct"]] : [];
  };
  const verify = async (u: string): Promise<[string, boolean]> => {
    const ok = async (x: string): Promise<[string, boolean]> => {
      const p = await f(x, { probe: true });
      if (p.status >= 400) throw new Error(`HTTP ${p.status}`);
      return [p.url, p.status === 206, isMedia(p.headers["content-type"] ?? "")] as any;
    };
    const a: any = await ok(u);
    if (a[2]) return [a[0], a[1]];
    const w = playbackUrl(new URL(a[0]).searchParams.get("link") ?? "");
    if (!w) throw new Error("not media");
    const b: any = await ok(w);
    if (!b[2]) throw new Error("not media");
    return [b[0], b[1]];
  };
  const one = async (r: (typeof rel)[0]) => {
    const c = (await Promise.all(r.links.map((l) => mirror(l).catch((e) => (console.debug(`[4khdhub] ${l}: ${e?.message}`), [] as [string, string][]))))).flat();
    const top = [...new Map(c.map((x) => [x[0], x])).values()].sort((a, b) => score(...a) - score(...b)).slice(0, 6);
    const ok = (await Promise.all(top.map(([u, l]) => verify(u).then((v) => ({ v, s: score(u, l) })).catch(() => null)))).filter((x) => x !== null);
    const best = ok.sort((a, b) => +!a.v[1] - +!b.v[1] || a.s - b.s)[0];
    console.debug(`[4khdhub] ${r.title.slice(0, 50)}: links`, r.links, "candidates", top, "playable", ok.length);
    return best && { url: best.v[0], seek: best.v[1], height: r.height, // Chrome/Firefox can't decode Dolby/DTS audio; flagged releases get it decoded by libav.js (use-libav-audio) and sort after AAC ones
    dolby: /ddp|dd\+|dd ?[257]\.1|e-?ac-?3|\bac-?3|dts|atmos|truehd/i.test(r.title) && !/aac|opus/i.test(r.title),
    label: `4KHDHub · ${r.height ? r.height + "p" : "Auto"}${/ddp|dd\+|dd ?[257]\.1|e-?ac-?3|\bac-?3|dts|atmos|truehd/i.test(r.title) && !/aac|opus/i.test(r.title) ? " · Dolby audio" : ""}`, codec: /av1/i.test(r.title) ? "AV1" : /hevc|x265|h\.?265/i.test(r.title) ? "HEVC" : /avc|x264|h\.?264/i.test(r.title) ? "H.264" : undefined, subtitles: [] as any[], langs: langs(r.title) };
  };
  const out = (await Promise.all(picked.map((r) => Promise.race([one(r), new Promise<null>((ok) => setTimeout(ok, 12000, null))]).catch(() => null)))).filter((x) => !!x);
  return out.sort((a, b) => b.height - a.height || +!a.seek - +!b.seek).map(({ seek, ...s }) => s);
}
