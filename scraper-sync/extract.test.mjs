// Offline check: node --test scraper-sync/
import { test } from "node:test";
import assert from "node:assert/strict";
import { fromDisk } from "./extract.mjs";
import { sign } from "./smoke.mjs";

const ani = "../ani-cli", mb = "../MovieBox-Tui"; // sibling checkouts, same layout the workflow uses

test("extractor finds every constant", () => {
  const h = fromDisk.hianime(ani), m = fromDisk.moviebox(mb);
  assert.equal(h.config.key.length, 14);
  assert.match(h.config.base, /^https:\/\//);
  assert.equal(m.config.secret.length, 30);
  assert.ok(m.config.hosts.length >= 2 && m.config.versionCodes.length && m.endpoints.includes("/wefeed-mobile-bff/subject-api/search/v2"));
});

test("dramachi and 4khdhub extractors find their config", () => {
  const d = fromDisk.dramachi(mb), f = fromDisk.fourkhdhub(mb);
  assert.deepEqual(d.config.interfaces, ["eplist", "getFile", "search", "title_v2"]);
  assert.match(d.config.cdnPath, /\/cdn\//);
  assert.ok(f.config.mirrors.includes("hubcloud.") && f.config.pixeldrain.length && Object.keys(f.logic).length > 10);
});

test("moviebox signature is stable and key-dependent", () => {
  const a = sign([1, 2, 3], "POST", "https://h/p?b=2&a=1", "{}", 1700000000000);
  assert.equal(a, sign([1, 2, 3], "POST", "https://h/p?a=1&b=2", "{}", 1700000000000)); // query order is canonicalised
  assert.notEqual(a, sign([1, 2, 4], "POST", "https://h/p?a=1&b=2", "{}", 1700000000000));
});
