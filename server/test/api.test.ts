import { exports } from "cloudflare:workers";
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
  const username = `user_${Date.now()}_${n++}`;
  const r = await call("/v1/auth/register", { method: "POST", json: { username, password: "password1" } });
  expect(r.status).toBe(201);
  const b = await j(r);
  return { username, token: b.token as string, id: b.user.id as string };
}

describe("auth", () => {
  it("registers, logs in, rejects bad input and bad passwords", async () => {
    const u = await signup();
    expect((await j(await call("/v1/me", { token: u.token }))).user.username).toBe(u.username);
    const l = await call("/v1/auth/login", { method: "POST", json: { username: u.username, password: "password1" } });
    expect(l.status).toBe(200);
    const t2 = (await j(l)).token;
    expect((await call("/v1/auth/login", { method: "POST", json: { username: u.username, password: "nope-nope" } })).status).toBe(401);
    expect((await call("/v1/auth/register", { method: "POST", json: { username: u.username, password: "password1" } })).status).toBe(409);
    expect((await call("/v1/auth/register", { method: "POST", json: { username: "A!", password: "password1" } })).status).toBe(400);
    expect((await call("/v1/auth/register", { method: "POST", json: { username: "okname", password: "short" } })).status).toBe(400);
    expect((await call("/v1/auth/logout", { method: "POST", token: t2 })).status).toBe(204);
    expect((await call("/v1/me", { token: t2 })).status).toBe(401);
    expect((await call("/v1/me")).status).toBe(401);
  });
});

describe("profiles", () => {
  it("starts with one, CRUD, 5 limit, keeps the last, isolates owners", async () => {
    const u = await signup(), other = await signup();
    const list = async () => (await j(await call("/v1/profiles", { token: u.token }))).profiles as any[];
    let ps = await list();
    expect(ps).toHaveLength(1);
    expect(ps[0].name).toBe(u.username);
    for (let i = 0; i < 4; i++)
      expect((await call("/v1/profiles", { method: "POST", token: u.token, json: { name: `p${i}`, avatar: "fox_face" } })).status).toBe(201);
    expect((await call("/v1/profiles", { method: "POST", token: u.token, json: { name: "six", avatar: "x" } })).status).toBe(409);
    ps = await list();
    expect(ps).toHaveLength(5);
    const p = await j(await call(`/v1/profiles/${ps[1].id}`, { method: "PATCH", token: u.token, json: { name: "Kid", kids: true } }));
    expect(p).toMatchObject({ name: "Kid", kids: true, avatar: "fox_face" });
    // mature results are opt-in, and a kids profile can't opt in
    expect(await j(await call(`/v1/profiles/${ps[0].id}`, { method: "PATCH", token: u.token, json: { mature: true } }))).toMatchObject({ mature: true });
    expect(await j(await call(`/v1/profiles/${ps[1].id}`, { method: "PATCH", token: u.token, json: { mature: true } }))).toMatchObject({ mature: false });
    expect((await call(`/v1/profiles/${ps[1].id}`, { method: "PATCH", token: other.token, json: { name: "x" } })).status).toBe(404);
    expect((await call(`/v1/profiles/${ps[1].id}`, { method: "DELETE", token: other.token })).status).toBe(404);
    for (const x of ps.slice(1)) expect((await call(`/v1/profiles/${x.id}`, { method: "DELETE", token: u.token })).status).toBe(204);
    expect((await call(`/v1/profiles/${ps[0].id}`, { method: "DELETE", token: u.token })).status).toBe(409);
  });
});

