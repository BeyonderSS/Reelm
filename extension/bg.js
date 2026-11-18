// Fetch bridge: the page can't reach the video sites (no CORS, they block datacenters), but this worker can from the viewer's IP.
// Only the Reelm pages can ask (content.js runs only there, and the sender is checked here). Plain requests stay limited to the
// known hosts; `vega` requests (provider code pinned by the Reelm admin) may reach any public https host, never a private address.
const REELM = /^(https:\/\/(reelm-tv\.vercel\.app|reelm-puneet-bhardwajs-projects-cfe0ba4b\.vercel\.app|reelm-web\.ssbeyonder\.workers\.dev)|http:\/\/localhost(:\d+)?)\//;
const KNOWN = ["hakunaymatata.com", "4khdhub.one", "greenmotors.club", "greenmotors.cc", "hubdrive.bar", "hubcloud.ist", "hubcloud.cx", "hubcloud.fans", "hubcloud.one",
  "cryptonewz.online", "gamerxyt.com", "pixeldrain.dev", "pixeldrain.com", "googleusercontent.com", "workers.dev"];
const PRIVATE = /^(localhost|127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|0\.|\[?::1\]?$|\[?f[cd]|.*\.local$|.*\.internal$)/i;
function allowed(u, vega) {
  try {
    const x = new URL(u);
    if (x.protocol !== "https:" || PRIVATE.test(x.hostname) || REELM.test(x.href)) return false;
    return vega || KNOWN.some((h) => x.hostname === h || x.hostname.endsWith("." + h));
  } catch { return false; }
}

// fetch() here silently drops Referer/Origin (forbidden headers), yet some provider APIs 403 without them (HDHub4u's search).
// A session rule sets them on this worker's own requests to that host. ponytail: last writer wins per host.
const ownRule = async (url, headers) => {
  const set = Object.entries(headers).filter(([k]) => k.toLowerCase() === "referer" || k.toLowerCase() === "origin");
  if (!set.length) return;
  const host = new URL(url).hostname, id = [...host].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 11) % 1e6 + 4_000_000; // was cors()'s id range: each replaced the other
  await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [id], addRules: [{
    id, priority: 3,
    action: { type: "modifyHeaders", requestHeaders: set.map(([header, value]) => ({ header, operation: "set", value: String(value) })) },
    condition: { requestDomains: [host], initiatorDomains: [chrome.runtime.id], resourceTypes: ["xmlhttprequest", "other"] },
  }] });
};

