"use client";
import Link from "next/link";
import { useState } from "react";
import { Notice } from "@/components/app";
import { useApi } from "@/lib/session";
import { cn } from "@/lib/utils";

export default function Schedule() {
  const { data, loading, error } = useApi<any[]>("anime/schedule");
  const [day, setDay] = useState(0);
  const cur = data?.[day];
  return (
    <main className="mx-auto max-w-5xl space-y-6 px-4 pt-4 pb-16 md:px-10">
      <h1 className="font-display text-3xl font-bold tracking-tight md:text-4xl">Airing this week</h1>
      {error && <Notice title="The schedule didn't load" body={error} />}
      <div role="tablist" aria-label="Day" className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4">
        {(data ?? [...Array(7)].map(() => null)).map((d, i) => d ? (
          <button key={d.date} role="tab" aria-selected={day === i} onClick={() => setDay(i)}
            className={cn("beam flex shrink-0 flex-col items-center rounded-[18px] px-4 py-2.5 transition-colors", day === i ? "bg-primary text-primary-foreground" : "bg-slate hover:bg-haze")}>
            <span className="text-sm font-semibold">{i === 0 ? "Today" : d.day.slice(0, 3)}</span><span className={cn("text-xs", day === i ? "opacity-70" : "text-foreground/52")}>{d.date.slice(5).replace("-", "/")}</span>
          </button>
        ) : <span key={i} className="skel h-14 w-20 shrink-0 rounded-[18px]" />)}
      </div>
      {loading && <div className="space-y-2">{[...Array(6)].map((_, i) => <div key={i} className="skel h-14 rounded-xl" />)}</div>}
      {cur && (cur.items.length ? (
        <ol className="space-y-2">
          {cur.items.map((i: any) => (
            <li key={i.slug + i.time}>
              <Link href={`/anime/${i.slug}`} className="beam flex items-center gap-4 rounded-xl bg-slate px-4 py-3 transition-colors hover:bg-haze">
                <span className="w-14 shrink-0 font-display text-lg font-semibold text-primary tabular-nums">{i.time}</span>
                <span className="min-w-0 flex-1 truncate font-medium">{i.title}</span>
                {i.episode && <span className="shrink-0 rounded-full bg-white/8 px-3 py-1 text-xs">Episode {i.episode}</span>}
              </Link>
            </li>
          ))}
        </ol>
      ) : <Notice title="Nothing airs this day" />)}
    </main>
  );
}
