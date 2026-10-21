import { DurableObject } from "cloudflare:workers";

export interface Env {
  DB: D1Database;
  USER_HUB: DurableObjectNamespace<UserHub>;
  ROOM: DurableObjectNamespace<Room>;
  ASSET_LINKS?: string;
}

class HttpError extends Error {
  constructor(public status: number, message: string) { super(message); }
}
const fail = (status: number, msg: string): never => { throw new HttpError(status, msg); };
const json = (body: unknown, status = 200) => Response.json(body, { status });
const none = () => new Response(null, { status: 204 });

// ---- crypto -------------------------------------------------------------
const b64 = (b: ArrayBuffer | Uint8Array) => btoa(String.fromCharCode(...new Uint8Array(b)));
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const b64url = (b: Uint8Array) => b64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
const sha256 = async (s: string) => hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));

// The free plan allows 10 ms CPU per request and workerd caps PBKDF2 at 100k iterations.
// 100k SHA-256 rounds is ~25-40 ms of CPU, so we use 30k (~8-10 ms). The count is stored with
// each hash, so it can be raised later (paid plan) without breaking existing accounts.
const ITERATIONS = 30_000;
async function pbkdf2(password: string, salt: Uint8Array, iterations: number) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  return crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: salt as BufferSource, iterations }, key, 256);
}
async function hashPassword(password: string) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return `${ITERATIONS}:${b64(salt)}:${b64(await pbkdf2(password, salt, ITERATIONS))}`;
}
async function checkPassword(password: string, stored: string) {
  const [it, salt, hash] = stored.split(":");
  const got = await pbkdf2(password, unb64(salt), Number(it));
  return (crypto.subtle as any).timingSafeEqual(got, unb64(hash));
}

// ---- helpers ------------------------------------------------------------
interface Ctx { req: Request; env: Env; url: URL; params: string[]; }
type User = { id: string; username: string };
const now = () => Date.now();
const uuid = () => crypto.randomUUID();
const randomCode = (alphabet: string, n: number) =>
  Array.from(crypto.getRandomValues(new Uint8Array(n)), (x) => alphabet[x % alphabet.length]).join("");

async function body<T = any>(req: Request): Promise<T> {
  try { const b = await req.json(); if (b && typeof b === "object") return b as T; } catch {}
  return fail(400, "invalid JSON body");
}

async function newSession(env: Env, userId: string) {
  const token = b64url(crypto.getRandomValues(new Uint8Array(32)));
  await env.DB.prepare("INSERT INTO sessions (token_hash, user_id, created_at) VALUES (?,?,?)")
    .bind(await sha256(token), userId, now()).run();
  return token;
}

async function userFromToken(env: Env, token: string | null | undefined): Promise<User | null> {
  if (!token) return null;
  return env.DB.prepare("SELECT u.id, u.username FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?")
    .bind(await sha256(token)).first<User>();
}
const bearer = (req: Request) => req.headers.get("Authorization")?.match(/^Bearer (.+)$/)?.[1];
async function auth(c: Ctx): Promise<User> {
  return (await userFromToken(c.env, bearer(c.req))) ?? fail(401, "unauthorized");
}

const profileOut = (r: any) => ({ id: r.id, name: r.name, avatar: r.avatar, kids: !!r.kids, createdAt: r.created_at });
async function ownProfile(c: Ctx, user: User, id: string) {
  return (await c.env.DB.prepare("SELECT * FROM profiles WHERE id = ? AND user_id = ?").bind(id, user.id).first())
    ?? fail(404, "profile not found");
}
const str = (v: unknown, max: number, what: string) =>
  typeof v === "string" && v.trim() && v.length <= max ? v.trim() : fail(400, `invalid ${what}`);

// ---- handlers -----------------------------------------------------------
async function register(c: Ctx) {
  const { username, password } = await body(c.req);
  if (typeof username !== "string" || !/^[a-z0-9_]{3,32}$/.test(username)) fail(400, "username must be 3-32 chars of a-z, 0-9, _");
  if (typeof password !== "string" || password.length < 8) fail(400, "password must be at least 8 characters");
  const id = uuid(), t = now();
  try {
    await c.env.DB.prepare("INSERT INTO users (id, username, pass, created_at) VALUES (?,?,?,?)")
      .bind(id, username, await hashPassword(password), t).run();
  } catch { fail(409, "username taken"); }
  await c.env.DB.prepare("INSERT INTO profiles (id, user_id, name, avatar, kids, created_at) VALUES (?,?,?,?,0,?)")
    .bind(uuid(), id, username, "grinning_face", t).run();
  return json({ token: await newSession(c.env, id), user: { id, username } }, 201);
}

