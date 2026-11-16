"use client";
// Trailer previews on Home (TRAILER_CONTRACT §6): the one YouTube player, the lifted trailer card, Tonight's reel and its feed.
import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Play, Star, Volume2, VolumeX } from "lucide-react";
import { Chips, FavButton, pill } from "@/components/app";
import { get, watchHref, type CardData } from "@/lib/core";
import { keyOf, loadYT, PeekCtx, trailerOf, useOverlayHold, usePreviewGate, type TKey, type Trailer } from "@/lib/trailer";
import { cn } from "@/lib/utils";

/** The lookup for a key; undefined while loading or when there is no key. */
export function useTrailer(k?: TKey | null) {
  const sig = k ? JSON.stringify(k) : "";
  const [got, setGot] = useState<{ sig: string; t: Trailer | null }>();
  useEffect(() => {
    if (!sig) return;
    let live = true;
    trailerOf(JSON.parse(sig)).then((t) => live && setGot({ sig, t }));
    return () => { live = false; };
  }, [sig]);
  return got?.sig === sig ? got.t : undefined;
}

// YouTube shows its title bar, logo and a pause bezel for about 3 s after a video starts, even with controls=0, and no crop hides
// them in a 16:9 box; the art stays up until they're gone.
const CHROME_MS = 3000;
// One player page-wide: a surface that starts stops whichever one was playing.
let active: (() => void) | null = null;

/** Muted YouTube trailer filling its parent. Invisible until PLAYING (and YouTube's start-up chrome has gone), then fades in; walks ytIds past dead/blocked ones;
 *  stops at 30 s or the end (no loop) and calls onEnded once. Cropped a little so YouTube's title bar and logo stay out of frame. */
export function TrailerSurface({ t, playing, sound, onLive, onProgress, onEnded }: {
  t: Trailer; playing: boolean; sound: boolean; onLive?: (b: boolean) => void; onProgress?: (f: number) => void; onEnded?: () => void;
}) {
  const host = useRef<HTMLDivElement>(null), player = useRef<any>(null), [live, setLive] = useState(false);
  const cb = useRef({ onLive, onProgress, onEnded, sound });
  useEffect(() => { cb.current = { onLive, onProgress, onEnded, sound }; });
  useEffect(() => {
    const el = host.current;
    if (!playing || !t.ytIds.length || !el) return;
    let dead = false, i = 0, tick = 0, reveal = 0;
    const shown = (b: boolean) => { setLive(b); cb.current.onLive?.(b); };
    const stop = () => {
      if (dead) return;
      dead = true; clearInterval(tick); clearTimeout(reveal);
      try { player.current?.destroy(); } catch {}
      player.current = null; el.replaceChildren(); delete el.dataset.state; shown(false);
      if (active === stop) active = null;
    };
    const end = () => { if (!dead) { stop(); cb.current.onEnded?.(); } };
    active?.(); active = stop;
    loadYT().then((YT) => {
      if (dead) return;
      const make = () => {
        const div = document.createElement("div");
        el.replaceChildren(div); el.dataset.id = t.ytIds[i];
        player.current = new YT.Player(div, {
          host: "https://www.youtube-nocookie.com", videoId: t.ytIds[i], width: "100%", height: "100%",
          playerVars: { autoplay: 1, mute: 1, controls: 0, playsinline: 1, rel: 0, start: t.startAt, enablejsapi: 1, disablekb: 1, fs: 0, iv_load_policy: 3, origin: location.origin },
          events: {
            onReady: (e: any) => { if (dead) return; if (cb.current.sound) e.target.unMute(); else e.target.mute(); e.target.playVideo(); },
            onStateChange: (e: any) => { if (dead) return; el.dataset.state = String(e.data); if (e.data === 1 && !reveal) reveal = window.setTimeout(() => shown(true), CHROME_MS); else if (e.data === 0) end(); },
            // 100 removed, 101/150 embedding blocked, 2/5 bad id or player: try the next id
            onError: (e: any) => {
              if (dead) return;
              el.dataset.skipped = `${el.dataset.skipped ?? ""}${t.ytIds[i]}:${e.data} `;
              try { player.current?.destroy(); } catch {}
              if (++i < t.ytIds.length) make(); else end();
            },
          },
        });
      };
      make();
      tick = window.setInterval(() => {
        const p = player.current, c = p?.getCurrentTime?.(), d = p?.getDuration?.();
        if (!c || !d) return;
        const f = (c - t.startAt) / Math.max(1, Math.min(30, d - t.startAt));
        cb.current.onProgress?.(Math.min(1, Math.max(0, f)));
        if (f >= 1) end();
      }, 250);
    });
    return stop;
  }, [playing, t]);
  useEffect(() => { const p = player.current; if (p?.unMute) { if (sound) p.unMute(); else p.mute(); } }, [sound]);
  return <div ref={host} aria-hidden data-live={live || undefined} className={cn("yt pointer-events-none absolute inset-0 transition-opacity duration-[600ms] ease-dusk", live ? "opacity-100" : "opacity-0")} />;
}

