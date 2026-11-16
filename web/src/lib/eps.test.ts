import { test } from "node:test";
import assert from "node:assert/strict";
import { epsKey, finished, frac, mergeEps } from "./eps.ts";

test("keys match Android", () => {
  assert.equal(epsKey({ kind: "anime", ep: 12 }), "e12");
  assert.equal(epsKey({ kind: "movie", season: 5, episode: 1 }), "s5e1");
  assert.equal(epsKey({ kind: "movie", season: 0, episode: 0 }), "");
});

test("merge keeps the newest per episode and is symmetric", () => {
  const a = { e1: { p: 0.97, at: 200 }, e2: { p: 0.3, at: 100 } };
  const b = { e1: { p: 0.5, at: 100 }, e3: { p: 0.1, at: 50 } };
  assert.deepEqual(mergeEps(a, b), { e1: { p: 0.97, at: 200 }, e2: { p: 0.3, at: 100 }, e3: { p: 0.1, at: 50 } });
  assert.deepEqual(mergeEps(b, a), mergeEps(a, b));
  assert.deepEqual(mergeEps(undefined, { e1: { p: 2, at: 1 }, bad: null as any }), { e1: { p: 1, at: 1 } });
});

test("progress units", () => {
  assert.equal(frac(970, 1000), 0.97); // app permille
  assert.equal(frac(30, 120), 0.25); // web seconds
  assert.equal(frac(5, 0), 0);
  assert.equal(frac(1500, 1000), 1);
});

test("a film is finished once only its credits are left", () => {
  assert.equal(finished(8673, 9133, true), true); // The Dark Knight: 94.96%, 7 min 40 s of credits left
  assert.equal(finished(8673, 9133, false), false); // an episode keeps the plain 95% rule
  assert.equal(finished(7800, 9133, true), false); // 85%: still watching
  assert.equal(finished(4900, 5400, true), true); // 90.7%, 8 min 20 s left
  assert.equal(finished(920, 1000, true), false); // permille row: no minutes test
  assert.equal(finished(950, 1000, true), true);
});
