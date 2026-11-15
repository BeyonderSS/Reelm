"use client";
import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import { Cast, Pause, Play, RotateCcw, RotateCw, SkipBack, SkipForward, Square, Tv as TvIcon, X, MonitorSmartphone, GripVertical, Maximize2, Minimize2,
  ChevronUp, ChevronDown, ChevronLeft, ChevronRight, ArrowLeft, House, Menu, Volume2, VolumeX, Minus, Plus, FastForward } from "lucide-react";
import { entrySub, getHistory, playKey, toPlay, watchHref, type CardData, type Play as P } from "@/lib/core";
import { control, handedOff, handoff, histMs, lastTv, posOf, useHub, useInfo, type Action, type Info, type Now, type Tv } from "@/lib/hub";
import { useApi, useSession } from "@/lib/session";
import { pill } from "@/components/app";
import { cn } from "@/lib/utils";

const clock = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = String(s % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
};
export const nowLine = (n?: Now | null) => (n ? `${n.playing ? "Playing" : "Paused"} · ${n.title}${n.subtitle ? `, ${n.subtitle}` : ""}` : "Idle");
const card = "rounded-[22px] bg-[#101016]/95 shadow-[0_24px_60px_rgb(0_0_0/0.55)] ring-1 ring-white/8 backdrop-blur-xl";

type Get = () => { payload: P; positionMs: number; source?: string };
/**
 * "Play on TV": one TV sends at once, several open a picker (last used first). With `page`, every TV also offers "Open" (the title's
 * page, no playback), so the picker always opens. `get` reads the payload at click time. The picker is portalled (cards clip it otherwise).
 */
export function PlayOnTv({ get, page, onSent, icon, className }: { get: Get; page?: () => P; onSent?: (tv: string) => void; icon?: boolean; className?: string }) {
  const { s } = useSession(), { tvs } = useHub(), [at, setAt] = useState<Record<string, string> | null>(null), ref = useRef<HTMLDivElement>(null), menu = useRef<HTMLDivElement>(null);
  const open = !!at;
  useEffect(() => {
    if (!open) return;
    menu.current?.querySelector("button")?.focus();
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent) { if (e.key === "Escape") { e.stopPropagation(); setAt(null); ref.current?.querySelector("button")?.focus(); } return; }
      if (!ref.current?.contains(e.target as Node) && !menu.current?.contains(e.target as Node)) setAt(null);
    };
    addEventListener("pointerdown", close); addEventListener("keydown", close, true); addEventListener("resize", close);
    return () => { removeEventListener("pointerdown", close); removeEventListener("keydown", close, true); removeEventListener("resize", close); };
  }, [open]);
  if (!tvs.length) return null;
  const go = (tv: Tv, play = true) => {
    setAt(null);
    const g = play ? get() : { payload: page!(), positionMs: 0 };
    if (handoff(tv.deviceId, s.profile?.id ?? "guest", g.payload, g.positionMs, g.source, play) && play) onSent?.(tv.deviceId);
  };
  const one = tvs.length === 1, last = lastTv(), name = one ? tvs[0].name : "TV";
  const toggle = () => {
    if (one && !page) return go(tvs[0]);
    if (open) return setAt(null);
    const r = ref.current!.getBoundingClientRect(), W = 336, below = r.bottom + 8 + 90 + tvs.length * 64 < innerHeight;
    setAt({ "--l": `${Math.max(8, Math.min(r.left, innerWidth - W - 8))}px`, ...(below ? { "--t": `${r.bottom + 8}px` } : { "--b": `${innerHeight - r.top + 8}px` }) });
  };
  const g = open && page ? get() : null, ep = g && (g.payload.kind === "anime" || g.payload.season) ? ` ${entrySub(g.payload).toLowerCase()}` : "";
  const tvMark = (tv: Tv) => <span className={cn("grid size-10 shrink-0 place-items-center rounded-full", tv.now ? "bg-primary/16 text-primary" : "bg-white/8 text-foreground/72")}><TvIcon className="size-5" /></span>;
  const tvName = (tv: Tv) => <span className="min-w-0 flex-1"><b className="block truncate">{tv.name}</b><small className="block truncate text-xs text-[#a3a1ae]">{tv.deviceId === last && tvs.length > 1 ? "Last used · " : ""}{nowLine(tv.now)}</small></span>;
  return (
    <div ref={ref} className="relative">
      <button onClick={toggle} aria-haspopup={one && !page ? undefined : "menu"} aria-expanded={one && !page ? undefined : open} aria-label={page ? `On ${name}: play or open` : `Play on ${name}`} title={page ? `On ${name}` : `Play on ${name}`} className={className}>
        <Cast />{!icon && <span>{page ? `On ${name}` : `Play on ${name}`}</span>}
      </button>
      {at && createPortal(
        <div ref={menu} role="menu" aria-label={page ? "On TV" : "Play on"} data-tv-picker style={at as React.CSSProperties}
          className={cn(card, "page-in fixed inset-x-2 bottom-2 z-[70] p-2 text-left md:inset-x-auto md:top-(--t) md:bottom-(--b) md:left-(--l) md:w-84")}>
          <p className="px-3 pt-2 pb-1 font-display text-lg font-extrabold">{page ? "On TV" : "Play on"}</p>
          {g && <p className="px-3 pb-2 text-xs text-[#a3a1ae]">Play {g.positionMs ? `resumes${ep} at ${clock(g.positionMs)}` : `starts${ep || " from the beginning"}`}. Open shows its page on the TV.</p>}
          {tvs.map((tv) => page ? (
            <div key={tv.deviceId} className="flex items-center gap-3 rounded-xl px-3 py-2">
              {tvMark(tv)}{tvName(tv)}
              <button role="menuitem" aria-label={`Play on ${tv.name}`} onClick={() => go(tv)} className={cn(pill.primary, "h-9 shrink-0 px-3.5 text-sm [&_svg]:size-3.5")}><Play className="fill-current" />Play</button>
              <button role="menuitem" aria-label={`Open on ${tv.name}`} onClick={() => go(tv, false)} className={cn(pill.secondary, "h-9 shrink-0 px-3.5 text-sm")}>Open</button>
            </div>
          ) : (
            <button key={tv.deviceId} role="menuitem" onClick={() => go(tv)} className="beam flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left hover:bg-white/8">
              {tvMark(tv)}{tvName(tv)}
            </button>
          ))}
        </div>, document.fullscreenElement ?? document.body)}
    </div>
  );
}

