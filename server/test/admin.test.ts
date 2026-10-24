import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";

const call = (path: string, init: RequestInit & { token?: string; json?: unknown } = {}) =>
  exports.default.fetch(new Request(`https://api.test${path}`, {
    ...init,
    body: init.json !== undefined ? JSON.stringify(init.json) : init.body,
    headers: { ...(init.token ? { Authorization: `Bearer ${init.token}` } : {}), ...init.headers },
  }));
const j = async (r: Response) => (await r.json()) as any;
let n = 0;
async function signup() {
  const username = `adm_${Date.now()}_${n++}`;
  const b = await j(await call("/v1/auth/register", { method: "POST", json: { username, password: "password1" } }));
  return { username, token: b.token as string, id: b.user.id as string };
}
const GROUPS = ["overview", "users", "events", "history?profile=x", "live", "engagement", "content", "sources", "app", "security"];

describe("admin", () => {
  it("is 403 for non-admins and 401 without a token", async () => {
    const u = await signup(), other = await signup();
    expect((await j(await call("/v1/me", { token: u.token }))).user.is_admin).toBe(false);
    for (const g of [...GROUPS, `users/${other.id}`]) expect((await call(`/v1/admin/${g}`, { token: u.token })).status).toBe(403);
    expect((await call(`/v1/admin/users/${other.id}/disable`, { method: "POST", token: u.token })).status).toBe(403);
    expect((await call(`/v1/admin/users/${other.id}`, { method: "DELETE", token: u.token })).status).toBe(403);
    expect((await call("/v1/admin/overview")).status).toBe(401);
  });

  it("overview smoke test, telemetry validation and user actions", async () => {
    const u = await signup(), boss = await signup();
    const tel = (events: unknown) => call("/v1/telemetry", { method: "POST", token: u.token, json: { events, platform: "web", appVersion: "web-1" } });
    expect((await tel([{ kind: "play", detail: { title: "Frieren", source: "hianime" } },
      { kind: "heartbeat", detail: { title: "Frieren", kind: "anime" } },
      { kind: "play_error", detail: { source: "hianime", codec: "hevc", error: "decode" } }])).status).toBe(204);
    expect((await tel([{ kind: "nope" }])).status).toBe(400);
    expect((await tel(Array.from({ length: 20 }, () => ({ kind: "play" })))).status).toBe(204);
    expect((await tel(Array.from({ length: 20 }, () => ({ kind: "play" })))).status).toBe(429); // per-minute cap

    await (env as any).DB.prepare("UPDATE users SET is_admin = 1 WHERE id = ?").bind(boss.id).run();
    expect((await j(await call("/v1/me", { token: boss.token }))).user.is_admin).toBe(true);
    const o = await j(await call("/v1/admin/overview", { token: boss.token }));
    expect(o.totals.users).toBeGreaterThanOrEqual(2);
    expect(o.daily).toHaveLength(30);
    expect(o.daily[29].signups).toBeGreaterThanOrEqual(2);
    expect(o.topTitles[0]).toMatchObject({ title: "Frieren" });
    for (const g of GROUPS) expect((await call(`/v1/admin/${g}`, { token: boss.token })).status).toBe(200);
    expect((await j(await call("/v1/admin/live", { token: boss.token }))).watching[0].detail.title).toBe("Frieren");

    const ev = await j(await call(`/v1/admin/events?user=${u.username}&kind=register`, { token: boss.token }));
    expect(ev.events).toHaveLength(1);
    const detail = await j(await call(`/v1/admin/users/${u.id}`, { token: boss.token }));
    expect(detail.sessions).toHaveLength(1);
    expect((await call(`/v1/admin/sessions/${detail.sessions[0].id}`, { method: "DELETE", token: boss.token })).status).toBe(204);
    expect((await call("/v1/me", { token: u.token })).status).toBe(401);

    const pw = (await j(await call(`/v1/admin/users/${u.id}/reset_password`, { method: "POST", token: boss.token }))).password;
    expect((await call("/v1/auth/login", { method: "POST", json: { username: u.username, password: pw } })).status).toBe(200);
    expect((await call(`/v1/admin/users/${u.id}/disable`, { method: "POST", token: boss.token })).status).toBe(200);
    expect((await call("/v1/auth/login", { method: "POST", json: { username: u.username, password: pw } })).status).toBe(403);
    expect((await call(`/v1/admin/users/${boss.id}/disable`, { method: "POST", token: boss.token })).status).toBe(400);
    expect((await call(`/v1/admin/users/${u.id}`, { method: "DELETE", token: boss.token })).status).toBe(204);
    expect((await call(`/v1/admin/users/${u.id}`, { token: boss.token })).status).toBe(404);
  });
});

it("admin can create users and admins; non-admins can't", async () => {
  const { SELF, env } = await import("cloudflare:test");
  const reg = async (u: string) => (await (await SELF.fetch("https://x/v1/auth/register", { method: "POST", body: JSON.stringify({ username: u, password: "password123" }) })).json() as any);
  const boss = await reg("boss_create"), pleb = await reg("pleb_create");
  await (env as any).DB.prepare("UPDATE users SET is_admin = 1 WHERE id = ?").bind(boss.user.id).run();
  const create = (tok: string, b: object) => SELF.fetch("https://x/v1/admin/users", { method: "POST", headers: { Authorization: `Bearer ${tok}` }, body: JSON.stringify(b) });
  expect((await create(pleb.token, { username: "nope_user", password: "password123" })).status).toBe(403);
  const r = await create(boss.token, { username: "new_admin", password: "password123", admin: true });
  expect(r.status).toBe(201);
  expect(((await r.json()) as any).user.is_admin).toBe(true);
  expect((await create(boss.token, { username: "new_admin", password: "password123" })).status).toBe(409);
  const login = await SELF.fetch("https://x/v1/auth/login", { method: "POST", body: JSON.stringify({ username: "new_admin", password: "password123" }) });
  const me = await (await SELF.fetch("https://x/v1/me", { headers: { Authorization: `Bearer ${((await login.json()) as any).token}` } })).json() as any;
  expect(me.user.is_admin).toBeTruthy();
});
