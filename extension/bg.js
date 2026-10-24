// Fetch bridge: the page can't reach 4KHDHub (no CORS, blocks datacenters), but this worker can from the viewer's IP.
// Only hosts in host_permissions (minus the Reelm origins themselves) are fetched, so it is not an open proxy.
const hosts = chrome.runtime.getManifest().host_permissions.filter((p) => !/localhost|reelm-web|vercel\.app/.test(p)).map((p) => p.split("/")[2].replace(/^\*\./, ""));
const allowed = (u) => { try { const x = new URL(u); return x.protocol === "https:" && hosts.some((h) => x.hostname === h || x.hostname.endsWith("." + h)); } catch { return false; } };
async function run({ url, headers = {}, probe }) {
  if (!allowed(url)) throw new Error("host not allowed");
  const ac = new AbortController(), t = setTimeout(() => ac.abort(), 12000);
  try {
    const r = await fetch(url, { headers: probe ? { ...headers, Range: "bytes=0-1023" } : headers, signal: ac.signal });
    const text = probe ? "" : await r.text();
    if (probe) ac.abort();
    return { status: r.status, url: r.url, headers: { "content-type": (r.headers.get("content-type") || "").toLowerCase() }, text };
  } finally { clearTimeout(t); }
}
chrome.runtime.onMessage.addListener((m, _s, reply) => {
  if (m?.type !== "fetch") return;
  run(m).then((res) => reply({ res }), (e) => reply({ error: String(e.message || e) }));
  return true;
});