// NetMirror (1.4): `jar` requests carry cookies like the Vega app's providerCookieJar. fetch() can neither send Cookie nor
// read Set-Cookie, so a session rule sends the provider's Cookie merged over the jar's (theirs win on a clash), and webRequest
// reads Set-Cookie into the jar. The rule is per host, so requests needing another rule for a host wait until the ones on it
// finish (same-rule requests share it: a mirror's row asks 20 titles at once). The jar and the providers' kvStore (`kv`) live
// in chrome.storage, so one ~40 s unlock lasts its 12 h. ponytail: cookie paths ignored, domains matched by suffix; same-rule
// requests in flight together each see all of their Set-Cookies.
let jarP;
const jar = () => (jarP ??= chrome.storage.local.get("jar").then((r) => r.jar ?? []));
const caught = new Map(), groups = new Map();
async function keep(host, lines) {
  const j = await jar(), now = Date.now();
  for (const line of lines) {
    const [nv, ...attrs] = line.split(";"), eq = nv.indexOf("="), a = {};
    if (eq < 1) continue;
    for (const x of attrs) { const i = x.indexOf("="); a[(i < 0 ? x : x.slice(0, i)).trim().toLowerCase()] = i < 0 ? "" : x.slice(i + 1).trim(); }
    const c = { n: nv.slice(0, eq).trim(), v: nv.slice(eq + 1).trim(), d: (a.domain || host).replace(/^\./, "").toLowerCase(),
      x: a["max-age"] ? now + +a["max-age"] * 1000 : a.expires ? Date.parse(a.expires) || now : now + 12 * 36e5 }; // a session cookie: 12 h (the worker sleeps)
    j.splice(0, j.length, ...j.filter((o) => (o.n !== c.n || o.d !== c.d) && o.x > now));
    if (c.x > now) j.push(c);
  }
  await chrome.storage.local.set({ jar: j });
}
chrome.webRequest.onHeadersReceived.addListener((d) => {
  const host = new URL(d.url).hostname, sc = d.responseHeaders?.filter((h) => h.name.toLowerCase() === "set-cookie").map((h) => h.value) ?? [];
  if (!caught.get(host)?.size || !sc.length) return;
  for (const a of caught.get(host)) a.push(...sc);
  keep(host, sc);
}, { urls: ["https://*/*"], types: ["xmlhttprequest", "other"] }, ["responseHeaders", "extraHeaders"]);
/** Referer/Origin/User-Agent as given, Cookie = the provider's merged over the jar's */
async function jarHeaders(host, headers) {
  const now = Date.now(), mine = new Map();
  for (const [k, v] of Object.entries(headers)) if (k.toLowerCase() === "cookie") for (const p of String(v).split(";")) { const i = p.indexOf("="); if (i > 0) mine.set(p.slice(0, i).trim(), p.slice(i + 1).trim()); }
  for (const c of await jar()) if (c.x > now && (host === c.d || host.endsWith("." + c.d)) && !mine.has(c.n)) mine.set(c.n, c.v);
  const set = Object.entries(headers).filter(([k]) => ["referer", "origin", "user-agent"].includes(k.toLowerCase())).map(([header, value]) => ({ header, operation: "set", value: String(value) }));
  set.push(mine.size ? { header: "cookie", operation: "set", value: [...mine].map(([k, v]) => `${k}=${v}`).join("; ") } : { header: "cookie", operation: "remove" });
  return set;
}
/** Waits until `host` is free or runs this same rule, then holds it; returns the release. */
async function hold(host, set) {
  const sig = JSON.stringify(set);
  let g = groups.get(host);
  while (g && g.sig !== sig) { await g.gone; g = groups.get(host); }
  if (!g) {
    const id = [...host].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 13) % 1e6 + 3_000_000;
    g = { sig, n: 0 };
    g.gone = new Promise((r) => (g.end = r));
    g.ready = chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [id], addRules: [{
      id, priority: 4, action: { type: "modifyHeaders", requestHeaders: set },
      condition: { requestDomains: [host], initiatorDomains: [chrome.runtime.id], resourceTypes: ["xmlhttprequest", "other"] },
    }] });
    groups.set(host, g);
  }
  g.n++;
  const release = () => { if (--g.n === 0) { groups.delete(host); g.end(); } };
  try { await g.ready; } catch (e) { release(); throw e; }
  return release;
}
const kv = async ({ key, value }) => {
  const k = "kv:" + key;
  if (value === undefined) return (await chrome.storage.local.get(k))[k] ?? null;
  await (value === null ? chrome.storage.local.remove(k) : chrome.storage.local.set({ [k]: value }));
  return null;
};

async function run(m) {
  if (!allowed(m.url, m.vega)) throw new Error("host not allowed");
  if (!m.jar) { if (m.vega) await ownRule(m.url, m.headers ?? {}); return go(m); }
  const host = new URL(m.url).hostname, release = await hold(host, await jarHeaders(host, m.headers ?? {})), sc = [];
  if (!caught.has(host)) caught.set(host, new Set());
  caught.get(host).add(sc);
  try {
    const r = await go({ ...m, credentials: "omit" });
    if (sc.length) r.headers["set-cookie"] = sc;
    return r;
  } finally { caught.get(host).delete(sc); release(); }
}

