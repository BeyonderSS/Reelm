import { env, exports } from "cloudflare:workers";
import { createExecutionContext } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import worker, { toMs } from "../src/index";

const call = (path: string, init: RequestInit = {}, e?: object) => {
  const req = new Request(`https://api.test${path}`, init);
  return e ? worker.fetch(req, { ...env, ...e } as any, createExecutionContext()) : exports.default.fetch(req);
};
const until = async (f: () => any) => { for (let i = 0; i < 100 && !f(); i++) await new Promise((r) => setTimeout(r, 20)); return f(); };

async function room() {
  const { code } = (await (await call("/v1/rooms", { method: "POST", body: JSON.stringify({ payload: { title: "Film" } }) })).json()) as any;
  const open = async (name: string, extra: object = {}) => {
    const ws = (await call(`/v1/rooms/${code}`, { headers: { Upgrade: "websocket" } })).webSocket!;
    ws.accept();
    const msgs: any[] = [];
    ws.addEventListener("message", (m: MessageEvent) => msgs.push(JSON.parse(m.data as string)));
    ws.send(JSON.stringify({ type: "join", name, avatar: "fox_face", ...extra }));
    const st = await until(() => msgs.find((m) => m.type === "state"));
    const sendJ = (m: object) => ws.send(JSON.stringify(m));
    return { ws, msgs, st, id: st.you as string, send: sendJ, wait: (t: string, n = 1) => until(() => msgs.filter((m) => m.type === t).length >= n && msgs.filter((m) => m.type === t)[n - 1]) };
  };
  return { code: code as string, open };
}

describe("watch party room", () => {
  it("rounds positions to ms and rejects non-finite ones", async () => {
    expect([toMs(1234.6), toMs("99.4"), toMs(NaN), toMs("x"), toMs(null), toMs(Infinity)]).toEqual([1235, 99, null, null, null, null]);
    const { open } = await room();
    const a = await open("a", { device: "web", cam: true });
    expect(a.st).toMatchObject({ mode: "friends", members: [{ device: "web", cam: true, mic: false }] });
    a.send({ type: "seek", position: Infinity });
    a.send({ type: "pause", position: 61000.7 });
    expect(await a.wait("pause")).toMatchObject({ position: 61001 });
    expect(a.msgs.some((m) => m.type === "seek")).toBe(false);
  });

  it("keeps the first source, resets it on load, stores mode and media", async () => {
    const { open } = await room();
    const a = await open("a"), b = await open("b");
    a.send({ type: "source", source: { provider: "vega", quality: "1080p" } });
    b.send({ type: "source", source: { provider: "other", quality: "720p" } });
    a.send({ type: "source", source: { big: "x".repeat(2000) } });
    expect(await b.wait("source")).toMatchObject({ source: { provider: "vega" }, by: a.id });
    a.send({ type: "mode", mode: "date" });
    b.send({ type: "media", cam: true, mic: true });
    await a.wait("media");
    const c = await open("c");
    expect(c.st).toMatchObject({ source: { provider: "vega" }, mode: "date" });
    expect(c.st.members.find((m: any) => m.id === b.id)).toMatchObject({ cam: true, mic: true });
    expect(b.msgs.filter((m) => m.type === "source")).toHaveLength(1);

    a.send({ type: "load", payload: { title: "Film 2" } });
    await c.wait("load");
    b.send({ type: "source", source: { provider: "other" } });
    expect(await c.wait("source")).toMatchObject({ source: { provider: "other" } });
  });

  it("lets four members publish video, the fifth gets rtc_full and stays voice-only", async () => {
    const { open } = await room();
    const ms = [];
    for (let i = 0; i < 5; i++) ms.push(await open(`m${i}`));
    const tracks = (i: number) => [{ trackName: `a${i}`, kind: "audio" }, { trackName: `v${i}`, kind: "video" }];
    for (let i = 0; i < 4; i++) { ms[i].send({ type: "rtc", sessionId: `s${i}`, tracks: tracks(i) }); await ms[4].wait("rtc", i + 1); }
    ms[4].send({ type: "rtc", sessionId: "s4", tracks: tracks(4) });
    await ms[4].wait("rtc_full");
    expect(await ms[0].wait("rtc", 5)).toEqual({ type: "rtc", from: ms[4].id, sessionId: "s4", tracks: [{ trackName: "a4", kind: "audio" }] });
    expect(ms[0].msgs.some((m) => m.type === "rtc_full")).toBe(false);
    // one stops: the fifth can now publish video
    ms[0].send({ type: "rtc", sessionId: null });
    expect(await ms[4].wait("rtc", 6)).toMatchObject({ from: ms[0].id, sessionId: null, tracks: [] });
    ms[4].send({ type: "rtc", sessionId: "s4", tracks: tracks(4) });
    expect((await ms[1].wait("rtc", 7)).tracks).toHaveLength(2);
    const late = await open("late");
    expect(late.st.members.find((m: any) => m.id === ms[4].id).rtc).toEqual({ sessionId: "s4", tracks: tracks(4) });
  });

  it("broadcasts reactions and a sync_heart for two members' hearts within 1.5 s", async () => {
    const { open } = await room();
    const a = await open("a"), b = await open("b");
    a.send({ type: "react", kind: "heart", at: 3000 });
    a.send({ type: "react", kind: "heart", at: 3100 }); // same member: no sync
    a.send({ type: "react", kind: "wink", at: 3100 }); // unknown kind: dropped
    expect(await b.wait("react", 2)).toMatchObject({ kind: "heart", at: 3100, by: a.id });
    expect(b.msgs.some((m) => m.type === "sync_heart")).toBe(false);
    b.send({ type: "react", kind: "heart", at: 3200.4 });
    expect(await a.wait("sync_heart")).toEqual({ type: "sync_heart", at: 3200 });
    expect(a.msgs.filter((m) => m.type === "react")).toHaveLength(3);
  });

  it("lights down starts playing at countdownAt from the same position", async () => {
    const { open } = await room();
    const a = await open("a"), b = await open("b");
    a.send({ type: "pause", position: 5000 });
    await b.wait("pause");
    const t = Date.now();
    b.send({ type: "lights" });
    const l = await a.wait("lights");
    expect(l.position).toBe(5000);
    expect(l.countdownAt).toBeGreaterThanOrEqual(t + 3500);
    const c = await open("c");
    expect(c.st).toMatchObject({ playing: true, position: 5000, at: l.countdownAt, countdownAt: l.countdownAt });
  });
});

