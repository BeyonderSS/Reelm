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
  const host = new URL(url).hostname, id = [...host].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 11) % 1e6 + 2_000_000;
  await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [id], addRules: [{
    id, priority: 3,
    action: { type: "modifyHeaders", requestHeaders: set.map(([header, value]) => ({ header, operation: "set", value: String(value) })) },
    condition: { requestDomains: [host], initiatorDomains: [chrome.runtime.id], resourceTypes: ["xmlhttprequest", "other"] },
  }] });
};

async function run({ url, headers = {}, probe, method = "GET", body, vega }) {
  if (!allowed(url, vega)) throw new Error("host not allowed");
  if (vega) await ownRule(url, headers);
  const ac = new AbortController(), t = setTimeout(() => ac.abort(), 15000);
  try {
    const r = await fetch(url, { method, body, headers: probe ? { Range: "bytes=0-1023", ...headers } : headers, signal: ac.signal }); // a caller may probe with its own range (bytes=0-, what players send)
    const text = probe ? "" : await r.text();
    if (probe) ac.abort();
    const h = {};
    r.headers.forEach((v, k) => { h[k] = k === "content-type" ? v.toLowerCase() : v; });
    return { status: r.status, url: r.url, headers: h, text };
  } finally { clearTimeout(t); }
}

// Referer/Origin/User-Agent for one host, applied only to requests the Reelm pages start (fetch and <video>), so the
// user's other browsing is untouched. Session rules: gone when the browser closes. ponytail: last writer wins per host.
const SET = ["referer", "origin", "user-agent"];
async function hdr({ url, headers = {} }) {
  if (!allowed(url, true)) throw new Error("host not allowed");
  const host = new URL(url).hostname;
  const id = [...host].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7) % 1e6 + 1000; // stable per host, clear of rules.json ids
  const set = Object.entries(headers).filter(([k]) => SET.includes(k.toLowerCase()));
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [id],
    addRules: set.length ? [{
      id, priority: 2,
      action: { type: "modifyHeaders", requestHeaders: set.map(([header, value]) => ({ header, operation: "set", value: String(value) })) },
      condition: { requestDomains: [host], initiatorDomains: ["reelm-tv.vercel.app", "reelm-puneet-bhardwajs-projects-cfe0ba4b.vercel.app", "reelm-web.ssbeyonder.workers.dev", "localhost"], resourceTypes: ["media", "xmlhttprequest", "other"] },
    }] : [],
  });
  return { ok: true };
}

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  if (!REELM.test(sender?.url ?? "")) return; // only the Reelm pages (via content.js)
  const job = m?.type === "fetch" ? run(m) : m?.type === "hdr" ? hdr(m) : null;
  if (!job) return;
  job.then((res) => reply({ res }), (e) => reply({ error: String(e.message || e) }));
  return true;
});