async function login(c: Ctx) {
  const { username, password } = await body(c.req);
  const row = typeof username === "string" && typeof password === "string"
    ? await c.env.DB.prepare("SELECT id, username, pass FROM users WHERE username = ?").bind(username).first<any>() : null;
  if (!row || !(await checkPassword(password, row.pass))) fail(401, "wrong username or password");
  return json({ token: await newSession(c.env, row.id), user: { id: row.id, username: row.username } });
}

async function logout(c: Ctx) {
  const t = bearer(c.req);
  if (t) await c.env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(await sha256(t)).run();
  return none();
}

async function listProfiles(c: Ctx) {
  const u = await auth(c);
  const { results } = await c.env.DB.prepare("SELECT * FROM profiles WHERE user_id = ? ORDER BY created_at, id").bind(u.id).all();
  return json({ profiles: results.map(profileOut) });
}

async function createProfile(c: Ctx) {
  const u = await auth(c);
  const b = await body(c.req);
  const name = str(b.name, 32, "name"), avatar = str(b.avatar, 64, "avatar");
  const row = { id: uuid(), name, avatar, kids: b.kids ? 1 : 0, created_at: now() };
  // Insert only while under the limit, atomically.
  const r = await c.env.DB.prepare(
    "INSERT INTO profiles (id, user_id, name, avatar, kids, created_at) SELECT ?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM profiles WHERE user_id = ?) < 5")
    .bind(row.id, u.id, name, avatar, row.kids, row.created_at, u.id).run();
  if (!r.meta.changes) fail(409, "profile limit reached (5)");
  return json(profileOut(row), 201);
}

async function patchProfile(c: Ctx) {
  const u = await auth(c);
  const cur: any = await ownProfile(c, u, c.params[0]);
  const b = await body(c.req);
  const next = {
    name: b.name === undefined ? cur.name : str(b.name, 32, "name"),
    avatar: b.avatar === undefined ? cur.avatar : str(b.avatar, 64, "avatar"),
    kids: b.kids === undefined ? cur.kids : b.kids ? 1 : 0,
  };
  await c.env.DB.prepare("UPDATE profiles SET name=?, avatar=?, kids=? WHERE id=?").bind(next.name, next.avatar, next.kids, cur.id).run();
  return json(profileOut({ ...cur, ...next }));
}

async function deleteProfile(c: Ctx) {
  const u = await auth(c);
  const cur: any = await ownProfile(c, u, c.params[0]);
  const r = await c.env.DB.prepare(
    "DELETE FROM profiles WHERE id = ? AND (SELECT COUNT(*) FROM profiles WHERE user_id = ?) > 1").bind(cur.id, u.id).run();
  if (!r.meta.changes) fail(409, "cannot delete the last profile");
  await c.env.DB.prepare("DELETE FROM history WHERE profile_id = ?").bind(cur.id).run(); // D1 FKs are not always enforced
  return none();
}

async function getHistory(c: Ctx) {
  const u = await auth(c);
  const p: any = await ownProfile(c, u, c.params[0]);
  const since = Number(c.url.searchParams.get("since") ?? 0) || 0;
  const t = now();
  const { results } = await c.env.DB.prepare("SELECT * FROM history WHERE profile_id = ? AND synced_at > ? ORDER BY synced_at")
    .bind(p.id, since).all<any>();
  return json({
    entries: results.map((r) => ({ key: r.key, payload: JSON.parse(r.payload), position: r.position,
      duration: r.duration, updatedAt: r.updated_at, deleted: !!r.deleted })),
    now: t,
  });
}

