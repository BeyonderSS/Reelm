"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { Grid, Notice, pill } from "@/components/app";
import { entryHref, entrySub, getFavs, getHistory, removeHistory, type CardData } from "@/lib/core";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

export default function Library() {
  const { s } = useSession();
  const [tab, setTab] = useState<"fav" | "hist">("fav");
  const [favs, setFavs] = useState<CardData[]>([]), [hist, setHist] = useState<CardData[]>([]);
  const pid = s.profile?.id ?? "guest";
  const load = () => {
    setFavs(getFavs());
    setHist(Object.values(getHistory(pid)).filter((e) => !e.deleted && e.payload.kind !== "live").sort((a, b) => b.updatedAt - a.updatedAt).map((e) => ({
      href: entryHref(e), title: e.payload.title || "Untitled", poster: e.payload.poster, sub: entrySub(e.payload), progress: e.duration ? e.position / e.duration : 0,
      onRemove: () => removeHistory(pid, s.token, e.payload).then(load),
    })));
  };
  useEffect(load, [s.profile]); // eslint-disable-line react-hooks/exhaustive-deps
  const list = tab === "fav" ? favs : hist;
  return (
    <main className="mx-auto max-w-[1400px] space-y-6 px-4 pt-4 pb-16 md:px-10">
      <h1 className="font-display text-3xl font-bold tracking-tight md:text-4xl">Library</h1>
      <div role="tablist" aria-label="Library" className="inline-flex rounded-full bg-white/8 p-1">
        {([["fav", "Saved"], ["hist", "History"]] as const).map(([v, label]) => (
          <button key={v} role="tab" aria-selected={tab === v} onClick={() => setTab(v)} className={cn("beam rounded-full px-5 py-2 text-sm font-medium transition-colors", tab === v ? "bg-primary text-primary-foreground" : "text-foreground/72 hover:text-foreground")}>{label}</button>
        ))}
      </div>
      {tab === "hist" && hist.length > 0 && (
        <button type="button" className={pill.secondary} onClick={() => confirm("Clear all watch history on every device signed in to this profile?") && removeHistory(pid, s.token).then(load)}>Clear all history</button>
      )}
      {list.length ? <Grid items={list} /> : tab === "fav"
        ? <Notice title="Nothing saved yet" body="Use Add to library on any title to keep it here."><Link href="/" className={pill.secondary}>Browse home</Link></Notice>
        : <Notice title="No history yet" body={s.token ? "What you watch on any of your devices shows up here." : "What you watch in this browser shows up here."} />}
    </main>
  );
}
