"use client";
import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { RotateCcw, X } from "lucide-react";
import { animeCard, Grid, GridSkeleton, kidsOk, mediaCard, Notice } from "@/components/app";
import { useApi, useSession } from "@/lib/session";
import { track } from "@/lib/telemetry";

const TABS = [["anime", "Anime"], ["movies", "Movies & TV"]] as const;
const RECENT = "reelm.recent";
const readRecent = (): string[] => { try { return JSON.parse(localStorage.getItem(RECENT) ?? "[]"); } catch { return []; } };
/** "Series, 2008" -> "Series"; anime subs are already the type ("TV", "OVA"). */
const kindOf = (sub?: string) => sub?.split(",")[0].trim() || "";

function Search() {
  const q = useSearchParams(), router = useRouter(), { s } = useSession();
  const term = q.get("q") ?? "", tab = q.get("t") === "movies" ? "movies" : "anime";
  const [text, setText] = useState(term), [kind, setKind] = useState(""), [recent, setRecent] = useState<string[]>([]);
  const box = useRef<HTMLTextAreaElement>(null);
  useEffect(() => setText(term), [term]);
  useEffect(() => setRecent(readRecent()), []);
  const go = (t: string, x = text) => router.replace(`/search?q=${encodeURIComponent(x.trim())}&t=${t}`, { scroll: false });
  // search as you type, after a short pause
  useEffect(() => {
    if (text.trim() === term) return;
    const id = setTimeout(() => go(tab, text), 450);
    return () => clearTimeout(id);
  }, [text]); // eslint-disable-line react-hooks/exhaustive-deps
  const { data, loading, error } = useApi(term ? `${tab}/search?q=${encodeURIComponent(term)}${tab === "movies" && s.profile?.mature && !s.profile.kids ? "&mature=1" : ""}` : null);
  const all = ((data ?? []) as any[]).map(tab === "anime" ? animeCard : mediaCard).filter((c) => kidsOk(c, s.profile?.kids));
  const kinds = [...new Set(all.map((c) => kindOf(c.sub)).filter(Boolean))];
  const items = kind && kinds.includes(kind) ? all.filter((c) => kindOf(c.sub) === kind) : all;
  const empty = !!term && !loading && !error && !all.length;
  useEffect(() => { if (empty) track("search_empty", { query: term, tab }); }, [empty]); // eslint-disable-line react-hooks/exhaustive-deps
  // the last 10 searches you sent with Enter (not every pause while typing)
  const remember = (t: string) => {
    const x = t.trim().toLowerCase();
    if (!x) return;
    const next = [x, ...readRecent().filter((r) => r !== x)].slice(0, 10);
    try { localStorage.setItem(RECENT, JSON.stringify(next)); } catch {}
    setRecent(next);
  };
  // the outline completion: the rest of the first result that starts with what you typed
  const hit = text && all.find((c) => c.title.toLowerCase().startsWith(text.toLowerCase()) && c.title.length > text.length);
  const rest = hit ? hit.title.slice(text.length) : "";
  const accept = () => { if (rest) setText(text + rest); };
  return (
    <main className="relative isolate mx-auto grid max-w-[1500px] gap-8 px-4 pt-4 pb-16 md:px-10 lg:grid-cols-[minmax(0,34rem)_minmax(0,1fr)] lg:gap-14 lg:pt-10">
      {/* the top match lights the room, as on home */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {items[0]?.poster && <img key={items[0].poster} src={items[0].poster} alt="" aria-hidden referrerPolicy="no-referrer" className="page-in pointer-events-none fixed top-0 right-0 -z-10 h-[70vh] w-[70vw] object-cover opacity-40 blur-[100px] saturate-150 [mask-image:radial-gradient(closest-side,#000,transparent)]" />}
      <div className="space-y-6 lg:sticky lg:top-28 lg:self-start">
        <form onSubmit={(e) => { e.preventDefault(); remember(text); go(tab); }} role="search" className="group relative">
          <span aria-hidden className="bulbs absolute inset-x-0 -bottom-3 opacity-0 transition-opacity group-focus-within:opacity-100" />
          {/* what you typed, solid, and where it's heading, in outline; the real textarea sits on top with clear text and an amber caret */}
          <p aria-hidden className="giant min-h-[0.88em] pr-10 text-[clamp(48px,6.4vw,96px)] whitespace-pre-wrap [text-wrap:wrap]">
            {text || <span className="hollow">Search</span>}<span className="hollow">{rest}</span>
          </p>
          <textarea ref={box} aria-label="Search anime, movies and series" autoFocus value={text} rows={1} spellCheck={false} enterKeyHint="search"
            onChange={(e) => setText(e.target.value.replace(/\n/g, ""))}
            onKeyDown={(e) => {
              if (e.key === "Enter") { e.preventDefault(); remember(text); go(tab); }
              else if (rest && (e.key === "Tab" || (e.key === "ArrowRight" && e.currentTarget.selectionStart === text.length))) { e.preventDefault(); accept(); }
            }}
            className="giant absolute inset-0 resize-none overflow-hidden border-0 bg-transparent p-0 pr-10 [text-wrap:wrap] text-[clamp(48px,6.4vw,96px)] whitespace-pre-wrap text-transparent caret-primary outline-none" />
          {text && <button type="button" aria-label="Clear search" onClick={() => { setText(""); router.replace(`/search?t=${tab}`); box.current?.focus(); }} className="beam absolute -top-1 right-0 grid size-9 place-items-center rounded-full bg-white/8 hover:bg-white/14"><X className="size-4" /></button>}
        </form>
        {rest && <p className="text-sm text-foreground/52">Press Tab to complete “{hit && hit.title}”</p>}
        <div role="tablist" aria-label="Catalog" className="seg">
          {TABS.map(([v, label]) => <button key={v} role="tab" aria-selected={tab === v} onClick={() => { setKind(""); go(v, term); }} className="beam">{label}</button>)}
        </div>
        {recent.length > 0 && (
          <div className="space-y-2">
            <h2 className="font-display text-lg font-semibold">Recent</h2>
            <ul className="flex flex-wrap gap-2">
              {recent.map((r) => (
                <li key={r}><button onClick={() => { setText(r); go(tab, r); }} className="beam inline-flex items-center gap-1.5 rounded-lg border-l-2 border-dashed border-white/30 bg-[#1d1b26] py-1.5 pr-3.5 pl-3 text-sm text-[#dad6e4] hover:bg-haze">
                  <RotateCcw className="size-3.5 text-[#a3a1ae]" />{r}</button></li>
              ))}
            </ul>
          </div>
        )}
      </div>
      <section aria-label="Results" className="min-w-0 space-y-5">
        {term && !loading && all.length > 0 && (
          <div className="flex flex-wrap items-baseline justify-between gap-3">
            <p className="font-display text-xl font-semibold">{items.length} result{items.length === 1 ? "" : "s"}</p>
            {kinds.length > 1 && (
              <div role="radiogroup" aria-label="Type" className="flex flex-wrap gap-1.5">
                {["", ...kinds].map((k) => <button key={k || "all"} role="radio" aria-checked={kind === k || (!k && !kinds.includes(kind))} onClick={() => setKind(k)} className="beam opt solid">{k || "All"}</button>)}
              </div>
            )}
          </div>
        )}
        {!term && <Notice title="Find something to watch" body="Type a title. Results show anime or movies and series, depending on the switch." />}
        {loading && <GridSkeleton />}
        {error && <Notice title="Search didn't go through" body={error}><button className="beam rounded-full bg-secondary px-5 py-2.5 text-sm font-medium" onClick={() => location.reload()}>Try again</button></Notice>}
        {empty && <Notice title={`Nothing found for “${term}”`} body={`Check the spelling, or look in ${tab === "anime" ? "Movies & TV" : "Anime"}.`} />}
        {!loading && <Grid items={items} />}
      </section>
    </main>
  );
}
export default function Page() { return <Suspense><Search /></Suspense>; }