describe("history", () => {
  it("is last-write-wins, supports since and tombstones, and is owner-only", async () => {
    const u = await signup(), other = await signup();
    const pid = (await j(await call("/v1/profiles", { token: u.token }))).profiles[0].id;
    const url = `/v1/profiles/${pid}/history`;
    const e = (updatedAt: number, position: number, extra = {}) =>
      ({ key: "anime:x:1", payload: { title: "X" }, position, duration: 1000, updatedAt, deleted: false, ...extra });
    const put = (entries: unknown[]) => call(url, { method: "PUT", token: u.token, json: { entries } });

    const t1 = (await j(await put([e(100, 10)]))).now;
    await put([e(50, 99)]); // older, ignored
    let g = await j(await call(`${url}?since=0`, { token: u.token }));
    expect(g.entries).toHaveLength(1);
    expect(g.entries[0]).toMatchObject({ key: "anime:x:1", position: 10, updatedAt: 100, payload: { title: "X" } });

    await new Promise((r) => setTimeout(r, 5));
    await put([e(200, 20), { ...e(1, 0), key: "movie:1" }]);
    g = await j(await call(`${url}?since=${t1}`, { token: u.token }));
    expect(g.entries.map((x: any) => [x.key, x.position]).sort()).toEqual([["anime:x:1", 20], ["movie:1", 0]]);
    expect((await call(`${url}?since=${g.now}`, { token: u.token }).then(j)).entries).toHaveLength(0);

    await put([e(300, 20, { deleted: true })]);
    g = await j(await call(`${url}?since=${t1}`, { token: u.token }));
    expect(g.entries.find((x: any) => x.key === "anime:x:1").deleted).toBe(true);

    expect((await call(url, { token: other.token })).status).toBe(404);
    expect((await call(url, { method: "PUT", token: other.token, json: { entries: [] } })).status).toBe(404);
  });

  it("notifies the user's hub websocket after a PUT", async () => {
    const u = await signup();
    const pid = (await j(await call("/v1/profiles", { token: u.token }))).profiles[0].id;
    const ws = (await call(`/v1/hub?token=${u.token}`, { headers: { Upgrade: "websocket" } })).webSocket!;
    ws.accept();
    const got = new Promise<any>((res) => ws.addEventListener("message", (m: MessageEvent) => { const d = JSON.parse(m.data as string); if (d.type === "history") res(d); }));
    ws.send(JSON.stringify({ type: "hello", deviceId: "d1", deviceName: "Pixel", kind: "phone" }));
    await call(`/v1/profiles/${pid}/history`, { method: "PUT", token: u.token, json: { entries: [] } });
    expect(await got).toEqual({ type: "history", profileId: pid });
  });
});

describe("favorites", () => {
  it("is last-write-wins with tombstones, since, and owner-only", async () => {
    const u = await signup(), other = await signup();
    const pid = (await j(await call("/v1/profiles", { token: u.token }))).profiles[0].id;
    const url = `/v1/profiles/${pid}/favorites`;
    const e = (updatedAt: number, extra = {}) => ({ key: "anime:x", payload: { title: "X" }, updatedAt, deleted: false, ...extra });
    const put = (entries: unknown[]) => call(url, { method: "PUT", token: u.token, json: { entries } });
    const t1 = (await j(await put([e(100)]))).now;
    await put([e(50, { deleted: true })]); // older, ignored
    let g = await j(await call(`${url}?since=0`, { token: u.token }));
    expect(g.entries).toHaveLength(1);
    expect(g.entries[0]).toMatchObject({ key: "anime:x", deleted: false, payload: { title: "X" } });
    await new Promise((r) => setTimeout(r, 5));
    await put([e(300, { deleted: true })]);
    g = await j(await call(`${url}?since=${t1}`, { token: u.token }));
    expect(g.entries[0].deleted).toBe(true);
    expect((await call(`${url}?since=${g.now}`, { token: u.token }).then(j)).entries).toHaveLength(0);
    expect((await call(url, { token: other.token })).status).toBe(404);
  });
});

