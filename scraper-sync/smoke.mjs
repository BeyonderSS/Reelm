// Live end-to-end checks that run an extracted config against the real sites.
// Result is "pass", "fail" (site answered, flow broke) or "skip" (blocked: Cloudflare challenge, MovieBox HTTP 440 to datacenter IPs).
import { createHash, createHmac, randomBytes, randomUUID } from "node:crypto";

export class Skip extends Error {}
const T = 20000;
const attr = (tag, name) => tag.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1];
const tags = (html, cls) => html.match(new RegExp(`<[a-z]+\\b[^>]*\\b${cls}\\b[^>]*>`, "g")) ?? [];
const must = (v, msg) => { if (!v) throw new Error(msg); return v; };

// --- hianime ----------------------------------------------------------------------------------------------------
export async function hianime(c) {
  const get = async (url, referer) => {
    const r = await fetch(url, { headers: { "User-Agent": c.userAgent, ...(referer && { Referer: referer }) }, signal: AbortSignal.timeout(T) });
    const t = await r.text();
    if (/<title>Just a moment/i.test(t)) throw new Skip("hianime: Cloudflare challenge");
    if (!r.ok) throw new Error(`${new URL(url).host} HTTP ${r.status}`);
    return t;
  };
  const fill = (tpl, v) => c.base + tpl.replace("%s", v);

  const search = (await get(fill(c.search, "naruto"))).split('id="main-sidebar"')[0];
  const slug = must(search.match(/<h3 class="film-name">\s*<a href="[^"]*\/([^"\/]+)"/)?.[1], "search: no results parsed");

  const eps = JSON.parse(await get(fill(c.episodes, slug.split("-").pop()))).html;
  const ep = must(tags(eps, "ep-item").map((t) => attr(t, "data-id")).find(Boolean), "episodes: no episode id parsed");

  const servers = JSON.parse(await get(fill(c.servers, ep))).html;
  const hash = must(tags(servers, "server-item").find((t) => attr(t, "data-type") === "sub" && attr(t, "data-server-name")?.toLowerCase() === c.server.toLowerCase() && attr(t, "data-hash")), `servers: no ${c.server} sub server`);
  const embed = Buffer.from(attr(hash, "data-hash").trim(), "base64").toString();
  must(/^https?:\/\//.test(embed), "servers: data-hash is not a url");

  const blob = must((await get(embed)).match(/window\.__P\s*=\s*"([^"]+)"/)?.[1], "embed: window.__P missing");
  const xor = Buffer.from(blob, "base64").map((b, i) => b ^ c.key.charCodeAt(i % c.key.length));
  let cfg;
  try { cfg = JSON.parse(xor.toString()); } catch { throw new Error("embed: blob does not decode with the xor key"); }
  const master = must(cfg.src, "embed: decoded config has no src");

  const m3u8 = await get(master, new URL(embed).origin + "/");
  if (!m3u8.startsWith("#EXTM3U")) throw new Error("stream: master playlist is not an m3u8");
  return `${slug} ep ${ep} -> ${master.split("/").slice(2, 3)[0]}`;
}

// --- moviebox ---------------------------------------------------------------------------------------------------
const md5 = (b) => createHash("md5").update(b).digest("hex");
function canonicalUrl(url) {
  const u = new URL(url);
  const pairs = [...u.searchParams].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  return pairs.length ? `${u.pathname}?${pairs.map(([k, v]) => `${k}=${v}`).join("&")}` : u.pathname;
}
export function sign(secret, method, url, body, ts) {
  const bytes = body == null ? null : Buffer.from(body);
  const canon = [method, "application/json", "application/json", bytes ? bytes.length : "", ts, bytes ? md5(bytes.subarray(0, 102400)) : "", canonicalUrl(url)].join("\n");
  return `${ts}|2|${createHmac("md5", Buffer.from(secret)).update(canon).digest("base64")}`;
}

export async function moviebox(c) {
  const pick = (a) => a[Math.floor(Math.random() * a.length)];
  const vc = pick(c.versionCodes), [os, build] = pick(c.androidVersions), [model, brand] = pick(c.devices);
  const ua = `${c.packageName}/${vc} (Linux; U; Android ${os}; en_US; ${model}; Build/${build}; Cronet/${c.cronet})`;
  const info = JSON.stringify({ package_name: c.packageName, version_name: c.versionName, version_code: vc, os: "android", os_version: os, install_ch: "ps", device_id: randomBytes(16).toString("hex"), install_store: "ps", gaid: randomUUID(), brand, model, system_language: "en", net: pick(c.networks), region: "US", timezone: pick(c.timezones), sp_code: c.spCode, "X-Play-Mode": "2" });
  let token, blocked = 0;

  const call = async (method, path, body = null) => {
    let last;
    for (const host of c.hosts) {
      const ts = Date.now(), url = host + path;
      const h = { "User-Agent": ua, Accept: "application/json", "Content-Type": "application/json", "x-client-token": `${ts},${md5([...String(ts)].reverse().join(""))}`, "x-tr-signature": sign(c.secret, method, url, body, ts), "x-client-info": info, "x-client-status": "0" };
      if (token) h.Authorization = `Bearer ${token}`;
      try {
        const r = await fetch(url, { method, headers: h, body: method === "POST" ? body ?? "{}" : undefined, signal: AbortSignal.timeout(T) });
        if (r.status === 440) { blocked++; last = new Error("HTTP 440"); continue; }
        if (c.retryStatus.includes(r.status)) { last = new Error(`HTTP ${r.status}`); continue; }
        if (!r.ok) throw new Error(`${path} HTTP ${r.status}`);
        try { token = JSON.parse(r.headers.get("x-user") ?? "{}").token || token; } catch {}
        const root = await r.json();
        if (Number(root.code ?? 0) !== 0) throw new Error(`${path} code ${root.code}: ${root.message}`);
        return root.data ?? {};
      } catch (e) { last = e; if (/^\/wefeed.* (HTTP|code)/.test(e.message)) throw e; }
    }
    if (blocked === c.hosts.length) throw new Skip("moviebox: every host answered HTTP 440 (datacenter IP)");
    throw new Error(`moviebox: all hosts failed (${last?.message}${last?.cause?.code ? ` ${last.cause.code}` : ""})`);
  };

  const login = await call("POST", "/wefeed-mobile-bff/user-api/visitor-login", "{}");
  token = login.token || token;
  must(token, "login: no token");
  const found = await call("POST", "/wefeed-mobile-bff/subject-api/search/v2", JSON.stringify({ keyword: "avatar", page: 1, perPage: 15, subjectType: 0 }));
  const hit = must((found.results ?? []).flatMap((r) => r.subjects ?? []).find((s) => s.subjectId && s.title), "search: no results parsed");
  const d = await call("GET", `/wefeed-mobile-bff/subject-api/get?subjectId=${hit.subjectId}`);
  must(d.title, "detail: no title");
  return `${hit.title} (${hit.subjectId})`;
}

// --- dramachi ---------------------------------------------------------------------------------------------------
export async function dramachi(c) {
  const api = async (q) => {
    const r = await fetch(`${c.base}?${new URLSearchParams(q)}`, { signal: AbortSignal.timeout(T) });
    const t = await r.text();
    if (/<title>Just a moment/i.test(t)) throw new Skip("dramachi: Cloudflare challenge");
    if (!r.ok) throw new Error(`dramachi ${q.interface} HTTP ${r.status}`);
    try { return JSON.parse(t); } catch { throw new Error(`dramachi ${q.interface}: response is not JSON`); }
  };
  // anything that is not "movie(s)" is a series (the app maps it the same way)
  const hit = must(((await api({ interface: "search", q: "queen of tears", filter: "all", page: "1" })).data ?? []).find((o) => o.id && !["movie", "movies"].includes(String(o.content).toLowerCase())), "search: no series result");
  const seasons = must((await api({ interface: "title_v2", id: String(hit.id) })).seasons, "title_v2: no seasons");
  const rip = must(Object.values(seasons)[0]?.versions?.[0]?.rip, "title_v2: no rip in first season");
  const ep = must(((await api({ interface: "eplist", season: rip, id: String(hit.id) })).episode_list ?? []).find((e) => e.fid && e.disk), "eplist: no playable episode");
  const f = await api({ interface: "getFile", fid: ep.fid, findex: ep.disk });
  const url = c.cdnPath.replace("{}", String(must(f.hostInfo?.host, "getFile: no host")).replace(/\/$/, "")).replace("{}", String(must(f.fileInfo?.[0]?.url, "getFile: no file url")).replace(/^\//, ""));
  const r = await fetch(url, { headers: { Range: "bytes=0-0" }, signal: AbortSignal.timeout(T) });
  if (![200, 206].includes(r.status)) throw new Error(`cdn file HTTP ${r.status}`);
  r.body?.cancel();
  return `${hit.title} -> ${new URL(url).host}`;
}

// --- 4khdhub ----------------------------------------------------------------------------------------------------
export async function fourkhdhub(c) {
  const get = async (url) => {
    let r;
    try { r = await fetch(url, { headers: { "User-Agent": c.userAgent }, signal: AbortSignal.timeout(T) }); } catch (e) { throw new Skip(`4khdhub: unreachable (${e.cause?.code ?? e.message})`); }
    const t = await r.text();
    if (r.status === 403 || r.status === 503 || /<title>Just a moment/i.test(t)) throw new Skip(`4khdhub: blocked (HTTP ${r.status})`);
    if (!r.ok) throw new Error(`4khdhub HTTP ${r.status}`);
    return t;
  };
  const base = c.base.replace(/\/$/, "");
  const cards = [...(await get(`${base}/?${c.searchParam}=${encodeURIComponent("inception")}`)).matchAll(/<a href="(\/[^"]+)" class="movie-card"[^>]*>([\s\S]*?)<\/a>/g)]
    .filter((m) => /class="movie-card-title">[^<]+</.test(m[2]));
  const first = must(cards[0], "search: no movie-card parsed");
  const page = await get(base + first[1]);
  const items = page.split('class="download-item ').slice(1);
  must(items.length, "detail: no download-item blocks");
  const links = items.flatMap((i) => [...i.matchAll(/href="(https:\/\/[^"]+)"/g)].map((m) => m[1])).filter((u) => c.mirrors.some((m) => u.includes(m)));
  must(links.length, "detail: no mirror links in download items");
  return `${first[1]} (${items.length} releases, ${links.length} mirror links)`;
}

// --- sources with no upstream code to track: plain liveness -------------------------------------------------------
async function probe(name, url, ok, init = {}) {
  try {
    const r = await fetch(url, { ...init, signal: AbortSignal.timeout(T) });
    const t = await r.text();
    if (!r.ok) return { name, status: "fail", detail: `HTTP ${r.status}` };
    return ok(t) ? { name, status: "pass", detail: "ok" } : { name, status: "fail", detail: "unexpected response" };
  } catch (e) { return { name, status: "fail", detail: e.cause?.code ?? e.message }; }
}

export const health = () => Promise.all([
  probe("cinemeta", "https://v3-cinemeta.strem.io/catalog/movie/top/search=inception.json", (t) => JSON.parse(t).metas?.length > 0),
  probe("aniskip", "https://api.aniskip.com/v2/skip-times/21/1?types[]=op&types[]=ed&episodeLength=0", (t) => Array.isArray(JSON.parse(t).results)),
  probe("iptv-org", "https://iptv-org.github.io/iptv/index.m3u", (t) => t.startsWith("#EXTM3U")),
  probe("vsembed", "https://vsembed.ru/embed/movie?imdb=tt0111161", (t) => t.length > 200),
]);

export const checks = { hianime, moviebox, dramachi, fourkhdhub };

/** Runs one check and never throws: { status, detail }. */
export async function run(source, config) {
  try { return { status: "pass", detail: await checks[source](config) }; }
  catch (e) { return { status: e instanceof Skip ? "skip" : "fail", detail: e.message }; }
}

if (process.argv[1]?.endsWith("smoke.mjs")) {
  const [, , source, file] = process.argv;
  const cfg = JSON.parse((await import("node:fs")).readFileSync(file, "utf8"))[source].config;
  const r = await run(source, cfg);
  console.log(source, r.status, r.detail);
  process.exit(r.status === "fail" ? 1 : 0);
}