async function putHistory(c: Ctx) {
  const u = await auth(c);
  const p: any = await ownProfile(c, u, c.params[0]);
  const { entries } = await body(c.req);
  if (!Array.isArray(entries) || entries.length > 500) fail(400, "entries must be an array of at most 500");
  const t = now();
  const stmt = c.env.DB.prepare(
    `INSERT INTO history (profile_id, key, payload, position, duration, updated_at, synced_at, deleted) VALUES (?,?,?,?,?,?,?,?)
     ON CONFLICT(profile_id, key) DO UPDATE SET payload=excluded.payload, position=excluded.position, duration=excluded.duration,
       updated_at=excluded.updated_at, synced_at=excluded.synced_at, deleted=excluded.deleted
     WHERE excluded.updated_at > history.updated_at`);
  const batch = entries.map((e: any) => {
    if (!e || typeof e.key !== "string" || !e.key || typeof e.updatedAt !== "number") fail(400, "invalid entry");
    return stmt.bind(p.id, e.key, JSON.stringify(e.payload ?? null), Math.round(Number(e.position) || 0),
      Math.round(Number(e.duration) || 0), Math.round(e.updatedAt), t, e.deleted ? 1 : 0);
  });
  if (batch.length) await c.env.DB.batch(batch);
  await c.env.USER_HUB.getByName(u.id).fetch("https://hub/notify", {
    method: "POST", body: JSON.stringify({ type: "history", profileId: p.id }),
  });
  return json({ now: t });
}

// pair flow
const PAIR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
async function pairStart(c: Ctx) {
  const code = randomCode(PAIR_ALPHABET, 8), expiresAt = now() + 10 * 60_000;
  await c.env.DB.prepare("DELETE FROM pairs WHERE expires_at < ?").bind(now()).run();
  await c.env.DB.prepare("INSERT INTO pairs (code, expires_at) VALUES (?,?)").bind(code, expiresAt).run();
  return json({ code, expiresAt }, 201);
}
const pairCode = (c: Ctx) => c.params[0].toUpperCase();
async function pairClaim(c: Ctx) {
  const u = await auth(c);
  const r = await c.env.DB.prepare("UPDATE pairs SET user_id = ? WHERE code = ? AND expires_at > ? AND user_id IS NULL")
    .bind(u.id, pairCode(c), now()).run();
  if (!r.meta.changes) fail(404, "code not found or expired");
  return none();
}
async function pairPoll(c: Ctx) {
  const row = await c.env.DB.prepare("SELECT user_id FROM pairs WHERE code = ? AND expires_at > ?").bind(pairCode(c), now()).first<any>();
  if (!row) fail(404, "code not found or expired");
  if (!row.user_id) return json({ status: "pending" });
  // One-shot: only the request that deletes the row gets the token.
  const del = await c.env.DB.prepare("DELETE FROM pairs WHERE code = ?").bind(pairCode(c)).run();
  if (!del.meta.changes) fail(404, "code not found or expired");
  const user = await c.env.DB.prepare("SELECT id, username FROM users WHERE id = ?").bind(row.user_id).first<User>();
  return json({ status: "claimed", token: await newSession(c.env, row.user_id), user });
}
function pairPage(c: Ctx) {
  const code = c.params[0].toUpperCase();
  if (!/^[A-Z0-9]{4,16}$/.test(code)) fail(404, "not found");
  return new Response(`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Reelm</title><style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:20px;background:#15171F;color:#eee;font-family:system-ui,sans-serif}
a{background:#F2B45A;color:#15171F;padding:14px 28px;border-radius:12px;font-weight:700;text-decoration:none}code{color:#F2B45A;font-size:28px;letter-spacing:4px}</style></head>
<body><h2>Sign in your TV</h2><code>${code}</code><a href="reelm://pair/${code}">Open in Reelm</a></body></html>`,
    { headers: { "content-type": "text/html; charset=utf-8" } });
}

// websockets
async function hub(c: Ctx) {
  const u = await userFromToken(c.env, c.url.searchParams.get("token") ?? bearer(c.req)) ?? fail(401, "unauthorized");
  if (c.req.headers.get("Upgrade") !== "websocket") fail(426, "expected websocket");
  return c.env.USER_HUB.getByName(u.id).fetch(c.req);
}
async function createRoom(c: Ctx) {
  const { payload } = await body(c.req);
  if (payload === undefined) fail(400, "payload required");
  const code = randomCode(PAIR_ALPHABET.replace(/[01IO]/g, ""), 6);
  await c.env.ROOM.getByName(code).fetch("https://room/init", { method: "POST", body: JSON.stringify({ payload }) });
  return json({ code }, 201);
}
async function joinRoom(c: Ctx) {
  if (c.req.headers.get("Upgrade") !== "websocket") fail(426, "expected websocket");
  return c.env.ROOM.getByName(c.params[0].toUpperCase()).fetch(c.req);
}