export const SoundButton = ({ on, set, className }: { on: boolean; set: (b: boolean) => void; className?: string }) => (
  <button type="button" aria-label={on ? "Turn sound off" : "Turn sound on"} aria-pressed={on} onClick={(e) => { e.stopPropagation(); set(!on); }}
    className={cn("beam z-10 grid size-9 place-items-center rounded-full bg-dusk/55 backdrop-blur-md", className)}>{on ? <Volume2 className="size-4" /> : <VolumeX className="size-4" />}</button>
);

/** ★ rating, then year, any extra chips and two genres. */
export const Meta = ({ rating, year, genres, extra, className }: { rating?: string | number; year?: number; genres?: string[]; extra?: (string | number | undefined)[]; className?: string }) => (
  <div className={cn("flex flex-wrap items-center gap-1.5", className)}>
    {rating ? <span className="inline-flex items-center gap-1 pr-1 text-sm font-semibold"><Star className="size-3.5 fill-primary text-primary" />{rating}</span> : null}
    <Chips items={[year, ...(extra ?? []), ...(genres ?? []).slice(0, 2)]} />
  </div>
);

/** The title logo, or `fallback` while there is none or it fails to load. */
export function Logo({ src, title, className, fallback }: { src?: string | null; title: string; className?: string; fallback: ReactNode }) {
  const [bad, setBad] = useState<string>();
  // eslint-disable-next-line @next/next/no-img-element
  return src && bad !== src ? <img src={src} alt={title} referrerPolicy="no-referrer" onError={() => setBad(src)} className={cn("object-contain drop-shadow-[0_4px_12px_rgb(10_12_18/0.6)]", className)} /> : <>{fallback}</>;
}

/** Synopsis, year, rating and genres for a card, from its detail route (shared per page load). */
function useAbout(c: CardData) {
  const [a, setA] = useState<{ desc?: string; year?: number; rating?: string; genres?: string[] }>({});
  useEffect(() => {
    const m = c.href.match(/^\/title\/([^?]+)\?type=(\w+)|^\/anime\/([^?]+)/);
    if (!m) return;
    let live = true;
    (m[3] ? get(`anime/detail?slug=${m[3]}`) : get(`movies/detail?id=${m[1]}&type=${m[2]}`))
      .then((d: any) => live && setA({ desc: d.synopsis ?? d.description, year: d.year, rating: d.score ?? (d.rating ? String(d.rating) : undefined), genres: d.genres }))
      .catch(() => {});
    return () => { live = false; };
  }, [c.href]);
  return a;
}

/** Movies play straight away; series and anime open their page to pick an episode. */
const playHref = (c: CardData) => {
  const m = c.href.match(/^\/title\/([^?]+)\?type=movie/);
  return m ? watchHref({ kind: "movie", id: decodeURIComponent(m[1]), title: c.title, poster: c.poster, season: 0, episode: 0, type: "movie" }) : c.href;
};

type PeekAt = { c: CardData; r: DOMRect; hold: boolean };
/** Home's posters lift into a trailer card: hover 600 ms with a mouse (moving off closes it), hold 380 ms on touch (tap outside closes it). */
export function PeekProvider({ children }: { children: ReactNode }) {
  const gate = usePreviewGate(), [at, setAt] = useState<PeekAt | null>(null);
  const open = useCallback((c: CardData, el: HTMLElement, hold: boolean) => { if (hold ? gate.demand : gate.auto) setAt({ c, r: el.getBoundingClientRect(), hold }); }, [gate.auto, gate.demand]);
  return <PeekCtx.Provider value={open}>{children}{at && <PeekCard key={at.c.href + at.hold} {...at} close={() => setAt(null)} />}</PeekCtx.Provider>;
}

