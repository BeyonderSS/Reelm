import assert from "node:assert/strict";
import { blend, erotic, lowRated, matureOk, rank, roundRobin } from "./recs-rank.ts";

const doc = (genres: string[], text = "") => ({ genres, text });
const seeds = [{ doc: doc(["Drama"], "college engineering friends"), weight: 1, lang: "hi" }, { doc: doc(["Crime"], "gangster town"), weight: 0.2, lang: "hi" }];
const c = (id: string, by: number[], o: Partial<{ lang: string; year: number; rating: number; genres: string[] }> = {}) =>
  ({ item: id, doc: doc(o.genres ?? ["Drama"], "friends college"), by, lang: o.lang, year: o.year, rating: o.rating });

// support is the share of seeds, not their weight: a pick backed by both seeds beats one backed only by the heavy seed
assert.deepEqual(rank(seeds, [c("heavy", [0]), c("both", [0, 1])]).map((x) => x.item)[0], "both");
// matching original language (seed's or the preferred audio language) earns the +0.15
assert.equal(rank(seeds, [c("en", [0], { lang: "en" }), c("hi", [0], { lang: "hi" })])[0].item, "hi");
assert.equal(rank([seeds[0]], [c("en", [0], { lang: "en" }), c("ta", [0], { lang: "ta" })], "ta")[0].item, "ta");
// pre-1990 dropped unless rated 8+
assert.deepEqual(rank(seeds, [c("old", [0], { year: 1975, rating: 7 }), c("classic", [0], { year: 1975, rating: 8.4 }), c("new", [0], { year: 2020 })]).map((x) => x.item).sort(), ["classic", "new"]);
// round-robin across seeds: seed 1's best shows up second, not after all of seed 0's
assert.deepEqual(roundRobin(["a0", "a1", "a2", "b0", "b1"], (x) => x[0]), ["a0", "b0", "a1", "b1", "a2"]);
assert.equal(rank(seeds, [c("s0a", [0]), c("s0b", [0]), c("s0c", [0]), c("s1", [1], { genres: ["Crime"] })])[1].item, "s1");

// server blend: confidence 0 changes nothing; at 1 a server-only pick makes the top picks; matches dedupe across movie/series keys
const local = [c("a", [0]), c("b", [0]), c("c", [1], { genres: ["Crime"] }), c("d", [0]), c("e", [0])];
const srv = [{ item: "media:movie:zz", score: 4, payload: {} }, { item: "media:series:b", score: 2, payload: {} }];
const key = (x: { item: string }) => `media:${x.item}`, mk = (r: { item: string }) => c(r.item.split(":")[2], []);
const ids = (xs: { item: string }[]) => xs.map((x) => x.item);
assert.deepEqual(ids(rank(seeds, blend(local, key, srv, 0, mk))), ids(rank(seeds, local)));
const top = ids(rank(seeds, blend(local, key, srv, 1, mk))).slice(0, 3);
assert.ok(top.includes("zz"), String(top));
assert.equal(ids(blend(local, key, srv, 1, mk)).filter((x) => x === "b").length, 1);
assert.equal(blend(local, key, srv, 1, mk).find((x) => x.item === "b")!.bonus, 0.15);
// rating floor: disliked by 50+ voters goes; unrated or barely voted stays
assert.equal(lowRated(4.9, 300), true);
assert.equal(lowRated(4.9, 12), false);
assert.equal(lowRated(undefined, 0), false);
assert.equal(lowRated(6.1, 900), false);

// mature filter
assert.equal(matureOk(["Action", "Ecchi"]), false);
assert.equal(matureOk(["hentai"], false), false);
assert.equal(matureOk(["Ecchi"], true), true);
assert.equal(matureOk(["Action", "Fantasy"]), true);
assert.equal(erotic({ name: "Gandii Baat" }), true);
assert.equal(erotic({ title: "Mirzapur", overview: "A gangster drama in the heartland." }), false);
console.log("recs-rank ok");