/** A search/grid card's TV action: the title from the card, resumed from this profile's history (series start at S1 E1). */
export function CardTv({ c }: { c: CardData }) {
  const { s } = useSession(), u = new URL(c.href, "http://x"), id = decodeURIComponent(u.pathname.split("/").pop() ?? "");
  const t: P = c.kind === "anime" ? { kind: "anime", id, title: c.title, poster: c.poster, ep: 1, lang: "sub" }
    : { kind: "movie", id, title: c.title, poster: c.poster, type: u.searchParams.get("type") ?? "movie", year: Number(/\b(\d{4})\b/.exec(c.sub ?? "")?.[1]) || undefined };
  const get = () => {
    const h = getHistory(s.profile?.id ?? "guest")[playKey(t)], r = h && !h.deleted ? h.payload : null;
    if (r) return { payload: t.kind === "anime" ? { ...t, ep: r.ep, epId: r.epId, lang: r.lang } : { ...t, season: r.season, episode: r.episode }, positionMs: histMs(h) };
    return { payload: t.type === "series" ? { ...t, season: 1, episode: 1 } : t, positionMs: 0 };
  };
  return <PlayOnTv icon get={get} page={() => t} className="beam grid size-9 place-items-center rounded-full bg-dusk/85 text-foreground backdrop-blur hover:bg-primary hover:text-primary-foreground [&_svg]:size-4" />;
}

const SIZES = { s: "S", m: "M", l: "L", xl: "XL" } as const, LINES = { bottom: "Bottom", raised: "Raised", higher: "Higher", top: "Top" } as const;
const SLEEP = [0, 15, 30, 45, 60, 90], SPEEDS = [50, 75, 100, 125, 150, 200];
const langName = (c: string) => { try { return new Intl.DisplayNames(["en"], { type: "language" }).of(c) ?? c; } catch { return c; } };
const note = "text-sm text-[#a3a1ae]";

function Pick<T>({ label, items, value, onPick, name, off }: { label: string; items: readonly T[]; value: T; onPick: (v: T) => void; name: (v: T) => string; off?: (v: T) => boolean }) {
  return (
    <div>
      <p className="mt-4 mb-1.5 text-sm text-[#a3a1ae] first:mt-0">{label}</p>
      <div role="radiogroup" aria-label={label} className="flex flex-wrap gap-1.5">
        {items.map((v) => <button key={String(v)} role="radio" aria-checked={v === value} disabled={off?.(v)} onClick={() => onPick(v)} className="beam opt disabled:opacity-40">{name(v)}</button>)}
      </div>
    </div>
  );
}
const Skel = () => (
  <div aria-hidden className="space-y-5">
    {[4, 3, 5].map((n, k) => <div key={k}><div className="skel mb-2 h-4 w-24 rounded-full" /><div className="flex flex-wrap gap-1.5">{[...Array(n)].map((_, j) => <span key={j} className="skel h-8 w-16 rounded-full" />)}</div></div>)}
  </div>
);
const isSeries = (i?: Info) => { const p = toPlay(i?.payload); return !!p && (p.kind === "anime" || p.type === "series"); };

