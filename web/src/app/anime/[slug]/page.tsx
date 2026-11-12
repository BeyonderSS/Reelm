"use client";
import { use, useEffect, useState } from "react";
import Link from "next/link";
import { Play } from "lucide-react";
import { animeCard, Chips, FavButton, Notice, pill, Rail } from "@/components/app";
import { DetailHero, DetailSkeleton, SeasonNumerals } from "@/components/detail";
import { EpisodeStrip, EpisodeStripSkeleton, type EpInfo } from "@/components/episode-cards";
import { PlayOnTv } from "@/components/play-on-tv";
import { histMs } from "@/lib/hub";
import { frac } from "@/lib/eps";
import { getHistory, kids, KIDS_BLOCKED, epProgress, syncHistory, watchHref, type Play as P } from "@/lib/core";
import { useApi, useSession } from "@/lib/session";

/** MAL's "you might also like" mapped onto hianime; hianime's own generic list (as "Popular on Reelm") when MAL has nothing. */
function MoreLike({ slug, fallback }: { slug: string; fallback: any[] }) {
  const { s } = useSession(), p = s.profile;
  const r = useApi(`anime/similar?slug=${slug}${p?.mature && !p.kids ? "&mature=1" : ""}${p?.kids ? "&kids=1" : ""}`);
  if (r.loading) return null;
  const mal = r.data?.source === "mal";
  return <Rail title={mal ? "You might also like" : "Popular on Reelm"} items={(mal ? r.data.items : r.data?.items ?? fallback).map(animeCard).filter((c: any) => !p?.kids || kids.animeCard(c))} />;
}

export default function Anime({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params), { s } = useSession();
  const d = useApi(`anime/detail?slug=${slug}`), e = useApi(`anime/episodes?slug=${slug}`);
  const info = useApi(d.data ? `anime/episodes-info?title=${encodeURIComponent(d.data.title)}&jp=${encodeURIComponent(d.data.japanese ?? "")}&year=${d.data.year ?? ""}` : null);
  const [lang, setLang] = useState("sub");
  const [resume, setResume] = useState<{ p: P; f: number; ms: number } | null>(null), [prog, setProg] = useState<Record<string, number>>({});
  useEffect(() => {
    const pid = s.profile?.id ?? "guest";
    const read = () => {
      const h = Object.values(getHistory(pid)).filter((x) => !x.deleted && x.payload.kind === "anime" && x.payload.id === slug).sort((a, b) => b.updatedAt - a.updatedAt)[0];
      setResume(h ? { p: h.payload, f: frac(h.position, h.duration), ms: histMs(h) } : null);
      setProg(epProgress(pid, { kind: "anime", id: slug, title: "" }));
    };
    read();
    if (s.token) syncHistory(pid, s.token).then(read).catch(() => {});
  }, [s.profile, s.token, slug]);
  const a = d.data;
  if (d.error) return <Notice title="This title didn't load" body={d.error}><Link href="/" className={pill.secondary}>Go home</Link></Notice>;
  if (!a) return <DetailSkeleton />;
  if (s.profile?.kids && !kids.anime(a.rating, a.genres)) return <Notice title={KIDS_BLOCKED} body="Switch profile to watch this."><Link href="/profiles" className={pill.secondary}>Switch profile</Link></Notice>;
  const eps = (e.data ?? []) as any[];
  const play = (ep: any): P => ({ kind: "anime", id: slug, title: a.title, poster: a.poster, ep: ep.number, epId: ep.id, lang });
  const resumeEp = resume && eps.find((x) => x.number === resume.p.ep);
  const kitsu = new Map<number, EpInfo>(((info.data?.episodes ?? []) as EpInfo[]).map((k) => [k.n, k]));
  const curSeason = a.seasons.find((x: any) => x.slug === slug);
  return (
    <main className="pb-16">
      <DetailHero wash={a.poster} poster={a.poster} title={a.title}>
        {a.japanese && a.japanese !== a.title && <p className="-mt-1 text-sm text-foreground/52">{a.japanese}</p>}
        <Chips items={[a.type, a.year, a.status, a.rating, a.duration, a.score && `MAL ${a.score}`, a.sub && `${a.sub} sub`, a.dub && `${a.dub} dub`, a.genres.slice(0, 3).join(", ")]} />
        {a.synopsis && <p className="line-clamp-3 max-w-2xl leading-relaxed text-[#cfccd9]">{a.synopsis}</p>}
        <div className="flex flex-wrap items-center gap-2 pt-1">
          {e.loading ? <span className="skel h-11 w-44 rounded-full" /> : (resumeEp || eps[0]) && (
            <Link href={resumeEp ? watchHref({ ...play(resumeEp), lang: resume!.p.lang ?? lang }, resume!.f) : watchHref(play(eps[0]))} className={`${pill.primary} whitespace-nowrap ring-2 ring-primary ring-offset-2 ring-offset-dusk`}>
              <Play className="fill-current" />{resumeEp ? `Resume episode ${resumeEp.number}` : eps.length > 1 ? "Play episode 1" : "Play"}
            </Link>
          )}
          {!e.loading && (resumeEp || eps[0]) && <PlayOnTv className={pill.secondary} get={() => (resumeEp ? { payload: { ...play(resumeEp), lang: resume!.p.lang ?? lang }, positionMs: resume!.ms } : { payload: play(eps[0]), positionMs: 0 })} />}
          <FavButton c={{ ...animeCard(a), href: `/anime/${slug}`, sub: a.type }} />
          <div role="radiogroup" aria-label="Audio" className="seg">
            {["sub", "dub"].filter((l) => l === "sub" || a.dub).map((l) => (
              <button key={l} role="radio" aria-checked={lang === l} onClick={() => setLang(l)} className="beam">{l === "sub" ? "Japanese, subtitled" : "English dub"}</button>
            ))}
          </div>
        </div>
      </DetailHero>
      <section aria-label="Episodes" className="space-y-3">
        <div className="mx-auto max-w-[1500px] px-4 md:px-10">
          {a.seasons.length > 1
            ? <SeasonNumerals cur={`${curSeason?.title ?? a.title}, ${eps.length} episode${eps.length === 1 ? "" : "s"}`}
                items={a.seasons.map((x: any, i: number) => ({ key: x.slug, label: x.title.match(/season (\d+)/i)?.[1] ?? i + 1, name: x.title, on: x.slug === slug, href: `/anime/${x.slug}` }))} />
            : <p className="text-sm font-medium text-[#a3a1ae]">{eps.length} episode{eps.length === 1 ? "" : "s"}</p>}
        </div>
        {e.error ? <p className="px-4 text-sm text-foreground/60 md:px-10">Episodes didn't load. Reload the page to try again.</p>
          : e.loading || info.loading ? <EpisodeStripSkeleton />
          : <EpisodeStrip items={eps.map((x) => ({ n: x.number, info: kitsu.get(x.number) ?? { title: x.title }, href: watchHref(play(x)), progress: prog[`e${x.number}`], current: resume?.p.ep === x.number }))} />}
      </section>
      <MoreLike slug={slug} fallback={a.recommendations} />
    </main>
  );
}