// ---- router -------------------------------------------------------------
const routes: [string, RegExp, (c: Ctx) => Response | Promise<Response>][] = [
  ["POST", /^\/v1\/auth\/register$/, register],
  ["POST", /^\/v1\/auth\/login$/, login],
  ["POST", /^\/v1\/auth\/logout$/, logout],
  ["GET", /^\/v1\/me$/, async (c) => json({ user: await auth(c) })],
  ["GET", /^\/v1\/profiles$/, listProfiles],
  ["POST", /^\/v1\/profiles$/, createProfile],
  ["PATCH", /^\/v1\/profiles\/([^/]+)$/, patchProfile],
  ["DELETE", /^\/v1\/profiles\/([^/]+)$/, deleteProfile],
  ["GET", /^\/v1\/profiles\/([^/]+)\/history$/, getHistory],
  ["PUT", /^\/v1\/profiles\/([^/]+)\/history$/, putHistory],
  ["POST", /^\/v1\/pair$/, pairStart],
  ["POST", /^\/v1\/pair\/([^/]+)\/claim$/, pairClaim],
  ["GET", /^\/v1\/pair\/([^/]+)$/, pairPoll],
  ["GET", /^\/pair\/([^/]+)$/, pairPage],
  ["GET", /^\/v1\/proxy$/, proxy],
  ["GET", /^\/v1\/probe$/, probe],
  ["GET", /^\/v1\/hub$/, hub],
  ["POST", /^\/v1\/rooms$/, createRoom],
  ["GET", /^\/v1\/rooms\/([^/]+)$/, joinRoom],
  ["GET", /^\/\.well-known\/assetlinks\.json$/, (c) =>
    new Response(c.env.ASSET_LINKS || "[]", { headers: { "content-type": "application/json" } })],
];

// ---- stream proxy (web player) ----------------------------------------------
// Replaces what the Reelm Helper extension did, so the web player needs no install: the browser can't send a Referer or an
// app User-Agent, can't drop Sec-Fetch-*, and can't read hosts without CORS. Hosts the parsers return only.
const PROXY_HOSTS = [
  "dramahot.top", "drama1.cfd", "zokoanime.video", "zokoanime.com", "zokoanime.net", "hianime.at",
  "hakunaymatata.com", "pixeldrain.com", "pixeldrain.dev", // MovieBox CDN, Pixeldrain (4KHDHub)
  "4khdhub.one", "cryptonewz.online", "gamerxyt.com", // 4KHDHub pages and its resolver chain
];
// the 4KHDHub mirrors change TLD often (hubcloud.ist, .cx, .fans ...), so match on the name
const MIRROR = /(^|\.)(hubcloud|hubdrive|greenmotors|greenmountmotors)\.[a-z]{2,}$/;
export const hostOk = (h: string) => MIRROR.test(h) || PROXY_HOSTS.some((p) => h === p || h.endsWith("." + p));
const isPrivate = (h: string) => h === "localhost" || h.endsWith(".local") || h.startsWith("[") || /^(0|10|127)\.|^192\.168\.|^172\.(1[6-9]|2\d|3[01])\.|^169\.254\./.test(h);
const BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
// MovieBox's CDN answers 428/429 to browser user agents and wants no Referer at all
const APP_UA_HOSTS = ["hakunaymatata.com"];

export function upstreamHeaders(host: string, referer: string, range: string | null): Record<string, string> {
  const app = APP_UA_HOSTS.some((p) => host === p || host.endsWith("." + p));
  return { "User-Agent": app ? "okhttp/4.12.0" : BROWSER_UA, ...(referer && !app ? { Referer: referer } : {}), ...(range ? { Range: range } : {}) };
}

/** Point every segment/key/variant URL of a playlist back at the proxy. */
export function rewritePlaylist(text: string, base: string, proxyBase: string, referer: string, any: boolean) {
  const wrap = (u: string) => `${proxyBase}?u=${encodeURIComponent(new URL(u, base).href)}&r=${encodeURIComponent(referer)}${any ? "&any=1" : ""}`;
  return text.split("\n").map((l) => {
    const t = l.trim();
    if (!t) return l;
    return t.startsWith("#") ? l.replace(/URI="([^"]+)"/g, (_, u) => `URI="${wrap(u)}"`) : wrap(t);
  }).join("\n");
}

