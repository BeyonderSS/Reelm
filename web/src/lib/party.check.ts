import assert from "node:assert/strict";
import { canSend, clampBox, clock, fromFrac, parseBoxes, resizeAt, snapBox, toFrac, describe, driftFix, duck0, duckGain, duckStep, fromMs, guard, hold, matchSource, nextOffset, ownSeek, pickCut, roomPos, sameCopy, sameCut, toMs } from "./party.ts";

// positions: integer ms on the wire (the old web sent float seconds: 153.4 meant 153 s to web, 153 ms to the phone)
assert.equal(toMs(153.4567), 153457); assert.equal(fromMs(153457), 153.457); assert.equal(toMs(NaN), 0);
assert.ok(Number.isInteger(toMs(118.67)));
assert.equal(roomPos({ playing: true, position: 1000, at: 5000 }, 7000), 3000);
assert.equal(roomPos({ playing: false, position: 1000, at: 5000 }, 7000), 1000);
assert.equal(roomPos({ playing: true, position: 1000, at: 9000 }, 7000), 1000); // lights down: not started yet
assert.equal(nextOffset(null, 100, 50), 50); assert.equal(nextOffset(50, 100, 80), 50); assert.equal(nextOffset(50, 200, 100), 100);

// echo guard: nothing before the state reached a ready player, nothing in the first 2 s, nothing while applying
const g = guard(0);
assert.equal(canSend(g, 5000), false); // state not applied yet: a late joiner must not broadcast play 0
g.ready = true;
assert.equal(canSend(g, 1999), false); assert.equal(canSend(g, 2000), true);
hold(g, 3000, 60000);
assert.equal(canSend(g, 3500), false); assert.equal(canSend(g, 3800), true);
assert.equal(ownSeek(g, 40000, 60000), false, "the applied seek's `seeked` is swallowed however late it lands");
assert.equal(ownSeek(g, 41000, 90000), true, "the next seek is the user's");
hold(g, 50000, 120000); assert.equal(ownSeek(g, 51000, 300000), true, "the user seeked elsewhere before ours landed: theirs wins");
assert.equal(ownSeek(g, 52000, 120000), true, "the mark is used once");

// ducking: 150 ms of voice ducks to 35 %, 400 ms of silence brings it back (ramping)
let d = duck0();
d = duckStep(d, 0.2, 0); assert.equal(duckGain(d, 0), 1);
d = duckStep(d, 0.2, 100); assert.equal(d.ducked, false);
d = duckStep(d, 0.2, 160); assert.equal(d.ducked, true); assert.equal(duckGain(d, 160), 0.35);
d = duckStep(d, 0.0, 200); assert.equal(d.ducked, true); assert.ok(Math.abs(duckGain(d, 400) - 0.675) < 1e-9);
d = duckStep(d, 0.2, 300); assert.equal(duckGain(d, 300), 0.35, "a word mid-ramp ducks again at once");
d = duckStep(d, 0.0, 400); d = duckStep(d, 0.0, 799); assert.equal(d.ducked, true);
d = duckStep(d, 0.0, 800); assert.equal(d.ducked, false); assert.equal(duckGain(d, 800), 1);
d = duckStep(duck0(), 0.2, 0); d = duckStep(d, 0, 100); d = duckStep(d, 0.2, 120); d = duckStep(d, 0.2, 200);
assert.equal(d.ducked, false, "a blip that stops resets the 150 ms");

// the room's copy of the film
const list = [
  { src: "https://a.io/x.mp4", label: "VegaMovies · VCloud 1080p", height: 1080 },
  { src: "/api/proxy?u=https%3A%2F%2Fcdn.hot.io%2Fm.m3u8", label: "Hotstar mirror · 1080p", height: 1080 },
  { src: "https://b.io/y.mp4", label: "Hotstar mirror · 720p", height: 720 },
];
assert.equal(matchSource(list, { provider: "Hotstar mirror", quality: "1080p", host: "cdn.hot.io" }), 1);
assert.equal(matchSource(list, { provider: "Hotstar mirror", quality: "720p", host: "other.io" }), 2, "host differs: same provider + quality still beats another cut");
assert.equal(matchSource(list, { provider: "VegaMovies", quality: "1080p", label: "VegaMovies · VCloud 1080p" }), 0);
assert.equal(matchSource(list, { provider: "castle", quality: "1080p" }), -1);
assert.equal(sameCopy(list[0], null), true); assert.equal(sameCopy(list[0], { provider: "castle", quality: "1080p" }), false);
// same cut, not same file: the room's copy this browser can't decode (HEVC) is skipped; a copy within ±3 s of its length is the same cut
const hevc = { src: "https://h.io/z.mp4", label: "Dramachi · 540p", codec: "hevc" };
const d0 = describe(hevc, 8880000);
assert.equal(d0.durationMs, 8880000); assert.equal(d0.codec, "hevc"); assert.equal(describe(hevc).durationMs, undefined);
assert.equal(matchSource([...list, hevc], d0), 3);
assert.equal(matchSource([...list, hevc], d0, (x) => x.codec !== "hevc"), -1, "undecodable exact copy: no match");
assert.equal(sameCut(8880000, 8882900), true); assert.equal(sameCut(8880000, 8883100), false); assert.equal(sameCut(8880000, undefined), false);
assert.equal(pickCut([2, 0, 1], [8520000, 8879000, 8881000], 8880000), 0, "the best-ranked copy of the same cut");
assert.equal(pickCut([2, 0], [8520000, 0], 8880000), -1); assert.equal(pickCut([2], [8880000], undefined), -1, "no room length: no cut match");

