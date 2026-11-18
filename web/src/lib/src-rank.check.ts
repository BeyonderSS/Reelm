import assert from "node:assert/strict";
import { fallbackOrder, hostKey, provider, qualities, score } from "./src-rank.ts";
const now = 1e12, day = 864e5;
const L = [
  { height: 1080, label: "VegaMovies · VCloud 1080p" }, // 0
  { height: 1080, label: "MoD · Direct 1080p", codec: "HEVC" }, // 1: browser may not decode -> last of 1080p
  { height: 1080, label: "Drive · GDrive 1080p" }, // 2: reliable -> first
  { height: 720, label: "MovieBox · 720p", langs: ["hi"] }, // 3
  { height: 480, label: "MovieBox · 480p" }, // 4
  { height: 2160, label: "4KHDHub · 2160p" }, // 5
  { height: 720, label: "Drive · GDrive 720p" }, // 6
];
const stats = { Drive: { ok: 5, fail: 0, stall: 0, ttff: 2000, t: now }, VegaMovies: { ok: 1, fail: 4, stall: 0, ttff: 0, t: now } };
assert.equal(provider("VegaMovies · VCloud 1080p"), "VegaMovies");
// within 1080p: reliable Drive, then Vega, HEVC last; then 720p, 480p (lower, nearest first); 2160p last
assert.deepEqual(fallbackOrder(L, { height: 1080, stats, now }), [2, 0, 1, 6, 3, 4, 5]);
// unknown providers keep list order (MovieBox 720 before Drive 720 only if Drive had no stats)
assert.deepEqual(fallbackOrder(L, { orig: ["hi"], height: 720, stats: {}, now }).slice(0, 2), [3, 6]);
// a language puts its sources first
assert.equal(fallbackOrder(L, { lang: "hi", height: 1080, stats, now })[0], 3);
// decay: a provider that failed two months ago is nearly back to neutral
const old = { ok: 0, fail: 10, stall: 0, ttff: 0, t: now - 60 * day };
assert.ok(score(old, now) > 0.45 && score({ ...old, t: now }, now) < 0.1);
// chips: heights for the language only (all when none carry it), best first, with counts
assert.deepEqual(qualities(L), [{ h: 2160, n: 1 }, { h: 1080, n: 3 }, { h: 720, n: 2 }, { h: 480, n: 1 }]);
assert.deepEqual(qualities(L, "hi"), [{ h: 720, n: 1 }]);
assert.deepEqual(qualities(L, "ta"), qualities(L));
// within a height the fast starter goes first (Inception 2160p, 2026-11-17); this browser's own first-frame times win over the defaults
const K = [
  { height: 2160, hls: true, label: "autoEmbed · Movy Miami (Multi/4K) 2160p", src: "https://moon.goldenkite.top/m.m3u8" },
  { height: 2160, label: "MoviesDrive · GDrive (download only) 2160p", src: "https://video-downloads.googleusercontent.com/x" },
  { height: 2160, label: "MoviesDrive · Pixeldrain 2160p", src: "https://pixeldrain.dev/api/file/abc" },
  { height: 1080, label: "MovieBox · 1080p", src: "https://bcdnxw.hakunaymatata.com/a.mp4" },
];
assert.deepEqual(fallbackOrder(K, { height: 2160, stats: {}, now }), [2, 0, 1, 3]);
const seen = { "@pixeldrain.dev": { ok: 0, fail: 0, stall: 0, ttff: 7000, t: now }, "@goldenkite.top": { ok: 0, fail: 0, stall: 0, ttff: 1500, t: now } };
assert.deepEqual(fallbackOrder(K, { height: 2160, stats: seen, now }), [0, 1, 2, 3]);
assert.equal(hostKey("https://small-dew-a3b5.becini95037599.workers.dev/f.mkv"), "@workers.dev");
console.log("ok");
// dub-safe (2026-11-17: Inception auto-picked "castle · Inception - Tamil - 1080p"): within a height a single-language dub of another original goes last
import { langs, origLangs } from "./langs.ts";
const D = (label: string, x: object = {}) => ({ height: 1080, label, langs: langs(label), ...x });
const inc = [D("castle · Inception - Tamil - 1080p"), D("VegaMovies · Inception 2010 Dual Audio {Hindi-English} 1080p", { codec: "HEVC" }), D("MovieBox · 1080p")];
const ukus = origLangs("United Kingdom, United States");
assert.deepEqual(fallbackOrder(inc, { orig: ukus, height: 1080, stats: {}, now }), [2, 1, 0]);
assert.deepEqual(fallbackOrder(inc, { height: 1080, stats: {}, now }), [2, 1, 0]); // country not known yet: same
assert.deepEqual(fallbackOrder([inc[0], inc[1]], { height: 1080, stats: {}, now }), [1, 0]); // even over a file the browser may not decode
assert.equal(fallbackOrder(inc, { lang: "ta", height: 1080, stats: {}, now })[0], 0); // unless Tamil is the language picked
const gow = [D("castle · Gangs of Wasseypur - Hindi - 1080p"), D("Somewhere · Gangs of Wasseypur English Dub 1080p")];
assert.deepEqual(fallbackOrder(gow, { orig: origLangs("India, France"), height: 1080, stats: {}, now }), [0, 1]); // Hindi original: Hindi is right
const dh = [D("castle · Dhurandhar - Tamil - 720p", { height: 720 }), D("castle · Dhurandhar - Hindi, Tamil, Telugu - 720p", { height: 720 })];
assert.deepEqual(fallbackOrder(dh, { height: 720, stats: {}, now }), [1, 0]); // multi-audio over a single regional dub
assert.deepEqual(fallbackOrder(dh, { orig: origLangs("India"), height: 720, stats: {}, now }), [0, 1]); // an Indian original: either is original, list order
console.log("dub-safe ok");
// resume point: a Range-ignoring host (always starts at 0:00) goes after a seekable file of its height, even one measured fast
const gd = [D("A · 1080p", { url: "https://video-downloads.googleusercontent.com/x" }), D("B · 1080p", { url: "https://cdn.example/b.mp4" })];
const fast = { "@googleusercontent.com": { ok: 0, fail: 0, stall: 0, ttff: 1000, t: now } };
assert.deepEqual(fallbackOrder(gd, { height: 1080, stats: fast, now }), [0, 1]);
assert.deepEqual(fallbackOrder(gd, { height: 1080, stats: fast, now, resume: true }), [1, 0]);
console.log("resume seekable ok");
