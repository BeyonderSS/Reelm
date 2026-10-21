// Daily scraper sync: extract upstream constants, smoke test them live, publish scrapers/scrapers.json if they pass.
//   node sync.mjs --ani <ani-cli dir> --mb <MovieBox-Tui dir> [--report report.md] [--accept] [--no-smoke]
// --accept re-baselines the logic fingerprints after someone has ported an upstream logic change.
import { execFileSync } from "node:child_process";
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { fromDisk } from "./extract.mjs";
import { health, run } from "./smoke.mjs";

const OUT = join(dirname(fileURLToPath(import.meta.url)), "../scrapers/scrapers.json");
const arg = (n) => { const i = process.argv.indexOf(n); return i > 0 ? process.argv[i + 1] : undefined; };
const flag = (n) => process.argv.includes(n);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const commit = (dir) => { try { return execFileSync("git", ["-C", dir, "rev-parse", "HEAD"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim(); } catch { return "unknown"; } };

const dirs = { hianime: arg("--ani"), moviebox: arg("--mb"), dramachi: arg("--mb"), fourkhdhub: arg("--mb") };
const cur = existsSync(OUT) ? JSON.parse(readFileSync(OUT, "utf8")) : { schema: 1 };
const next = structuredClone(cur);
const report = []; // lines for the issue; empty = nothing needs a human
let dirty = false;

// one source failing to parse must not hide the other
const ex = {}, exErr = {};
for (const s of Object.keys(dirs)) {
  try { ex[s] = fromDisk[s](dirs[s]); } catch (e) { exErr[s] = e.message; }
}

for (const s of Object.keys(dirs)) {
  const old = cur[s];
  if (exErr[s]) { report.push(`- **${s}**: upstream no longer matches the extractor (${exErr[s]}). Keeping the published config; update \`scraper-sync/extract.mjs\` and port the change.`); continue; }
  const up = ex[s], sha = commit(dirs[s]);
  const changed = !old || !same(old.config, up.config);

  // smoke the config that would be live after this run: the new one if it changed, otherwise the current one
  const res = flag("--no-smoke") ? { status: "skip", detail: "--no-smoke" } : await run(s, changed ? up.config : old.config);
  console.log(`${s}: ${changed ? "constants changed" : "constants unchanged"}, smoke ${res.status} (${res.detail})`);

  if (changed) {
    if (res.status === "pass") { next[s] = { ...old, config: up.config, upstream: { ...old?.upstream, commit: sha } }; dirty = true; }
    else report.push(`- **${s}**: upstream constants changed but smoke test ${res.status === "fail" ? "FAILED" : "was inconclusive"} (${res.detail}); not published.`);
  } else if (res.status === "fail") report.push(`- **${s}**: published config now FAILS the live smoke test (${res.detail}). The site or its API likely changed; this needs a code port.`);

  // logic fingerprints: a change means upstream edited scraping logic that this tool cannot port
  const base = flag("--accept") || !old?.upstream?.logic ? up.logic : old.upstream.logic;
  const drift = Object.keys(up.logic).filter((k) => up.logic[k] !== base[k]);
  if (drift.length) report.push(`- **${s}**: upstream logic changed in \`${drift.join("`, `")}\` (${(old?.upstream?.commit ?? "?").slice(0, 7)}..${sha.slice(0, 7)}). Review the diff, port it to the Reelm scrapers, then run \`node scraper-sync/sync.mjs --accept\`.`);
  if (next[s] && !same(next[s].upstream?.logic, base)) { next[s].upstream = { ...next[s].upstream, logic: base }; dirty = true; }
}

// the Reelm Helper extension can only reach hosts listed in its manifest, and a store-installed extension cannot be hot-patched
const perms = JSON.parse(readFileSync(join(dirname(OUT), "../extension/manifest.json"), "utf8")).host_permissions.join(" ");
const missing = (next.fourkhdhub?.config.mirrors ?? []).map((m) => m.replace(/\.$/, "")).filter((m) => !perms.includes(m));
if (missing.length) report.push(`- **fourkhdhub**: extension/manifest.json has no host permission for mirror(s) \`${missing.join("`, `")}\`. Add them and ship a new Reelm Helper.`);

// sources with no upstream to track still get a liveness check
for (const h of flag("--no-smoke") ? [] : await health()) {
  console.log(`${h.name}: ${h.status} (${h.detail})`);
  if (h.status === "fail") report.push(`- **${h.name}**: live check FAILED (${h.detail}).`);
}

if (dirty) {
  next.updatedAt = new Date().toISOString();
  writeFileSync(OUT, JSON.stringify(next, null, 2) + "\n");
}
const out = process.env.GITHUB_OUTPUT;
if (out) appendFileSync(out, `changed=${dirty}\nreport=${report.length > 0}\n`);
if (arg("--report")) writeFileSync(arg("--report"), report.length ? `${report.join("\n")}\n` : "");
console.log(dirty ? "scrapers.json updated" : "scrapers.json unchanged");
if (report.length) console.log("\nNeeds attention:\n" + report.join("\n"));