async function proxy({ req, url }: Ctx) {
  let u: URL;
  try { u = new URL(url.searchParams.get("u") ?? ""); } catch { return json({ error: "bad url" }, 400); }
  const any = url.searchParams.get("any") === "1"; // live TV: channels live on arbitrary hosts
  if (!/^https?:$/.test(u.protocol) || isPrivate(u.hostname) || !(any || hostOk(u.hostname))) return json({ error: "host not allowed" }, 403);
  const referer = url.searchParams.get("r") ?? "";
  const up = await fetch(u, { headers: upstreamHeaders(u.hostname, referer, req.headers.get("range")) });
  const ct = up.headers.get("content-type") ?? "";
  if (up.ok && (u.pathname.endsWith(".m3u8") || /mpegurl/i.test(ct))) {
    const body = rewritePlaylist(await up.text(), u.href, `${url.origin}/v1/proxy`, referer, any);
    return new Response(body, { headers: { "content-type": "application/vnd.apple.mpegurl", "cache-control": "no-store" } });
  }
  const h = new Headers();
  for (const k of ["content-type", "content-length", "content-range", "accept-ranges"]) if (up.headers.has(k)) h.set(k, up.headers.get(k)!);
  h.set("x-final-url", up.url); // after redirects; the 4KHDHub chain unwraps resolver urls from it
  return new Response(up.body, { status: up.status, headers: h });
}

/** First kilobyte of any public https file: status, final url, content type. Lets the player pick a working 4KHDHub mirror. */
async function probe({ url }: Ctx) {
  let u: URL;
  try { u = new URL(url.searchParams.get("u") ?? ""); } catch { return json({ error: "bad url" }, 400); }
  if (u.protocol !== "https:" || isPrivate(u.hostname) || /^[\d.]+$/.test(u.hostname)) return json({ error: "host not allowed" }, 403);
  try {
    const up = await fetch(u, { headers: upstreamHeaders(u.hostname, "", "bytes=0-1023"), signal: AbortSignal.timeout(10000) });
    up.body?.cancel();
    return json({ status: up.status, url: up.url, type: (up.headers.get("content-type") ?? "").toLowerCase() });
  } catch { return json({ status: 0, url: u.href, type: "" }); }
}

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
  "access-control-allow-headers": "authorization,content-type,range",
  "access-control-expose-headers": "content-length,content-range,accept-ranges,x-final-url",
};

async function route(req: Request, env: Env): Promise<Response> {
  const url = new URL(req.url);
  if (req.method === "OPTIONS") return new Response(null, { status: 204 });
  let pathMatched = false;
  for (const [method, re, fn] of routes) {
    const m = url.pathname.match(re);
    if (!m) continue;
    pathMatched = true;
    if (method === req.method) return fn({ req, env, url, params: m.slice(1).map(decodeURIComponent) });
  }
  return json({ error: pathMatched ? "method not allowed" : "not found" }, pathMatched ? 405 : 404);
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    let res: Response;
    try { res = await route(req, env); }
    catch (e) {
      if (!(e instanceof HttpError)) console.error(e);
      res = e instanceof HttpError ? json({ error: e.message }, e.status) : json({ error: "internal error" }, 500);
    }
    if (res.status === 101) return res;
    res = new Response(res.body, res);
    for (const [k, v] of Object.entries(CORS)) res.headers.set(k, v);
    return res;
  },
};

// ---- Durable Objects ----------------------------------------------------
const send = (ws: WebSocket, m: unknown) => { try { ws.send(JSON.stringify(m)); } catch {} };
const parse = (m: string | ArrayBuffer) => { try { const v = JSON.parse(m as string); return v && typeof v === "object" ? v : null; } catch { return null; } };
function upgrade(state: DurableObjectState) {
  const [client, server] = Object.values(new WebSocketPair());
  state.acceptWebSocket(server);
  return { client, server };
}

type Device = { deviceId: string; deviceName: string; kind: string };

/** One per account: device presence, handoff, and "history changed" pushes. */
export class UserHub extends DurableObject<Env> {
  async fetch(req: Request): Promise<Response> {
    if (req.headers.get("Upgrade") === "websocket") {
      const { client } = upgrade(this.ctx);
      return new Response(null, { status: 101, webSocket: client });
    }
    const msg = await req.text(); // internal notify from the worker
    for (const ws of this.ctx.getWebSockets()) ws.send(msg);
    return none();
  }

