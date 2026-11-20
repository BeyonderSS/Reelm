import assert from "node:assert/strict";
import { canSend, clock, duck0, duckGain, duckStep, fromMs, guard, hold, matchSource, nextOffset, ownSeek, roomPos, sameCopy, toMs } from "./party.ts";

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
hold(g, 3000, true);
assert.equal(canSend(g, 3500), false); assert.equal(canSend(g, 3800), true);
assert.equal(ownSeek(g, 4500), false, "the applied seek's `seeked` is swallowed even after the hold");
assert.equal(ownSeek(g, 4600), true, "the next seek is the user's");
hold(g, 10000, true); hold(g, 10001, true); assert.equal(ownSeek(g, 10500), false);
assert.equal(ownSeek(g, 16000), true, "a coalesced seek's leftover count expires");

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

assert.equal(clock(3723000), "1:02:03"); assert.equal(clock(62000), "1:02");
console.log("party ok");
