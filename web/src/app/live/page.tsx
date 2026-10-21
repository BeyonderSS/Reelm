"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { Search, Tv } from "lucide-react";
import { Notice } from "@/components/app";
import { watchHref } from "@/lib/core";
import { useApi, useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

export default function Live() {
  const { s } = useSession();
  const [text, setText] = useState(""), [q, setQ] = useState(""), [group, setGroup] = useState("");
  useEffect(() => { const t = setTimeout(() => setQ(text.trim()), 300); return () => clearTimeout(t); }, [text]);
  const { data, loading, error } = useApi(`live?q=${encodeURIComponent(q)}&group=${encodeURIComponent(group)}${s.profile?.kids ? "&kids=1" : ""}`);
  const [groups, setGroups] = useState<string[]>([]);
  useEffect(() => { if (data?.groups?.length && !groups.length) setGroups(data.groups); }, [data, groups.length]);
  return (
    <main className="mx-auto max-w-[1400px] space-y-5 px-4 pt-4 pb-16 md:px-10">
      <h1 className="font-display text-3xl font-bold tracking-tight md:text-4xl">Live TV</h1>
      <div className="relative max-w-md">
        <Search className="pointer-events-none absolute top-1/2 left-4 size-4 -translate-y-1/2 text-foreground/52" />
        <input aria-label="Search channels" placeholder="Search channels" value={text} onChange={(e) => setText(e.target.value)} className="beam h-11 w-full rounded-full bg-slate pr-4 pl-11 outline-none placeholder:text-foreground/40" />
      </div>
      {groups.length > 0 && (
        <div role="radiogroup" aria-label="Category" className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4">
          {["", ...groups].map((g) => (
            <button key={g || "all"} role="radio" aria-checked={group === g} onClick={() => setGroup(g)} className={cn("beam shrink-0 rounded-full px-4 py-1.5 text-sm font-medium transition-colors", group === g ? "bg-primary text-primary-foreground" : "bg-white/8 text-foreground/72 hover:text-foreground")}>{g || "All"}</button>
          ))}
        </div>
      )}
      {error && <Notice title="Channels didn't load" body={error} />}
      {data && <p className="text-sm text-foreground/52">{data.total} channels{data.total > 120 ? ". Showing the first 120, search to narrow down." : ""}</p>}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5 lg:grid-cols-6">
        {loading && !data ? [...Array(18)].map((_, i) => <div key={i} className="skel h-32 rounded-[18px]" />) : data?.channels.map((c: any) => (
          <Link key={c.url} href={watchHref({ kind: "live", id: c.url, title: c.name, poster: c.logo, url: c.url })} className="beam group flex flex-col items-center gap-2 rounded-[18px] bg-slate p-3 text-center transition-colors hover:bg-haze">
            <div className="grid h-16 w-full place-items-center">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {c.logo ? <img src={c.logo} alt="" loading="lazy" referrerPolicy="no-referrer" className="max-h-14 max-w-[80%] object-contain transition-transform group-hover:scale-105" /> : <Tv className="size-8 text-foreground/32" />}
            </div>
            <span className="line-clamp-2 text-sm font-medium">{c.name}</span>{c.group && <span className="line-clamp-1 text-xs text-foreground/52">{c.group}</span>}
          </Link>
        ))}
      </div>
      {data && !data.channels.length && <Notice title="No channels match" body="Try another name or category." />}
    </main>
  );
}
