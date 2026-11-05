"use client";
import { Suspense, use, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { Play } from "lucide-react";
import { Chips, FavButton, mediaCard, Notice, pill, timeLeft } from "@/components/app";
import { EpisodeStrip, EpisodeStripSkeleton, type EpInfo } from "@/components/episode-cards";
import { DetailHero, DetailSkeleton, SeasonNumerals } from "@/components/detail";
import { getHistory, playKey, kids, KIDS_BLOCKED, epProgress, syncHistory, watchHref, type Entry, type Play as P } from "@/lib/core";
import { useApi, useSession } from "@/lib/session";

/** Seasons and the episode strip. TMDB stills and names when TMDB knows the season; numbered stubs otherwise. */
function Episodes({ m, id, cur, seasons, play, prog, last }: { m: any; id: string; cur: any; seasons: React.ReactNode; play: (se?: number, ep?: number) => P; prog: Record<string, number>; last: Entry | null }) {
  const { data, loading } = useApi(`movies/episodes?id=${encodeURIComponent(id)}&season=${cur.season}&title=${encodeURIComponent(m.title)}&year=${m.year ?? ""}`);
  const info: EpInfo[] = data?.episodes ?? [];
  const n = info.length || cur.episodes;
  const items = Array.from({ length: n }, (_, i) => {
    const e = info[i], k = e?.n ?? i + 1;
    return { n: k, info: e, href: watchHref(play(cur.season, k)), progress: prog[`s${cur.season}e${k}`], current: last?.payload.season === cur.season && last?.payload.episode === k };
  });
  return (
    <section aria-label="Episodes" className="space-y-3">
      <div className="mx-auto max-w-[1500px] space-y-2 px-4 md:px-10">
        {seasons}
        {data?.overview && <p className="line-clamp-2 max-w-prose text-sm leading-relaxed text-foreground/60">{data.overview}</p>}
      </div>
      {loading ? <EpisodeStripSkeleton /> : <EpisodeStrip items={items} />}
    </section>
  );
}

function Title({ id }: { id: string }) {
  const type = useSearchParams().get("type") ?? "movie", { s } = useSession();
  const { data: m, error } = useApi(`movies/detail?id=${encodeURIComponent(id)}&type=${type}`);
  const audio: { id: string; label: string; current: boolean }[] =
    useApi(m ? `movies/audio?id=${encodeURIComponent(id)}&type=${m.type}&title=${encodeURIComponent(m.title)}&year=${m.year ?? ""}` : null).data ?? [];
  const [season, setSeason] = useState<number | null>(null);
  const [last, setLast] = useState<Entry | null>(null), [prog, setProg] = useState<Record<string, number>>({});
  useEffect(() => {
    const pid = s.profile?.id ?? "guest";
    const read = () => {
      const t: P = { kind: "movie", id, title: m?.title, type: m?.type }, h = m && getHistory(pid)[playKey(t)];
      setLast(h && !h.deleted ? h : null);
      setProg(epProgress(pid, t));
    };
    read();
    if (m && s.token) syncHistory(pid, s.token).then(read).catch(() => {}); // pick up what the TV/phone watched since this device last synced
  }, [s.profile, s.token, id, m]);
  if (error) return <Notice title="This title didn't load" body={error}><Link href="/" className={pill.secondary}>Go home</Link></Notice>;
  if (!m) return <DetailSkeleton />;
  if (s.profile?.kids && !kids.media(m.genres, m.certification)) return <Notice title={KIDS_BLOCKED} body="Switch profile to watch this."><Link href="/profiles" className={pill.secondary}>Switch profile</Link></Notice>;
  const series = m.type === "series";
  const play = (se = 0, ep = 0): P => ({ kind: "movie", id, title: m.title, poster: m.poster, season: se, episode: ep, type: m.type, year: m.year });
  const cur = m.seasons.find((x: any) => x.season === (season ?? last?.payload.season)) ?? m.seasons[0];
  const lf = last?.duration ? last.position / last.duration : 0;
  const resume = last && (series ? !!last.payload.season : true);
  const primary = resume ? watchHref({ ...play(last!.payload.season, last!.payload.episode) }, lf) : series ? (cur ? watchHref(play(cur.season, 1)) : null) : watchHref(play());
  const left = resume && timeLeft(last!.position, last!.duration);
  return (
    <main className="pb-16">
      <DetailHero wash={m.backdrop || m.poster} poster={m.poster} title={m.title}>
        <Chips items={[series ? "Series" : "Movie", m.year, m.runtime, m.rating && `★ ${m.rating}`, m.certification, m.genres.slice(0, 3).join(", ")]} />
        {m.description && <p className="line-clamp-3 max-w-2xl leading-relaxed text-[#cfccd9]">{m.description}</p>}
        {m.cast.length > 0 && <p className="text-sm text-foreground/52">Starring {m.cast.slice(0, 5).join(", ")}</p>}
        <div className="flex flex-wrap items-center gap-2 pt-1">
          {primary && m.playable !== false && (
            <Link href={primary} className={`${pill.primary} whitespace-nowrap ring-2 ring-primary ring-offset-2 ring-offset-dusk`}><Play className="fill-current" />
              {resume ? (series ? `Resume S${last!.payload.season} E${last!.payload.episode}` : "Resume") : series ? `Play S${cur?.season ?? 1} E1` : "Play"}
              {left && <small className="text-xs font-medium opacity-75">{left.join(" ")}</small>}
            </Link>
          )}
          <FavButton c={mediaCard(m)} />
          {audio.length > 1 && (
            <nav aria-label="Audio language" className="seg">
              {audio.map((v) => <Link key={v.id} href={`/title/${encodeURIComponent(v.id)}?type=${m.type}`} aria-current={v.current ? "page" : undefined} className="beam">{v.label}</Link>)}
            </nav>
          )}
        </div>
      </DetailHero>
      {series && cur && (
        <Episodes m={m} id={id} cur={cur} play={play} prog={prog} last={last}
          seasons={<SeasonNumerals onPick={(k) => setSeason(Number(k))} cur={`Season ${cur.season}, ${cur.episodes} episode${cur.episodes === 1 ? "" : "s"}`}
            items={m.seasons.map((x: any) => ({ key: x.season, label: x.season, name: `Season ${x.season}`, on: x.season === cur.season }))} />} />
      )}
    </main>
  );
}
export default function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <Suspense><Title id={decodeURIComponent(id)} /></Suspense>;
}