// drift correction: nothing while not quiet, a nudge from 0.25 s until within 0.1 s, a quiet seek past 1.5 s
assert.deepEqual(driftFix(-1000, 1, false), { rate: 1 }, "buffering or just after an event: no correction");
assert.deepEqual(driftFix(-200, 1, true), { rate: 1 }); assert.deepEqual(driftFix(-300, 1, true), { rate: 1.05 }, "behind: faster");
assert.deepEqual(driftFix(400, 1, true), { rate: 0.95 }, "ahead: slower"); assert.deepEqual(driftFix(-1500, 1, true), { rate: 1.05 });
assert.deepEqual(driftFix(-150, 1.05, true), { rate: 1.05 }, "keeps nudging until within 0.1 s"); assert.deepEqual(driftFix(-80, 1.05, true), { rate: 1 });
assert.deepEqual(driftFix(1600, 1, true), { rate: 1, seek: true }); assert.deepEqual(driftFix(-5000, 0.95, true), { rate: 1, seek: true });

assert.equal(clock(3723000), "1:02:03"); assert.equal(clock(62000), "1:02");
// face tiles: inside the player, 96 px to half its width, snapped onto an edge within 24 px, stored as fractions
const ar = 74 / 112;
assert.deepEqual(clampBox({ x: -50, y: 990, w: 112 }, ar, 1900, 1000), { x: 0, y: 1000 - 112 * ar, w: 112 });
assert.equal(clampBox({ x: 0, y: 0, w: 40 }, ar, 1900, 1000).w, 96, "never smaller than 96 px");
assert.equal(clampBox({ x: 0, y: 0, w: 1500 }, ar, 1900, 1000).w, 950, "never wider than half the player");
assert.equal(clampBox({ x: 0, y: 0, w: 900 }, 1, 1900, 600).w, 600, "never taller than the player");
assert.equal(clampBox({ x: 0, y: 0, w: 300 }, ar, 150, 300).w, 75, "a tiny player: half its width wins over 96");
assert.equal(clampBox({ x: 1850, y: 0, w: 200 }, ar, 1900, 1000).x, 1700);
assert.deepEqual(snapBox({ x: 20, y: 500, w: 100 }, 1, 1900, 1000), { x: 0, y: 500, w: 100 }, "near the left edge");
assert.deepEqual(snapBox({ x: 1780, y: 880, w: 100 }, 1, 1900, 1000), { x: 1800, y: 900, w: 100 }, "near the right and bottom");
assert.deepEqual(snapBox({ x: 30, y: 30, w: 100 }, 1, 1900, 1000), { x: 30, y: 30, w: 100 }, "24 px+ away: stays");
assert.deepEqual(resizeAt({ x: 100, y: 100, w: 100 }, 200, 0.5, 1900, 1000), { x: 50, y: 75, w: 200 }, "grows about its centre");
assert.deepEqual(resizeAt({ x: 100, y: 100, w: 100 }, 900, 0.5, 400, 1000), { x: 50, y: 75, w: 200 }, "past the max it stops at the max, still about its centre");
const fb = toFrac({ x: 950, y: 250, w: 190 }, 1900, 1000);
assert.deepEqual(fb, { x: 0.5, y: 0.25, w: 0.1 }); assert.deepEqual(fromFrac(fb, 3800, 2000), { x: 1900, y: 500, w: 380 }, "fullscreen: same spot, same share");
assert.deepEqual(parseBoxes('{"self":{"x":0.1,"y":0.2,"w":0.3},"o0":{"x":"a"},"o1":{"x":2,"y":0,"w":0.1}}'), { self: { x: 0.1, y: 0.2, w: 0.3 } });
assert.deepEqual(parseBoxes("not json"), {}); assert.deepEqual(parseBoxes(null), {}); assert.deepEqual(parseBoxes("null"), {});

console.log("party ok");