/** Season tabs and the episodes of the title the TV plays (the web's own episode APIs); a pick plays it on the TV. */
function Episodes({ i, pick }: { i: Info; pick: (season: number, episode: number) => void }) {
  const p = toPlay(i.payload)!, anime = p.kind === "anime";
  const det = useApi(anime ? null : `movies/detail?id=${encodeURIComponent(p.id)}&type=series`);
  const seasons: { season: number; episodes: number }[] = det.data?.seasons ?? [];
  const [se, setSe] = useState(i.season || p.season || 1);
  const list = useApi(anime ? `anime/episodes?slug=${encodeURIComponent(p.id)}` : det.data ? `movies/episodes?id=${encodeURIComponent(p.id)}&season=${se}&title=${encodeURIComponent(det.data.title)}&year=${det.data.year ?? ""}` : null);
  const info: { n: number; title?: string }[] = anime ? (list.data ?? []).map((x: any) => ({ n: x.number, title: x.title })) : list.data?.episodes ?? [];
  const n = anime ? info.length : info.length || seasons.find((x) => x.season === se)?.episodes || 0;
  if (det.error || list.error) return <p className={note}>Episodes didn&apos;t load.</p>;
  return (
    <div className="space-y-3">
      {seasons.length > 1 && (
        <div role="tablist" aria-label="Season" className="seg">
          {seasons.map((x) => <button key={x.season} role="tab" aria-selected={x.season === se} onClick={() => setSe(x.season)} className="beam">Season {x.season}</button>)}
        </div>
      )}
      {list.loading || det.loading ? <div aria-hidden className="grid gap-1.5 sm:grid-cols-2">{[...Array(6)].map((_, k) => <span key={k} className="skel h-11 rounded-xl" />)}</div> : (
        <ol aria-label={anime ? "Episodes" : `Season ${se} episodes`} className="grid gap-1.5 sm:grid-cols-2">
          {Array.from({ length: n }, (_, k) => {
            const e = info[k], num = e?.n ?? k + 1, cur = num === i.episode && (anime || se === (i.season ?? se));
            return (
              <li key={num}><button aria-current={cur ? "true" : undefined} onClick={() => pick(anime ? i.season || 1 : se, num)}
                className="beam flex w-full items-center gap-3 rounded-xl bg-white/4 px-3 py-2 text-left hover:bg-white/9 aria-[current=true]:bg-primary/16 aria-[current=true]:text-primary">
                <b className="w-8 shrink-0 font-display text-lg tabular-nums">{num}</b><span className="min-w-0 flex-1 truncate text-sm">{e?.title || `Episode ${num}`}</span>
                {cur && <small className="text-xs font-semibold">Now</small>}
              </button></li>
            );
          })}
        </ol>
      )}
    </div>
  );
}

const PAD_KEYS: Record<string, string> = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right", Enter: "ok", Backspace: "back", Escape: "back" };
/** A round TV remote pad. Focused, it takes the keyboard: arrows, Enter = OK, Backspace/Escape = Back. */
function DPad({ send, playing }: { send: (k: string) => void; playing?: boolean }) {
  const [hit, setHit] = useState(""), t = useRef<ReturnType<typeof setTimeout>>(undefined);
  const press = (k: string) => { send(k); setHit(k); clearTimeout(t.current); t.current = setTimeout(() => setHit(""), 180); };
  const onKey = (e: React.KeyboardEvent) => {
    const k = PAD_KEYS[e.key];
    if (!k || (e.target !== e.currentTarget && (e.key === "Enter" || e.key === "Escape"))) return; // Enter/Escape on a pad button keep their usual meaning
    e.preventDefault(); e.stopPropagation(); press(k);
  };
  const arrow = (k: string, label: string, Icon: typeof ChevronUp, at: string) => (
    <button aria-label={label} onClick={() => press(k)} data-hit={hit === k || undefined}
      className={cn("beam absolute grid size-16 place-items-center rounded-full text-foreground/80 hover:bg-white/8 hover:text-foreground data-hit:bg-primary/22 data-hit:text-primary [&_svg]:size-7", at)}><Icon /></button>
  );
  const small = (k: string, label: string, el: React.ReactNode) => (
    <button aria-label={label} onClick={() => press(k)} data-hit={hit === k || undefined}
      className="beam grid w-16 justify-items-center gap-1 rounded-2xl py-2 text-[11px] font-medium text-[#a3a1ae] hover:bg-white/6 hover:text-foreground data-hit:text-primary [&_svg]:size-5">
      <span className="grid size-11 place-items-center rounded-full bg-white/8">{el}</span>{label}</button>
  );
  return (
    <div className="flex flex-col items-center gap-5 py-1">
      <div tabIndex={0} role="group" aria-label="D-pad: arrow keys move, Enter is OK, Backspace or Escape is Back" onKeyDown={onKey} data-pad
        className="beam relative size-60 shrink-0 rounded-full bg-[radial-gradient(circle_at_50%_30%,#262330,#101016)] shadow-[inset_0_2px_14px_rgb(0_0_0/0.7),0_14px_40px_rgb(0_0_0/0.45)] ring-1 ring-white/10 outline-none">
        <span aria-hidden className="absolute inset-[21%] rounded-full border-2 border-dashed border-white/12" />
        {arrow("up", "Up", ChevronUp, "top-1.5 left-1/2 -translate-x-1/2")}
        {arrow("down", "Down", ChevronDown, "bottom-1.5 left-1/2 -translate-x-1/2")}
        {arrow("left", "Left", ChevronLeft, "left-1.5 top-1/2 -translate-y-1/2")}
        {arrow("right", "Right", ChevronRight, "right-1.5 top-1/2 -translate-y-1/2")}
        <button aria-label="OK" onClick={() => press("ok")} data-hit={hit === "ok" || undefined}
          className="beam absolute top-1/2 left-1/2 grid size-22 -translate-1/2 place-items-center rounded-full bg-primary font-display text-xl font-extrabold text-primary-foreground shadow-[0_0_28px_rgb(242_180_90/0.35)] hover:bg-[#f6c57a] motion-safe:data-hit:scale-95">OK</button>
      </div>
      <div className="flex gap-1">
        {small("back", "Back", <ArrowLeft />)}{small("home", "Home", <House />)}{small("menu", "Menu", <Menu />)}
        {small("playpause", playing ? "Pause" : "Play", playing ? <Pause className="fill-current" /> : <Play className="fill-current" />)}
      </div>
    </div>
  );
}

