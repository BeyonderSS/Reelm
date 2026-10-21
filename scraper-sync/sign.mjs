// ECDSA P-256 signing for scrapers.json. P-256 because every client verifies it natively
// (WebCrypto in browsers/Workers, java.security on Android API 22+), unlike Ed25519.
// Signature is base64 of the raw 64-byte r||s (IEEE P1363), which is what WebCrypto expects.
//   node sign.mjs keygen            prints SCRAPER_SIGNING_KEY (repo secret) and PUBLIC_KEY (bake into the clients)
//   node sign.mjs sign <file>       needs env SCRAPER_SIGNING_KEY, writes <file>.sig
//   node sign.mjs verify <file> <publicKeyBase64>
import { createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const [, , cmd, file, pub] = process.argv;
const P1363 = { dsaEncoding: "ieee-p1363" };
if (cmd === "keygen") {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
  console.log("SCRAPER_SIGNING_KEY=" + privateKey.export({ type: "pkcs8", format: "der" }).toString("base64"));
  console.log("PUBLIC_KEY=" + publicKey.export({ type: "spki", format: "der" }).toString("base64"));
} else if (cmd === "sign") {
  const key = createPrivateKey({ key: Buffer.from(process.env.SCRAPER_SIGNING_KEY, "base64"), format: "der", type: "pkcs8" });
  const data = readFileSync(file), sigFile = file + ".sig";
  // ECDSA signatures differ on every run, so keep a still-valid one instead of dirtying the repo daily
  const valid = existsSync(sigFile) && verify("sha256", data, { key: createPublicKey(key), ...P1363 }, Buffer.from(readFileSync(sigFile, "utf8").trim(), "base64"));
  if (!valid) writeFileSync(sigFile, sign("sha256", data, { key, ...P1363 }).toString("base64") + "\n");
  console.log(valid ? "signature still valid" : "signed");
} else if (cmd === "verify") {
  const key = createPublicKey({ key: Buffer.from(pub, "base64"), format: "der", type: "spki" });
  const ok = verify("sha256", readFileSync(file), { key, ...P1363 }, Buffer.from(readFileSync(file + ".sig", "utf8").trim(), "base64"));
  console.log(ok ? "ok" : "BAD SIGNATURE");
  process.exit(ok ? 0 : 1);
} else { console.error("usage: sign.mjs keygen | sign <file> | verify <file> <pub>"); process.exit(2); }