  private devices(except?: WebSocket): Device[] {
    return this.ctx.getWebSockets().filter((w) => w !== except)
      .map((w) => w.deserializeAttachment() as Device | null).filter((d): d is Device => !!d);
  }
  private broadcastDevices(except?: WebSocket) {
    const m = { type: "devices", devices: this.devices(except) };
    for (const ws of this.ctx.getWebSockets()) if (ws !== except) send(ws, m);
  }

  webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer) {
    const m = parse(raw);
    if (!m) return;
    if (m.type === "hello" && typeof m.deviceId === "string") {
      ws.serializeAttachment({ deviceId: m.deviceId, deviceName: String(m.deviceName ?? "").slice(0, 64),
        kind: m.kind === "tv" ? "tv" : "phone" } satisfies Device);
      this.broadcastDevices();
    } else if (m.type === "handoff") {
      const from = ws.deserializeAttachment() as Device | null;
      for (const t of this.ctx.getWebSockets())
        if ((t.deserializeAttachment() as Device | null)?.deviceId === m.to) send(t, { ...m, from: from?.deviceId });
    }
  }
  webSocketClose(ws: WebSocket, code: number) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code); } catch {}
    this.broadcastDevices(ws);
  }
  webSocketError(ws: WebSocket) { this.broadcastDevices(ws); }
}

type Member = { id: string; name: string; avatar: string };
const HOUR = 3_600_000;

/** One per watch-together room code. State lives in storage so it survives hibernation. */
export class Room extends DurableObject<Env> {
  async fetch(req: Request): Promise<Response> {
    const s = this.ctx.storage;
    if (req.method === "POST") { // internal init
      const { payload } = await req.json<any>();
      await s.put({ payload, playing: false, position: 0, at: now() });
      await s.setAlarm(now() + HOUR); // never-joined rooms expire too
      return none();
    }
    if ((await s.get("payload")) === undefined) return json({ error: "room not found" }, 404);
    await s.deleteAlarm();
    const { client } = upgrade(this.ctx);
    return new Response(null, { status: 101, webSocket: client });
  }

  private members(except?: WebSocket): Member[] {
    return this.ctx.getWebSockets().filter((w) => w !== except)
      .map((w) => w.deserializeAttachment() as Member | null).filter((m): m is Member => !!m);
  }
  private broadcast(m: unknown, except?: WebSocket) {
    for (const ws of this.ctx.getWebSockets()) if (ws !== except && ws.deserializeAttachment()) send(ws, m);
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer) {
    const m = parse(raw), s = this.ctx.storage;
    if (!m) return;
    if (m.type === "join") {
      const id = uuid();
      ws.serializeAttachment({ id, name: String(m.name ?? "Guest").slice(0, 32), avatar: String(m.avatar ?? "").slice(0, 64) } satisfies Member);
      const st = await s.get(["payload", "playing", "position", "at"]);
      send(ws, { type: "state", you: id, payload: st.get("payload"), playing: st.get("playing"), position: st.get("position"), at: st.get("at"), members: this.members() });
      this.broadcast({ type: "members", members: this.members() }, ws);
      return;
    }
    const me = ws.deserializeAttachment() as Member | null;
    if (!me) return;
    if (m.type === "load") { // switch media: new payload, playback reset
      if (!m.payload || typeof m.payload !== "object") return;
      const at = now();
      await s.put({ payload: m.payload, playing: false, position: 0, at });
      this.broadcast({ type: "load", payload: m.payload, at, by: me.id });
      return;
    }
    if (!["play", "pause", "seek"].includes(m.type) || typeof m.position !== "number") return;
    const at = now();
    const playing = m.type === "seek" ? await s.get("playing") : m.type === "play";
    await s.put({ playing, position: m.position, at });
    this.broadcast({ type: m.type, position: m.position, at, by: me.id });
  }

  private async left(ws: WebSocket) {
    this.broadcast({ type: "members", members: this.members(ws) }, ws);
    if (this.ctx.getWebSockets().every((w) => w === ws)) await this.ctx.storage.setAlarm(now() + HOUR);
  }
  async webSocketClose(ws: WebSocket, code: number) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code); } catch {}
    await this.left(ws);
  }
  async webSocketError(ws: WebSocket) { await this.left(ws); }
  async alarm() {
    if (!this.ctx.getWebSockets().length) await this.ctx.storage.deleteAll();
  }
}
