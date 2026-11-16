"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Info, Play } from "lucide-react";
import { animeCard, ContinueStrip, Marquee, kidsOk, mediaCard, pill, Rail, Row, useContinue } from "@/components/app";
import { Logo, Meta, PeekProvider, SoundButton, TonightsReel, TrailerSurface, useTrailer } from "@/components/trailers";
import { keyOf, useOverlayOpen, usePreviewGate } from "@/lib/trailer";
import { audioLang, HomeRecs } from "@/components/home-recs";
import { INDIAN } from "@/lib/recs-rank";
import { ShimmerButton } from "@/components/ui/shimmer-button";
import { useApi, useSession } from "@/lib/session";
import { entryHref, entrySub, shows, syncFavs, watchHref, type CardData, type Entry } from "@/lib/core";
import { cn } from "@/lib/utils";
import { useRouter } from "next/navigation";

type Slide = { key: string; title: string; poster?: string; backdrop?: string; desc?: string; rating?: string | number; year?: number; genres?: string[]; extra: (string | number | undefined)[]; href: string; play?: string; cta?: string; anime?: string };
const SLIDE_MS = 8000, DWELL_MS = 1500;

/** What you were last watching leads the marquee, with Resume as the first action. */
const resumeSlide = (e: Entry): Slide => {
  const p = e.payload, series = p.kind === "movie" && p.type === "series";
  return {
    key: `cw:${e.key}`, title: p.title || "Untitled", poster: p.poster, year: p.year, extra: [p.kind === "anime" ? "Anime" : series ? "Series" : "Movie", p.kind === "movie" && !series ? undefined : entrySub(p)],
    href: p.kind === "anime" ? `/anime/${p.id}` : `/title/${encodeURIComponent(p.id)}?type=${p.type ?? "movie"}`, play: entryHref(e),
    cta: p.kind === "anime" ? `Resume episode ${p.ep}` : series ? `Resume S${p.season} E${p.episode}` : "Resume", anime: p.kind === "anime" ? p.id : undefined,
  };
};
const movieSlide = (m: any): Slide => ({
  key: m.id, title: m.title, poster: m.poster, backdrop: m.backdrop, desc: m.description, rating: m.rating, year: m.year, genres: m.genres, extra: ["Movie"], href: mediaCard(m).href,
  play: watchHref({ kind: "movie", id: m.id, title: m.title, poster: m.poster, season: 0, episode: 0, type: "movie", year: m.year }),
});

/** Is at least half of `el` on screen, in a visible tab? */
function useOnScreen(el: React.RefObject<HTMLElement | null>) {
  const [half, setHalf] = useState(true), [tab, setTab] = useState(true);
  useEffect(() => {
    const io = new IntersectionObserver(([e]) => setHalf(e.intersectionRatio >= 0.5), { threshold: [0, 0.5, 1] });
    if (el.current) io.observe(el.current);
    const vis = () => setTab(!document.hidden);
    document.addEventListener("visibilitychange", vis);
    return () => { io.disconnect(); document.removeEventListener("visibilitychange", vis); };
  }, [el]);
  return half && tab;
}

/** The living hero: a card with the title's art, logo (or the marquee type) and ★ meta. Resting 1.5 s on a slide fades its trailer in
 *  (muted, speaker top-right, amber progress along the bottom); it stops when half scrolled away or a trailer card opens, and the next slide follows its end.
 *  Without a trailer the slides advance every 8 s, paused on hover or focus. */
