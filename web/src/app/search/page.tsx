"use client";
import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Search as SearchIcon, X } from "lucide-react";
import { animeCard, Grid, GridSkeleton, kidsOk, mediaCard, Notice } from "@/components/app";
import { useApi, useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

const TABS = [["anime", "Anime"], ["movies", "Movies & TV"]] as const;

function Search() {
  const q = useSearchParams(), router = useRouter(), { s } = useSession();
  const term = q.get("q") ?? "", tab = q.get("t") === "movies" ? "movies" : "anime";
  const [text, setText] = useState(term);
  useEffect(() => setText(term), [term]);
  const go = (t: string, x = text) => router.replace(`/search?q=${encodeURIComponent(x.trim())}&t=${t}`, { scroll: false });
  // search as you type, after a short pause
  useEffect(() => {
    if (text.trim() === term) return;
    const id = setTimeout(() => go(tab, text), 450);
    return () => clearTimeout(id);
  }, [text]); // eslint-disable-line react-hooks/exhaustive-deps
  const { data, loading, error } = useApi(term ? `${tab}/search?q=${encodeURIComponent(term)}` : null);
  const items = ((data ?? []) as any[]).map(tab === "anime" ? animeCard : mediaCard).filter((c) => kidsOk(c, s.profile?.kids));
  return (
    <main className="mx-auto max-w-[1400px] space-y-6 px-4 pt-4 pb-16 md:px-10">
      <form onSubmit={(e) => { e.preventDefault(); go(tab); }} role="search" className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-5 size-5 -translate-y-1/2 text-foreground/52" />
        <input aria-label="Search" autoFocus value={text} onChange={(e) => setText(e.target.value)} placeholder="Search anime, movies and series" enterKeyHint="search"
          className="beam h-14 w-full rounded-full bg-slate pr-12 pl-14 text-base outline-none placeholder:text-foreground/40 focus-visible:bg-haze" />
        {text && <button type="button" aria-label="Clear search" onClick={() => { setText(""); router.replace(`/search?t=${tab}`); }} className="beam absolute top-1/2 right-3 grid size-9 -translate-y-1/2 place-items-center rounded-full hover:bg-white/8"><X className="size-4" /></button>}
      </form>
      <div role="tablist" aria-label="Catalog" className="inline-flex rounded-full bg-white/8 p-1">
        {TABS.map(([v, label]) => (
          <button key={v} role="tab" aria-selected={tab === v} onClick={() => go(v, term)} className={cn("beam rounded-full px-5 py-2 text-sm font-medium transition-colors", tab === v ? "bg-primary text-primary-foreground" : "text-foreground/72 hover:text-foreground")}>{label}</button>
        ))}
      </div>
      {!term && <Notice title="Find something to watch" body="Search by title. Results show anime or movies and series, depending on the tab." />}
      {loading && <GridSkeleton />}
      {error && <Notice title="Search didn't go through" body={error}><button className="beam rounded-full bg-secondary px-5 py-2.5 text-sm font-medium" onClick={() => location.reload()}>Try again</button></Notice>}
      {term && !loading && !error && !items.length && <Notice title={`Nothing found for “${term}”`} body={`Check the spelling, or look in ${tab === "anime" ? "Movies & TV" : "Anime"}.`} />}
      {!loading && <Grid items={items} />}
    </main>
  );
}
export default function Page() { return <Suspense><Search /></Suspense>; }
