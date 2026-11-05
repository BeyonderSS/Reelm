import * as md5ns from "js-md5";
const md5: any = (md5ns as any).default ?? md5ns;

// Port of MovieBoxSigner/MovieBoxClient (oneroom mobile API).
const KEY = Uint8Array.from([0xef,0xa8,0x91,0x97,0x4e,0xec,0xd3,0x14,0x8d,0xf6,0x3a,0xa6,0x11,0x60,0x2d,0xef,0xd1,0x01,0x25,0x9b,0xa5,0x21,0x02,0x2c,0x57,0xae,0x05,0x66,0xbd,0x8e]);
const HOSTS = ["api6.aoneroom.com", "api5.aoneroom.com", "api4.aoneroom.com", "api4sg.aoneroom.com", "api3.aoneroom.com", "api6sg.aoneroom.com", "api.inmoviebox.com"];
const JSON_T = "application/json";
const RETRY = new Set([403, 406, 407, 429, 500, 502, 503, 504]);
const hex = (n: number) => [...Array(n)].map(() => Math.floor(Math.random() * 16).toString(16)).join("");
const pick = <T,>(a: T[]) => a[Math.floor(Math.random() * a.length)];

// ponytail: identity + token live per isolate; a cold isolate just logs in again.
const vc = pick([50020117, 50020118, 50020119, 50020120, 50020121]);
const [os, build] = pick([["10", "QP1A.191005.007.A3"], ["11", "RP1A.200720.011"], ["12", "S1B.220414.015"], ["13", "TQ2A.230405.003"]]);
const model = pick(["23078RKD5C", "2201117TY", "2201117TG", "22101316G", "M2012K11AG"]);
const UA = `com.community.oneroom/${vc} (Linux; U; Android ${os}; en_US; ${model}; Build/${build}; Cronet/135.0.7012.3)`;
const CLIENT_INFO = JSON.stringify({
  package_name: "com.community.oneroom", version_name: "4.0.01.0813.03", version_code: vc, os: "android", os_version: os, install_ch: "ps",
  device_id: hex(32), install_store: "ps", gaid: crypto.randomUUID(), brand: "Redmi", model, system_language: "en", net: "NETWORK_WIFI",
  region: "US", timezone: "Asia/Kolkata", sp_code: "40401", "X-Play-Mode": "2",
});
const IP = `${pick(["103.241", "49.36", "117.195", "106.198", "122.162", "157.32"])}.${1 + Math.floor(Math.random() * 253)}.${1 + Math.floor(Math.random() * 253)}`;
let token: string | undefined;
let hostIdx = 0;

function canonicalUrl(url: string) {
  const u = new URL(url);
  const pairs = u.search.slice(1).split("&").filter(Boolean).map((p) => {
    const i = p.indexOf("=");
    const d = (s: string) => decodeURIComponent(s.replace(/\+/g, " "));
    return [d(i < 0 ? p : p.slice(0, i)), i < 0 ? "" : d(p.slice(i + 1))] as const;
  }).sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  return pairs.length ? `${u.pathname}?${pairs.map(([k, v]) => `${k}=${v}`).join("&")}` : u.pathname;
}

export function sign(method: string, url: string, body: string | null, ts: number) {
  const bytes = body == null ? null : new TextEncoder().encode(body);
  const hash = bytes ? md5(bytes.length > 102400 ? bytes.slice(0, 102400) : bytes) : "";
  const canon = [method.toUpperCase(), JSON_T, JSON_T, bytes ? String(bytes.length) : "", String(ts), hash, canonicalUrl(url)].join("\n");
  const mac = new Uint8Array(md5.hmac.arrayBuffer(KEY, canon));
  return `${ts}|2|${btoa(String.fromCharCode(...mac))}`;
}
export const clientToken = (ts: number) => `${ts},${md5([...String(ts)].reverse().join(""))}`;

class AuthError extends Error {}

/** MovieBox answers HTTP 440 to datacenter IPs (Cloudflare, Vercel), so in the web app this runs in the browser (lib/movies.ts). */
function send(method: string, host: string, path: string, headers: Record<string, string>, body?: BodyInit) {
  return fetch(`https://${host}${path}`, { method, headers, body });
}