function PeekCard({ c, r, hold, close }: PeekAt & { close: () => void }) {
  useOverlayHold(true);
  const t = useTrailer(keyOf(c)), about = useAbout(c), box = useRef<HTMLDivElement>(null);
  const [sound, setSound] = useState(false), [prog, setProg] = useState(0), [ended, setEnded] = useState(false);
  const [pos] = useState(() => {
    if (hold) return undefined;
    const w = Math.min(innerWidth - 32, Math.max(380, r.width * 2.2)), h = w * 0.5625 + 230;
    return { width: w, left: Math.min(innerWidth - w - 16, Math.max(16, r.left + r.width / 2 - w / 2)), top: Math.min(innerHeight - h - 16, Math.max(16, r.top + r.height / 2 - h / 2)) };
  });
  // the finger that held the poster lifts over the card: that lift's click is not a tap on Play
  const up = useRef(0);
  useEffect(() => { const f = () => (up.current = Date.now()); addEventListener("pointerup", f, { once: true }); return () => removeEventListener("pointerup", f); }, []);
  const swallow = (e: React.MouseEvent) => { if (hold && (!up.current || Date.now() - up.current < 400)) { e.preventDefault(); e.stopPropagation(); } };
  useEffect(() => {
    const key = (e: KeyboardEvent) => e.key === "Escape" && close();
    // hover card: leaving both the card and the poster under it closes it, and so does scrolling
    const move = (e: PointerEvent) => {
      if (e.pointerType !== "mouse" || box.current?.contains(e.target as Node)) return;
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) close();
    };
    addEventListener("keydown", key);
    if (!hold) { addEventListener("pointermove", move); addEventListener("scroll", close, { passive: true }); }
    return () => { removeEventListener("keydown", key); removeEventListener("pointermove", move); removeEventListener("scroll", close); };
  }, [hold, r, close]);
  const card = (
    <div ref={box} role="dialog" aria-label={`${c.title} trailer`} style={pos}
      className={cn("peek-in overflow-hidden rounded-[24px] bg-slate shadow-[0_30px_60px_-20px_rgb(10_12_18/0.9),0_0_60px_-10px_rgb(242_180_90/0.25)] ring-1 ring-white/8", hold ? "w-full max-w-md" : "fixed z-50")}>
      <div className="relative aspect-video bg-dusk">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        {(t?.backdrops[0] ?? c.poster) && <img src={t?.backdrops[0] ?? c.poster} alt="" referrerPolicy="no-referrer" className="absolute inset-0 size-full object-cover" />}
        {t?.ytIds.length ? <TrailerSurface t={t} playing={!ended} sound={sound} onProgress={setProg} onEnded={() => setEnded(true)} /> : null}
        {t?.ytIds.length && !ended ? <SoundButton on={sound} set={setSound} className="absolute top-3 right-3" /> : null}
        <i className="absolute bottom-0 left-0 h-[2.5px] bg-primary" style={{ width: `${prog * 100}%` }} />
      </div>
      <div className="grid gap-2.5 p-4">
        <Logo src={t?.logo} title={c.title} className="h-10 max-w-[70%] object-left" fallback={<p className="font-display text-xl leading-tight font-bold">{c.title}</p>} />
        <Meta rating={about.rating ?? c.rating} year={about.year} genres={about.genres ?? c.genres} />
        {about.desc && <p className="line-clamp-3 text-sm leading-relaxed text-foreground/72">{about.desc}</p>}
        <div className="flex flex-wrap gap-2">
          <Link href={playHref(c)} className={cn(pill.primary, "flex-1")}><Play className="fill-current" />Play</Link>
          <FavButton c={c} />
          <Link href={c.href} className={pill.secondary}>Details</Link>
        </div>
      </div>
    </div>
  );
  return hold ? <div className="fixed inset-0 z-50 grid place-items-center bg-dusk/60 p-4 backdrop-blur-md" onClickCapture={swallow} onClick={(e) => e.target === e.currentTarget && close()}>{card}</div> : card;
}
