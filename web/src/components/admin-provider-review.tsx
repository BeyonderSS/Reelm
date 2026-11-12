"use client";
/* eslint-disable @typescript-eslint/no-explicit-any -- admin API rows are untyped JSON, shapes documented in server/API.md */
// Admin Providers: review an upstream vega-providers update in-app (diffs + risky-change scan) and test it against the pinned commit before approving.
import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { AlertTriangle, ArrowLeft, Check as CheckIcon, ChevronDown, ChevronRight, ExternalLink, Loader2, X } from "lucide-react";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { adminProviderDiff, type ProviderDiff } from "@/lib/admin";
import { helperVega } from "@/lib/bridge";
import { parsePatch, scanRisks, type Risk } from "@/lib/diff";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";
import { checkProvider, type Check, type CheckCase } from "@/lib/vega";

const REPO = "https://github.com/Zenda-Cross/vega-providers";
const day = (t: string) => new Date(t).toLocaleDateString("en", { month: "short", day: "numeric" });
const diffs = new Map<string, ProviderDiff>(); // per session: the overview shows +/- for providers already opened

// ---- review ----------------------------------------------------------------
export function ProviderReview({ view, setView, d }: { view: string | null; setView: (v: string | null) => void; d: any }) {
  const enabled: string[] = d.pinned.enabled;
  return (
    <Dialog open={!!view} onOpenChange={(o) => !o && setView(null)}>
      <DialogContent className="flex max-h-[92dvh] flex-col gap-0 overflow-hidden rounded-[24px] border-white/8 bg-haze p-0 sm:max-w-5xl">
        {view === "all" ? <Overview d={d} enabled={enabled} open={setView} /> : view && <ProviderDiffView key={view} slug={view} d={d} enabled={enabled} back={() => setView("all")} />}
      </DialogContent>
    </Dialog>
  );
}