async function request(method: string, path: string, body: string | null, tok?: string): Promise<any> {
  let last: Error | undefined;
  for (let a = 0; a < HOSTS.length; a++) {
    const idx = (hostIdx + a) % HOSTS.length;
    const url = `https://${HOSTS[idx]}${path}`;
    const ts = Date.now();
    const h: Record<string, string> = {
      "User-Agent": UA, Accept: JSON_T, "Content-Type": JSON_T, "x-client-token": clientToken(ts),
      "x-tr-signature": sign(method, url, body, ts), "x-client-info": CLIENT_INFO, "x-client-status": "0", "x-forwarded-for": IP,
    };
    if (tok) h.Authorization = `Bearer ${tok}`;
    try {
      const r = await send(method, HOSTS[idx], path, h, method === "POST" ? new TextEncoder().encode(body ?? "{}") : undefined);
      const xu = r.headers.get("x-user");
      if (xu) try { const t = JSON.parse(xu).token; if (t) token = t; } catch {}
      if (r.status === 401) throw new AuthError();
      if (RETRY.has(r.status)) { last = new Error(`MovieBox host returned HTTP ${r.status}`); continue; }
      if (!r.ok) throw new Error(`MovieBox returned HTTP ${r.status} ${(await r.text()).slice(0, 120)}`);
      const root = await r.json() as any;
      const code = Number(root.code ?? 0);
      if (code === 401 || code === 1001) throw new AuthError();
      if (code !== 0) throw new Error(`MovieBox error ${code}: ${root.message}`);
      hostIdx = idx;
      return root.data ?? {};
    } catch (e) {
      if (e instanceof AuthError) throw e;
      last = e as Error;
    }
  }
  throw last ?? new Error("MovieBox unreachable");
}

async function login() {
  const d = await request("POST", "/wefeed-mobile-bff/user-api/visitor-login", "{}");
  if (!d.token) throw new Error("MovieBox login returned no token");
  return (token = d.token as string);
}
async function call(method: string, path: string, body: string | null = null) {
  try { return await request(method, path, body, token ?? (await login())); }
  catch (e) { if (!(e instanceof AuthError)) throw e; return request(method, path, body, await login()); }
}

export type Item = { id: string; type: "movie" | "series"; title: string; poster?: string; year?: number; rating?: number; genres: string[] };
const s = (v: unknown) => (v == null || v === "" || v === "null" ? undefined : String(v));

function item(o: any): Item | null {
  const id = s(o.subjectId), title = s(o.title);
  if (!id || !title) return null;
  return {
    id: `mb:${id}`, type: s(o.subjectType) === "2" ? "series" : "movie", title, poster: s(o.cover?.url),
    year: Number(s(o.releaseDate)?.slice(0, 4)) || undefined, rating: Number(s(o.imdbRatingValue)) || undefined,
    genres: (s(o.genre) ?? "").split(",").map((g) => g.trim()).filter(Boolean),
  };
}

export async function search(q: string, page = 1) {
  const data = await call("POST", "/wefeed-mobile-bff/subject-api/search/v2", JSON.stringify({ keyword: q, page, perPage: 15, subjectType: 0 }));
  const seen = new Set<string>();
  return ((data.results ?? []) as any[]).flatMap((r) => r.subjects ?? []).map(item).filter((i): i is Item => !!i && !seen.has(i.id) && !!seen.add(i.id));
}

/** "esla dub" -> Spanish (Latin America), "Original Audio" -> Original, "hi" -> Hindi (same rules as the TV app's audioLabel). */
const REGIONS: Record<string, string> = { esla: "es-419", eses: "es-ES", ptbr: "pt-BR", ptpt: "pt-PT", zhcn: "zh-Hans", zhtw: "zh-Hant", frca: "fr-CA", enus: "en-US", engb: "en-GB" };
export function audioLabel(label: string, code = "") {
  const clean = label.trim().replace(/\s+(dub|sub|audio)$/i, "").trim(), k = clean.toLowerCase().replace("-", "");
  if (!k || k === "original") return "Original";
  const name = (c: string) => { try { const n = new Intl.DisplayNames(["en"], { type: "language" }).of(c); return n && n.toLowerCase() !== c.toLowerCase() ? n : undefined; } catch { return undefined; } };
  return (REGIONS[k] && name(REGIONS[k])) || (k.length <= 3 && name(k)) || (k.length <= 4 && code && name(code.slice(0, 2))) || clean[0].toUpperCase() + clean.slice(1);
}