async function go({ url, headers = {}, probe, method = "GET", body, credentials }) {
  const ac = new AbortController(), t = setTimeout(() => ac.abort(), 15000);
  try {
    const r = await fetch(url, { method, body, credentials, headers: probe ? { Range: "bytes=0-1023", ...headers } : headers, signal: ac.signal }); // a caller may probe with its own range (bytes=0-, what players send)
    const text = probe ? "" : await r.text();
    if (probe) ac.abort();
    const h = {};
    r.headers.forEach((v, k) => { h[k] = k === "content-type" ? v.toLowerCase() : v; });
    return { status: r.status, url: r.url, headers: h, text };
  } finally { clearTimeout(t); }
}

// A CDN that hands out numbered hosts per playlist (NetMirror: makhi1.top, makhi11.top…): one rule for the whole family.
const family = (host) => host.replace(/^([a-z-]*)\d+\./i, "$1#.");
const where = (host) => { const m = host.match(/^([a-z-]*)\d+\.(.+)$/i); return m ? { regexFilter: `^https://${m[1]}\\d+\\.${m[2].replace(/\./g, "\\.")}/` } : { requestDomains: [host] }; };

// Referer/Origin/User-Agent for one host, applied only to requests the Reelm pages start (fetch and <video>), so the
// user's other browsing is untouched. Session rules: gone when the browser closes. ponytail: last writer wins per host.
const SET = ["referer", "origin", "user-agent", "cookie"]; // cookie: 1.4, a NetMirror stream's session
async function hdr({ url, headers = {} }) {
  if (!allowed(url, true)) throw new Error("host not allowed");
  const host = new URL(url).hostname;
  const id = [...family(host)].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7) % 1e6 + 1000; // stable per host, clear of rules.json ids
  const set = Object.entries(headers).filter(([k]) => SET.includes(k.toLowerCase()));
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [id],
    addRules: set.length ? [{
      id, priority: 2,
      action: { type: "modifyHeaders", requestHeaders: set.map(([header, value]) => ({ header, operation: "set", value: String(value) })) },
      condition: { ...where(host), initiatorDomains: ["reelm-tv.vercel.app", "reelm-puneet-bhardwajs-projects-cfe0ba4b.vercel.app", "reelm-web.ssbeyonder.workers.dev", "localhost"], resourceTypes: ["media", "xmlhttprequest", "other"] },
    }] : [],
  });
  return { ok: true };
}

// Lets the Reelm page read one host's files with fetch (the web player decodes audio tracks itself: multi-audio and
// Dolby releases). Most file hosts send no CORS headers, so this adds them, only for fetches Reelm pages start.
async function cors({ url }) {
  if (!allowed(url, true)) throw new Error("host not allowed");
  const host = new URL(url).hostname;
  const id = [...family(host)].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 11) % 1e6 + 2e6; // own id range, so hdr() rules survive
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [id],
    addRules: [{
      id, priority: 2,
      action: { type: "modifyHeaders", responseHeaders: [
        { header: "access-control-allow-origin", operation: "set", value: "*" },
        { header: "access-control-expose-headers", operation: "set", value: "Content-Length, Content-Range, Accept-Ranges" },
      ] },
      condition: { ...where(host), initiatorDomains: ["reelm-tv.vercel.app", "reelm-puneet-bhardwajs-projects-cfe0ba4b.vercel.app", "reelm-web.ssbeyonder.workers.dev", "localhost"], resourceTypes: ["xmlhttprequest", "other"] },
    }],
  });
  return { ok: true };
}

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  if (!REELM.test(sender?.url ?? "")) return; // only the Reelm pages (via content.js)
  const job = m?.type === "fetch" ? run(m) : m?.type === "hdr" ? hdr(m) : m?.type === "cors" ? cors(m) : m?.type === "kv" ? kv(m) : null;
  if (!job) return;
  job.then((res) => reply({ res }), (e) => reply({ error: String(e.message || e) }));
  return true;
});