type TabK = "subs" | "audio" | "quality" | "episodes" | "more" | "pad";
const TABS: [TabK, string][] = [["subs", "Subtitles"], ["audio", "Audio"], ["quality", "Quality"], ["episodes", "Episodes"], ["more", "More"], ["pad", "D-pad"]];
/** Which sections the TV reported (all, as skeletons, until its Info arrives). */
const shownTabs = (i: Info | undefined, late: boolean): TabK[] => !i ? (late ? ["pad"] : TABS.map((x) => x[0])) : TABS.map((x) => x[0]).filter((k) =>
  k === "subs" ? !!i.subtitles : k === "audio" ? !!(i.audio && (i.audio.tracks.length || i.audio.langs.length)) : k === "quality" ? !!(i.quality?.chips.length || i.sources?.length)
    : k === "episodes" ? isSeries(i) : k === "more" ? i.speed != null || i.sleepMin !== undefined || !!i.volume : true);

/**
 * The remote: what the TV plays (scrubbable, extrapolated from `at`), transport, Stop on TV and Play here; a banner while the TV shows
 * Skip or the next-episode countdown; then the TV's own settings in tabs. A dialog on desktop, the whole screen on phones.
 */
export function RemotePanel({ tv, fallback, onPlayHere, onClose, className }: { tv: string; fallback?: { title: string; subtitle?: string; poster?: string }; onPlayHere?: (ms: number, n: Now | null) => void; onClose: () => void; className?: string }) {
  const { tvs } = useHub(), d = tvs.find((t) => t.deviceId === tv), now = d?.now, [last, setLast] = useState(now), info = useInfo(tv);
  const [, tick] = useState(0), [drag, setDrag] = useState<number | null>(null), box = useRef<HTMLElement>(null), uid = useId();
  const [tab, setTab] = useState<TabK>(), [late, setLate] = useState(false);
  if (now && now !== last) { setLast(now); setDrag(null); } // the last state still shows once the TV drops off; a fresh one carries the seek
  useEffect(() => { if (!now?.playing && now?.nextInMs == null) return; const i = setInterval(() => tick((x) => x + 1), 500); return () => clearInterval(i); }, [now]);
  useEffect(() => box.current?.focus(), []);
  useEffect(() => { if (info) return; const t = setTimeout(() => setLate(true), 5000); return () => clearTimeout(t); }, [info]);
  const off = !d, n = now ?? (off ? last : null), pos = drag ?? (n ? posOf(n) : 0), dur = n?.durationMs ?? 0;
  const c = (a: Action, v?: number, arg?: unknown) => !off && control(tv, a, v, arg);
  const title = n?.title ?? fallback?.title ?? "", sub = n ? n.subtitle : fallback?.subtitle, poster = n?.poster ?? fallback?.poster;
  const status = off ? "TV disconnected" : !now ? `${fallback ? "Starting" : "Idle"} on ${d.name}` : `${now.playing ? "Playing" : "Paused"} on ${d.name}`;
  const shown = shownTabs(info, late), cur = tab && shown.includes(tab) ? tab : shown[0];
  const skip = now?.skip, nextLeft = now?.nextInMs != null ? Math.max(0, now.nextInMs - (now.playing ? Date.now() - now.at : 0)) : null;
  const key = (e: React.KeyboardEvent) => {
    const t = e.target as HTMLElement, k = e.key;
    if (k === "Escape") { e.stopPropagation(); return onClose(); }
    if (t.closest("[role=tablist],[role=radiogroup],[data-pad]")) return;
    if (k === " " && t.tagName !== "BUTTON") c("toggle");
    else if (k === "ArrowLeft" || k === "ArrowRight") c("seekBy", k === "ArrowLeft" ? -10_000 : 10_000);
    else return;
    e.preventDefault(); e.stopPropagation(); // the player's own hotkeys are for the local video
  };
  const tabKey = (e: React.KeyboardEvent) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const k = shown[(shown.indexOf(cur) + step + shown.length) % shown.length];
    setTab(k); document.getElementById(`${uid}-${k}`)?.focus();
  };
  const S = info?.subtitles, A = info?.audio, Q = info?.quality, src = info?.sources ?? [], V = info?.volume;
  const btn = "beam grid size-12 shrink-0 place-items-center rounded-full text-foreground hover:bg-white/10 disabled:opacity-35 [&_svg]:size-5.5";
  return (
    <section ref={box} tabIndex={-1} onKeyDown={key} aria-label={`Remote, ${status}`}
      className={cn(card, "page-in relative isolate flex w-[min(60rem,100%)] flex-col overflow-hidden outline-none max-md:fixed max-md:inset-0 max-md:rounded-none max-md:pt-[env(safe-area-inset-top)] md:grid md:h-[min(90dvh,44rem)] md:grid-cols-[23rem_minmax(0,1fr)]", className)}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {poster && <img src={poster} alt="" aria-hidden referrerPolicy="no-referrer" className="pointer-events-none absolute -z-10 h-72 w-full object-cover opacity-30 blur-3xl saturate-150 md:h-full md:w-[23rem]" />}
      <button aria-label="Close remote" onClick={onClose} className="beam absolute top-[max(env(safe-area-inset-top),8px)] right-2 z-10 grid size-9 place-items-center rounded-full hover:bg-white/12 md:top-2"><X className="size-5" /></button>
      <div className="shrink-0 space-y-3 p-5 md:overflow-y-auto md:border-r-2 md:border-dashed md:border-white/20 md:p-6">
        <p role="status" className={cn("flex items-center gap-2 pr-8 text-sm font-semibold", off ? "text-destructive" : "text-primary")}><TvIcon className="size-4" />{status}</p>
        <div className="flex gap-3 md:flex-col">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {poster ? <img src={poster} alt="" referrerPolicy="no-referrer" className="w-14 flex-none rounded-md object-cover shadow-[0_12px_30px_rgb(0_0_0/0.5)] md:w-28 md:rounded-lg" /> : <span className="hidden h-42 w-28 rounded-lg bg-haze md:block" />}
          <div className="min-w-0"><h2 className="line-clamp-2 font-display text-2xl leading-tight font-extrabold md:text-3xl">{title || "Nothing playing"}</h2>{sub && <p className="truncate text-sm text-[#cfcbd9]">{sub}</p>}</div>
        </div>
        <div>
          <input type="range" aria-label="Position" min={0} max={dur || 1} step={1000} value={Math.min(pos, dur || 1)} disabled={off || !dur}
            onChange={(e) => setDrag(Number(e.target.value))} onPointerUp={(e) => c("seekTo", Number(e.currentTarget.value))}
            className="w-full accent-primary disabled:opacity-40" />
          <div className="flex justify-between text-xs text-[#a3a1ae] tabular-nums"><span>{clock(pos)}</span><span>{dur ? `-${clock(dur - pos)}` : "--:--"}</span></div>
        </div>
        <div className="flex items-center justify-center gap-1 sm:gap-2">
          <button aria-label="Previous episode" disabled={off || !n?.hasPrev} onClick={() => c("prev")} className={btn}><SkipBack /></button>
          <button aria-label="Back 10 seconds" disabled={off || !now} onClick={() => c("seekBy", -10_000)} className={btn}><RotateCcw /></button>
          <button aria-label={now?.playing ? "Pause" : "Play"} disabled={off || !now} onClick={() => c("toggle")} className="beam grid size-16 place-items-center rounded-full bg-primary text-primary-foreground hover:bg-[#f6c57a] disabled:opacity-40 [&_svg]:size-7">
            {now?.playing ? <Pause className="fill-current" /> : <Play className="fill-current" />}</button>
          <button aria-label="Forward 10 seconds" disabled={off || !now} onClick={() => c("seekBy", 10_000)} className={btn}><RotateCw /></button>
          <button aria-label="Next episode" disabled={off || !n?.hasNext} onClick={() => c("next")} className={btn}><SkipForward /></button>
        </div>
        <div className="flex flex-wrap gap-2">
          <button disabled={off || !now} onClick={() => { c("stop"); onClose(); }} className={cn(pill.ghost, "h-10 px-4")}><Square className="size-4" />Stop on TV</button>
          {onPlayHere && <button onClick={() => { c("stop"); onPlayHere(pos, n ?? null); }} className={cn(pill.secondary, "h-10")}><MonitorSmartphone className="size-4" />Play here</button>}
        </div>
      </div>
      <div className="flex min-h-0 flex-1 flex-col">
        {!off && (skip || nextLeft != null) && (
          <div role="status" className="page-in flex shrink-0 flex-wrap items-center gap-3 border-t-2 border-dashed border-white/15 bg-primary/8 px-5 py-3 md:border-t-0 md:border-b-2 md:pr-14">
            {skip && <button onClick={() => c("skip")} className={cn(pill.primary, "h-12 px-7 text-base [&_svg]:size-5")}><FastForward className="fill-current" />{skip.label}</button>}
            {nextLeft != null && <>
              <div className="min-w-0 flex-1 basis-40">
                <p className="font-display font-bold">Next episode <span aria-hidden className="tabular-nums">in {Math.ceil(nextLeft / 1000)} s</span><span className="sr-only">starts soon</span></p>
                <div className="track-bar mt-1.5"><i style={{ width: `${Math.min(100, Math.max(0, 100 - nextLeft / 100))}%` }} /></div>
              </div>
              <button onClick={() => c("next")} className={cn(pill.primary, "h-10 px-4 text-sm")}><SkipForward />Play now</button>
              <button onClick={() => c("cancelNext")} className={cn(pill.ghost, "h-10 px-4 text-sm")}>Watch credits</button>
            </>}
          </div>
        )}
        <div role="tablist" aria-label="Remote" onKeyDown={tabKey} className="no-scrollbar flex shrink-0 gap-1 overflow-x-auto border-y-2 border-dashed border-white/15 px-3 py-2 md:border-t-0 md:pr-14">
          {TABS.filter(([k]) => shown.includes(k)).map(([k, label]) => (
            <button key={k} id={`${uid}-${k}`} role="tab" aria-selected={cur === k} aria-controls={`${uid}-panel`} tabIndex={cur === k ? 0 : -1} onClick={() => setTab(k)}
              className={cn("beam shrink-0 rounded-xl px-3.5 py-2 text-sm", cur === k ? "bg-primary font-semibold text-primary-foreground" : "text-[#cfcbd9] hover:bg-white/6")}>{label}</button>
          ))}
        </div>
        <fieldset disabled={off} id={`${uid}-panel`} role="tabpanel" aria-labelledby={`${uid}-${cur}`} className="min-h-0 min-w-0 flex-1 overflow-y-auto p-5 md:p-6">
          {!info && !late && cur !== "pad" ? <><span role="status" className="sr-only">Asking the TV for its settings</span><Skel /></>
          : cur === "subs" && S ? <>
            <Pick label="Subtitles" items={[null, ...S.tracks.map((x) => x.id)]} value={S.on ? S.selected : null} name={(id) => (id == null ? "Off" : S.tracks.find((x) => x.id === id)?.label ?? id)} onPick={(id) => c("subs", undefined, { track: id })} />
            {!S.tracks.length && <p className={cn(note, "mt-2")}>This stream has no subtitles.</p>}
            <Pick label="Size" items={Object.keys(SIZES) as (keyof typeof SIZES)[]} value={S.size} name={(k) => SIZES[k]} onPick={(v) => c("subSize", undefined, v)} />
            <Pick label="Position" items={Object.keys(LINES) as (keyof typeof LINES)[]} value={S.position} name={(k) => LINES[k]} onPick={(v) => c("subPos", undefined, v)} />
          </> : cur === "audio" && A ? <>
            {A.langs.length > 0 && <Pick label="Language" items={[null, ...A.langs]} value={A.lang} name={(l) => (l ? langName(l) : "Any")} onPick={(l) => c("audioLang", undefined, l)} />}
            {A.tracks.length > 0 && <Pick label="Track" items={A.tracks.map((x) => x.id)} value={A.selected ?? ""} name={(id) => A.tracks.find((x) => x.id === id)?.label ?? id} onPick={(id) => c("audio", undefined, { track: id })} />}
          </> : cur === "quality" && info ? <>
            {Q && Q.chips.length > 0 && <>
              <Pick label="Quality" items={[0, ...Q.chips.map((x) => x.height)]} value={Q.current} onPick={(h) => c("quality", h)} off={(h) => !!h && !Q.chips.find((x) => x.height === h)?.playable}
                name={(h) => { const x = Q.chips.find((y) => y.height === h); return !x ? "Auto" : `${h}p · ${x.playable ? x.count : "not supported"}`; }} />
              {Q.chips.some((x) => !x.playable) && <p className={cn(note, "mt-2 text-xs")}>Greyed out: this TV can&apos;t decode those files.</p>}
            </>}
            {src.length > 0 && <>
              <p className="mt-4 mb-1.5 text-sm text-[#a3a1ae]">Source</p>
              <div role="radiogroup" aria-label="Source" className="space-y-1">
                {src.map((x) => (
                  <button key={x.id} role="radio" aria-checked={x.id === info.source} disabled={!x.playable} onClick={() => c("source", undefined, { id: x.id })}
                    className="beam flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left hover:bg-white/6 disabled:opacity-40 aria-checked:bg-primary/16 aria-checked:text-primary">
                    <b className="w-14 shrink-0 font-display tabular-nums">{x.height ? `${x.height}p` : "Auto"}</b><span className="min-w-0 flex-1 truncate text-sm">{x.label}</span>
                    {!x.playable ? <small className="text-xs">Not supported</small> : x.id === info.source && <small className="text-xs font-semibold">Playing</small>}
                  </button>
                ))}
              </div>
            </>}
          </> : cur === "episodes" && info ? <Episodes key={tv} i={info} pick={(season, episode) => c("episode", undefined, { season, episode })} />
          : cur === "more" && info ? <>
            {info.speed != null && <Pick label="Speed" items={info.speeds?.length ? info.speeds : SPEEDS} value={info.speed} name={(v) => `${v / 100}×`} onPick={(v) => c("speed", v)} />}
            {info.sleepMin !== undefined && <Pick label={info.sleepMin ? `Sleep timer · stops in ${info.sleepMin} min` : "Sleep timer"} items={SLEEP} value={info.sleepMin ?? 0} name={(v) => (v ? `${v} min` : "Off")} onPick={(v) => c("sleep", v)} />}
            {V && <div>
              <p className="mt-4 mb-1.5 text-sm text-[#a3a1ae]">Volume</p>
              <div className="flex items-center gap-2">
                <button aria-label={V.muted ? "Unmute" : "Mute"} aria-pressed={V.muted} onClick={() => c("volume", undefined, V.muted ? "unmute" : "mute")} className={cn(btn, "size-11 bg-white/6 aria-pressed:text-primary")}>{V.muted ? <VolumeX /> : <Volume2 />}</button>
                <button aria-label="Volume down" onClick={() => c("volume", undefined, "down")} className={cn(btn, "size-11 bg-white/6")}><Minus /></button>
                <div role="meter" aria-label="TV volume" aria-valuemin={0} aria-valuemax={V.max} aria-valuenow={V.level} className="track-bar h-2 min-w-16 flex-1"><i className={cn(V.muted && "opacity-35")} style={{ width: `${V.max ? (V.level / V.max) * 100 : 0}%` }} /></div>
                <button aria-label="Volume up" onClick={() => c("volume", undefined, "up")} className={cn(btn, "size-11 bg-white/6")}><Plus /></button>
                <span className="w-14 text-right text-sm text-[#cfcbd9] tabular-nums">{V.muted ? "Muted" : `${V.level}/${V.max}`}</span>
              </div>
            </div>}
          </> : <>
            {late && !info && <p className={cn(note, "mb-4")}>This TV didn&apos;t send its settings. Update Reelm on the TV for subtitles, audio, quality and episodes here.</p>}
            <DPad send={(k) => c("key", undefined, k)} playing={now?.playing} />
          </>}
        </fieldset>
      </div>
    </section>
  );
}