describe("pair", () => {
  it("TV starts, phone claims, TV gets a one-shot token", async () => {
    const u = await signup();
    const { code, expiresAt } = await j(await call("/v1/pair", { method: "POST" }));
    expect(code).toMatch(/^[A-Z2-9]{8}$/);
    expect(expiresAt).toBeGreaterThan(Date.now());
    expect(await j(await call(`/v1/pair/${code}`))).toEqual({ status: "pending" });
    expect((await call(`/v1/pair/${code}/claim`, { method: "POST" })).status).toBe(401);
    expect((await call(`/v1/pair/${code}/claim`, { method: "POST", token: u.token })).status).toBe(204);
    const done = await j(await call(`/v1/pair/${code}`));
    expect(done.status).toBe("claimed");
    expect(done.user.id).toBe(u.id);
    expect((await call("/v1/me", { token: done.token })).status).toBe(200);
    expect((await call(`/v1/pair/${code}`)).status).toBe(404);
  });

  it("serves the fallback page and assetlinks", async () => {
    const html = await (await call("/pair/ABCD2345")).text();
    expect(html).toContain("reelm://pair/ABCD2345");
    expect(html).toContain("#15171F");
    expect((await call("/pair/<script>")).status).toBe(404);
    expect(await j(await call("/.well-known/assetlinks.json"))).toEqual([]);
  });

  it("sends CORS headers", async () => {
    const r = await call("/v1/me", { method: "OPTIONS" });
    expect(r.status).toBe(204);
    expect(r.headers.get("access-control-allow-origin")).toBe("*");
  });
});

describe("rooms", () => {
  it("joins and broadcasts seek to other members", async () => {
    const { code } = await j(await call("/v1/rooms", { method: "POST", json: { payload: { title: "Movie" } } }));
    expect(code).toMatch(/^[A-Z2-9]{6}$/);
    const open = async (name: string) => {
      const ws = (await call(`/v1/rooms/${code}`, { headers: { Upgrade: "websocket" } })).webSocket!;
      ws.accept();
      const msgs: any[] = [];
      ws.addEventListener("message", (m: MessageEvent) => msgs.push(JSON.parse(m.data as string)));
      ws.send(JSON.stringify({ type: "join", name, avatar: "fox_face" }));
      return { ws, msgs };
    };
    const until = async (f: () => any) => { for (let i = 0; i < 100 && !f(); i++) await new Promise((r) => setTimeout(r, 20)); return f(); };

    const a = await open("alice");
    const st = await until(() => a.msgs.find((m) => m.type === "state"));
    expect(st).toMatchObject({ payload: { title: "Movie" }, playing: false, position: 0 });
    expect(st.members).toHaveLength(1);

    const b = await open("bob");
    expect((await until(() => b.msgs.find((m) => m.type === "state"))).members).toHaveLength(2);
    a.ws.send(JSON.stringify({ type: "seek", position: 4242 }));
    const seek = await until(() => b.msgs.find((m) => m.type === "seek"));
    expect(seek).toMatchObject({ type: "seek", position: 4242 });
    expect(seek.at).toBeGreaterThan(0);
    expect(seek.by).toBe(st.members[0].id);

    // load: stored as the room payload, broadcast with `by`, and visible to late joiners
    const next = { title: "Movie 2", episode: 2 };
    a.ws.send(JSON.stringify({ type: "load", payload: next, position: 0 }));
    const load = await until(() => b.msgs.find((m) => m.type === "load"));
    expect(load).toMatchObject({ payload: next, by: st.members[0].id });
    expect(load.at).toBeGreaterThan(0);
    const c = await open("carol");
    expect(await until(() => c.msgs.find((m) => m.type === "state"))).toMatchObject({ payload: next, playing: false, position: 0 });

    expect((await call("/v1/rooms/NOPE23", { headers: { Upgrade: "websocket" } })).status).toBe(404);
  });
});

describe("proxy", () => {
  it("rewrites playlist urls through the proxy and refuses unlisted hosts", async () => {
    const { rewritePlaylist } = await import("../src/index");
    const out = rewritePlaylist('#EXT-X-KEY:METHOD=AES-128,URI="k.key"\n#EXTINF:4,\nseg/1.ts', "https://x.zokoanime.video/a/b.m3u8", "https://p/v1/proxy", "https://zokoanime.video/", false);
    expect(out).toContain("https://p/v1/proxy?u=https%3A%2F%2Fx.zokoanime.video%2Fa%2Fk.key");
    expect(out).toContain("u=https%3A%2F%2Fx.zokoanime.video%2Fa%2Fseg%2F1.ts&r=");
    expect((await call("/v1/proxy?u=" + encodeURIComponent("https://example.com/a.m3u8"))).status).toBe(403);
  });
});
