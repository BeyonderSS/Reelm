"use client";
import Link from "next/link";
import type { ReactNode } from "react";
import { Check, Play } from "lucide-react";
import { cn } from "@/lib/utils";

/** Full-bleed art under a dusk gradient; portrait posters are blurred so they read as light, not as a stretched image. */
export function Backdrop({ src, blur, children }: { src?: string; blur?: boolean; children: ReactNode }) {
  return (
    <section className="relative -mt-16 md:-mt-20 overflow-hidden">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {src && <img src={src} alt="" referrerPolicy="no-referrer" className={cn("absolute inset-0 size-full object-cover object-[50%_20%]", blur ? "scale-125 opacity-45 blur-3xl" : "opacity-55")} />}
      <div className="absolute inset-0 bg-gradient-to-t from-dusk via-dusk/60 to-dusk/20" />
      <div className="absolute inset-0 bg-gradient-to-r from-dusk/90 via-dusk/40 to-transparent" />
      <div className="relative px-4 pt-28 pb-10 md:px-10 md:pt-40">{children}</div>
    </section>
  );
}

export const DetailSkeleton = () => (
  <div className="px-4 pt-12 md:px-10" aria-label="Loading">
    <div className="flex flex-col gap-6 md:flex-row md:items-end">
      <div className="skel aspect-[2/3] w-36 rounded-xl md:w-52" />
      <div className="flex-1 space-y-4"><div className="skel h-12 w-2/3 rounded-xl" /><div className="skel h-7 w-1/2 rounded-full" /><div className="skel h-20 w-full max-w-xl rounded-xl" /><div className="skel h-11 w-44 rounded-full" /></div>
    </div>
    <div className="mt-10 grid grid-cols-[repeat(auto-fill,minmax(10rem,1fr))] gap-2">{[...Array(12)].map((_, i) => <div key={i} className="skel h-16 rounded-xl" />)}</div>
  </div>
);

export type Ep = { n: number; title?: string; href: string; progress?: number; current?: boolean };
/** Episode tiles with a progress line; watched episodes get a tick, the one you're on gets the lamp. */
export function EpisodeGrid({ items, loading }: { items: Ep[]; loading?: boolean }) {
  if (loading) return <div className="grid grid-cols-[repeat(auto-fill,minmax(10rem,1fr))] gap-2">{[...Array(12)].map((_, i) => <div key={i} className="skel h-16 rounded-xl" />)}</div>;
  if (!items.length) return <p className="text-sm text-foreground/60">No episodes are out yet.</p>;
  return (
    <ol className="grid grid-cols-[repeat(auto-fill,minmax(10rem,1fr))] gap-2">
      {items.map((e) => {
        const done = (e.progress ?? 0) >= 0.9;
        return (
          <li key={e.n}>
            <Link href={e.href} title={e.title} className={cn("beam group relative flex h-16 items-center gap-3 overflow-hidden rounded-xl bg-slate px-3 transition-colors hover:bg-haze", e.current && "ring-1 ring-primary/60")}>
              <span className={cn("grid size-9 shrink-0 place-items-center rounded-full font-display text-sm font-semibold", done ? "bg-signal/15 text-signal" : e.current ? "bg-primary text-primary-foreground" : "bg-white/8")}>
                {done ? <Check className="size-4" /> : <><span className="group-hover:hidden">{e.n}</span><Play className="hidden size-3.5 fill-current group-hover:block" /></>}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium">Episode {e.n}</span>
                {e.title && !/^episode \d+$/i.test(e.title) && <span className="block truncate text-xs text-foreground/52">{e.title}</span>}
              </span>
              {e.progress != null && e.progress > 0.02 && !done && <span className="absolute inset-x-0 bottom-0 h-0.5 bg-white/10"><span className="block h-full bg-primary" style={{ width: `${e.progress * 100}%` }} /></span>}
            </Link>
          </li>
        );
      })}
    </ol>
  );
}
