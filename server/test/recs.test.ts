import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { rebuildRecs } from "../src/index";

const call = (path: string, init: RequestInit & { token?: string; json?: unknown } = {}) =>
  exports.default.fetch(new Request(`https://api.test${path}`, {
    ...init,
    body: init.json !== undefined ? JSON.stringify(init.json) : init.body,
    headers: { ...(init.token ? { Authorization: `Bearer ${init.token}` } : {}), ...init.headers },
  }));
const j = async (r: Response) => (await r.json()) as any;
const DB = (env as any).DB as D1Database;
let n = 0;
async function signup() {
  const b = await j(await call("/v1/auth/register", { method: "POST", json: { username: `rec_${Date.now()}_${n++}`, password: "password1" } }));
  const pid = (await j(await call("/v1/profiles", { token: b.token }))).profiles[0].id as string;
  return { token: b.token as string, id: b.user.id as string, pid };
}
const TITLES: Record<string, string> = {
  "media:series:mirzapur": "Mirzapur", "media:series:sacredgames": "Sacred Games", "media:series:squidgame": "Squid Game",
  "media:series:familyman": "The Family Man", "media:series:panchayat": "Panchayat", "media:movie:rrr": "RRR",
  "media:movie:palangtod": "Palang Tod", "anime:doraemon": "Doraemon", "anime:shinchan": "Shin-chan", "anime:naruto": "Naruto",
};
// web-style wire payload, incl. per-viewer progress that must not leak into recs
const payload = (k: string) => k.startsWith("anime:")
  ? { kind: "anime", id: k.slice(6), slug: k.slice(6), title: TITLES[k], poster: `https://img/${k}.jpg`, episode: 3, progress: 0.9 }
  : { kind: "media", slug: k.slice(6), mediaId: `tt${k.length}`, title: TITLES[k], type: k.split(":")[1], poster: `https://img/${k}.jpg`, progress: 0.9, eps: { s1e1: { p: 1 } } };
const watch = (u: { token: string }, pid: string, keys: string[]) => call(`/v1/profiles/${pid}/history`, {
  method: "PUT", token: u.token,
  json: { entries: keys.map((key, i) => ({ key, payload: payload(key), position: 900, duration: 1000, updatedAt: Date.now() - i * 3_600_000, deleted: false })) },
});