export async function detail(subjectId: string) {
  const [d, si] = await Promise.all([
    call("GET", `/wefeed-mobile-bff/subject-api/get?subjectId=${subjectId}`),
    call("GET", `/wefeed-mobile-bff/subject-api/season-info?subjectId=${subjectId}`).catch(() => null),
  ]);
  const base = item(d);
  if (!base) throw new Error("MovieBox detail missing");
  return {
    ...base, description: s(d.description)?.replace(/&apos;/g, "'").replace(/&quot;/g, '"').replace(/&amp;/g, "&"),
    backdrop: s(d.stills?.url) ?? base.poster, runtime: s(d.duration), country: s(d.countryName),
    cast: ((d.staffList ?? []) as any[]).map((x) => s(x.name)).filter(Boolean).slice(0, 12),
    seasons: ((si?.seasons ?? []) as any[]).map((x) => ({ season: Number(x.se), episodes: Number(x.maxEp) })).filter((x) => x.season > 0 && x.episodes > 0),
    // each dub is its own MovieBox subject; the current one is this subject
    audio: ((d.dubs ?? []) as any[]).filter((x) => s(x.subjectId)).map((x) => ({ id: `mb:${s(x.subjectId)}`, label: audioLabel(s(x.lanName) ?? "", s(x.lanCode)), current: s(x.subjectId) === subjectId })),
    playable: s(d.hasResource) !== "false",
  };
}

const resources = (d: any) => ((d?.list ?? []) as any[]).map((o) => ({
  id: s(o.resourceId), link: s(o.resourceLink) ?? "", season: Number(s(o.se)) || 0, episode: Number(s(o.ep)) || 0,
  height: Number(s(o.resolution)) || 0, codec: s(o.codecName),
})).filter((r) => r.id);
const isNotice = (u: string) => u.includes("macdn.aoneroom.com") && u.includes("/other/");

export async function streams(subjectId: string, season: number, episode: number) {
  const list = await call("GET", `/wefeed-mobile-bff/subject-api/resource/v2?subjectId=${subjectId}&page=1&perPage=20&all=0&startPosition=1&endPosition=1&pagerMode=2&resolution=0&se=${season}&epFrom=${episode}&epTo=${episode}&isVip=true`);
  const out = await Promise.all(resources(list).filter((r) => r.season === season && r.episode === episode).map(async (r) => {
    try {
      const q = Object.entries({ subjectId, resourceId: r.id!, failUrl: r.link, failCode: "404", resourceNum: "0", isVip: "true" }).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
      const pos = await call("GET", `/wefeed-mobile-bff/subject-api/resource-position/v2?${q}`);
      const real = resources(pos).find((x) => x.id === r.id && x.season === r.season && x.episode === r.episode && x.link.startsWith("http") && !isNotice(x.link));
      if (!real) return null;
      const caps = await call("GET", `/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=${subjectId}&resourceId=${r.id}`).catch(() => ({}));
      const subtitles = ((caps.extCaptions ?? []) as any[]).map((c) => ({ url: s(c.url)!, lang: s(c.lan) ?? "", label: s(c.lanName) ?? s(c.lan) ?? "" })).filter((c) => c.url)
        .sort((a, b) => +!a.lang.startsWith("en") - +!b.lang.startsWith("en"));
      return { url: real.link, height: r.height, label: r.height ? `${r.height}p` : "Auto", codec: r.codec, subtitles };
    } catch { return null; }
  }));
  return out.filter((x): x is NonNullable<typeof x> => !!x).sort((a, b) => b.height - a.height || +(a.codec !== "h264") - +(b.codec !== "h264"));
}
