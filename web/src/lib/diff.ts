// Unified-diff parsing and a crude "risky change" scan for the admin provider review. Pure, node-testable (diff.check.ts).
export type DiffLine = { kind: "hunk" | "add" | "del" | "ctx" | "meta"; text: string; old?: number; new?: number };

export function parsePatch(patch: string): DiffLine[] {
  const out: DiffLine[] = [];
  let o = 0, n = 0;
  for (const t of patch.split("\n")) {
    const h = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(t);
    if (h) { o = +h[1]; n = +h[2]; out.push({ kind: "hunk", text: t }); }
    else if (t.startsWith("+")) out.push({ kind: "add", text: t.slice(1), new: n++ });
    else if (t.startsWith("-")) out.push({ kind: "del", text: t.slice(1), old: o++ });
    else if (t.startsWith("\\")) out.push({ kind: "meta", text: t });
    else out.push({ kind: "ctx", text: t.slice(1), old: o++, new: n++ });
  }
  return out;
}

export type Risk = { kind: "domain" | "network" | "eval" | "base64"; what: string; file: string; line: number };
const host = (u: string) => { try { return new URL(u).host; } catch { return u; } };
/** Added lines only. A domain counts as new unless the same diff also removes a line naming it (a moved/edited line). */
export function scanRisks(files: { filename: string; patch?: string }[]): Risk[] {
  const out: Risk[] = [], gone = new Set<string>(), seen = new Set<string>();
  const parsed = files.map((f) => ({ f: f.filename, ls: f.patch ? parsePatch(f.patch) : [] }));
  for (const { ls } of parsed) for (const l of ls) if (l.kind === "del") for (const m of l.text.matchAll(/https?:\/\/[^\s'"`)\\]+/g)) gone.add(host(m[0]));
  const add = (r: Risk) => { const k = `${r.kind}|${r.what}`; if (!seen.has(k)) { seen.add(k); out.push(r); } };
  for (const { f, ls } of parsed) for (const l of ls) {
    if (l.kind !== "add") continue;
    const at = { file: f, line: l.new! };
    for (const m of l.text.matchAll(/https?:\/\/[^\s'"`)\\]+/g)) { const hh = host(m[0]); if (!gone.has(hh)) add({ kind: "domain", what: hh, ...at }); }
    for (const m of l.text.matchAll(/\b(fetch|axios(?:\.(?:get|post|head|put|request))?)\s*\(\s*[`'"](https?:\/\/[^/'"`\s]+)/g)) add({ kind: "network", what: `${m[1]} → ${host(m[2])}`, ...at });
    if (/\beval\s*\(|\bFunction\s*\(/.test(l.text)) add({ kind: "eval", what: l.text.trim().slice(0, 80), ...at });
    for (const m of l.text.matchAll(/[A-Za-z0-9+/]{80,}={0,2}/g)) add({ kind: "base64", what: `${m[0].slice(0, 24)}… (${m[0].length} chars)`, ...at });
  }
  return out;
}
