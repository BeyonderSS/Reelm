"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { Info, Play } from "lucide-react";
import { animeCard, Chips, ContinueStrip, Marquee, RecommendedRow, kidsOk, mediaCard, pill, Row, useContinue } from "@/components/app";
import { ShimmerButton } from "@/components/ui/shimmer-button";
import { useApi, useSession } from "@/lib/session";
import { entryHref, entrySub, syncFavs, watchHref, type Entry } from "@/lib/core";
import { cn } from "@/lib/utils";
import { useRouter } from "next/navigation";

type Slide = { key: string; title: string; poster?: string; desc?: string; chips: (string | number | undefined)[]; href: string; play?: string; cta?: string; anime?: string };
const SLIDE_MS = 8000;

/** What you were last watching leads the marquee, with Resume as the first action. */
const resumeSlide = (e: Entry): Slide => {
  const p = e.payload, series = p.kind === "movie" && p.type === "series";
  return {
    key: `cw:${e.key}`, title: p.title || "Untitled", poster: p.poster, chips: [p.kind === "anime" ? "Anime" : series ? "Series" : "Movie", p.year, p.kind === "movie" && !series ? undefined : entrySub(p)],
    href: p.kind === "anime" ? `/anime/${p.id}` : `/title/${encodeURIComponent(p.id)}?type=${p.type ?? "movie"}`, play: entryHref(e),
    cta: p.kind === "anime" ? `Resume episode ${p.ep}` : series ? `Resume S${p.season} E${p.episode}` : "Resume", anime: p.kind === "anime" ? p.id : undefined,
  };
};

/** The marquee: the title set huge (name solid, subtitle in outline), the whole poster tilted beside it, lit by its own colours. One slide every 8 s, paused on hover or focus. */
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
  const desc = cur.desc ?? detail?.synopsis;
  return (
    <section aria-roledescription="carousel" aria-label="Featured" onMouseEnter={() => setHold(true)} onMouseLeave={() => setHold(false)} onFocus={() => setHold(true)} onBlur={() => setHold(false)}
      className="relative -mt-16 overflow-hidden md:-mt-20">
      {/* the glow: each poster blurred into light, crossfading with the slide */}
      {slides.map((sl, k) => sl.poster && (
        // eslint-disable-next-line @next/next/no-img-element
        <img key={sl.key} src={sl.poster} alt="" aria-hidden referrerPolicy="no-referrer"
          className={cn("pointer-events-none absolute -top-1/4 right-[-10%] h-[140%] w-[85%] object-cover blur-[90px] saturate-150 transition-opacity duration-[400ms] ease-dusk [mask-image:radial-gradient(closest-side,#000,transparent)]", k === i % slides.length ? "opacity-75" : "opacity-0")} />
      ))}
      <div className="relative mx-auto grid min-h-[520px] max-w-[1500px] grid-cols-[minmax(0,1fr)_auto] items-start gap-5 px-4 pt-24 pb-16 md:min-h-[600px] md:gap-12 md:px-10 md:pt-32 md:pb-24">
        <div key={cur.key} className="page-in flex min-w-0 flex-col gap-5">
          <Marquee text={cur.title} className="text-[clamp(42px,7.4vw,124px)]" />
          <div className="mt-2 flex flex-col gap-4 md:mt-8">
            <Chips items={[...cur.chips, ...(detail?.genres?.slice(0, 3) ?? [])]} />
            {desc && <p className="line-clamp-2 max-w-xl text-[15px] leading-relaxed text-foreground/72">{desc}</p>}
            <div className="flex flex-wrap gap-2">
              <ShimmerButton onClick={() => router.push(cur.play ?? cur.href)} background="#F2B45A" shimmerColor="#fff3dc" shimmerDuration="2.6s"
                className="beam h-11 gap-2 border-0 px-6 font-semibold text-primary-foreground"><Play className="size-4 fill-current" />{cur.cta ?? (cur.play ? "Play" : "Watch now")}</ShimmerButton>
              <Link href={cur.href} className={pill.secondary}><Info />Details</Link>
            </div>
            {slides.length > 1 && (
              <div className="flex w-56 gap-1.5" role="tablist" aria-label="Featured titles">
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
          </div>
        </div>
        {cur.poster && (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={cur.key + "p"} src={cur.poster} alt="" referrerPolicy="no-referrer"
            className="page-in mt-2 aspect-[2/3] w-24 rotate-[4deg] rounded-xl object-cover shadow-[0_30px_70px_rgb(0_0_0/0.6)] ring-1 ring-white/10 sm:w-36 md:mt-4 md:w-[clamp(170px,15vw,240px)] md:-rotate-3" />
        )}
      </div>
    </section>
  );
}

export default function HomePage() {
  const { s } = useSession();
  useEffect(() => { if (s.token && s.profile) syncFavs(s.profile.id, s.token).catch(() => {}); }, [s.token, s.profile?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const anime = useApi("anime/home"), movies = useApi("movies/catalog?key=movie/top");
  const [cw, removeCw] = useContinue();
  const k = s.profile?.kids;
  const slides: Slide[] = cw[0] ? [resumeSlide(cw[0])] : [];
  const m = ((movies.data ?? []) as any[]).filter((x) => x.backdrop && kidsOk(mediaCard(x), k)).slice(0, 3);
  const a = ((anime.data?.trending ?? []) as any[]).filter((x) => kidsOk(animeCard(x), k)).slice(0, 3);
  for (let n = 0; n < 3; n++) {
    if (m[n]) slides.push({ key: m[n].id, title: m[n].title, poster: m[n].poster, desc: m[n].description, chips: ["Movie", m[n].year, m[n].rating && `IMDb ${m[n].rating}`], href: mediaCard(m[n]).href, play: watchHref({ kind: "movie", id: m[n].id, title: m[n].title, poster: m[n].poster, season: 0, episode: 0, type: "movie", year: m[n].year }) });
    if (a[n]) slides.push({ key: a[n].slug, title: a[n].title, poster: a[n].poster, chips: ["Anime", a[n].rating], href: `/anime/${a[n].slug}`, anime: a[n].slug });
  }
  const loading = anime.loading || movies.loading;
  return (
    <main className="pb-16">
      {loading && !slides.length ? <div className="skel -mt-16 h-[600px] md:-mt-20" /> : slides.length ? <Hero slides={slides} /> : <div className="h-8" />}
      <div className="relative z-10 -mt-10 md:-mt-14"><ContinueStrip items={cw} onRemove={removeCw} /></div>
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
