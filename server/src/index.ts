import { DurableObject } from "cloudflare:workers";

export interface Env {
  DB: D1Database;
  USER_HUB: DurableObjectNamespace<UserHub>;
  ROOM: DurableObjectNamespace<Room>;
  ASSET_LINKS?: string;
  CF_ANALYTICS_TOKEN?: string; // secret; enables the admin Ops page
  CF_ACCOUNT_ID?: string;
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
type User = { id: string; username: string; is_admin?: number; last_seen_at?: number | null; last_used_at?: number | null; token_hash?: string };
const now = () => Date.now();
const uuid = () => crypto.randomUUID();
const randomCode = (alphabet: string, n: number) =>
  Array.from(crypto.getRandomValues(new Uint8Array(n)), (x) => alphabet[x % alphabet.length]).join("");

async function body<T = any>(req: Request): Promise<T> {
  try { const b = await req.json(); if (b && typeof b === "object") return b as T; } catch {}
  return fail(400, "invalid JSON body");
}

async function newSession(env: Env, userId: string, req: Request) {
  const token = b64url(crypto.getRandomValues(new Uint8Array(32)));
  await env.DB.prepare("INSERT INTO sessions (token_hash, user_id, created_at, last_used_at, device, ua) VALUES (?,?,?,?,?,?)")
    .bind(await sha256(token), userId, now(), now(), deviceOf(req), req.headers.get("User-Agent")?.slice(0, 200) ?? null).run();
  return token;
}

async function userFromToken(env: Env, token: string | null | undefined): Promise<User | null> {
  if (!token) return null;
  return env.DB.prepare("SELECT u.id, u.username, u.is_admin, u.last_seen_at, s.last_used_at, s.token_hash FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND u.disabled = 0")
    .bind(await sha256(token)).first<User>();
}
const bearer = (req: Request) => req.headers.get("Authorization")?.match(/^Bearer (.+)$/)?.[1];
const SEEN_EVERY = 15 * 60_000;
const day = (t: number) => Math.floor(t / 86_400_000);
async function auth(c: Ctx): Promise<User> {
  const u = (await userFromToken(c.env, bearer(c.req))) ?? fail(401, "unauthorized");
  // At most one write per session per 15 min, plus one "active" event per day (drives DAU/WAU).
  const t = now(), last = u.last_seen_at ?? 0;
  if (t - (u.last_used_at ?? 0) > SEEN_EVERY) {
    await c.env.DB.batch([c.env.DB.prepare("UPDATE users SET last_seen_at = ? WHERE id = ?").bind(t, u.id),
      c.env.DB.prepare("UPDATE sessions SET last_used_at = ? WHERE token_hash = ?").bind(t, u.token_hash)]);
    if (day(last) !== day(t)) await logEvent(c.env, c.req, "active", u.id);
  }
  return u;
}
/** POST /v1/admin/users {username, password, admin?}: an admin creates an account, optionally another admin. */
async function adminCreateUser(c: Ctx) {
  const me = await admin(c);
  const { username, password, admin: makeAdmin } = await body(c.req);
  const u = await createUser(c, username, password, makeAdmin === true);
  await logEvent(c.env, c.req, makeAdmin === true ? "admin_create_admin" : "admin_create_user", me.id, { target: u.id, username: u.username });
  return json({ user: { ...u, is_admin: makeAdmin === true } }, 201);
}

async function admin(c: Ctx) {
  const u = await auth(c);
  return u.is_admin ? u : fail(403, "admin only");
}

// ---- events (admin log). Only meaningful actions, to stay inside D1 free write limits. ----
function deviceOf(req: Request | null) {
  const h = req?.headers.get("X-Reelm-Device"), ua = req?.headers.get("User-Agent") ?? "";
  if (h && /^[a-z]{2,16}$/.test(h)) return h;
  return !req ? null : /Android/i.test(ua) ? "android" : /Mozilla/.test(ua) ? "web" : "other";
}
const eventStmt = (env: Env, req: Request | null, kind: string, userId: string | null, detail?: unknown, profileId?: string | null,
  extra: { device?: string; version?: string } = {}) =>
  env.DB.prepare("INSERT INTO events (at, user_id, profile_id, device, kind, detail, ip_country, ua, app_version) VALUES (?,?,?,?,?,?,?,?,?)")
    .bind(now(), userId, profileId ?? null, extra.device ?? deviceOf(req), kind, detail === undefined ? null : JSON.stringify(detail),
      ((req as any)?.cf?.country as string | undefined) ?? null, req?.headers.get("User-Agent")?.slice(0, 200) ?? null, extra.version ?? null);
async function logEvent(...a: Parameters<typeof eventStmt>) {
  try { await eventStmt(...a).run(); } catch (e) { console.error("event log", e); } // logging never breaks a request
}

const profileOut = (r: any) => ({ id: r.id, name: r.name, avatar: r.avatar, kids: !!r.kids, createdAt: r.created_at });
async function ownProfile(c: Ctx, user: User, id: string) {
  return (await c.env.DB.prepare("SELECT * FROM profiles WHERE id = ? AND user_id = ?").bind(id, user.id).first())
    ?? fail(404, "profile not found");
}
const str = (v: unknown, max: number, what: string) =>
  typeof v === "string" && v.trim() && v.length <= max ? v.trim() : fail(400, `invalid ${what}`);

// ---- handlers -----------------------------------------------------------
/** Shared by sign-up and admin "create user": validates, inserts the user and their first profile. */
async function createUser(c: Ctx, username: unknown, password: unknown, isAdmin = false) {
  if (typeof username !== "string" || !/^[a-z0-9_]{3,32}$/.test(username)) fail(400, "username must be 3-32 chars of a-z, 0-9, _");
  if (typeof password !== "string" || password.length < 8) fail(400, "password must be at least 8 characters");
  const id = uuid(), t = now();
  try {
    await c.env.DB.prepare("INSERT INTO users (id, username, pass, created_at, is_admin) VALUES (?,?,?,?,?)")
      .bind(id, username, await hashPassword(password as string), t, isAdmin ? 1 : 0).run();
  } catch { fail(409, "username taken"); }
  await c.env.DB.prepare("INSERT INTO profiles (id, user_id, name, avatar, kids, created_at) VALUES (?,?,?,?,0,?)")
    .bind(uuid(), id, username, "grinning_face", t).run();
  return { id, username: username as string };
}

async function register(c: Ctx) {
  const { username, password } = await body(c.req);
  const { id } = await createUser(c, username, password);
  await logEvent(c.env, c.req, "register", id);
  return json({ token: await newSession(c.env, id, c.req), user: { id, username } }, 201);
}

async function login(c: Ctx) {
  const { username, password } = await body(c.req);
  const row = typeof username === "string" && typeof password === "string"
    ? await c.env.DB.prepare("SELECT id, username, pass, disabled FROM users WHERE username = ?").bind(username).first<any>() : null;
  if (!row || !(await checkPassword(password, row.pass))) {
    await logEvent(c.env, c.req, "login_failed", row?.id ?? null, { username: String(username).slice(0, 32) });
    fail(401, "wrong username or password");
  }
  if (row.disabled) fail(403, "account disabled");
  await logEvent(c.env, c.req, "login", row.id);
  return json({ token: await newSession(c.env, row.id, c.req), user: { id: row.id, username: row.username } });
}

async function logout(c: Ctx) {
  const t = bearer(c.req);
  const u = await userFromToken(c.env, t);
  if (t) await c.env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(await sha256(t)).run();
  if (u) await logEvent(c.env, c.req, "logout", u.id);
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
  await logEvent(c.env, c.req, "profile_create", u.id, { name }, row.id);
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
  await logEvent(c.env, c.req, "profile_update", u.id, next, cur.id);
  return json(profileOut({ ...cur, ...next }));
}

async function deleteProfile(c: Ctx) {
  const u = await auth(c);
  const cur: any = await ownProfile(c, u, c.params[0]);
  const r = await c.env.DB.prepare(
    "DELETE FROM profiles WHERE id = ? AND (SELECT COUNT(*) FROM profiles WHERE user_id = ?) > 1").bind(cur.id, u.id).run();
  if (!r.meta.changes) fail(409, "cannot delete the last profile");
  await c.env.DB.prepare("DELETE FROM favorites WHERE profile_id = ?").bind(cur.id).run();
  await c.env.DB.prepare("DELETE FROM history WHERE profile_id = ?").bind(cur.id).run(); // D1 FKs are not always enforced
  await logEvent(c.env, c.req, "profile_delete", u.id, { name: cur.name }, cur.id);
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
  if (batch.length) await c.env.DB.batch([...batch, eventStmt(c.env, c.req, "history_push", u.id, { count: batch.length }, p.id)]);
  await c.env.USER_HUB.getByName(u.id).fetch("https://hub/notify", {
    method: "POST", body: JSON.stringify({ type: "history", profileId: p.id }),
  });
  return json({ now: t });
}

async function getFavorites(c: Ctx) {
  const u = await auth(c);
  const p: any = await ownProfile(c, u, c.params[0]);
  const since = Number(c.url.searchParams.get("since") ?? 0) || 0;
  const t = now();
  const { results } = await c.env.DB.prepare("SELECT * FROM favorites WHERE profile_id = ? AND synced_at > ? ORDER BY synced_at")
    .bind(p.id, since).all<any>();
  return json({
    entries: results.map((r) => ({ key: r.key, payload: JSON.parse(r.payload), updatedAt: r.updated_at, deleted: !!r.deleted })),
    now: t,
  });
}

async function putFavorites(c: Ctx) {
  const u = await auth(c);
  const p: any = await ownProfile(c, u, c.params[0]);
  const { entries } = await body(c.req);
  if (!Array.isArray(entries) || entries.length > 500) fail(400, "entries must be an array of at most 500");
  const t = now();
  const stmt = c.env.DB.prepare(
    `INSERT INTO favorites (profile_id, key, payload, updated_at, synced_at, deleted) VALUES (?,?,?,?,?,?)
     ON CONFLICT(profile_id, key) DO UPDATE SET payload=excluded.payload,
       updated_at=excluded.updated_at, synced_at=excluded.synced_at, deleted=excluded.deleted
     WHERE excluded.updated_at > favorites.updated_at`);
  const batch = entries.map((e: any) => {
    if (!e || typeof e.key !== "string" || !e.key || typeof e.updatedAt !== "number") fail(400, "invalid entry");
    return stmt.bind(p.id, e.key, JSON.stringify(e.payload ?? null), Math.round(e.updatedAt), t, e.deleted ? 1 : 0);
  });
  if (batch.length) await c.env.DB.batch(batch);
  await c.env.USER_HUB.getByName(u.id).fetch("https://hub/notify", {
    method: "POST", body: JSON.stringify({ type: "favorites", profileId: p.id }),
  });
  return json({ now: t });
}

// pair flow
const PAIR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
async function pairStart(c: Ctx) {
  const code = randomCode(PAIR_ALPHABET, 8), expiresAt = now() + 10 * 60_000;
  await c.env.DB.prepare("DELETE FROM pairs WHERE expires_at < ?").bind(now()).run();
  await c.env.DB.prepare("INSERT INTO pairs (code, expires_at) VALUES (?,?)").bind(code, expiresAt).run();
  await logEvent(c.env, c.req, "pair_start", null, { code });
  return json({ code, expiresAt }, 201);
}
const pairCode = (c: Ctx) => c.params[0].toUpperCase();
async function pairClaim(c: Ctx) {
  const u = await auth(c);
  const r = await c.env.DB.prepare("UPDATE pairs SET user_id = ? WHERE code = ? AND expires_at > ? AND user_id IS NULL")
    .bind(u.id, pairCode(c), now()).run();
  if (!r.meta.changes) fail(404, "code not found or expired");
  await logEvent(c.env, c.req, "pair_claim", u.id, { code: pairCode(c) });
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
  return json({ status: "claimed", token: await newSession(c.env, row.user_id, c.req), user });
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
  await logEvent(c.env, c.req, "hub_connect", u.id);
  return c.env.USER_HUB.getByName(u.id).fetch(c.req);
}
async function createRoom(c: Ctx) {
  const { payload } = await body(c.req);
  if (payload === undefined) fail(400, "payload required");
  const code = randomCode(PAIR_ALPHABET.replace(/[01IO]/g, ""), 6);
  await c.env.ROOM.getByName(code).fetch("https://room/init", { method: "POST", body: JSON.stringify({ payload }) });
  await c.env.DB.prepare("INSERT OR REPLACE INTO rooms (code, title, created_at, updated_at) VALUES (?,?,?,?)")
    .bind(code, String(payload?.title ?? "").slice(0, 200) || null, now(), now()).run();
  await logEvent(c.env, c.req, "room_create", (await userFromToken(c.env, bearer(c.req)))?.id ?? null, { code, title: payload?.title });
  return json({ code }, 201);
}
async function joinRoom(c: Ctx) {
  if (c.req.headers.get("Upgrade") !== "websocket") fail(426, "expected websocket");
  await logEvent(c.env, c.req, "room_join", null, { code: c.params[0].toUpperCase() });
  return c.env.ROOM.getByName(c.params[0].toUpperCase()).fetch(c.req);
}

// ---- telemetry ----------------------------------------------------------
const TELEMETRY_KINDS = ["play", "play_error", "crash", "source_used", "heartbeat", "search_empty", "update_offered", "update_installed"];
const TELEMETRY_PER_MIN = 30;
async function telemetry(c: Ctx) {
  const u = await auth(c);
  const { events, profileId, appVersion, platform, helper } = await body(c.req);
  if (!Array.isArray(events) || events.length > 20) fail(400, "events must be an array of at most 20");
  const pid = profileId == null ? null : str(profileId, 64, "profileId");
  const extra = {
    device: ["phone", "tv", "web"].includes(platform) ? platform : undefined,
    version: appVersion == null ? undefined : str(appVersion, 32, "appVersion"),
  };
  const recent = await c.env.DB.prepare("SELECT COUNT(*) n FROM events WHERE user_id = ? AND at > ?").bind(u.id, now() - 60_000).first<number>("n");
  if ((recent ?? 0) + events.length > TELEMETRY_PER_MIN) fail(429, "too many events, slow down");
  const stmts = events.map((e: any) => {
    if (!e || !TELEMETRY_KINDS.includes(e.kind)) fail(400, "invalid event kind");
    if (e.detail != null && typeof e.detail !== "object") fail(400, "detail must be an object");
    if (JSON.stringify(e.detail ?? {}).length > 4000) fail(400, "detail too large (4000 chars)");
    const detail = typeof helper === "string" && helper.length <= 32 ? { ...e.detail, helper } : e.detail ?? {};
    return eventStmt(c.env, c.req, e.kind, u.id, detail, pid, extra);
  });
  if (stmts.length) await c.env.DB.batch(stmts);
  return none();
}

// ---- admin --------------------------------------------------------------
const DAY = 86_400_000;
const isoDay = (t: number) => new Date(t).toISOString().slice(0, 10);
const eventOut = (r: any) => ({ ...r, detail: r.detail ? JSON.parse(r.detail) : null });
const clampInt = (v: string | null, def: number, max: number) => Math.min(max, Math.max(0, Number(v) || def));

async function adminOverview(c: Ctx) {
  await admin(c);
  const db = c.env.DB, t = now(), since30 = t - 30 * DAY;
  const one = (sql: string, ...b: unknown[]) => db.prepare(sql).bind(...b);
  const [totals, signups, active, titles, sources, codecs] = await db.batch<any>([
    // ponytail: "online" is last_seen within 15 min (hub sockets live in DOs that can't be enumerated).
    one(`SELECT (SELECT COUNT(*) FROM users) users, (SELECT COUNT(*) FROM profiles) profiles,
      (SELECT COUNT(DISTINCT user_id || '|' || COALESCE(device,'') || '|' || COALESCE(ua,'')) FROM events WHERE user_id IS NOT NULL) devices,
      (SELECT COUNT(*) FROM history WHERE deleted = 0) history,
      (SELECT COUNT(*) FROM rooms WHERE members != '[]') rooms,
      (SELECT COUNT(DISTINCT user_id || '|' || COALESCE(device,'')) FROM events WHERE kind = 'heartbeat' AND at > ?) watching,
      (SELECT COUNT(*) FROM users WHERE last_seen_at > ?) online`, t - 2 * 60_000, t - SEEN_EVERY),
    one("SELECT created_at FROM users WHERE created_at > ?", since30),
    // distinct (day, user) pairs over 36 days; WAU is folded in JS below.
    one("SELECT DISTINCT at / 86400000 d, user_id FROM events WHERE user_id IS NOT NULL AND at > ?", since30 - 6 * DAY),
    one(`SELECT json_extract(detail,'$.title') title, COUNT(*) plays, COUNT(DISTINCT user_id) viewers FROM events
      WHERE kind = 'play' AND at > ? AND json_extract(detail,'$.title') IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 10`, since30),
    one(`SELECT COALESCE(json_extract(detail,'$.source'),'unknown') source, SUM(kind = 'source_used') ok, SUM(kind = 'play_error') errors
      FROM events WHERE kind IN ('source_used','play_error') AND at > ? GROUP BY 1 ORDER BY ok + errors DESC`, since30),
    one(`SELECT COALESCE(json_extract(detail,'$.codec'),'unknown') codec, COUNT(*) errors FROM events
      WHERE kind = 'play_error' AND at > ? GROUP BY 1 ORDER BY 2 DESC LIMIT 10`, since30),
  ]);
  const byDay = new Map<number, Set<string>>();
  for (const r of active.results) {
    if (!byDay.has(r.d)) byDay.set(r.d, new Set());
    byDay.get(r.d)!.add(r.user_id);
  }
  const today = day(t);
  const daily = Array.from({ length: 30 }, (_, i) => {
    const d = today - 29 + i, week = new Set<string>();
    for (let k = d - 6; k <= d; k++) byDay.get(k)?.forEach((u) => week.add(u));
    return { day: isoDay(d * DAY), signups: signups.results.filter((r) => day(r.created_at) === d).length,
      dau: byDay.get(d)?.size ?? 0, wau: week.size };
  });
  const tot = totals.results[0];
  return json({
    totals: { users: tot.users, profiles: tot.profiles, devices: tot.devices, history: tot.history, activeRooms: tot.rooms, watchingNow: tot.watching, onlineNow: tot.online },
    daily, topTitles: titles.results, sources: sources.results, codecs: codecs.results,
  });
}

async function adminUsers(c: Ctx) {
  await admin(c);
  const q = `%${(c.url.searchParams.get("q") ?? "").toLowerCase()}%`;
  const limit = clampInt(c.url.searchParams.get("limit"), 50, 200), offset = clampInt(c.url.searchParams.get("offset"), 0, 1e9);
  const [list, count] = await c.env.DB.batch<any>([
    c.env.DB.prepare(`SELECT u.id, u.username, u.is_admin, u.disabled, u.created_at, u.last_seen_at,
      (SELECT COUNT(*) FROM profiles p WHERE p.user_id = u.id) profiles,
      (SELECT COUNT(*) FROM sessions s WHERE s.user_id = u.id) sessions
      FROM users u WHERE u.username LIKE ? ORDER BY u.created_at DESC LIMIT ? OFFSET ?`).bind(q, limit, offset),
    c.env.DB.prepare("SELECT COUNT(*) n FROM users WHERE username LIKE ?").bind(q),
  ]);
  return json({ users: list.results, total: count.results[0].n });
}

async function adminUser(c: Ctx) {
  await admin(c);
  const id = c.params[0], db = c.env.DB;
  const [user, profiles, devices, history, events, sessions] = await db.batch<any>([
    db.prepare(`SELECT id, username, is_admin, disabled, created_at, last_seen_at,
      (SELECT COUNT(*) FROM sessions WHERE user_id = users.id) sessions FROM users WHERE id = ?`).bind(id),
    db.prepare("SELECT * FROM profiles WHERE user_id = ? ORDER BY created_at").bind(id),
    db.prepare(`SELECT device, ua, MAX(ip_country) country, MIN(at) first_at, MAX(at) last_at, COUNT(*) events
      FROM events WHERE user_id = ? GROUP BY device, ua ORDER BY last_at DESC LIMIT 20`).bind(id),
    db.prepare(`SELECT h.profile_id, h.key, h.payload, h.position, h.duration, h.updated_at FROM history h
      JOIN profiles p ON p.id = h.profile_id WHERE p.user_id = ? AND h.deleted = 0 ORDER BY h.updated_at DESC LIMIT 50`).bind(id),
    db.prepare("SELECT * FROM events WHERE user_id = ? ORDER BY id DESC LIMIT 50").bind(id),
    db.prepare("SELECT token_hash id, created_at, last_used_at, device, ua FROM sessions WHERE user_id = ? ORDER BY COALESCE(last_used_at, created_at) DESC").bind(id),
  ]);
  if (!user.results[0]) fail(404, "user not found");
  return json({
    user: user.results[0], profiles: profiles.results.map(profileOut), devices: devices.results,
    history: history.results.map((r) => ({ ...r, payload: JSON.parse(r.payload) })), events: events.results.map(eventOut),
    sessions: sessions.results,
  });
}

async function adminEvents(c: Ctx) {
  await admin(c);
  const sp = c.url.searchParams, where: string[] = [], b: unknown[] = [];
  if (sp.get("kind")) { where.push("e.kind = ?"); b.push(sp.get("kind")); }
  if (sp.get("user")) { where.push("(e.user_id = ? OR u.username = ?)"); b.push(sp.get("user"), sp.get("user")); }
  if (sp.get("before")) { where.push("e.id < ?"); b.push(Number(sp.get("before")) || 0); }
  const limit = clampInt(sp.get("limit"), 50, 200);
  const { results } = await c.env.DB.prepare(`SELECT e.*, u.username FROM events e LEFT JOIN users u ON u.id = e.user_id
    ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY e.id DESC LIMIT ?`).bind(...b, limit).all<any>();
  return json({ events: results.map(eventOut), next: results.length === limit ? results[results.length - 1].id : null });
}

async function adminHistory(c: Ctx) {
  await admin(c);
  const { results } = await c.env.DB.prepare("SELECT * FROM history WHERE profile_id = ? ORDER BY updated_at DESC LIMIT 200")
    .bind(c.url.searchParams.get("profile") ?? "").all<any>();
  return json({ entries: results.map((r) => ({ ...r, payload: JSON.parse(r.payload), deleted: !!r.deleted })) });
}

/** Target of an admin action. Destructive actions refuse your own account so an admin can't lock themselves out. */
async function targetUser(c: Ctx, me: User, allowSelf = false) {
  const id = c.params[0];
  if (id === me.id && !allowSelf) fail(400, "not on your own account");
  return (await c.env.DB.prepare("SELECT id, username FROM users WHERE id = ?").bind(id).first<User>()) ?? fail(404, "user not found");
}

async function adminAction(c: Ctx) {
  const me = await admin(c), action = c.params[1], db = c.env.DB;
  const u = await targetUser(c, me, action === "revoke" || action === "reset_password");
  let out: Record<string, unknown> = {};
  const revoke = db.prepare("DELETE FROM sessions WHERE user_id = ?").bind(u.id);
  switch (action) {
    case "disable": await db.batch([db.prepare("UPDATE users SET disabled = 1 WHERE id = ?").bind(u.id), revoke]); break;
    case "enable": await db.prepare("UPDATE users SET disabled = 0 WHERE id = ?").bind(u.id).run(); break;
    case "revoke": await revoke.run(); break;
    case "reset_password": {
      const password = randomCode("abcdefghjkmnpqrstuvwxyz23456789", 12);
      await db.batch([db.prepare("UPDATE users SET pass = ? WHERE id = ?").bind(await hashPassword(password), u.id), revoke]);
      out = { password };
      break;
    }
    case "make_admin": case "remove_admin":
      await db.prepare("UPDATE users SET is_admin = ? WHERE id = ?").bind(action === "make_admin" ? 1 : 0, u.id).run(); break;
    default: fail(404, "unknown action");
  }
  await logEvent(c.env, c.req, `admin_${action}`, me.id, { target: u.username });
  return json({ ok: true, ...out });
}

async function adminDeleteUser(c: Ctx) {
  const me = await admin(c), u = await targetUser(c, me), db = c.env.DB;
  await db.batch([ // D1 FKs are not always enforced
    db.prepare("DELETE FROM history WHERE profile_id IN (SELECT id FROM profiles WHERE user_id = ?)").bind(u.id),
    db.prepare("DELETE FROM favorites WHERE profile_id IN (SELECT id FROM profiles WHERE user_id = ?)").bind(u.id),
    db.prepare("DELETE FROM profiles WHERE user_id = ?").bind(u.id),
    db.prepare("DELETE FROM sessions WHERE user_id = ?").bind(u.id),
    db.prepare("DELETE FROM users WHERE id = ?").bind(u.id),
  ]);
  await logEvent(c.env, c.req, "admin_delete", me.id, { target: u.username });
  return none();
}

// ---- admin: one endpoint per dashboard group, aggregated in SQL on request ----
const q = (db: D1Database, sql: string, ...b: unknown[]) => db.prepare(sql).bind(...b);
const rows = async (db: D1Database, ...stmts: D1PreparedStatement[]) => (await db.batch<any>(stmts)).map((r) => r.results);
const HEARTBEAT_MS = 60_000; // clients send one heartbeat per minute of playback

async function adminLive(c: Ctx) {
  await admin(c);
  const db = c.env.DB, since = now() - 2 * 60_000;
  const [watching, rooms] = await rows(db,
    q(db, `SELECT e.at, e.user_id, u.username, e.device, e.app_version, e.detail, p.name profile, p.avatar
      FROM events e LEFT JOIN users u ON u.id = e.user_id LEFT JOIN profiles p ON p.id = e.profile_id
      WHERE e.id IN (SELECT MAX(id) FROM events WHERE kind = 'heartbeat' AND at > ? GROUP BY user_id, device, ua) ORDER BY e.at DESC`, since),
    q(db, "SELECT * FROM rooms WHERE members != '[]' OR updated_at > ? ORDER BY updated_at DESC", now() - 3_600_000));
  return json({ watching: watching.map(eventOut), rooms: rooms.map((r) => ({ ...r, members: JSON.parse(r.members) })) });
}

async function adminEngagement(c: Ctx) {
  await admin(c);
  const db = c.env.DB, since = now() - 30 * DAY, hb = "kind = 'heartbeat' AND at > ?";
  const [perUser, perProfile, heat, platforms, days] = await rows(db,
    q(db, `SELECT e.user_id, u.username, COUNT(*) minutes FROM events e LEFT JOIN users u ON u.id = e.user_id WHERE ${hb} GROUP BY 1 ORDER BY 3 DESC LIMIT 20`, since),
    q(db, `SELECT e.profile_id, p.name, p.avatar, p.kids, u.username, COUNT(*) minutes FROM events e JOIN profiles p ON p.id = e.profile_id
      LEFT JOIN users u ON u.id = p.user_id WHERE ${hb} GROUP BY 1 ORDER BY minutes DESC LIMIT 20`, since),
    q(db, `SELECT CAST(strftime('%w', at / 1000, 'unixepoch') AS INT) dow, CAST(strftime('%H', at / 1000, 'unixepoch') AS INT) hour, COUNT(*) minutes
      FROM events WHERE ${hb} GROUP BY 1, 2`, since),
    q(db, `SELECT COALESCE(device, 'other') platform, COUNT(*) minutes, COUNT(DISTINCT user_id) users FROM events WHERE ${hb} GROUP BY 1 ORDER BY 2 DESC`, since),
    q(db, `SELECT DISTINCT e.user_id, u.username, e.at / 86400000 d FROM events e LEFT JOIN users u ON u.id = e.user_id
      WHERE e.kind = 'heartbeat' AND e.at > ? ORDER BY 1, 3`, now() - 60 * DAY));
  const [completion] = await rows(db, q(db, `SELECT json_extract(payload, '$.title') title, COUNT(*) starts,
      SUM(duration > 0 AND position * 1.0 / duration >= 0.9) completed FROM history
      WHERE deleted = 0 AND json_extract(payload, '$.title') IS NOT NULL GROUP BY 1 HAVING starts >= 1 ORDER BY starts DESC LIMIT 20`));
  // Binge streaks: consecutive UTC days with any playback, per user.
  const streaks = new Map<string, { username: string; current: number; longest: number; prev: number }>();
  const today = day(now());
  for (const r of days) {
    const s = streaks.get(r.user_id) ?? { username: r.username, current: 0, longest: 0, prev: -2 };
    s.current = r.d === s.prev + 1 ? s.current + 1 : 1;
    s.longest = Math.max(s.longest, s.current);
    s.prev = r.d;
    streaks.set(r.user_id, s);
  }
  const binge = [...streaks.values()].map(({ username, current, longest, prev }) =>
    ({ username, current: prev >= today - 1 ? current : 0, longest })).sort((a, b) => b.longest - a.longest).slice(0, 10);
  return json({ perUser, perProfile, heatmap: heat, platforms, completion, binge, minutesPerHeartbeat: HEARTBEAT_MS / 60_000 });
}

async function adminContent(c: Ctx) {
  await admin(c);
  const db = c.env.DB, since = now() - 30 * DAY;
  const [watched, abandoned, kinds, searches] = await rows(db,
    q(db, `SELECT json_extract(detail, '$.title') title, COUNT(*) minutes, COUNT(DISTINCT user_id) viewers FROM events
      WHERE kind = 'heartbeat' AND at > ? AND json_extract(detail, '$.title') IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 15`, since),
    q(db, `SELECT json_extract(payload, '$.title') title, COUNT(*) abandoned FROM history
      WHERE deleted = 0 AND duration > 0 AND position * 1.0 / duration < 0.2 AND updated_at < ? AND json_extract(payload, '$.title') IS NOT NULL
      GROUP BY 1 ORDER BY 2 DESC LIMIT 15`, now() - DAY),
    q(db, `SELECT COALESCE(json_extract(detail, '$.kind'), 'unknown') kind, COUNT(*) minutes FROM events
      WHERE kind = 'heartbeat' AND at > ? GROUP BY 1 ORDER BY 2 DESC`, since),
    q(db, `SELECT lower(trim(json_extract(detail, '$.query'))) query, COUNT(*) times, MAX(at) last_at FROM events
      WHERE kind = 'search_empty' AND at > ? GROUP BY 1 ORDER BY 2 DESC LIMIT 25`, since));
  return json({ watched, abandoned, kinds, searches });
}

async function adminSources(c: Ctx) {
  await admin(c);
  const db = c.env.DB, t = now(), since = t - 14 * DAY, k = "kind IN ('source_used','play_error')";
  const src = "COALESCE(json_extract(detail, '$.source'), 'unknown')";
  const [bySource, daily, lastHour, codecs, recent] = await rows(db,
    q(db, `SELECT ${src} source, COALESCE(json_extract(detail, '$.host'), '') host, SUM(kind = 'source_used') ok, SUM(kind = 'play_error') errors,
      CAST(AVG(CASE WHEN kind = 'source_used' THEN json_extract(detail, '$.ttff') END) AS INT) ttff FROM events
      WHERE ${k} AND at > ? GROUP BY 1, 2 ORDER BY ok + errors DESC LIMIT 40`, since),
    q(db, `SELECT ${src} source, at / 86400000 d, SUM(kind = 'source_used') ok, SUM(kind = 'play_error') errors FROM events
      WHERE ${k} AND at > ? GROUP BY 1, 2 ORDER BY 2`, since),
    q(db, `SELECT ${src} source, SUM(kind = 'source_used') ok, SUM(kind = 'play_error') errors FROM events WHERE ${k} AND at > ? GROUP BY 1`, t - 3_600_000),
    q(db, `SELECT COALESCE(json_extract(detail, '$.codec'), 'unknown') codec, COUNT(*) errors,
      SUM(lower(COALESCE(json_extract(detail, '$.codec'), '') || ' ' || COALESCE(json_extract(detail, '$.error'), '')) GLOB '*[hx]265*'
        OR lower(COALESCE(json_extract(detail, '$.codec'), '')) GLOB '*hevc*' OR lower(COALESCE(json_extract(detail, '$.codec'), '')) GLOB '*hvc1*') hevc,
      SUM(lower(COALESCE(json_extract(detail, '$.codec'), '') || ' ' || COALESCE(json_extract(detail, '$.error'), '')) GLOB '*dolby*'
        OR lower(COALESCE(json_extract(detail, '$.codec'), '')) GLOB '*ac3*' OR lower(COALESCE(json_extract(detail, '$.codec'), '')) GLOB '*ac-3*') dolby
      FROM events WHERE kind = 'play_error' AND at > ? GROUP BY 1 ORDER BY 2 DESC`, since),
    q(db, `SELECT e.*, u.username FROM events e LEFT JOIN users u ON u.id = e.user_id WHERE e.kind = 'play_error' ORDER BY e.id DESC LIMIT 50`));
  const alerts = lastHour.filter((r) => r.ok + r.errors >= 5 && r.ok / (r.ok + r.errors) < 0.5);
  return json({
    bySource, daily: daily.map((r) => ({ ...r, day: isoDay(r.d * DAY) })), alerts, codecs, recent: recent.map(eventOut),
    hevcFailures: codecs.reduce((a, r) => a + r.hevc, 0), dolbyFailures: codecs.reduce((a, r) => a + r.dolby, 0),
  });
}

async function adminApp(c: Ctx) {
  await admin(c);
  const db = c.env.DB, since = now() - 30 * DAY;
  const [crashes, versions, updates, helpers] = await rows(db,
    q(db, `SELECT e.*, u.username FROM events e LEFT JOIN users u ON u.id = e.user_id WHERE e.kind = 'crash' ORDER BY e.id DESC LIMIT 30`),
    q(db, `SELECT device platform, app_version version, COUNT(DISTINCT user_id) users, MAX(at) last_at FROM events
      WHERE app_version IS NOT NULL AND at > ? GROUP BY 1, 2 ORDER BY 1, 3 DESC`, since),
    q(db, `SELECT COALESCE(json_extract(detail, '$.to'), app_version) version, SUM(kind = 'update_offered') offered, SUM(kind = 'update_installed') installed
      FROM events WHERE kind IN ('update_offered','update_installed') AND at > ? GROUP BY 1 ORDER BY 1 DESC`, since),
    q(db, `SELECT COALESCE(json_extract(detail, '$.helper'), 'none') helper, COUNT(DISTINCT user_id) users FROM events
      WHERE device = 'web' AND kind IN ('play','heartbeat') AND at > ? GROUP BY 1 ORDER BY 2 DESC`, since));
  return json({ crashes: crashes.map(eventOut), versions, updates, helpers });
}

async function adminSecurity(c: Ctx) {
  await admin(c);
  const db = c.env.DB, t = now();
  const [failed, newDevices, sessions, kids] = await rows(db,
    q(db, `SELECT e.at, e.ip_country, e.device, e.ua, json_extract(e.detail, '$.username') username, e.user_id IS NOT NULL known
      FROM events e WHERE e.kind = 'login_failed' ORDER BY e.id DESC LIMIT 50`),
    // ponytail: a "device" is (user, platform, user agent); a per-install id would need clients to send one.
    q(db, `SELECT e.user_id, u.username, e.device, e.ua, e.ip_country, MIN(e.at) first_at FROM events e LEFT JOIN users u ON u.id = e.user_id
      WHERE e.user_id IS NOT NULL GROUP BY e.user_id, e.device, e.ua HAVING first_at > ? ORDER BY first_at DESC LIMIT 30`, t - 7 * DAY),
    q(db, `SELECT u.id user_id, u.username, COUNT(s.token_hash) sessions, MAX(s.last_used_at) last_used_at FROM users u
      JOIN sessions s ON s.user_id = u.id GROUP BY 1 ORDER BY 3 DESC LIMIT 30`),
    q(db, `SELECT p.id, p.name, p.avatar, u.username, COUNT(e.id) minutes, MAX(e.at) last_at,
      (SELECT json_extract(detail, '$.title') FROM events WHERE profile_id = p.id AND kind = 'heartbeat' ORDER BY id DESC LIMIT 1) last_title
      FROM profiles p JOIN users u ON u.id = p.user_id LEFT JOIN events e ON e.profile_id = p.id AND e.kind = 'heartbeat' AND e.at > ?
      WHERE p.kids = 1 GROUP BY p.id ORDER BY minutes DESC LIMIT 30`, t - 30 * DAY));
  return json({ failed, newDevices, sessions, kids });
}

async function adminRevokeSession(c: Ctx) {
  const me = await admin(c);
  const r = await c.env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(c.params[0]).run();
  if (!r.meta.changes) fail(404, "session not found");
  await logEvent(c.env, c.req, "admin_revoke_session", me.id);
  return none();
}

// ---- ops: Cloudflare analytics + GitHub deploys, cached at the edge for 5 min ----
async function cached(key: string, ttl: number, load: () => Promise<unknown>) {
  const req = new Request(`https://reelm-cache.internal/${key}`), cache = (caches as any).default as Cache;
  const hit = await cache.match(req);
  if (hit) return hit.json();
  const v = await load();
  await cache.put(req, new Response(JSON.stringify(v), { headers: { "cache-control": `max-age=${ttl}` } }));
  return v;
}

async function cfAnalytics(env: Env) {
  const since = new Date(now() - 7 * DAY), date = since.toISOString().slice(0, 10);
  const query = `query($a: String!, $since: Time!, $date: Date!) { viewer { accounts(filter: {accountTag: $a}) {
    workers: workersInvocationsAdaptive(limit: 500, filter: {datetime_geq: $since, scriptName_in: ["reelm-api", "reelm-web"]}) {
      sum { requests errors subrequests } quantiles { cpuTimeP50 cpuTimeP99 } dimensions { scriptName status date }
    }
    d1: d1AnalyticsAdaptiveGroups(limit: 100, filter: {date_geq: $date}) {
      sum { readQueries writeQueries rowsRead rowsWritten } dimensions { date databaseId }
    }
    storage: d1StorageAdaptiveGroups(limit: 100, filter: {date_geq: $date}) { max { databaseSizeBytes } dimensions { date databaseId } }
  } } }`;
  const r = await fetch("https://api.cloudflare.com/client/v4/graphql", {
    method: "POST", headers: { Authorization: `Bearer ${env.CF_ANALYTICS_TOKEN}`, "content-type": "application/json" },
    body: JSON.stringify({ query, variables: { a: env.CF_ACCOUNT_ID, since: since.toISOString(), date } }),
  });
  const b: any = await r.json().catch(() => ({}));
  if (!r.ok || b.errors?.length) return { error: b.errors?.[0]?.message ?? `HTTP ${r.status}` };
  return b.data?.viewer?.accounts?.[0] ?? {};
}

async function github() {
  const gh = (p: string) => fetch(`https://api.github.com/repos/BeyonderSS/Reelm/${p}`, { headers: { "User-Agent": "reelm-api", Accept: "application/vnd.github+json" } })
    .then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const [runs, releases]: any[] = await Promise.all([gh("actions/runs?per_page=15"), gh("releases?per_page=8")]);
  return {
    runs: (runs?.workflow_runs ?? []).map((r: any) => ({ id: r.id, name: r.name, branch: r.head_branch, status: r.status,
      conclusion: r.conclusion, at: r.created_at, url: r.html_url, title: r.display_title })),
    releases: (releases ?? []).map((r: any) => ({ tag: r.tag_name, name: r.name, at: r.published_at, url: r.html_url,
      downloads: (r.assets ?? []).reduce((a: number, x: any) => a + (x.download_count ?? 0), 0) })),
  };
}

async function adminOps(c: Ctx) {
  await admin(c);
  const connected = !!(c.env.CF_ANALYTICS_TOKEN && c.env.CF_ACCOUNT_ID);
  const [cloudflare, deploys] = await Promise.all([
    connected ? cached("cf-analytics", 300, () => cfAnalytics(c.env)) : null,
    cached("github", 600, github),
  ]);
  return json({ connected, cloudflare, deploys, limits: { rowsReadPerDay: 5_000_000, rowsWrittenPerDay: 100_000, storageBytes: 5e9 } });
}

// ---- router -------------------------------------------------------------
const routes: [string, RegExp, (c: Ctx) => Response | Promise<Response>][] = [
  ["POST", /^\/v1\/auth\/register$/, register],
  ["POST", /^\/v1\/auth\/login$/, login],
  ["POST", /^\/v1\/auth\/logout$/, logout],
  ["GET", /^\/v1\/me$/, async (c) => { const u = await auth(c); return json({ user: { id: u.id, username: u.username, is_admin: !!u.is_admin } }); }],
  ["POST", /^\/v1\/telemetry$/, telemetry],
  ["GET", /^\/v1\/admin\/overview$/, adminOverview],
  ["GET", /^\/v1\/admin\/users$/, adminUsers],
  ["GET", /^\/v1\/admin\/users\/([^/]+)$/, adminUser],
  ["DELETE", /^\/v1\/admin\/users\/([^/]+)$/, adminDeleteUser],
  ["POST", /^\/v1\/admin\/users$/, adminCreateUser],
  ["POST", /^\/v1\/admin\/users\/([^/]+)\/([a-z_]+)$/, adminAction],
  ["GET", /^\/v1\/admin\/events$/, adminEvents],
  ["GET", /^\/v1\/admin\/history$/, adminHistory],
  ["GET", /^\/v1\/admin\/live$/, adminLive],
  ["GET", /^\/v1\/admin\/engagement$/, adminEngagement],
  ["GET", /^\/v1\/admin\/content$/, adminContent],
  ["GET", /^\/v1\/admin\/sources$/, adminSources],
  ["GET", /^\/v1\/admin\/app$/, adminApp],
  ["GET", /^\/v1\/admin\/security$/, adminSecurity],
  ["GET", /^\/v1\/admin\/ops$/, adminOps],
  ["DELETE", /^\/v1\/admin\/sessions\/([0-9a-f]{64})$/, adminRevokeSession],
  ["GET", /^\/v1\/profiles$/, listProfiles],
  ["POST", /^\/v1\/profiles$/, createProfile],
  ["PATCH", /^\/v1\/profiles\/([^/]+)$/, patchProfile],
  ["DELETE", /^\/v1\/profiles\/([^/]+)$/, deleteProfile],
  ["GET", /^\/v1\/profiles\/([^/]+)\/history$/, getHistory],
  ["PUT", /^\/v1\/profiles\/([^/]+)\/history$/, putHistory],
  ["GET", /^\/v1\/profiles\/([^/]+)\/favorites$/, getFavorites],
  ["PUT", /^\/v1\/profiles\/([^/]+)\/favorites$/, putFavorites],
  ["POST", /^\/v1\/pair$/, pairStart],
  ["POST", /^\/v1\/pair\/([^/]+)\/claim$/, pairClaim],
  ["GET", /^\/v1\/pair\/([^/]+)$/, pairPoll],
  ["GET", /^\/pair\/([^/]+)$/, pairPage],
  ["GET", /^\/v1\/proxy$/, proxy],
  ["GET", /^\/v1\/hub$/, hub],
  ["POST", /^\/v1\/rooms$/, createRoom],
  ["GET", /^\/v1\/rooms\/([^/]+)$/, joinRoom],
  ["GET", /^\/\.well-known\/assetlinks\.json$/, (c) =>
    new Response(c.env.ASSET_LINKS || "[]", { headers: { "content-type": "application/json" } })],
];

// ---- stream proxy (web player) ----------------------------------------------
// Anime HLS needs a Referer and no CORS headers; the browser can't send either. Hosts the parsers return only.
const PROXY_HOSTS = ["dramahot.top", "drama1.cfd", "zokoanime.video", "zokoanime.com", "zokoanime.net", "hianime.at"];
const hostOk = (h: string) => PROXY_HOSTS.some((p) => h === p || h.endsWith("." + p));
const isPrivate = (h: string) => h === "localhost" || h.endsWith(".local") || h.startsWith("[") || /^(0|10|127)\.|^192\.168\.|^172\.(1[6-9]|2\d|3[01])\.|^169\.254\./.test(h);

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
  const up = await fetch(u, { headers: {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
    ...(referer ? { Referer: referer } : {}), ...(req.headers.get("range") ? { Range: req.headers.get("range")! } : {}),
  } });
  const ct = up.headers.get("content-type") ?? "";
  if (up.ok && (u.pathname.endsWith(".m3u8") || /mpegurl/i.test(ct))) {
    const body = rewritePlaylist(await up.text(), u.href, `${url.origin}/v1/proxy`, referer, any);
    return new Response(body, { headers: { "content-type": "application/vnd.apple.mpegurl", "cache-control": "no-store" } });
  }
  const h = new Headers();
  for (const k of ["content-type", "content-length", "content-range", "accept-ranges"]) if (up.headers.has(k)) h.set(k, up.headers.get(k)!);
  return new Response(up.body, { status: up.status, headers: h });
}

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
  "access-control-allow-headers": "authorization,content-type,range",
  "access-control-expose-headers": "content-length,content-range",
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
      this.ctx.waitUntil(logEvent(this.env, null, "handoff", this.ctx.id.name ?? null, { from: from?.deviceName, to: m.to, title: m.playback?.payload?.title }));
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

  /** Keep the D1 `rooms` row in step so admin can list open rooms (DOs can't be enumerated). */
  private mirror(members: Member[], title?: unknown) {
    if (!this.ctx.id.name) return;
    const m = JSON.stringify(members.map(({ name, avatar }) => ({ name, avatar })));
    this.ctx.waitUntil(this.env.DB.prepare("UPDATE rooms SET members = ?, title = COALESCE(?, title), updated_at = ? WHERE code = ?")
      .bind(m, typeof title === "string" ? title.slice(0, 200) : null, now(), this.ctx.id.name).run().catch(console.error));
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
      this.mirror(this.members());
      return;
    }
    const me = ws.deserializeAttachment() as Member | null;
    if (!me) return;
    if (m.type === "load") { // switch media: new payload, playback reset
      if (!m.payload || typeof m.payload !== "object") return;
      const at = now();
      await s.put({ payload: m.payload, playing: false, position: 0, at });
      this.ctx.waitUntil(logEvent(this.env, null, "room_load", null, { code: this.ctx.id.name, title: (m.payload as any).title }));
      this.mirror(this.members(), (m.payload as any).title);
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
    this.mirror(this.members(ws));
    if (this.ctx.getWebSockets().every((w) => w === ws)) await this.ctx.storage.setAlarm(now() + HOUR);
  }
  async webSocketClose(ws: WebSocket, code: number) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code); } catch {}
    await this.left(ws);
  }
  async webSocketError(ws: WebSocket) { await this.left(ws); }
  async alarm() {
    if (this.ctx.getWebSockets().length) return;
    await this.ctx.storage.deleteAll();
    if (this.ctx.id.name) await this.env.DB.prepare("DELETE FROM rooms WHERE code = ?").bind(this.ctx.id.name).run();
  }
}