function Overview({ d, enabled, open }: { d: any; enabled: string[]; open: (v: string) => void }) {
  const rows = [...(d.changed ?? [])].sort((a: any, b: any) => +enabled.includes(b.provider) - +enabled.includes(a.provider));
  return (
    <>
      <DialogHeader className="border-b border-white/6 p-5 pr-12">
        <DialogTitle className="font-display text-xl">Review changes</DialogTitle>
        <DialogDescription>{d.pinned.commit.slice(0, 7)} → {d.upstream.commit.slice(0, 7)}, {d.behind} commit{d.behind === 1 ? "" : "s"}. {rows.length} provider{rows.length === 1 ? "" : "s"} changed, enabled ones first.{" "}
          <a href={d.compareUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">Open on GitHub<ExternalLink className="size-3" /></a></DialogDescription>
      </DialogHeader>
      <ul className="divide-y divide-white/6 overflow-y-auto px-2 py-1">{rows.map((c: any) => { const on = enabled.includes(c.provider), x = diffs.get(c.provider); return (
        <li key={c.provider}><button onClick={() => open(c.provider)} className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left text-sm hover:bg-white/4">
          <span className={cn("size-2 shrink-0 rounded-full", on ? "bg-primary" : "bg-white/20")} />
          <span className={cn("font-medium", on && "text-primary")}>{name(d, c.provider)}</span>
          <span className="truncate text-xs text-foreground/48">{c.status}, {c.files.length} built file{c.files.length === 1 ? "" : "s"}</span>
          {x && <Counts f={x.files} />}
          <span className={cn("ml-auto shrink-0 rounded-full px-2 py-0.5 text-xs", on ? "bg-primary/14 text-primary" : "bg-white/6 text-foreground/56")}>{on ? "enabled" : "off"}</span>
          <ChevronRight className="size-4 shrink-0 text-foreground/40" />
        </button></li>); })}</ul>
    </>
  );
}

const name = (d: any, slug: string) => d.manifest?.find((m: any) => m.value === slug)?.name ?? slug;
const Counts = ({ f }: { f: ProviderDiff["files"] }) => (
  <span className="shrink-0 [font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace] text-xs tabular-nums"><span className="text-signal">+{f.reduce((s, x) => s + (x.patch ? x.additions : 0), 0)}</span> <span className="text-[#f07c6c]">−{f.reduce((s, x) => s + (x.patch ? x.deletions : 0), 0)}</span></span>
);
const RISK: Record<Risk["kind"], string> = { domain: "New domain", network: "New request host", eval: "Runs code from text", base64: "Long base64 blob" };

function ProviderDiffView({ slug, d, enabled, back }: { slug: string; d: any; enabled: string[]; back: () => void }) {
  const { s } = useSession();
  const [x, setX] = useState<ProviderDiff | undefined>(diffs.get(slug)), [err, setErr] = useState(""), [open, setOpen] = useState<Set<string>>(new Set());
  useEffect(() => {
    if (x) return;
    adminProviderDiff(s.token, slug).then((r) => { diffs.set(slug, r); setX(r); setOpen(new Set(r.files.filter((f) => f.patch).slice(0, 1).map((f) => f.filename))); }, (e) => setErr(e.message));
  }, [slug, s.token, x]);
  const risks = useMemo(() => (x ? scanRisks(x.files) : []), [x]);
  const hot = useMemo(() => new Set(risks.map((r) => `${r.file}:${r.line}`)), [risks]);
  const readable = x?.files.filter((f) => f.patch) ?? [], built = x?.files.filter((f) => !f.patch) ?? [];
  const on = enabled.includes(slug), toggle = (f: string) => setOpen((o) => { const n = new Set(o); if (n.has(f)) n.delete(f); else n.add(f); return n; });
  return (
    <>
      <DialogHeader className="border-b border-white/6 p-5 pr-12">
        <button onClick={back} className="-ml-1 mb-1 inline-flex w-fit items-center gap-1 text-xs text-foreground/56 hover:text-foreground"><ArrowLeft className="size-3.5" />All changes</button>
        <DialogTitle className="flex flex-wrap items-center gap-2 font-display text-xl">{name(d, slug)}<span className="[font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace] text-xs font-normal text-foreground/48">{slug}</span>
          <span className={cn("rounded-full px-2 py-0.5 font-sans text-xs font-medium", on ? "bg-primary/14 text-primary" : "bg-white/6 text-foreground/56")}>{on ? "enabled" : "disabled"}</span>
          {x && <Counts f={x.files} />}</DialogTitle>
        <DialogDescription>{d.pinned.commit.slice(0, 7)} → {d.upstream.commit.slice(0, 7)}.{" "}
          <a href={`${REPO}/compare/${d.pinned.commit}...${d.upstream.commit}`} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">Open on GitHub<ExternalLink className="size-3" /></a></DialogDescription>
      </DialogHeader>
      <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-5">
        {err ? <p className="rounded-xl bg-white/4 px-4 py-6 text-center text-sm text-foreground/52">Couldn&apos;t load the diff: {err}</p> : !x ? <div className="skel h-64 rounded-[18px]" aria-busy /> : <>
          <section className={cn("rounded-[18px] p-4 ring-1", risks.length ? "bg-[#f2b45a]/8 ring-[#f2b45a]/30" : "bg-white/4 ring-white/6")}>
            <h3 className="flex items-center gap-2 text-sm font-semibold">{risks.length ? <AlertTriangle className="size-4 text-primary" /> : <CheckIcon className="size-4 text-signal" />}Risky changes{risks.length ? ` (${risks.length})` : ""}</h3>
            {risks.length ? <ul className="mt-2 space-y-1.5 text-sm">{risks.map((r, i) => (
              <li key={i} className="flex flex-wrap items-baseline gap-x-2"><span className="text-xs font-medium text-primary">{RISK[r.kind]}</span><code className="[font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace] text-xs [overflow-wrap:anywhere]">{r.what}</code>
                <span className="text-xs text-foreground/44">{r.file.split("/").pop()}:{r.line}</span></li>))}</ul>
              : <p className="mt-1 text-sm text-foreground/60">No new domains, request hosts, eval/Function or long base64 in the added source lines.</p>}
            <p className="mt-2 text-xs text-foreground/44">A regex scan of added lines in the readable sources: a hint where to look, not a verdict.</p>
          </section>
          {x.commits.length > 0 && <section>
            <h3 className="mb-2 text-sm font-semibold">Commits</h3>
            <ul className="divide-y divide-white/6 rounded-[18px] bg-white/4 px-4">{x.commits.map((c) => (
              <li key={c.sha} className="flex items-center gap-3 py-2 text-sm"><a href={`${REPO}/commit/${c.sha}`} target="_blank" rel="noreferrer" className="shrink-0 [font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace] text-xs text-primary hover:underline">{c.sha.slice(0, 7)}</a>
                <span className="min-w-0 flex-1 truncate">{c.message}</span><span className="hidden shrink-0 text-xs text-foreground/48 sm:block">{c.author}</span><span className="shrink-0 text-xs text-foreground/48">{c.date ? day(c.date) : ""}</span></li>))}</ul>
          </section>}
          <section>
            <div className="mb-2 flex flex-wrap items-center gap-2 whitespace-nowrap"><h3 className="text-sm font-semibold">Source changes</h3><span className="text-xs text-foreground/48">{readable.length} file{readable.length === 1 ? "" : "s"}</span>
              {readable.length > 0 && <span className="ml-auto flex gap-1 text-xs"><button onClick={() => setOpen(new Set(readable.map((f) => f.filename)))} className="beam h-7 rounded-full px-2.5 hover:bg-white/8">Expand all</button>
                <button onClick={() => setOpen(new Set())} className="beam h-7 rounded-full px-2.5 hover:bg-white/8">Collapse all</button></span>}</div>
            {readable.length ? <div className="space-y-2">{readable.map((f) => <FileDiff key={f.filename} f={f} open={open.has(f.filename)} toggle={() => toggle(f.filename)} hot={hot} />)}</div>
              : <p className="rounded-xl bg-white/4 px-4 py-4 text-sm text-foreground/52">No readable source changed for this provider (only its built files). Shared extractors in providers/extractors aren&apos;t shown here.</p>}
          </section>
          {built.length > 0 && <section>
            <h3 className="mb-2 text-sm font-semibold">Built files <span className="font-normal text-foreground/48">minified, what apps actually load</span></h3>
            <ul className="space-y-1 [font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace] text-xs text-foreground/64">{built.map((f) => <li key={f.filename} className="flex gap-3"><span className="min-w-0 flex-1 truncate">{f.filename}</span><span className="text-foreground/44">{f.status}</span></li>)}</ul>
          </section>}
        </>}
      </div>
    </>
  );
}

function FileDiff({ f, open, toggle, hot }: { f: ProviderDiff["files"][number]; open: boolean; toggle: () => void; hot: Set<string> }) {
  const lines = useMemo(() => (open ? parsePatch(f.patch!) : []), [open, f.patch]);
  return (
    <div className="overflow-hidden rounded-xl ring-1 ring-white/8">
      <button onClick={toggle} aria-expanded={open} className="flex w-full items-center gap-2 bg-white/4 px-3 py-2 text-left text-sm hover:bg-white/6">
        <ChevronDown className={cn("size-4 shrink-0 text-foreground/48 transition-transform", !open && "-rotate-90")} />
        <span className="min-w-0 flex-1 truncate [font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace] text-xs">{f.filename}</span>
        {f.status !== "modified" && <span className="rounded-full bg-white/8 px-2 py-0.5 text-[11px] text-foreground/64">{f.status}</span>}
        <span className="[font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace] text-xs tabular-nums"><span className="text-signal">+{f.additions}</span> <span className="text-[#f07c6c]">−{f.deletions}</span></span>
      </button>
      {open && <div className="overflow-x-auto bg-dusk"><table className="min-w-full border-collapse [font-variant-ligatures:none] [font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace] text-[12px] leading-5"><tbody>{lines.map((l, i) => l.kind === "hunk" ? (
        <tr key={i} className="bg-[#7fd1b9]/8 text-foreground/52"><td colSpan={3} className="px-3 py-1 whitespace-pre">{l.text}</td></tr>
      ) : (
        <tr key={i} className={cn(l.kind === "add" ? "bg-[#7fd1b9]/12" : l.kind === "del" ? "bg-[#f07c6c]/14" : "", l.kind === "add" && hot.has(`${f.filename}:${l.new}`) && "shadow-[inset_3px_0_0_#f2b45a]")}>
          <td className={cn("sticky left-0 w-10 min-w-10 bg-dusk px-2 text-right text-foreground/32 select-none", l.kind === "del" && "bg-[#2a1d22]")}>{l.old ?? ""}</td>
          <td className={cn("sticky left-10 w-10 min-w-10 border-r border-white/6 bg-dusk px-2 text-right text-foreground/32 select-none", l.kind === "add" && "bg-[#18292a]")}>{l.new ?? ""}</td>
          <td className="px-3 whitespace-pre"><span className={cn("mr-2 select-none", l.kind === "add" ? "text-signal" : l.kind === "del" ? "text-[#f07c6c]" : "text-transparent")}>{l.kind === "add" ? "+" : l.kind === "del" ? "−" : " "}</span>{l.text}</td>
        </tr>
      ))}</tbody></table></div>}
    </div>
  );
}

// ---- test update -------------------------------------------------------------
type Side = "pinned" | "upstream";
const CASES: CheckCase[] = ["movie", "series"], SIDES: Side[] = ["pinned", "upstream"];
export type TestRow = { provider: string; enabled: boolean; changed: boolean; r: Partial<Record<string, Check | "run">> }; // r["pinned.movie"]
export type TestRun = { pinned: string; upstream: string; at: number; running?: boolean; rows: TestRow[] };
const runs = new Map<string, TestRun>(), subs = new Set<() => void>(), key = (sha: string) => `reelm.vegaCheck.${sha}`;
let ver = 0, stop = false;
const emit = () => { ver++; subs.forEach((f) => f()); };
const save = (r: TestRun) => { try { localStorage.setItem(key(r.upstream), JSON.stringify(r)); } catch {} };
/** Re-render when any test run changes. */
export const useUpdateTests = () => useSyncExternalStore((cb) => (subs.add(cb), () => void subs.delete(cb)), () => ver, () => 0);
/** The last test of this upstream commit (this session, else localStorage). */
export function testRun(sha: string): TestRun | null {
  if (!runs.has(sha)) try {
    const v = JSON.parse(localStorage.getItem(key(sha)) ?? "null") as TestRun | null;
    if (v) { v.running = false; for (const row of v.rows) for (const k in row.r) if (row.r[k] === "run") delete row.r[k]; runs.set(sha, v); } // a reload mid-run
  } catch {}
  return runs.get(sha) ?? null;
}
export const stopTest = () => { stop = true; };
/** Every enabled + every changed provider, at both commits, both cases; 3 checks at a time. Changed-and-enabled first: those answers matter most. */
export async function startTest(pinned: string, upstream: string, enabled: string[], changed: string[]) {
  const order = [...new Set([...changed.filter((p) => enabled.includes(p)), ...enabled, ...changed])];
  const run: TestRun = { pinned, upstream, at: Date.now(), running: true, rows: order.map((p) => ({ provider: p, enabled: enabled.includes(p), changed: changed.includes(p), r: {} })) };
  runs.set(upstream, run); stop = false; emit();
  const jobs = run.rows.flatMap((row) => SIDES.flatMap((side) => CASES.map((c) => ({ row, side, c }))));
  let i = 0;
  await Promise.all(Array.from({ length: 3 }, async () => {
    while (i < jobs.length && !stop) {
      const { row, side, c } = jobs[i++], k = `${side}.${c}`;
      row.r[k] = "run"; emit();
      row.r[k] = await checkProvider(side === "pinned" ? pinned : upstream, row.provider, c);
      save(run); emit();
    }
  }));
  run.running = false; run.at = Date.now(); save(run); emit();
}

const done = (c?: Check | "run"): c is Check => !!c && c !== "run";
/** regression: a case pinned passed and upstream fails. A provider failing every case at both commits is blocked here or just lacks the titles, not broken by the update. */
export function verdict(run: TestRun) {
  const broke: string[] = [], fixed: string[] = [], both: string[] = [], added: string[] = [];
  let pass = false, finished = 0;
  for (const row of run.rows) {
    let reg = false, fix = false, any = false, all = true;
    for (const c of CASES) {
      const a = row.r[`pinned.${c}`], b = row.r[`upstream.${c}`];
      if (!done(a) || !done(b)) { all = false; continue; }
      finished++;
      if (a.ok || b.ok) any = pass = true;
      if (a.ok && !b.ok) reg = true; if (!a.ok && b.ok) fix = true;
    }
    const fresh = CASES.every((c) => { const a = row.r[`pinned.${c}`]; return done(a) && a.stage === "load"; }); // not in the pinned commit at all
    if (reg) broke.push(row.provider); else if (fresh && fix) added.push(row.provider); else if (fix) fixed.push(row.provider); else if (all && !any) both.push(row.provider);
  }
  const text = broke.length ? `${broke.length} provider${broke.length === 1 ? "" : "s"} broke in this update` : !finished ? (run.running ? "Testing…" : "Not tested") : !pass ? "Unreachable from this network"
    : run.running ? "No regressions so far" : "No regressions";
  const note = !pass && finished ? "Every check failed at both commits, so this says nothing about the update: the sites are likely blocked on this network (ISP DNS blocks) or the Helper can't reach them."
    : both.length ? `${both.length} fail at both commits (${both.join(", ")}): blocked here or no match for the test titles, not caused by the update.` : "";
  return { broke, fixed, both, added, text, note, tone: broke.length ? "bad" : pass && finished ? "good" : "neutral" } as const;
}

export function Verdict({ run }: { run: TestRun }) {
  const v = verdict(run);
  return (
    <div className={cn("rounded-xl px-4 py-3 text-sm ring-1", v.tone === "bad" ? "bg-[#f07c6c]/10 ring-[#f07c6c]/30" : v.tone === "good" ? "bg-signal/8 ring-signal/24" : "bg-white/4 ring-white/8")}>
      <p className={cn("font-semibold", v.tone === "bad" ? "text-[#f07c6c]" : v.tone === "good" && "text-signal")}>{v.text}{v.broke.length ? `: ${v.broke.join(", ")}` : ""}</p>
      {v.note && <p className="mt-1 text-xs text-foreground/60">{v.note}</p>}
      {v.fixed.length > 0 && <p className="mt-1 text-xs text-foreground/60">Fixed by the update: {v.fixed.join(", ")}.</p>}
      {v.added.length > 0 && <p className="mt-1 text-xs text-foreground/60">New in the update, working: {v.added.join(", ")}.</p>}
    </div>
  );
}

const sec = (ms: number) => `${(ms / 1000).toFixed(ms < 10000 ? 1 : 0)} s`;
function Cell({ c }: { c?: Check | "run" }) {
  if (!c) return <span className="text-foreground/28">—</span>;
  if (c === "run") return <span className="inline-flex items-center gap-1 text-xs text-foreground/56"><Loader2 className="size-3 animate-spin" />running</span>;
  return (
    <span title={c.error} className={cn("inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs whitespace-nowrap", c.ok ? "bg-signal/14 text-signal" : "bg-[#f07c6c]/14 text-[#f07c6c]")}>
      {c.ok ? <CheckIcon className="size-3" /> : <X className="size-3" />}{c.ok ? `${c.files} file${c.files === 1 ? "" : "s"}` : c.stage}<span className="text-foreground/48">{sec(c.ms)}</span>
    </span>
  );
}

export const HelperMissing = () => (
  <p className="rounded-xl bg-white/4 px-4 py-3 text-sm text-foreground/64">The test runs each provider from this browser, the same way the web player does, so it needs the <b>Reelm Helper</b> extension (1.2 or newer) to reach the sites.
    {" "}<a href="/reelm-helper.zip" className="text-primary hover:underline">Download it</a>, load it unpacked in chrome://extensions, then reload this page.</p>
);
export const helperReady = () => helperVega();

export function UpdateTest({ run }: { run: TestRun }) {
  const v = verdict(run);
  const tag = (p: string) => (v.broke.includes(p) ? ["broke", "bg-[#f07c6c]/14 text-[#f07c6c]"] : v.fixed.includes(p) ? ["fixed", "bg-signal/14 text-signal"] : v.added.includes(p) ? ["new", "bg-signal/14 text-signal"] : v.both.includes(p) ? ["fails both", "bg-white/6 text-foreground/56"] : null);
  const total = run.rows.length * 4, ran = run.rows.reduce((s, r) => s + Object.values(r.r).filter(done).length, 0);
  return (
    <div className="space-y-3">
      <Verdict run={run} />
      <p className="text-xs text-foreground/48">{run.pinned.slice(0, 7)} (pinned) vs {run.upstream.slice(0, 7)} (upstream). Spider-Man: Into the Spider-Verse (2018) and Breaking Bad S1E1. {ran} of {total} checks{run.running ? " so far" : `, ${new Date(run.at).toLocaleString("en", { dateStyle: "medium", timeStyle: "short" })}`}.</p>
      <div className="overflow-x-auto rounded-xl ring-1 ring-white/6">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="text-xs text-foreground/48"><tr className="border-b border-white/6"><th rowSpan={2} className="px-3 py-2 text-left font-medium">Provider</th><th colSpan={2} className="px-3 pt-2 text-left font-medium">Pinned</th><th colSpan={2} className="px-3 pt-2 text-left font-medium">Upstream</th></tr>
            <tr className="border-b border-white/6">{SIDES.flatMap((s) => CASES.map((c) => <th key={s + c} className="px-3 pb-2 text-left font-normal">{c === "movie" ? "Movie" : "Series"}</th>))}</tr></thead>
          <tbody>{run.rows.map((row) => { const t = tag(row.provider); return (
            <tr key={row.provider} className="border-b border-white/6 last:border-0">
              <td className="px-3 py-2"><span className={cn("font-medium", row.enabled && "text-primary")}>{row.provider}</span>
                {row.changed && <span className="ml-2 text-[11px] text-foreground/48">changed</span>}{t && <span className={cn("ml-2 rounded-full px-2 py-0.5 text-[11px]", t[1])}>{t[0]}</span>}</td>
              {SIDES.flatMap((s) => CASES.map((c) => <td key={s + c} className="px-3 py-2"><Cell c={row.r[`${s}.${c}`]} /></td>))}
            </tr>); })}</tbody>
        </table>
      </div>
    </div>
  );
}
