"use client";
import { use, useEffect, useState } from "react";
import Link from "next/link";
import { Play } from "lucide-react";
import { animeCard, Chips, FavButton, Notice, pill, Rail } from "@/components/app";
import { Backdrop, DetailSkeleton, EpisodeGrid } from "@/components/detail";
import { getHistory, kids, KIDS_BLOCKED, progressOf, watchHref, type Play as P } from "@/lib/core";
import { useApi, useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

export default function Anime({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params), { s } = useSession();
  const d = useApi(`anime/detail?slug=${slug}`), e = useApi(`anime/episodes?slug=${slug}`);
  const [lang, setLang] = useState("sub");
  const [resume, setResume] = useState<{ p: P; f: number } | null>(null), [prog, setProg] = useState<Record<string, number>>({});
  useEffect(() => {
    const pid = s.profile?.id ?? "guest";
    const h = Object.values(getHistory(pid)).filter((x) => !x.deleted && x.payload.kind === "anime" && x.payload.id === slug).sort((a, b) => b.updatedAt - a.updatedAt)[0];
    setResume(h ? { p: h.payload, f: h.duration ? h.position / h.duration : 0 } : null);
    setProg(progressOf(pid));
  }, [s.profile, slug]);
  const a = d.data;
  if (d.error) return <Notice title="This title didn't load" body={d.error}><Link href="/" className={pill.secondary}>Go home</Link></Notice>;
  if (!a) return <DetailSkeleton />;
  if (s.profile?.kids && !kids.anime(a.rating, a.genres)) return <Notice title={KIDS_BLOCKED} body="Switch profile to watch this."><Link href="/profiles" className={pill.secondary}>Switch profile</Link></Notice>;
  const eps = (e.data ?? []) as any[];
  const play = (ep: any): P => ({ kind: "anime", id: slug, title: a.title, poster: a.poster, ep: ep.number, epId: ep.id, lang });
  const resumeEp = resume && eps.find((x) => x.number === resume.p.ep);
  return (
    <main className="pb-16">
      <Backdrop src={a.poster} blur>
        <div className="flex flex-col gap-6 md:flex-row md:items-end">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {a.poster && <img src={a.poster} alt="" referrerPolicy="no-referrer" className="page-in w-36 self-start rounded-xl shadow-[0_30px_80px_rgb(10_12_18/0.7)] ring-1 ring-white/10 md:w-52" />}
          <div className="max-w-2xl space-y-4">
            <h1 className="font-display text-4xl leading-[1.05] font-bold tracking-tight text-balance md:text-5xl">{a.title}</h1>
            {a.japanese && a.japanese !== a.title && <p className="text-sm text-foreground/52">{a.japanese}</p>}
            <Chips items={[a.type, a.year, a.status, a.rating, a.duration, a.score && `MAL ${a.score}`, a.sub && `${a.sub} sub`, a.dub && `${a.dub} dub`]} />
            {a.synopsis && <p className="line-clamp-4 max-w-prose leading-relaxed text-foreground/72">{a.synopsis}</p>}
            {a.genres.length > 0 && <p className="text-sm text-foreground/52">{a.genres.join(", ")}</p>}
            <div className="flex flex-wrap gap-2 pt-1">
              {e.loading ? <span className="skel h-11 w-44 rounded-full" /> : (resumeEp || eps[0]) && (
                <Link href={resumeEp ? watchHref({ ...play(resumeEp), lang: resume!.p.lang ?? lang }, resume!.f) : watchHref(play(eps[0]))} className={pill.primary}>
                  <Play className="fill-current" />{resumeEp ? `Resume episode ${resumeEp.number}` : eps.length > 1 ? "Play episode 1" : "Play"}
                </Link>
              )}
              <FavButton c={{ ...animeCard(a), href: `/anime/${slug}`, sub: a.type }} />
            </div>
          </div>
        </div>
      </Backdrop>
      <div className="space-y-8 px-4 md:px-10">
        {a.seasons.length > 1 && (
          <nav aria-label="Seasons" className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4">
            {a.seasons.map((x: any) => <Link key={x.slug} href={`/anime/${x.slug}`} aria-current={x.slug === slug ? "page" : undefined} className={cn("beam shrink-0 rounded-full px-4 py-2 text-sm font-medium", x.slug === slug ? "bg-primary text-primary-foreground" : "bg-white/8 hover:bg-white/12")}>{x.title}</Link>)}
          </nav>
        )}
        <section>
          <div className="mb-4 flex items-center gap-3">
            <h2 className="font-display text-2xl font-semibold tracking-tight">Episodes</h2>
            {eps.length > 0 && <span className="text-sm text-foreground/52">{eps.length}</span>}
            <div className="flex-1" />
            <div role="radiogroup" aria-label="Audio" className="flex rounded-full bg-white/8 p-1">
              {["sub", "dub"].filter((l) => l === "sub" || a.dub).map((l) => (
                <button key={l} role="radio" aria-checked={lang === l} onClick={() => setLang(l)} className={cn("beam rounded-full px-4 py-1.5 text-sm font-medium transition-colors", lang === l ? "bg-primary text-primary-foreground" : "text-foreground/72")}>{l === "sub" ? "Sub" : "Dub"}</button>
              ))}
            </div>
          </div>
          {e.error ? <p className="text-sm text-foreground/60">Episodes didn't load. Reload the page to try again.</p>
            : <EpisodeGrid loading={e.loading} items={eps.map((x) => ({ n: x.number, title: x.title, href: watchHref(play(x)), progress: prog[`anime:${slug}:${x.number}`], current: resume?.p.ep === x.number }))} />}
        </section>
      </div>
      <Rail title="More like this" items={a.recommendations.map(animeCard).filter((c: any) => !s.profile?.kids || kids.animeCard(c))} />
    </main>
  );
}
