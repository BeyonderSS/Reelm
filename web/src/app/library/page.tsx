"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { Grid, Marquee, Notice, pill, Stub } from "@/components/app";
import { getFavs, getHistory, removeHistory, syncFavs, syncHistory, type CardData, type Entry } from "@/lib/core";
import { useSession } from "@/lib/session";

export default function Library() {
  const { s } = useSession();
  const [tab, setTab] = useState<"fav" | "hist">("fav");
  const [favs, setFavs] = useState<CardData[]>([]), [hist, setHist] = useState<Entry[]>([]);
  const pid = s.profile?.id ?? "guest";
  const load = () => {
    setFavs(getFavs(pid));
    setHist(Object.values(getHistory(pid)).filter((e) => !e.deleted && e.payload.kind !== "live").sort((a, b) => b.updatedAt - a.updatedAt));
  };
  useEffect(() => { load(); if (s.token) Promise.all([syncFavs(pid, s.token), syncHistory(pid, s.token)]).then(load).catch(() => {}); }, [s.profile]); // eslint-disable-line react-hooks/exhaustive-deps
  const n = tab === "fav" ? favs.length : hist.length;
  return (
    <main className="mx-auto max-w-[1500px] space-y-7 px-4 pt-6 pb-16 md:px-10 md:pt-10">
      <Marquee text="Your library" word className="text-[clamp(48px,7vw,112px)]" />
      <div className="flex flex-wrap items-center gap-3">
        <div role="tablist" aria-label="Library" className="seg">
          {([["fav", "Saved"], ["hist", "History"]] as const).map(([v, label]) => <button key={v} role="tab" aria-selected={tab === v} onClick={() => setTab(v)} className="beam">{label}</button>)}
        </div>
        {tab === "hist" && hist.length > 0 && (
          <button type="button" className={pill.ghost} onClick={() => confirm("Clear all watch history on every device signed in to this profile?") && removeHistory(pid, s.token).then(load)}>Clear all history</button>
        )}
      </div>
      {n === 0 ? (tab === "fav"
        ? <Notice title="Nothing saved yet" body="Press Favourite on any title to keep it here."><Link href="/" className={pill.secondary}>Browse home</Link></Notice>
        : <Notice title="No history yet" body={s.token ? "What you watch on any of your devices shows up here." : "What you watch in this browser shows up here."} />)
        : tab === "fav" ? <Grid items={favs} />
        : <ol className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] justify-items-start gap-4 max-md:[&>li]:[--w:min(calc(100vw_-_32px),400px)] md:grid-cols-[repeat(auto-fill,300px)]">
            {hist.map((e) => <Stub key={e.key} e={e} onRemove={() => removeHistory(pid, s.token, e.payload).then(load)} />)}
          </ol>}
    </main>
  );
}
