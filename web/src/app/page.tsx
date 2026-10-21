"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { Info, Play } from "lucide-react";
import { animeCard, Chips, ContinueRow, RecommendedRow, kidsOk, mediaCard, pill, Row } from "@/components/app";
import { ShimmerButton } from "@/components/ui/shimmer-button";
import { useApi, useSession } from "@/lib/session";
import { watchHref } from "@/lib/core";
import { cn } from "@/lib/utils";
import { useRouter } from "next/navigation";

type Slide = { key: string; title: string; image?: string; poster?: string; desc?: string; chips: (string | number | undefined)[]; href: string; play?: string; anime?: string };
const SLIDE_MS = 8000;

/** "The projector": the backdrop crossfades to each featured title; one slide every 8 s, paused on hover or focus. */
function Hero({ slides }: { slides: Slide[] }) {
  const [i, setI] = useState(0), [hold, setHold] = useState(false);
  const router = useRouter();
  const cur = slides[i % slides.length];
  const detail = useApi(cur?.anime ? `anime/detail?slug=${cur.anime}` : null).data;
  useEffect(() => {
    if (hold || slides.length < 2) return;
    const t = setTimeout(() => setI((x) => (x + 1) % slides.length), SLIDE_MS);
    return () => clearTimeout(t);
  }, [i, hold, slides.length]);
  if (!cur) return <div className="skel -mt-16 md:-mt-20 h-[72vh] min-h-[460px]" />;
  const desc = cur.desc ?? detail?.synopsis;
  return (
    <section aria-roledescription="carousel" aria-label="Featured" onMouseEnter={() => setHold(true)} onMouseLeave={() => setHold(false)} onFocus={() => setHold(true)} onBlur={() => setHold(false)}
      className="relative -mt-16 md:-mt-20 h-[78vh] max-h-[820px] min-h-[500px] overflow-hidden">
      {slides.map((sl, k) => (
        // eslint-disable-next-line @next/next/no-img-element
        <img key={sl.key} src={sl.image ?? sl.poster} alt="" referrerPolicy="no-referrer"
          className={cn("absolute inset-0 size-full object-cover object-[50%_25%] transition-opacity duration-[400ms] ease-dusk", !sl.image && "scale-110 blur-2xl", k === i % slides.length ? "opacity-70" : "opacity-0")} />
      ))}
      <div className="absolute inset-0 bg-gradient-to-t from-dusk via-dusk/40 to-dusk/10" />
      <div className="absolute inset-0 bg-gradient-to-r from-dusk/95 via-dusk/50 to-transparent" />
      <div className="relative mx-auto flex h-full items-end gap-10 px-4 pb-14 md:px-10 md:pb-20">
        <div key={cur.key} className="page-in flex max-w-xl flex-col gap-4">
          <h1 className="font-display text-4xl leading-[1.02] font-bold tracking-tight text-balance md:text-6xl">{cur.title}</h1>
          <Chips items={[...cur.chips, ...(detail?.genres?.slice(0, 3) ?? [])]} />
          {desc && <p className="line-clamp-3 max-w-prose text-[15px] leading-relaxed text-foreground/72">{desc}</p>}
          <div className="mt-1 flex flex-wrap gap-2">
            <ShimmerButton onClick={() => router.push(cur.play ?? cur.href)} background="#F2B45A" shimmerColor="#fff3dc" shimmerDuration="2.6s"
              className="beam h-11 gap-2 border-0 px-6 font-semibold text-primary-foreground"><Play className="size-4 fill-current" />{cur.play ? "Play" : "Watch now"}</ShimmerButton>
            <Link href={cur.href} className={pill.secondary}><Info />More info</Link>
          </div>
        </div>
        {cur.poster && cur.image && (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={cur.key + "p"} src={cur.poster} alt="" referrerPolicy="no-referrer" className="page-in ml-auto hidden aspect-[2/3] w-52 rounded-xl object-cover shadow-[0_30px_80px_rgb(10_12_18/0.7)] ring-1 ring-white/10 lg:block" />
        )}
      </div>
      {slides.length > 1 && (
        <div className="absolute inset-x-4 bottom-5 flex gap-1.5 md:inset-x-10 md:w-80" role="tablist" aria-label="Featured titles">
          {slides.map((sl, k) => (
            <button key={sl.key} role="tab" aria-selected={k === i % slides.length} aria-label={sl.title} onClick={() => setI(k)} className="group h-6 flex-1 py-2.5">
              <span className="block h-1 overflow-hidden rounded-full bg-white/20 group-hover:bg-white/35">
                {k < i % slides.length && <span className="block h-full bg-foreground/70" />}
                {k === i % slides.length && <span className="block h-full origin-left bg-primary" style={{ animation: `fill ${SLIDE_MS}ms linear`, animationPlayState: hold ? "paused" : "running" }} />}
              </span>
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

export default function HomePage() {
  const { s } = useSession();
  const anime = useApi("anime/home"), movies = useApi("movies/catalog?key=movie/top");
  const k = s.profile?.kids;
  const slides: Slide[] = [];
  const m = ((movies.data ?? []) as any[]).filter((x) => x.backdrop && kidsOk(mediaCard(x), k)).slice(0, 3);
  const a = ((anime.data?.trending ?? []) as any[]).filter((x) => kidsOk(animeCard(x), k)).slice(0, 3);
  for (let n = 0; n < 3; n++) {
    if (m[n]) slides.push({ key: m[n].id, title: m[n].title, image: m[n].backdrop, poster: m[n].poster, desc: m[n].description, chips: ["Movie", m[n].year, m[n].rating && `IMDb ${m[n].rating}`], href: mediaCard(m[n]).href, play: watchHref({ kind: "movie", id: m[n].id, title: m[n].title, poster: m[n].poster, season: 0, episode: 0, type: "movie", year: m[n].year }) });
    if (a[n]) slides.push({ key: a[n].slug, title: a[n].title, poster: a[n].poster, chips: ["Anime", a[n].rating], href: `/anime/${a[n].slug}`, anime: a[n].slug });
  }
  const loading = anime.loading || movies.loading;
  return (
    <main className="pb-16">
      {loading && !slides.length ? <div className="skel -mt-16 md:-mt-20 h-[78vh] max-h-[820px] min-h-[500px]" /> : slides.length ? <Hero slides={slides} /> : <div className="h-8" />}
      <ContinueRow />
      <RecommendedRow />
      <Row title="Trending anime" path="anime/home" map={(d) => d.trending.map(animeCard)} />
      <Row title="Popular movies" path="movies/catalog?key=movie/top" map={(d) => d.map(mediaCard)} />
      <Row title="Popular series" path="movies/catalog?key=series/top" map={(d) => d.map(mediaCard)} />
      <Row title="Top airing" path="anime/home" map={(d) => d.topAiring.map(animeCard)} />
      <Row title="Most popular anime" path="anime/home" map={(d) => d.popular.map(animeCard)} />
      <Row title="Latest episodes" path="anime/home" map={(d) => d.recent.map(animeCard)} />
    </main>
  );
}
