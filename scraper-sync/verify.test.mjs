// The web loader's WebCrypto check must accept exactly what sign.mjs produces, and nothing else.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { verifySignature } from "../web/src/lib/scraperVerify.ts";

const sign = join(import.meta.dirname, "sign.mjs");

test("web verifier accepts sign.mjs output and rejects tampering or another key", async () => {
  const keys = Object.fromEntries(execFileSync("node", [sign, "keygen"], { encoding: "utf8" }).trim().split("\n").map((l) => l.split(/=(.*)/s).slice(0, 2)));
  const other = Object.fromEntries(execFileSync("node", [sign, "keygen"], { encoding: "utf8" }).trim().split("\n").map((l) => l.split(/=(.*)/s).slice(0, 2)));
  const file = join(mkdtempSync(join(tmpdir(), "sig-")), "scrapers.json");
  const body = '{"hianime":{"config":{"key":"x"}}}\n';
  writeFileSync(file, body);
  execFileSync("node", [sign, "sign", file], { env: { ...process.env, SCRAPER_SIGNING_KEY: keys.SCRAPER_SIGNING_KEY } });
  const sig = readFileSync(file + ".sig", "utf8");
  assert.equal(await verifySignature(body, sig, keys.PUBLIC_KEY), true);
  assert.equal(await verifySignature(body.replace("x", "y"), sig, keys.PUBLIC_KEY), false);
  assert.equal(await verifySignature(body, sig, other.PUBLIC_KEY), false);
  assert.equal(await verifySignature(body, "not base64!!", keys.PUBLIC_KEY), false);
});