describe("recs", () => {
  it("relates co-watched titles, normalises popularity, filters per profile", async () => {
    const crime = ["media:series:mirzapur", "media:series:sacredgames", "media:series:squidgame", "media:series:familyman", "media:movie:palangtod"];
    const users = await Promise.all(Array.from({ length: 12 }, signup));
    for (const [i, u] of users.entries()) {
      const keys = i < 4 ? crime
        : i === 4 ? ["media:series:sacredgames", "media:series:squidgame", "media:series:panchayat"]
        : i < 8 ? ["media:series:squidgame", "anime:naruto", "anime:doraemon", "anime:shinchan", "media:movie:rrr"]
        : ["media:series:squidgame", "media:series:panchayat", "media:movie:rrr"];
      expect((await watch(u, u.pid, keys)).status).toBe(200);
    }
    // the profile we recommend for: watched Mirzapur only; a sibling profile hides series
    const me = await signup();
    await watch(me, me.pid, ["media:series:mirzapur"]);
    const hid = await j(await call("/v1/profiles", { method: "POST", token: me.token, json: { name: "nos", avatar: "x", hide: ["series"] } }));
    await watch(me, hid.id, ["media:series:mirzapur"]);
    // a kids account: two kids profiles share Doraemon, one also Shin-chan
    const fam = await signup();
    const kid = await j(await call("/v1/profiles", { method: "POST", token: fam.token, json: { name: "kid", avatar: "x", kids: true } }));
    const kid2 = await j(await call("/v1/profiles", { method: "POST", token: fam.token, json: { name: "kid2", avatar: "x", kids: true } }));
    await watch(fam, kid.id, ["anime:doraemon"]);
    await watch(fam, kid2.id, ["anime:doraemon", "anime:shinchan"]);
    await watch(fam, kid2.id, ["anime:legacy:3"]); // legacy per-episode key: ignored

    const boss = await signup();
    expect((await call("/v1/admin/recs/rebuild", { method: "POST", token: boss.token })).status).toBe(403);
    await DB.prepare("UPDATE users SET is_admin = 1 WHERE id = ?").bind(boss.id).run();
    const built = await j(await call("/v1/admin/recs/rebuild", { method: "POST", token: boss.token }));
    expect(built.related).toBeGreaterThan(0);
    expect(await DB.prepare("SELECT COUNT(*) n FROM item_meta WHERE item = 'anime:legacy:3'").first("n")).toBe(0);
    expect(await DB.prepare("SELECT kids FROM item_meta WHERE item = 'anime:shinchan'").first("kids")).toBe(1);

    const rel = Object.fromEntries((await DB.prepare("SELECT other, score FROM related WHERE item = 'media:series:mirzapur'").all<any>())
      .results.map((r) => [r.other, r.score]));
    // same 4 co-viewers each, but Squid Game is watched by everyone, so Sacred Games wins
    expect(rel["media:series:sacredgames"]).toBeGreaterThan(rel["media:series:squidgame"]);

    const recs = await call(`/v1/recs?profile=${me.pid}`, { token: me.token });
    expect(recs.headers.get("cache-control")).toBe("private, max-age=300");
    const r = await j(recs);
    const keys = r.items.map((x: any) => x.item);
    expect(keys).not.toContain("media:series:mirzapur"); // already watched
    expect(keys).not.toContain("media:movie:palangtod"); // mature title, profile not opted in
    expect(keys.indexOf("media:series:sacredgames")).toBeLessThan(keys.indexOf("media:series:squidgame"));
    expect(r.items.find((x: any) => x.item === "media:series:sacredgames")).toMatchObject({
      because: "media:series:mirzapur", becauseTitle: "Mirzapur",
      payload: { title: "Sacred Games", type: "series", kind: "media", poster: "https://img/media:series:sacredgames.jpg" },
    });
    expect(r.items[0].payload.progress).toBeUndefined();
    expect(r.items[0].payload.eps).toBeUndefined();
    expect(r.coverage).toEqual({ seeds: 1, related_items: 4 });
    expect(r.confidence).toBeCloseTo(4 / 50);
    expect(r.popular.map((x: any) => x.item)).toContain("media:series:squidgame");
    expect(r.popular.every((x: any) => x.viewers >= 3)).toBe(true);
    console.log("recs for Mirzapur viewer:", JSON.stringify(r.items.map((x: any) => [x.item, +x.score.toFixed(3)])));

    // mature profile does get it
    await call(`/v1/profiles/${me.pid}`, { method: "PATCH", token: me.token, json: { mature: true } });
    expect((await j(await call(`/v1/recs?profile=${me.pid}&limit=2`, { token: me.token }))).items.map((x: any) => x.item)).toHaveLength(2);
    const all = (await j(await call(`/v1/recs?profile=${me.pid}`, { token: me.token }))).items.map((x: any) => x.item);
    expect(all).toContain("media:movie:palangtod");

    const h = await j(await call(`/v1/recs?profile=${hid.id}`, { token: me.token }));
    expect([...h.items, ...h.popular].some((x: any) => x.item.startsWith("media:series:"))).toBe(false);
    expect(h.popular.map((x: any) => x.item)).toContain("media:movie:rrr");

    const k = await j(await call(`/v1/recs?profile=${kid.id}`, { token: fam.token }));
    expect(k.items.map((x: any) => x.item)).toEqual(["anime:shinchan"]); // Naruto is co-watched but never by a kids profile
    expect(k.popular.map((x: any) => x.item)).toEqual(["anime:shinchan"]);

    expect((await call(`/v1/recs?profile=${me.pid}`, { token: fam.token })).status).toBe(404);
    expect((await call(`/v1/recs?profile=${me.pid}`)).status).toBe(401);
  });

  it("rebuilds 2,000 profiles x 50 titles", { timeout: 300_000 }, async () => {
    const P = 2000, I = 50, t = Date.now();
    await DB.batch([
      DB.prepare(`WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${P - 1})
        INSERT INTO users (id, username, pass, created_at) SELECT 'perfu' || i, 'perfu' || i, 'x', 0 FROM n`),
      DB.prepare(`WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${P - 1})
        INSERT INTO profiles (id, user_id, name, avatar, kids, created_at) SELECT 'perfp' || i, 'perfu' || i, 'p', 'x', 0, 0 FROM n`),
      // 40 taste clusters, each drawing from its own 200-title window of a 1,400-title catalogue
      DB.prepare(`WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${P * I - 1})
        INSERT OR IGNORE INTO history (profile_id, key, payload, position, duration, updated_at, synced_at, deleted)
        SELECT 'perfp' || (i / ${I}), 'media:movie:perf' || ((i / ${I} % 40) * 30 + abs(random()) % 200),
          '{"title":"Perf","kind":"media"}', abs(random()) % 1000, 1000, ${t} - (i % 300) * 86400000, ${t}, 0 FROM n`),
    ]);
    const rows = await DB.prepare("SELECT COUNT(*) n FROM history WHERE profile_id GLOB 'perfp*'").first<number>("n");
    const t0 = performance.now();
    const out = await rebuildRecs(env as any);
    const ms = performance.now() - t0;
    console.log(`rebuild: ${rows} history rows, ${P} profiles -> ${JSON.stringify(out)} in ${ms.toFixed(0)} ms`);
    expect(out.related).toBeGreaterThan(1000);
  });
});
