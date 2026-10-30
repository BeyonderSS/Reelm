import assert from "node:assert/strict";
import { makeAxios, makeFetch, pickSeason, pickQualities, pickEpisode } from "./vega.ts";
const R = (status: number, url: string, text = "", headers = {}) => ({ status, url, text, headers });
const calls: any[] = [];
const f = async (u: string, o: any = {}) => { calls.push([u, o]);
  return u.includes("/redir") ? R(200, "https://cdn.x/file.mkv") : u.includes("/404") ? R(404, u, "nope") : u.includes("/json") ? R(200, u, '{"a":1}', { "content-type": "application/json" }) : R(200, u, "<p>hi</p>"); };
const ax = makeAxios(f), tx = (p: Promise<any>) => p.then(() => null, (e) => e);
// redirect: maxRedirects:0 reads Location off the final URL; without it the page body is returned
const r = await ax.get("https://a.x/redir", { maxRedirects: 0, validateStatus: (s: number) => s >= 200 && s < 400 });
assert.equal(r.status, 302); assert.equal(r.headers.location, "https://cdn.x/file.mkv");
assert.equal((await ax.get("https://a.x/page", { maxRedirects: 0 })).data, "<p>hi</p>");
// validateStatus: default rejects non-2xx with .response; a custom one accepts
assert.equal((await tx(ax.get("https://a.x/404"))).response.status, 404);
assert.equal((await ax.get("https://a.x/404", { validateStatus: () => true })).status, 404);
assert.equal((await tx(ax.get("https://a.x/redir", { maxRedirects: 0 }))).response.status, 302);
// callable forms, params, post body, JSON parsing
assert.deepEqual((await ax("https://a.x/json")).data, { a: 1 });
assert.equal((await ax({ url: "https://a.x/p", method: "post", data: { k: 1 }, params: { q: "x y" } })).status, 200);
const c = calls.at(-1); assert.equal(c[0], "https://a.x/p?q=x+y"); assert.equal(c[1].method, "POST"); assert.equal(c[1].body, '{"k":1}');
await ax.post("https://a.x/p", new URLSearchParams({ a: "1" })); assert.equal(calls.at(-1)[1].body, "a=1");
const ac = new AbortController(); ac.abort(); assert.equal((await tx(ax.get("https://a.x/p", { signal: ac.signal }))).code, "ERR_CANCELED");
// fetch shim
const fr = await makeFetch(f)("https://a.x/json"); assert.ok(fr.ok); assert.equal(fr.url, "https://a.x/json"); assert.deepEqual(await fr.json(), { a: 1 });
assert.equal((await makeFetch(f)("https://a.x/404")).ok, false);
// season / episode / quality picking
const L = (title: string, extra = {}) => ({ title, ...extra });
const ll = [L("Season 1 1080p 10bit HEVC [20GB]"), L("Season 1 1080p x264 [30GB]"), L("Season 1 720p [8GB]"), L("Season 1 480p [4GB]"), L("S02 1080p [25GB]"), L("Season 1-3 Pack 720p"), L("S03 720p")];
assert.deepEqual(pickSeason(ll, 1).map((l) => l.title).slice(0, 5), ll.slice(0, 4).concat(ll[5]).map((l) => l.title));
assert.deepEqual(pickSeason(ll, 3).map((l) => l.title), ["Season 1-3 Pack 720p", "S03 720p"]);
assert.equal(pickSeason([L("1080p [2GB]"), L("720p [1GB]")], 4).length, 2); // no season tags: one-season show
assert.deepEqual(pickQualities(pickSeason(ll, 1)).map((l) => l.title), ["Season 1 1080p x264 [30GB]", "Season 1 720p [8GB]"]); // lightest codec at 1080, then ~720
assert.equal(pickQualities([L("480p [1GB]"), L("1080p [2GB]")]).length, 2);
assert.equal(pickQualities([L("1080p [2GB]")]).length, 1);
const eps = [{ title: "Episode 1", link: "a" }, { title: "Episode 2", link: "b" }, { title: "S01E10 x", link: "c" }];
assert.equal(pickEpisode(eps, 2)?.link, "b"); assert.equal(pickEpisode(eps, 10)?.link, "c");
assert.equal(pickEpisode([{ title: "Ep one", link: "x" }, { title: "Ep two", link: "y" }], 2)?.link, "y"); // index fallback