/** The handed-off title, moved to the episode the TV shows now (its subtitle reads "S2 · E5 · …" or "Episode 5"). */
export const hereFor = (p: P | null, n: Now): P | null => {
  if (!p || !n.title.toLowerCase().includes(p.title.toLowerCase().slice(0, 12))) return null;
  const m = n.subtitle?.match(/(?:S(\d+)\D+)?E(?:pisode )?(\d+)/);
  if (!m) return p;
  return p.kind === "anime" ? { ...p, ep: +m[2], epId: +m[2] === p.ep ? p.epId : undefined } : { ...p, season: m[1] ? +m[1] : p.season, episode: +m[2] };
};

/** "Playing on <TV>": a mini bar while any TV on the account plays; tapping opens the remote. Above the tab bar, or up top where
 *  there is none (the player's own controls own the bottom). */
type Dock = { side: "left" | "right"; y: number; min: boolean };
const DOCK = "reelm.tvBar";
const readDock = (raised?: boolean): Dock => {
  try { const d = JSON.parse(localStorage.getItem(DOCK) ?? ""); if (d && (d.side === "left" || d.side === "right") && typeof d.y === "number") return { side: d.side, y: d.y, min: !!d.min }; } catch {}
  return { side: "right", y: raised ? 0.82 : 0.86, min: false }; // y = fraction of the free height, so it survives resizes
};
const saveDock = (d: Dock) => { try { localStorage.setItem(DOCK, JSON.stringify(d)); } catch {} };