function Hero({ slides }: { slides: Slide[] }) {
  const [i, setI] = useState(0), [hold, setHold] = useState(false);
  const router = useRouter();
  const cur = slides[i % slides.length];
  const detail = useApi(cur?.anime ? `anime/detail?slug=${cur.anime}` : null).data;
  const t = useTrailer(keyOf({ href: cur.href, title: cur.title, sub: String(cur.year ?? detail?.year ?? "") }));
  const card = useRef<HTMLDivElement>(null), onScreen = useOnScreen(card), gate = usePreviewGate(), over = useOverlayOpen();
  const [go, setGo] = useState(false), [live, setLive] = useState(false), [sound, setSound] = useState(false), [prog, setProg] = useState(0), [done, setDone] = useState<string[]>([]);
  const can = gate.auto && onScreen && !over && !!t?.ytIds.length && !done.includes(cur.key);
  useEffect(() => {
    setGo(false); setSound(false); setProg(0);
    if (!can) return;
    const d = setTimeout(() => setGo(true), DWELL_MS);
    return () => clearTimeout(d);
  }, [cur.key, can]);
  useEffect(() => {
    if (hold || go || slides.length < 2) return;
    const t = setTimeout(() => setI((x) => (x + 1) % slides.length), SLIDE_MS);
    return () => clearTimeout(t);
  }, [i, hold, go, slides.length]);
  const desc = cur.desc ?? detail?.synopsis, art = t?.backdrops[0] ?? cur.backdrop;
  return (
    <section aria-roledescription="carousel" aria-label="Featured" onMouseEnter={() => setHold(true)} onMouseLeave={() => setHold(false)} onFocus={() => setHold(true)} onBlur={() => setHold(false)}
      className="relative px-4 md:px-10">
      {/* the glow: each poster blurred into light around the card, crossfading with the slide */}
      {slides.map((sl, k) => sl.poster && (
        // eslint-disable-next-line @next/next/no-img-element
        <img key={sl.key} src={sl.poster} alt="" aria-hidden referrerPolicy="no-referrer"
          className={cn("pointer-events-none absolute -top-1/4 right-[-10%] h-[140%] w-[85%] object-cover blur-[90px] saturate-150 transition-opacity duration-[400ms] ease-dusk [mask-image:radial-gradient(closest-side,#000,transparent)]", k === i % slides.length ? "opacity-60" : "opacity-0")} />
      ))}
      <div ref={card} data-hero className="relative mx-auto h-[500px] max-w-[1500px] overflow-hidden rounded-[24px] bg-slate shadow-[0_24px_48px_-24px_rgb(10_12_18/0.6)] md:h-[min(620px,74vh)]">
        {(art ?? cur.poster) && (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={cur.key + "a"} src={art ?? cur.poster} alt="" aria-hidden referrerPolicy="no-referrer"
            className={cn("page-in absolute inset-0 size-full object-cover", !art && "md:scale-110 md:opacity-40 md:blur-2xl")} />
        )}
        {t?.ytIds.length ? <TrailerSurface key={cur.key} t={t} playing={go} sound={sound} onLive={setLive} onProgress={setProg}
          onEnded={() => { setDone((d) => [...d, cur.key]); setI((x) => (x + 1) % slides.length); }} /> : null}
        <div aria-hidden className="absolute inset-0 bg-[linear-gradient(0deg,rgb(21_23_31/0.96)_8%,rgb(21_23_31/0.4)_46%,transparent_70%)] md:bg-[linear-gradient(90deg,rgb(21_23_31/0.92),rgb(21_23_31/0.4)_45%,transparent_70%),linear-gradient(0deg,rgb(21_23_31/0.85),transparent_40%)]" />
        {live && <SoundButton on={sound} set={setSound} className="absolute top-3 right-3 md:top-5 md:right-5" />}
        {cur.poster && !art && (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={cur.key + "p"} src={cur.poster} alt="" referrerPolicy="no-referrer"
            className={cn("page-in absolute top-1/2 right-10 hidden aspect-[2/3] w-[clamp(170px,15vw,240px)] -translate-y-1/2 -rotate-3 rounded-xl object-cover shadow-[0_30px_70px_rgb(10_12_18/0.6)] ring-1 ring-white/10 transition-opacity duration-500 md:block", live && "opacity-0")} />
        )}
        <div key={cur.key} className="page-in absolute inset-x-4 bottom-4 flex flex-col items-center gap-3 text-center md:inset-x-10 md:bottom-10 md:max-w-xl md:items-start md:gap-4 md:text-left">
          <div className={cn("flex h-16 w-full items-end justify-center transition-transform duration-500 ease-dusk md:h-28 md:justify-start", live && "scale-[.85] md:origin-bottom-left")}>
            <Logo src={t?.logo} title={cur.title} className="h-full max-w-[min(240px,80%)] object-bottom md:max-w-md md:object-left-bottom"
              fallback={<Marquee text={cur.title} className="text-[clamp(34px,5.6vw,92px)]" />} />
          </div>
          <Meta rating={cur.rating ?? detail?.score} year={cur.year ?? detail?.year} genres={cur.genres ?? detail?.genres} extra={cur.extra} className="justify-center md:justify-start" />
          {desc && <p className="hidden max-w-xl text-[15px] leading-relaxed text-foreground/72 md:line-clamp-2">{desc}</p>}
          <div className="flex w-full gap-2 md:w-auto md:flex-wrap">
            <ShimmerButton onClick={() => router.push(cur.play ?? cur.href)} background="#F2B45A" shimmerColor="#fff3dc" shimmerDuration="2.6s"
              className="beam h-11 flex-1 gap-2 border-0 px-6 font-semibold text-primary-foreground md:flex-none"><Play className="size-4 fill-current" />{cur.cta ?? (cur.play ? "Play" : "Watch now")}</ShimmerButton>
            <Link href={cur.href} className={cn(pill.secondary, "flex-1 md:flex-none")}><Info />Details</Link>
          </div>
          {slides.length > 1 && (
            <div className="flex w-56 gap-1.5" role="tablist" aria-label="Featured titles">
              {slides.map((sl, k) => (
                <button key={sl.key} role="tab" aria-selected={k === i % slides.length} aria-label={sl.title} onClick={() => setI(k)} className="group h-6 flex-1 py-2.5">
                  <span className="block h-1 overflow-hidden rounded-full bg-white/20 group-hover:bg-white/35">
                    {k < i % slides.length && <span className="block h-full bg-foreground/70" />}
                    {k === i % slides.length && <span className="block h-full origin-left bg-primary" style={{ animation: `fill ${SLIDE_MS}ms linear`, animationPlayState: hold || go ? "paused" : "running" }} />}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
        <i aria-hidden className="absolute bottom-0 left-0 h-[2.5px] bg-primary" style={{ width: `${live ? prog * 100 : 0}%` }} />
      </div>
    </section>
  );
}

type Filter = "all" | "movies" | "series" | "anime";

export default function HomePage() {
  const { s } = useSession();
  useEffect(() => { if (s.token && s.profile) syncFavs(s.profile.id, s.token).catch(() => {}); }, [s.token, s.profile?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const sh = (k: Parameters<typeof shows>[1]) => shows(s.profile, k);
  // hidden kinds aren't even fetched
  const anime = useApi(sh("anime") ? "anime/home" : null), movies = useApi(sh("movies") ? "movies/catalog?key=movie/top" : null);
  const ind = useApi<{ country: string; rows: { title: string; items: any[] }[] }>(sh("movies") || sh("series") ? "india" : null);
  const [lang] = useState(audioLang); // "" on the server; only reorders rows that load client side
  const [cw, removeCw] = useContinue();
  const [f, setF] = useState<Filter>("all"), [picks, setPicks] = useState<CardData[]>([]);
  const k = s.profile?.kids;
  const is = (x: Filter) => f === "all" || f === x;
  // Indian rows lead for viewers in India or who chose an Indian audio language; otherwise they follow the Cinemeta rows
  const indiaFirst = ind.data?.country === "IN" || INDIAN.includes(lang);
  const indiaRows = (ind.data?.rows ?? []).map((r) => ({ title: r.title, items: r.items.map(mediaCard).filter((c) => kidsOk(c, k, s.profile) && (f === "all" || c.href.endsWith(f === "movies" ? "type=movie" : "type=series"))) }));
  const slides: Slide[] = cw[0] ? [resumeSlide(cw[0])] : [];
  const desi = indiaFirst ? (ind.data?.rows ?? []).flatMap((r) => r.items).filter((x) => x.backdrop && x.type === "movie" && kidsOk(mediaCard(x), k, s.profile)).slice(0, 2) : [];
  const m = ((movies.data ?? []) as any[]).filter((x) => x.backdrop && kidsOk(mediaCard(x), k)).slice(0, 3);
  const a = ((anime.data?.trending ?? []) as any[]).filter((x) => kidsOk(animeCard(x), k)).slice(0, 3);
  for (let n = 0; n < 3; n++) {
    if (desi[n]) slides.push(movieSlide(desi[n]));
    if (m[n]) slides.push(movieSlide(m[n]));
    if (a[n]) slides.push({ key: a[n].slug, title: a[n].title, poster: a[n].poster, rating: a[n].rating, extra: ["Anime"], href: `/anime/${a[n].slug}`, anime: a[n].slug });
  }
  const indian = (f === "all" || f === "movies" || f === "series") && indiaRows.map((r) => <Rail key={r.title} title={r.title} items={r.items} />);
  const loading = anime.loading || movies.loading || !s.profile;
  // the filter chips match the TV's Home filters; Live TV is its own page
  const chips = ([["all", "All"], ["movies", "Movies"], ["series", "Series"], ["anime", "Anime"]] as [Filter, string][]).filter(([x]) => x === "all" || sh(x));
  return (
    <PeekProvider>
    <main className="pb-16">
      <div role="radiogroup" aria-label="Show" className="no-scrollbar flex gap-2 overflow-x-auto px-4 pt-1 pb-3 md:px-10">
        {chips.map(([x, l]) => <button key={x} type="button" role="radio" aria-checked={f === x} onClick={() => setF(x)} className="opt flex-none">{l}</button>)}
        {sh("live") && <Link href="/live" className="opt flex-none">Live TV</Link>}
      </div>
      <TonightsReel picks={picks} />
      {loading && !slides.length ? <div className="skel mx-4 h-[500px] rounded-[24px] md:mx-10 md:h-[min(620px,74vh)]" /> : slides.length ? <Hero slides={slides} /> : <div className="h-8" />}
      <div className="relative z-10 mt-6 md:mt-8"><ContinueStrip items={cw} onRemove={removeCw} /></div>
      {f === "all" ? <HomeRecs onTop={setPicks} /> : null}
      {indiaFirst && indian}
      {sh("anime") && is("anime") && <Row title="Trending anime" path="anime/home" map={(d) => d.trending.map(animeCard)} />}
      {sh("movies") && is("movies") && <Row title="Popular movies" path="movies/catalog?key=movie/top" map={(d) => d.map(mediaCard)} />}
      {sh("series") && is("series") && <Row title="Popular series" path="movies/catalog?key=series/top" map={(d) => d.map(mediaCard)} />}
      {!indiaFirst && indian}
      {sh("anime") && is("anime") && <Row title="Top airing" path="anime/home" map={(d) => d.topAiring.map(animeCard)} />}
      {sh("anime") && is("anime") && <Row title="Most popular anime" path="anime/home" map={(d) => d.popular.map(animeCard)} />}
      {sh("anime") && is("anime") && <Row title="Latest episodes" path="anime/home" map={(d) => d.recent.map(animeCard)} />}
    </main>
    </PeekProvider>
  );
}
