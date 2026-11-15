"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Cast, Pause, Play, RotateCcw, RotateCw, SkipBack, SkipForward, Square, Tv as TvIcon, X, MonitorSmartphone, GripVertical, Maximize2, Minimize2 } from "lucide-react";
import { watchHref, type Play as P } from "@/lib/core";
import { control, handedOff, handoff, lastTv, posOf, useHub, type Now, type Tv } from "@/lib/hub";
import { useSession } from "@/lib/session";
import { pill } from "@/components/app";
import { cn } from "@/lib/utils";

const clock = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = String(s % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
};
export const nowLine = (n?: Now | null) => (n ? `${n.playing ? "Playing" : "Paused"} · ${n.title}${n.subtitle ? `, ${n.subtitle}` : ""}` : "Idle");
const card = "rounded-[22px] bg-[#101016]/95 shadow-[0_24px_60px_rgb(0_0_0/0.55)] ring-1 ring-white/8 backdrop-blur-xl";

/** "Play on TV": one TV sends at once, several open a picker (last used first). `get` reads the payload at click time. */
export function PlayOnTv({ get, onSent, icon, className }: { get: () => { payload: P; positionMs: number; source?: string }; onSent?: (tv: string) => void; icon?: boolean; className?: string }) {
  const { s } = useSession(), { tvs } = useHub(), [open, setOpen] = useState(false), ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: Event) => { if (e instanceof KeyboardEvent ? e.key === "Escape" : !ref.current?.contains(e.target as Node)) setOpen(false); };
    addEventListener("pointerdown", close); addEventListener("keydown", close);
    return () => { removeEventListener("pointerdown", close); removeEventListener("keydown", close); };
  }, [open]);
  if (!tvs.length) return null;
  const go = (tv: Tv) => { const g = get(); setOpen(false); if (handoff(tv.deviceId, s.profile?.id ?? "guest", g.payload, g.positionMs, g.source)) onSent?.(tv.deviceId); };
  const one = tvs.length === 1, last = lastTv();
  return (
    <div ref={ref} className="relative">
      <button onClick={() => (one ? go(tvs[0]) : setOpen(!open))} aria-haspopup={one ? undefined : "menu"} aria-expanded={one ? undefined : open} aria-label={`Play on ${one ? tvs[0].name : "TV"}`} title={`Play on ${one ? tvs[0].name : "TV"}`} className={className}>
        <Cast />{!icon && <span>Play on {one ? tvs[0].name : "TV"}</span>}
      </button>
      {open && (
        <div role="menu" aria-label="Play on" data-tv-picker className={cn(card, "page-in fixed inset-x-2 bottom-2 z-50 p-2 text-left md:absolute md:inset-x-auto md:bottom-auto md:top-12 md:w-80", icon ? "md:right-0" : "md:left-0")}>
          <p className="px-3 pt-2 pb-1 font-display text-lg font-extrabold">Play on</p>
          {tvs.map((tv) => (
            <button key={tv.deviceId} role="menuitem" onClick={() => go(tv)} className="beam flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left hover:bg-white/8">
              <span className={cn("grid size-10 shrink-0 place-items-center rounded-full", tv.now ? "bg-primary/16 text-primary" : "bg-white/8 text-foreground/72")}><TvIcon className="size-5" /></span>
              <span className="min-w-0 flex-1"><b className="block truncate">{tv.name}</b><small className="block truncate text-xs text-[#a3a1ae]">{nowLine(tv.now)}</small></span>
              {tv.deviceId === last && <span className="shrink-0 text-[10px] font-semibold text-[#a3a1ae]">Last used</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** The remote: what the TV plays, a scrubbable bar extrapolated from `at`, transport, Stop on TV and Play here. */
export function RemotePanel({ tv, fallback, onPlayHere, onClose, className }: { tv: string; fallback?: { title: string; subtitle?: string; poster?: string }; onPlayHere?: (ms: number, n: Now | null) => void; onClose: () => void; className?: string }) {
  const { tvs } = useHub(), d = tvs.find((t) => t.deviceId === tv), now = d?.now, [last, setLast] = useState(now);
  const [, tick] = useState(0), [drag, setDrag] = useState<number | null>(null), box = useRef<HTMLElement>(null);
  if (now && now !== last) { setLast(now); setDrag(null); } // the last state still shows once the TV drops off; a fresh one carries the seek
  useEffect(() => { if (!now?.playing) return; const i = setInterval(() => tick((x) => x + 1), 500); return () => clearInterval(i); }, [now]);
  useEffect(() => box.current?.focus(), []);
  const off = !d, n = now ?? (off ? last : null), pos = drag ?? (n ? posOf(n) : 0), dur = n?.durationMs ?? 0;
  const c = (a: Parameters<typeof control>[1], v?: number) => !off && control(tv, a, v);
  const title = n?.title ?? fallback?.title ?? "", sub = n ? n.subtitle : fallback?.subtitle, poster = n?.poster ?? fallback?.poster;
  const status = off ? "TV disconnected" : !now ? `Starting on ${d.name}` : `${now.playing ? "Playing" : "Paused"} on ${d.name}`;
  const key = (e: React.KeyboardEvent) => {
    const t = e.target as HTMLElement, k = e.key;
    if (k === "Escape") { e.stopPropagation(); return onClose(); }
    if (k === " " && t.tagName !== "BUTTON") c("toggle");
    else if (k === "ArrowLeft" || k === "ArrowRight") c("seekBy", k === "ArrowLeft" ? -10_000 : 10_000);
    else return;
    e.preventDefault(); e.stopPropagation(); // the player's own hotkeys are for the local video
  };
  const btn = "beam grid size-12 shrink-0 place-items-center rounded-full text-foreground hover:bg-white/10 disabled:opacity-35 [&_svg]:size-5.5";
  return (
    <section ref={box} tabIndex={-1} onKeyDown={key} aria-label={`Remote, ${status}`} className={cn(card, "page-in relative flex w-[min(40rem,100%)] overflow-hidden outline-none", className)}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {poster ? <img src={poster} alt="" referrerPolicy="no-referrer" className="hidden w-40 flex-none object-cover sm:block" /> : <span className="hidden w-40 flex-none bg-haze sm:block" />}
      <div className="min-w-0 flex-1 space-y-3 p-5 sm:border-l-2 sm:border-dashed sm:border-white/25 md:p-6">
        <button aria-label="Close remote" onClick={onClose} className="beam absolute top-2 right-2 grid size-9 place-items-center rounded-full hover:bg-white/12"><X className="size-5" /></button>
        <p role="status" className={cn("flex items-center gap-2 pr-8 text-sm font-semibold", off ? "text-destructive" : "text-primary")}><TvIcon className="size-4" />{status}</p>
        <div className="flex gap-3">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {poster && <img src={poster} alt="" referrerPolicy="no-referrer" className="w-14 flex-none rounded-md object-cover sm:hidden" />}
          <div className="min-w-0"><h2 className="line-clamp-2 font-display text-2xl leading-tight font-extrabold md:text-3xl">{title}</h2>{sub && <p className="truncate text-sm text-[#cfcbd9]">{sub}</p>}</div>
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
          <button disabled={off} onClick={() => { c("stop"); onClose(); }} className={cn(pill.ghost, "h-10 px-4")}><Square className="size-4" />Stop on TV</button>
          {onPlayHere && <button onClick={() => { c("stop"); onPlayHere(pos, n ?? null); }} className={cn(pill.secondary, "h-10")}><MonitorSmartphone className="size-4" />Play here</button>}
        </div>
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