/**
 * "Playing on <TV>": a floating card you can drag anywhere; on release it snaps to the nearest side, keeping its height.
 * Minimised it is a reel: the poster in a disc with the episode's progress as an amber ring (tap = play/pause).
 * It only covers its own box. Position and size are remembered per browser.
 */
export function TvBar({ raised }: { raised?: boolean }) {
  const { tvs, remoteHere } = useHub(), router = useRouter(), [open, setOpen] = useState<string | null>(null);
  const [dock, setDock] = useState<Dock>(() => (typeof window === "undefined" ? { side: "right", y: 0.86, min: false } : readDock(raised)));
  const [drag, setDrag] = useState<{ x: number; y: number } | null>(null), box = useRef<HTMLDivElement>(null);
  const dragged = useRef(false);
  const tv = tvs.find((t) => t.now && t.deviceId !== remoteHere);
  if (open) {
    const n = tvs.find((t) => t.deviceId === open)?.now, p = n && hereFor(handedOff(open), n);
    return (
      <div role="dialog" aria-modal aria-label="TV remote" className="fixed inset-0 z-50 flex items-end justify-center bg-black/60 p-2 backdrop-blur-sm md:items-center" onClick={(e) => e.target === e.currentTarget && setOpen(null)}>
        <RemotePanel tv={open} onClose={() => setOpen(null)} onPlayHere={p ? (ms) => { setOpen(null); router.push(`${watchHref(p)}&t=${Math.round(ms / 1000)}`); } : undefined} />
      </div>
    );
  }
  if (!tv?.now) return null;
  const n = tv.now, f = n.durationMs ? Math.min(1, posOf(n) / n.durationMs) : 0, M = 12;
  const update = (d: Partial<Dock>) => setDock((o) => { const v = { ...o, ...d }; saveDock(v); return v; });

  // drag: press anywhere on the card, then follow the pointer at window level (it leaves the card at once); 5 px keeps taps as taps
  const down = (e: React.PointerEvent) => {
    if (e.button !== 0 || (e.target as HTMLElement).closest("[data-nodrag]")) return;
    const r = box.current!.getBoundingClientRect(), dx = e.clientX - r.left, dy = e.clientY - r.top, sx = e.clientX, sy = e.clientY;
    let at: { x: number; y: number } | null = null;
    const move = (ev: PointerEvent) => {
      if (ev.pointerId !== e.pointerId || (!at && Math.hypot(ev.clientX - sx, ev.clientY - sy) < 5)) return;
      at = { x: Math.min(innerWidth - r.width - M, Math.max(M, ev.clientX - dx)), y: Math.min(innerHeight - r.height - M, Math.max(M, ev.clientY - dy)) };
      setDrag(at);
    };
    const up = (ev: PointerEvent) => {
      if (ev.pointerId !== e.pointerId) return;
      removeEventListener("pointermove", move); removeEventListener("pointerup", up); removeEventListener("pointercancel", up);
      if (!at) return;
      dragged.current = true; setTimeout(() => (dragged.current = false), 0); // the click that ends a drag is not a tap
      const reserve = raised && innerWidth < 768 ? 80 : 0; // the mobile tab bar
      const free = Math.max(1, innerHeight - r.height - 2 * M - reserve);
      update({ side: at.x + r.width / 2 < innerWidth / 2 ? "left" : "right", y: Math.min(1, Math.max(0, (at.y - M) / free)) });
      setDrag(null);
    };
    addEventListener("pointermove", move); addEventListener("pointerup", up); addEventListener("pointercancel", up);
  };
  const swallowClickAfterDrag = (e: React.MouseEvent) => { if (dragged.current) { e.preventDefault(); e.stopPropagation(); } };

  const pos: React.CSSProperties = drag
    ? { left: drag.x, top: drag.y }
    : { [dock.side]: M, top: `calc(${M}px + (100dvh - ${2 * M}px - var(--h, 0px) - var(--reserve, 0px)) * ${dock.y})` };
  const shell = cn("fixed z-40 touch-none select-none", raised ? "max-md:[--reserve:80px]" : "[body:has([data-tv-picker])_&]:hidden", !drag && "motion-safe:transition-[top,left,right] motion-safe:duration-300 motion-safe:ease-out", drag ? "cursor-grabbing" : "cursor-grab");
  const label = `${n.playing ? "Playing" : "Paused"} on ${tv.name}`;
  const toggle = <button data-nodrag aria-label={n.playing ? "Pause on TV" : "Play on TV"} onClick={() => control(tv.deviceId, "toggle")}
    className="beam grid size-11 flex-none place-items-center rounded-full bg-primary text-primary-foreground [&_svg]:size-5">{n.playing ? <Pause className="fill-current" /> : <Play className="fill-current" />}</button>;

  if (dock.min) {
    const R = 27, C = 2 * Math.PI * R;
    return (
      <div ref={box} role="group" aria-label={`${label}: ${n.title}`} style={{ ...pos, ["--h" as string]: "64px" }} className={cn(shell, "group size-16")}
        onPointerDown={down} onClickCapture={swallowClickAfterDrag}>
        <svg viewBox="0 0 64 64" aria-hidden className="absolute inset-0 -rotate-90">
          <circle cx="32" cy="32" r={R} fill="#101016" stroke="rgb(255 255 255 / 0.12)" strokeWidth="4" />
          <circle cx="32" cy="32" r={R} fill="none" stroke="var(--color-primary)" strokeWidth="4" strokeLinecap="round" strokeDasharray={C} strokeDashoffset={C * (1 - f)} />
        </svg>
        <button aria-label={n.playing ? `Pause ${n.title} on ${tv.name}` : `Play ${n.title} on ${tv.name}`} onClick={() => control(tv.deviceId, "toggle")}
          className="beam absolute inset-[7px] grid place-items-center overflow-hidden rounded-full bg-haze shadow-[0_12px_30px_rgb(0_0_0/0.55)] [&_svg]:size-5">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {n.poster && <img src={n.poster} alt="" referrerPolicy="no-referrer" draggable={false} className="absolute inset-0 size-full object-cover" />}
          <span className={cn("relative grid size-8 place-items-center rounded-full bg-black/55 text-white backdrop-blur-sm", n.playing && "opacity-0 group-hover:opacity-100 group-focus-within:opacity-100")}>{n.playing ? <Pause className="fill-current" /> : <Play className="fill-current" />}</span>
        </button>
        <button data-nodrag aria-label="Expand" onClick={() => update({ min: false })}
          className="beam absolute -top-1 grid size-6 place-items-center rounded-full bg-[#101016] text-[#a3a1ae] ring-1 ring-white/12 hover:text-foreground [&_svg]:size-3.5 group-hover:opacity-100 focus-visible:opacity-100 md:opacity-0"
          style={{ [dock.side === "right" ? "left" : "right"]: -4 }}><Maximize2 /></button>
      </div>
    );
  }
  return (
    <div ref={box} role="group" aria-label={label} style={{ ...pos, ["--h" as string]: "76px" }} className={cn(card, shell, "flex w-[min(26rem,calc(100vw-24px))] items-center gap-2 overflow-hidden p-2 pr-2.5")}
      onPointerDown={down} onClickCapture={swallowClickAfterDrag}>
      <GripVertical aria-hidden className="size-4 flex-none text-white/25" />
      <button onClick={() => setOpen(tv.deviceId)} aria-label={`${label}: ${n.title}. Open remote`} className="beam flex min-w-0 flex-1 items-center gap-3 rounded-xl text-left">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        {n.poster ? <img src={n.poster} alt="" referrerPolicy="no-referrer" draggable={false} className="h-14 w-10 flex-none rounded-md object-cover" /> : <span className="grid h-14 w-10 flex-none place-items-center rounded-md bg-haze"><TvIcon className="size-4" /></span>}
        <span className="min-w-0 flex-1">
          <small className="block truncate text-xs font-semibold text-primary">{label}</small>
          <b className="block truncate font-display">{n.title}</b>
          {n.subtitle && <small className="block truncate text-xs text-[#a3a1ae]">{n.subtitle}</small>}
        </span>
      </button>
      {toggle}
      <span data-nodrag className="flex flex-none flex-col gap-1">
        <button aria-label="Open remote" onClick={() => setOpen(tv.deviceId)} className="beam grid size-7 place-items-center rounded-full text-[#a3a1ae] hover:bg-white/8 hover:text-foreground [&_svg]:size-3.5"><Maximize2 /></button>
        <button aria-label="Minimise" onClick={() => update({ min: true })} className="beam grid size-7 place-items-center rounded-full text-[#a3a1ae] hover:bg-white/8 hover:text-foreground [&_svg]:size-3.5"><Minimize2 /></button>
      </span>
      <i aria-hidden className="absolute inset-x-0 bottom-0 h-0.5 bg-white/10"><i className="block h-full bg-primary" style={{ width: `${f * 100}%` }} /></i>
    </div>
  );
}