describe("realtime proxy", () => {
  afterEach(() => vi.restoreAllMocks());
  const secrets = { REALTIME_APP_ID: "app1", REALTIME_APP_TOKEN: "tok", TURN_KEY_ID: "k1", TURN_KEY_TOKEN: "ttok" };

  it("needs a room member, then 503s without secrets and falls back to STUN without TURN", async () => {
    const { code, open } = await room();
    const a = await open("a");
    const hdr = { "X-Room-Member": `${code}:${a.id}` };
    expect((await call("/v1/rtc/ice", {}, secrets)).status).toBe(403);
    expect((await call("/v1/rtc/ice", { headers: { "X-Room-Member": `${code}:nobody` } }, secrets)).status).toBe(403);
    expect((await call("/v1/rtc/ice", { headers: { "X-Room-Member": `NOPE23:${a.id}` } }, secrets)).status).toBe(403);
    const r = await call("/v1/rtc/sessions", { method: "POST", headers: hdr }, {});
    expect(r.status).toBe(503);
    expect(await r.json()).toEqual({ error: "video not configured" });
    expect(r.headers.get("access-control-allow-origin")).toBe("*");
    const spy = vi.spyOn(globalThis, "fetch");
    const ice = await call("/v1/rtc/ice", { headers: { "x-room-member": `${code.toLowerCase()}:${a.id}` } }, { REALTIME_APP_ID: "app1", REALTIME_APP_TOKEN: "tok" });
    expect(await ice.json()).toEqual({ iceServers: [{ urls: ["stun:stun.cloudflare.com:3478"] }] });
    expect(spy).not.toHaveBeenCalled();
    const pre = await call("/v1/rtc/ice", { method: "OPTIONS" });
    expect(pre.headers.get("access-control-allow-headers")).toContain("x-room-member");
  });

  it("passes requests through to Realtime with the app token", async () => {
    const { code, open } = await room();
    const a = await open("a");
    const hdr = { "X-Room-Member": `${code}:${a.id}` };
    const seen: { url: string; method: string; auth: string | null; body: string }[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (i: any, init?: any) => {
      const r = new Request(i, init);
      seen.push({ url: r.url, method: r.method, auth: r.headers.get("authorization"), body: await r.text() });
      if (r.url.includes("/turn/")) return Response.json({ iceServers: [{ urls: ["turn:turn.cloudflare.com:3478"], username: "u", credential: "c" }] }, { status: 201 });
      return Response.json(r.url.endsWith("/sessions/new") ? { sessionId: "abc" } : { requiresImmediateRenegotiation: false, tracks: [] }, { status: r.url.endsWith("/new") ? 201 : 200 });
    });
    expect((await (await call("/v1/rtc/ice", { headers: hdr }, secrets)).json() as any).iceServers[0].username).toBe("u");
    const s = await call("/v1/rtc/sessions", { method: "POST", headers: hdr }, secrets);
    expect([s.status, await s.json()]).toEqual([201, { sessionId: "abc" }]);
    const offer = JSON.stringify({ sessionDescription: { type: "offer", sdp: "v=0" }, tracks: [{ location: "local", mid: "0", trackName: "a" }] });
    expect((await call("/v1/rtc/sessions/abc/tracks", { method: "POST", headers: hdr, body: offer }, secrets)).status).toBe(201);
    await call("/v1/rtc/sessions/abc/renegotiate", { method: "PUT", headers: hdr, body: "{}" }, secrets);
    await call("/v1/rtc/sessions/abc/tracks/close", { method: "PUT", headers: hdr, body: '{"tracks":[{"mid":"0"}],"force":true}' }, secrets);
    expect(seen.map((x) => `${x.method} ${x.url}`)).toEqual([
      "POST https://rtc.live.cloudflare.com/v1/turn/keys/k1/credentials/generate-ice-servers",
      "POST https://rtc.live.cloudflare.com/v1/apps/app1/sessions/new",
      "POST https://rtc.live.cloudflare.com/v1/apps/app1/sessions/abc/tracks/new",
      "PUT https://rtc.live.cloudflare.com/v1/apps/app1/sessions/abc/renegotiate",
      "PUT https://rtc.live.cloudflare.com/v1/apps/app1/sessions/abc/tracks/close",
    ]);
    expect(seen[0]).toMatchObject({ auth: "Bearer ttok", body: '{"ttl":21600}' });
    expect(seen.slice(1).every((x) => x.auth === "Bearer tok")).toBe(true);
    expect(seen[2].body).toBe(offer);
  });
});
