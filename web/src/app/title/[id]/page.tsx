"use client";
import { Suspense, use, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { Play } from "lucide-react";
import { Chips, FavButton, mediaCard, Notice, pill } from "@/components/app";
import { Backdrop, DetailSkeleton, EpisodeGrid } from "@/components/detail";
import { getHistory, playKey, kids, KIDS_BLOCKED, progressOf, watchHref, type Entry, type Play as P } from "@/lib/core";
import { useApi, useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

function Title({ id }: { id: string }) {
  const type = useSearchParams().get("type") ?? "movie", { s } = useSession();
  const { data: m, error } = useApi(`movies/detail?id=${encodeURIComponent(id)}&type=${type}`);
  const [season, setSeason] = useState<number | null>(null);
  const [last, setLast] = useState<Entry | null>(null), [prog, setProg] = useState<Record<string, number>>({});
  useEffect(() => {
    const pid = s.profile?.id ?? "guest";
    const h = m && getHistory(pid)[playKey({ kind: "movie", id, title: m.title, type: m.type })];
    setLast(h && !h.deleted ? h : null);
    setProg(progressOf(pid));
  }, [s.profile, id, m]);
  if (error) return <Notice title="This title didn't load" body={error}><Link href="/" className={pill.secondary}>Go home</Link></Notice>;
  if (!m) return <DetailSkeleton />;
  if (s.profile?.kids && !kids.media(m.genres, m.certification)) return <Notice title={KIDS_BLOCKED} body="Switch profile to watch this."><Link href="/profiles" className={pill.secondary}>Switch profile</Link></Notice>;
  const series = m.type === "series";
  const play = (se = 0, ep = 0): P => ({ kind: "movie", id, title: m.title, poster: m.poster, season: se, episode: ep, type: m.type, year: m.year });
  const cur = m.seasons.find((x: any) => x.season === (season ?? last?.payload.season)) ?? m.seasons[0];
  const lf = last?.duration ? last.position / last.duration : 0;
  const resume = last && (series ? !!last.payload.season : true);
  const primary = resume ? watchHref({ ...play(last!.payload.season, last!.payload.episode) }, lf) : series ? (cur ? watchHref(play(cur.season, 1)) : null) : watchHref(play());
  return (
    <main className="pb-16">
      <Backdrop src={m.backdrop} blur={!m.backdrop || m.backdrop === m.poster}>
        <div className="flex flex-col gap-6 md:flex-row md:items-end">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {m.poster && <img src={m.poster} alt="" referrerPolicy="no-referrer" className="page-in hidden w-52 self-start rounded-xl shadow-[0_30px_80px_rgb(10_12_18/0.7)] ring-1 ring-white/10 md:block" />}
          <div className="max-w-2xl space-y-4">
            <h1 className="font-display text-4xl leading-[1.05] font-bold tracking-tight text-balance md:text-5xl">{m.title}</h1>
            <Chips items={[series ? "Series" : "Movie", m.year, m.runtime, m.rating && `IMDb ${m.rating}`, m.certification, series && m.seasons.length && `${m.seasons.length} season${m.seasons.length > 1 ? "s" : ""}`]} />
            {m.description && <p className="line-clamp-4 max-w-prose leading-relaxed text-foreground/72">{m.description}</p>}
            {m.genres.length > 0 && <p className="text-sm text-foreground/52">{m.genres.join(", ")}</p>}
            {m.cast.length > 0 && <p className="text-sm text-foreground/52">Starring {m.cast.slice(0, 5).join(", ")}</p>}
            <div className="flex flex-wrap gap-2 pt-1">
              {primary && m.playable !== false && (
                <Link href={primary} className={pill.primary}><Play className="fill-current" />
                  {resume ? (series ? `Resume S${last!.payload.season} E${last!.payload.episode}` : "Resume") : series ? `Play S${cur?.season ?? 1} E1` : "Play"}
                </Link>
              )}
              <FavButton c={mediaCard(m)} />
            </div>
          </div>
        </div>
      </Backdrop>
      {series && cur && (
        <section className="space-y-4 px-4 md:px-10">
          <div className="flex flex-wrap items-center gap-3">
            <h2 className="font-display text-2xl font-semibold tracking-tight">Episodes</h2>
            <div className="flex-1" />
            <nav aria-label="Seasons" className="no-scrollbar flex max-w-full gap-1 overflow-x-auto rounded-full bg-white/8 p-1">
              {m.seasons.map((x: any) => (
                <button key={x.season} aria-pressed={x.season === cur.season} onClick={() => setSeason(x.season)}
                  className={cn("beam shrink-0 rounded-full px-4 py-1.5 text-sm font-medium transition-colors", x.season === cur.season ? "bg-primary text-primary-foreground" : "text-foreground/72 hover:text-foreground")}>Season {x.season}</button>
              ))}
            </nav>
          </div>
          <EpisodeGrid items={[...Array(cur.episodes)].map((_, i) => ({
            n: i + 1, href: watchHref(play(cur.season, i + 1)), progress: prog[`movie:${id}:${cur.season}:${i + 1}`],
            current: last?.payload.season === cur.season && last?.payload.episode === i + 1,
          }))} />
        </section>
      )}
    </main>
  );
}
export default function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <Suspense><Title id={decodeURIComponent(id)} /></Suspense>;
}
