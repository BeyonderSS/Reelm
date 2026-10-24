"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { motion } from "motion/react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { X, Search, Tv, Library, CalendarDays, Home, Play, Heart, ChevronLeft, ChevronRight, Star, Users, Popcorn, LogOut, ShieldCheck } from "lucide-react";
import { Switch } from "@/components/ui/switch";
import { BlurFade } from "@/components/ui/blur-fade";
import { ReelMark } from "@/components/reel";
import { frac } from "@/lib/eps";
import { avatarUrl, continueWatching, EMOJI_GROUPS, entryHref, entrySub, getFavs, kids, syncFavs, recSeeds, removeHistory, syncHistory, toggleFav, type CardData, type Entry } from "@/lib/core";
import { useApi, useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

/** Link/button looks (a Link must not wrap a <button>). */
export const pill = {
  primary: "beam inline-flex h-11 items-center justify-center gap-2 rounded-full bg-primary px-6 font-semibold text-primary-foreground transition-colors hover:bg-[#f6c57a] disabled:opacity-50 [&_svg]:size-4.5",
  secondary: "beam inline-flex h-11 items-center justify-center gap-2 rounded-full bg-secondary px-5 font-medium text-foreground transition-colors hover:bg-white/12 disabled:opacity-50 [&_svg]:size-4.5",
  ghost: "beam inline-flex h-11 items-center justify-center gap-2 rounded-full px-5 font-medium text-foreground/72 transition-colors hover:bg-white/6 hover:text-foreground disabled:opacity-50",
  chip: "inline-flex h-7 items-center rounded-full bg-white/8 px-3 text-xs font-medium text-foreground/80",
};

export const Avatar = ({ id, size = 36, ring }: { id: string; size?: number; ring?: boolean }) => (
  <span className={cn("grid shrink-0 place-items-center rounded-full bg-gradient-to-b from-haze to-slate", ring ? "ring-2 ring-primary shadow-[0_0_24px_rgb(242_180_90/0.35)]" : "ring-1 ring-white/10")} style={{ width: size, height: size }}>
    {/* eslint-disable-next-line @next/next/no-img-element */}
    <img src={avatarUrl(id)} alt="" width={size} height={size} className="object-contain" style={{ width: size * 0.74, height: size * 0.74 }} />
  </span>
);

const LINKS = [["/", "Home", Home], ["/search", "Search", Search], ["/live", "Live TV", Tv], ["/schedule", "Schedule", CalendarDays], ["/library", "Library", Library]] as const;
const BARE = ["/welcome", "/signin", "/signup", "/profiles", "/watch", "/pair", "/admin"];
/** Admin mode: a per-browser switch, offered to admins only (the API enforces admin on every admin route). */
export function useAdminMode() {
  const { s } = useSession();
  const [on, setOn] = useState(false);
  useEffect(() => { try { setOn(localStorage.getItem("reelm.admin") === "1"); } catch {} }, []);
  const set = (v: boolean) => { setOn(v); try { localStorage.setItem("reelm.admin", v ? "1" : "0"); } catch {} };
  return [!!s.user?.is_admin && on, set, !!s.user?.is_admin] as const;
}
const useChrome = () => { const path = usePathname(), { s } = useSession(); return { path, s, show: !!s.profile && !BARE.some((p) => path.startsWith(p)) }; };
const active = (path: string, h: string) => (h === "/" ? path === "/" : path.startsWith(h));

export function Nav() {
  const { path, s, show } = useChrome();
  const { set } = useSession(), router = useRouter();
  const [solid, setSolid] = useState(false), [q, setQ] = useState(""), [open, setOpen] = useState(false), [menu, setMenu] = useState(false), [code, setCode] = useState("");
  const input = useRef<HTMLInputElement>(null), menuRef = useRef<HTMLDivElement>(null);
  const [adminOn, setAdminOn, isAdmin] = useAdminMode();
  useEffect(() => { const on = () => setSolid(scrollY > 24); on(); addEventListener("scroll", on, { passive: true }); return () => removeEventListener("scroll", on); }, []);
  useEffect(() => {
    if (!menu) return;
    const close = (e: Event) => { if (e instanceof KeyboardEvent ? e.key === "Escape" : !menuRef.current?.contains(e.target as Node)) setMenu(false); };
    addEventListener("pointerdown", close); addEventListener("keydown", close);
    return () => { removeEventListener("pointerdown", close); removeEventListener("keydown", close); };
  }, [menu]);
  useEffect(() => setMenu(false), [path]);
  if (!show) return null;
  const search = (e: React.FormEvent) => { e.preventDefault(); if (q.trim()) router.push(`/search?q=${encodeURIComponent(q.trim())}`); setOpen(false); setQ(""); };
  const item = "beam flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm hover:bg-white/8";
  return (
    <header className="sticky top-0 z-40 h-16 md:h-20">
      {/* the marquee front: dark at the top, fading into the art; solid once you scroll */}
      <div aria-hidden className={cn("absolute inset-0 transition-[background-color,backdrop-filter] duration-300 ease-dusk", solid ? "bg-[#0a0a0f]/85 backdrop-blur-xl" : "bg-gradient-to-b from-[#0a0a0f]/90 to-transparent")} />
      <div className="relative mx-auto flex h-full max-w-[1500px] items-center gap-2 px-4 md:gap-8 md:px-10">
        <Link href="/" className="beam flex shrink-0 items-center gap-2 rounded-full pr-2 font-display text-2xl font-extrabold tracking-tight text-primary"><ReelMark className="size-7" />Reelm</Link>
        {adminOn && <Link href="/admin" title="Admin mode is on" className="beam -ml-1 rounded-full bg-primary px-2 py-0.5 text-[10px] font-bold tracking-wide text-primary-foreground shadow-[0_0_18px_rgb(242_180_90/0.45)] md:-ml-6">ADMIN</Link>}
        <nav aria-label="Main" className="hidden flex-1 items-center gap-7 md:flex">
          {LINKS.filter(([h]) => h !== "/search").map(([h, label]) => {
            const on = active(path, h);
            return (
              <Link key={h} href={h} aria-current={on ? "page" : undefined} className={cn("beam relative rounded-md py-1 font-display text-[17px] font-semibold transition-colors", on ? "text-foreground" : "text-[#bdb9c9] hover:text-foreground")}>
                {label}
                {on && <motion.span layoutId="nav-bulbs" transition={{ type: "spring", stiffness: 420, damping: 34 }} className="bulbs absolute inset-x-0 -bottom-2" />}
              </Link>
            );
          })}
          {adminOn && <Link href="/admin" className="beam rounded-md py-1 font-display text-[17px] font-semibold text-primary">Admin</Link>}
        </nav>
        <div className="flex-1 md:hidden" />
        <form role="search" onSubmit={search} className={cn("relative hidden h-10 items-center rounded-full transition-[width,background-color] duration-300 ease-dusk md:flex", open ? "w-72 bg-white/10" : "w-10")}>
          {/* always type=button: switching to submit mid-click submitted the empty form and closed it again */}
          <button type="button" aria-label="Search" onClick={(e) => { if (open) e.currentTarget.form?.requestSubmit(); else { setOpen(true); setTimeout(() => input.current?.focus(), 50); } }}
            className={cn("beam grid size-10 shrink-0 place-items-center rounded-full", !open && "hover:bg-white/8", active(path, "/search") && !open && "text-primary")}><Search className="size-5" /></button>
          <input ref={input} aria-label="Search titles" value={q} onChange={(e) => setQ(e.target.value)} onBlur={() => !q && setOpen(false)} onKeyDown={(e) => e.key === "Escape" && (setQ(""), setOpen(false))}
            placeholder="Search anime, movies, series" tabIndex={open ? 0 : -1} className={cn("h-full min-w-0 flex-1 bg-transparent pr-4 text-sm outline-none placeholder:text-foreground/40", !open && "pointer-events-none w-0 opacity-0")} />
        </form>
        <div ref={menuRef} className="relative">
          <button aria-haspopup="menu" aria-expanded={menu} aria-label={`${s.profile!.name}, account menu`} onClick={() => setMenu(!menu)} className="beam flex items-center gap-2 rounded-full p-0.5 hover:bg-white/6 lg:pl-3">
            <span className="hidden text-sm text-foreground/72 lg:inline">{s.profile!.name}</span><Avatar id={s.profile!.avatar} size={38} ring={menu} />
          </button>
          {menu && (
            <div role="menu" className="page-in absolute top-12 right-0 w-72 rounded-[24px] bg-haze/95 p-2 shadow-[0_24px_60px_rgb(10_12_18/0.6)] ring-1 ring-white/8 backdrop-blur-2xl">
              <div className="flex items-center gap-3 px-3 py-3"><Avatar id={s.profile!.avatar} size={44} /><div className="min-w-0"><p className="truncate font-semibold">{s.profile!.name}</p><p className="truncate text-xs text-foreground/52">{s.user ? `@${s.user.username}` : "Guest, history stays in this browser"}</p></div></div>
              <div className="my-1 h-px bg-white/8" />
              <Link role="menuitem" href="/profiles" className={item}><Users className="size-4 text-foreground/60" />Switch profile</Link>
              <form className="px-3 py-2" onSubmit={(e) => { e.preventDefault(); const c = code.trim().toUpperCase(); if (c) router.push(`/watch?room=${encodeURIComponent(c)}`); }}>
                <label htmlFor="join-room" className="mb-1.5 flex items-center gap-3 text-sm"><Popcorn className="size-4 text-foreground/60" />Join watch together</label>
                <div className="flex gap-1.5"><input id="join-room" value={code} onChange={(e) => setCode(e.target.value)} placeholder="Room code" autoCapitalize="characters" className="beam h-9 min-w-0 flex-1 rounded-full bg-white/8 px-3 text-sm uppercase outline-none placeholder:normal-case placeholder:text-foreground/40" />
                  <button disabled={!code.trim()} className="beam h-9 rounded-full bg-primary px-4 text-sm font-semibold text-primary-foreground disabled:opacity-40">Join</button></div>
              </form>
              {isAdmin && <>
                <div className="my-1 h-px bg-white/8" />
                <label className={cn(item, "cursor-pointer")}><ShieldCheck className="size-4 text-foreground/60" /><span className="flex-1">Admin mode</span><Switch checked={adminOn} onCheckedChange={setAdminOn} /></label>
                {adminOn && <Link role="menuitem" href="/admin" className={cn(item, "text-primary")}><ShieldCheck className="size-4" />Open admin</Link>}
              </>}
              <div className="my-1 h-px bg-white/8" />
              <button role="menuitem" onClick={() => { set({}); router.replace("/welcome"); }} className={item}><LogOut className="size-4 text-foreground/60" />{s.token ? "Sign out" : "Leave guest mode"}</button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}

/** Mobile bottom tab bar (and the spacer that keeps content above it). */
export function TabBar() {
  const { path, show } = useChrome();
  if (!show) return null;
  return (
    <>
      <div className="h-24 md:hidden" />
      <nav aria-label="Tabs" className="fixed inset-x-0 bottom-0 z-40 border-t border-white/6 bg-dusk/85 pb-[max(env(safe-area-inset-bottom),6px)] backdrop-blur-2xl backdrop-saturate-150 md:hidden">
        <div className="mx-auto flex max-w-lg justify-around px-1">
          {LINKS.map(([h, label, Icon]) => {
            const on = active(path, h);
            return (
              <Link key={h} href={h} aria-current={on ? "page" : undefined} className={cn("flex min-w-16 flex-1 flex-col items-center gap-1 pt-2 pb-1.5 text-[11px] font-medium transition-colors", on ? "text-primary" : "text-foreground/52")}>
                <span className="relative grid h-8 w-14 place-items-center">
                  {on && <motion.span layoutId="tab-lamp" transition={{ type: "spring", stiffness: 480, damping: 36 }} className="absolute inset-0 rounded-full bg-primary/16 shadow-[0_0_18px_rgb(242_180_90/0.25)]" />}
                  <Icon className={cn("relative size-5 transition-transform duration-200", on && "scale-110")} strokeWidth={on ? 2.4 : 2} />
                </span>{label}
              </Link>
            );
          })}
        </div>
      </nav>
    </>
  );
}

export function Chips({ items }: { items: (string | number | undefined | null | false)[] }) {
  const list = items.filter(Boolean) as (string | number)[];
  return <div className="flex flex-wrap gap-1.5">{list.map((c) => <span key={String(c)} className={pill.chip}>{c}</span>)}</div>;
}

const Img = ({ src, className }: { src?: string; className?: string }) => {
  const [ok, setOk] = useState(true);
  // eslint-disable-next-line @next/next/no-img-element
  return src && ok ? <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setOk(false)} className={className} /> : <div className={cn("grid place-items-center bg-gradient-to-br from-haze to-slate", className)}><ReelMark className="size-8 opacity-30" /></div>;
};

export function PosterCard({ c, wide, fill }: { c: CardData; wide?: boolean; fill?: boolean }) {
  return (
    <div className={cn("group/rm relative shrink-0 snap-start", fill ? "w-full" : wide ? "w-64 sm:w-72" : "w-32 sm:w-40 lg:w-44")}>
    <Link href={c.href} className="group block w-full rounded-xl outline-none">
      <div className={cn("beam relative overflow-hidden rounded-xl bg-slate shadow-[0_8px_24px_rgb(10_12_18/0.5)] outline-offset-3 group-hover:outline-2 group-hover:outline-primary group-focus-visible:outline-2 group-focus-visible:outline-primary", wide ? "aspect-video" : "aspect-[2/3]")}>
        <Img src={c.poster} className="size-full object-cover transition-transform duration-500 ease-dusk group-hover:scale-[1.06]" />
        {c.rating && <span className="absolute left-2 top-2 inline-flex items-center gap-1 rounded-full bg-dusk/80 px-2 py-0.5 text-[11px] font-semibold backdrop-blur"><Star className="size-3 fill-primary text-primary" />{c.rating}</span>}
        {/* hover preview: the lamp comes on over the poster */}
        <div className="absolute inset-0 flex flex-col justify-end bg-gradient-to-t from-dusk via-dusk/60 to-transparent p-3 opacity-0 transition-opacity duration-300 group-hover:opacity-100 group-focus-visible:opacity-100">
          <span className="mb-2 grid size-10 place-items-center rounded-full bg-primary text-primary-foreground shadow-[0_0_24px_rgb(242_180_90/0.5)]"><Play className="size-4 translate-x-px fill-current" /></span>
          <p className="line-clamp-2 text-sm font-semibold leading-tight">{c.title}</p>
          {c.genres?.length ? <p className="mt-1 line-clamp-1 text-[11px] text-foreground/60">{c.genres.slice(0, 3).join(", ")}</p> : null}
        </div>
        {c.progress != null && <div className="absolute inset-x-3 bottom-2 h-1 overflow-hidden rounded-full bg-white/20"><div className="h-full rounded-full bg-primary" style={{ width: `${Math.max(4, c.progress * 100)}%` }} /></div>}
      </div>
      <p className="mt-2 line-clamp-1 text-sm font-medium text-foreground/90">{c.title}</p>
      {c.sub && <p className="line-clamp-1 text-xs text-foreground/52">{c.sub}</p>}
    </Link>
    {c.onRemove && (
      <button type="button" aria-label={`Remove ${c.title} from history`} onClick={c.onRemove}
        className="absolute right-2 top-2 grid size-7 place-items-center rounded-full bg-dusk/80 text-foreground backdrop-blur transition-opacity hover:bg-dusk focus-visible:opacity-100 md:opacity-0 md:group-hover/rm:opacity-100"><X className="size-4" /></button>
    )}
    </div>
  );
}

export function Rail({ title, items, wide, action }: { title: string; items: CardData[]; wide?: boolean; action?: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  if (!items.length) return null;
  const nudge = (d: number) => ref.current?.scrollBy({ left: d * ref.current.clientWidth * 0.8, behavior: "smooth" });
  return (
    <BlurFade inView delay={0.05} className="group/rail relative mt-9">
      <div className="mb-3 flex items-end gap-3 px-4 md:px-10">
        <h2 className="font-display text-xl font-semibold tracking-tight md:text-2xl">{title}</h2>
        <div className="flex-1" />{action}
        <div className="hidden gap-1 opacity-0 transition-opacity group-hover/rail:opacity-100 md:flex">
          <button aria-label={`Scroll ${title} back`} onClick={() => nudge(-1)} className="beam grid size-8 place-items-center rounded-full bg-white/8 hover:bg-white/14"><ChevronLeft className="size-4" /></button>
          <button aria-label={`Scroll ${title} forward`} onClick={() => nudge(1)} className="beam grid size-8 place-items-center rounded-full bg-white/8 hover:bg-white/14"><ChevronRight className="size-4" /></button>
        </div>
      </div>
      <div ref={ref} className="no-scrollbar flex snap-x scroll-px-4 gap-3 overflow-x-auto px-4 pt-2 pb-3 md:scroll-px-10 md:gap-4 md:px-10">{items.map((c) => <PosterCard key={c.href} c={c} wide={wide} />)}</div>
    </BlurFade>
  );
}

export const Grid = ({ items }: { items: CardData[] }) => (
  <div className="grid grid-cols-[repeat(auto-fill,minmax(7.5rem,1fr))] gap-x-3 gap-y-5 sm:grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))] md:gap-x-4">
    {items.map((c, i) => <BlurFade key={c.href} delay={Math.min(i, 12) * 0.03}><PosterCard c={c} fill /></BlurFade>)}
  </div>
);

export const RailSkeleton = ({ wide }: { wide?: boolean }) => (
  <div className="mt-9 px-4 md:px-10" aria-hidden>
    <div className="skel mb-3 h-6 w-44 rounded-full" />
    <div className="flex gap-3 overflow-hidden md:gap-4">{[...Array(8)].map((_, i) => <div key={i} className={cn("skel shrink-0 rounded-xl", wide ? "aspect-video w-64 sm:w-72" : "aspect-[2/3] w-32 sm:w-40 lg:w-44")} />)}</div>
  </div>
);
export const GridSkeleton = ({ n = 14 }: { n?: number }) => (
  <div className="grid grid-cols-[repeat(auto-fill,minmax(7.5rem,1fr))] gap-3 sm:grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))]" aria-hidden>{[...Array(n)].map((_, i) => <div key={i} className="skel aspect-[2/3] rounded-xl" />)}</div>
);

/** Friendly failure/empty card: what happened, and what to do next. */
export function Notice({ title, body, children }: { title: string; body?: string; children?: ReactNode }) {
  return (
    <div role="status" className="mx-auto my-10 flex max-w-md flex-col items-center gap-3 rounded-[18px] bg-slate px-6 py-8 text-center">
      <ReelMark className="size-10 opacity-60" />
      <p className="font-display text-lg font-semibold">{title}</p>
      {body && <p className="text-sm text-foreground/60">{body}</p>}
      {children && <div className="mt-2 flex flex-wrap justify-center gap-2">{children}</div>}
    </div>
  );
}

const allowed = (c: CardData, k?: boolean) => !k || (c.kind === "anime" ? kids.animeCard(c) : kids.media(c.genres));
/** A rail that loads itself; `map` turns the API payload into cards (kids filter applied here). */
export function Row({ title, path, map }: { title: string; path: string; map: (d: any) => CardData[] }) {
  const { s } = useSession();
  const { data, loading, error } = useApi(path);
  if (loading) return <RailSkeleton />;
  if (error || !data) return null;
  return <Rail title={title} items={map(data).filter((c) => allowed(c, s.profile?.kids))} />;
}
export const kidsOk = allowed;

const tc = (t?: string) => (t && t === t.toUpperCase() && t.length > 3 ? t[0] + t.slice(1).toLowerCase() : t); // "MOVIE" -> "Movie", keeps "TV", "OVA"
export const animeCard = (a: any): CardData => ({ href: `/anime/${a.slug}`, title: a.title, poster: a.poster, sub: tc(a.type), rating: a.rating, kind: "anime", genres: a.genres });
export const mediaCard = (m: any): CardData => ({
  href: `/title/${encodeURIComponent(m.id)}?type=${m.type}`, title: m.title, poster: m.poster,
  sub: [m.type === "series" ? "Series" : "Movie", m.year].filter(Boolean).join(", "), rating: m.rating ? String(m.rating) : undefined, genres: m.genres, kind: "media",
});

/** Marquee type: the name solid, the rest in outline. Splits at a colon or dash ("Code Geass: Lelouch…"), or after the first word with `word`. */
export function Marquee({ text, word, as: T = "h1", className }: { text: string; word?: boolean; as?: "h1" | "h2" | "p"; className?: string }) {
  const m = text.match(/^(.+?)(?:\s*[:：]\s+|\s+[-–—]\s+)(.+)$/), sp = text.indexOf(" ");
  const [a, b] = m ? [m[1], m[2]] : word && sp > 0 ? [text.slice(0, sp), text.slice(sp + 1)] : [text, ""];
  return <T aria-label={text} className={cn("giant", className)}><span aria-hidden>{a}{b && <> <span className="hollow">{b}</span></>}</span></T>;
}

/** "9 min left" / "1 h 6 min left" as [number, unit]. Web rows store seconds; rows synced from the apps store 0..1000, so no minutes for those.
 *  ponytail: the 1000 check guesses the unit; read a real duration once synced rows carry one. */
export function timeLeft(position: number, duration: number): [string, string] | null {
  if (!duration || duration === 1000 || duration < position) return null;
  const m = Math.max(1, Math.round((duration - position) / 60));
  return [m >= 60 ? `${Math.floor(m / 60)} h ${m % 60}` : String(m), "min left"];
}

/** Ticket stub for a history row: the whole poster, a perforation, then title, episode, minutes left and progress, tinted by its own poster. */
export function Stub({ e, onRemove }: { e: Entry; onRemove?: () => void }) {
  const title = e.payload.title || "Untitled", sub = entrySub(e.payload), left = timeLeft(e.position, e.duration), f = frac(e.position, e.duration);
  return (
    <li className="ticket group">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {e.payload.poster && <img src={e.payload.poster} alt="" aria-hidden referrerPolicy="no-referrer" className="tint" />}
      <i className="shade" />
      <Img src={e.payload.poster} className="relative h-full w-1/3 flex-none object-cover" />
      <div className="relative flex min-w-0 flex-1 flex-col gap-1 border-l-2 border-dashed border-white/25 py-3 pr-3 pl-4">
        <p className="line-clamp-2 font-display text-[19px] leading-[1.02] font-extrabold text-balance transition-[font-size] duration-200 md:group-hover:text-[22px]">{title}</p>
        <p className="truncate text-[13px] text-[#dad6e4]">{sub}</p>
        <div className="mt-auto flex items-baseline justify-between gap-2 text-xs text-[#bdb9c9]">
          <span className="font-semibold text-primary opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100">Resume</span>
          {left && <span><b className="font-display text-base font-extrabold text-foreground">{left[0]}</b> {left[1]}</span>}
        </div>
        <div className="track-bar"><i style={{ width: `${Math.max(3, f * 100)}%` }} /></div>
      </div>
      <Link href={entryHref(e)} aria-label={`Resume ${title}, ${sub}`} className="absolute inset-0 z-10 outline-none" />
      {onRemove && <button type="button" aria-label={`Remove ${title} from history`} onClick={onRemove}
        className="absolute top-2 right-2 z-20 grid size-7 place-items-center rounded-full bg-dusk/80 text-foreground backdrop-blur transition-opacity hover:bg-dusk focus-visible:opacity-100 md:opacity-0 md:group-hover:opacity-100"><X className="size-4" /></button>}
    </li>
  );
}

/** Continue watching rows for the current profile, newest first; `reload` after a removal. */
export function useContinue() {
  const { s } = useSession();
  const [items, setItems] = useState<Entry[]>([]);
  const load = () => { if (s.profile) setItems(continueWatching(s.profile.id)); };
  // pull what the TV/phone watched: on open and whenever the tab comes back into view (Home never pulled, so it showed stale episodes)
  useEffect(() => {
    load();
    if (!s.profile || !s.token) return;
    const pull = () => syncHistory(s.profile!.id, s.token!).then(load).catch(() => {});
    const onVis = () => document.visibilityState === "visible" && pull();
    pull(); document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, [s.profile, s.token]); // eslint-disable-line react-hooks/exhaustive-deps
  const remove = (e: Entry) => removeHistory(s.profile!.id, s.token, e.payload).then(load);
  return [items, remove] as const;
}

/** Continue watching as ticket stubs running along the 35mm strip. */
export function ContinueStrip({ items, onRemove }: { items: Entry[]; onRemove: (e: Entry) => void }) {
  if (!items.length) return null;
  return (
    <section aria-label="Continue watching" className="strip">
      <ol>{items.map((e) => <Stub key={e.key} e={e} onRemove={() => onRemove(e)} />)}</ol>
    </section>
  );
}

/** "Recommended for you": TMDB + hianime picks ranked server-side (/api/recs) from this profile's history; kids profiles only see kid-safe picks. */
export function RecommendedRow() {
  const { s } = useSession();
  const [items, setItems] = useState<CardData[]>([]);
  useEffect(() => {
    if (!s.profile) return;
    const body = recSeeds(s.profile.id);
    if (!body.seeds.length) return setItems([]);
    let live = true;
    fetch("/api/recs", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ ...body, kids: !!s.profile.kids }) })
      .then((r) => (r.ok ? r.json() : []))
      .then((d: CardData[]) => live && setItems(d.filter((c) => allowed(c, s.profile?.kids))))
      .catch(() => {});
    return () => { live = false; };
  }, [s.profile]);
  return <Rail title="Recommended for you" items={items} />;
}

export function FavButton({ c }: { c: CardData }) {
  const { s } = useSession(), pid = s.profile?.id ?? "guest", [on, setOn] = useState(false);
  useEffect(() => {
    const read = () => setOn(getFavs(pid).some((f) => f.href === c.href));
    read();
    if (s.token) syncFavs(pid, s.token).then(read).catch(() => {});
  }, [c.href, pid, s.token]);
  return <button type="button" aria-pressed={on} className={pill.secondary} onClick={() => setOn(toggleFav(pid, s.token, c))}><Heart className={cn(on && "fill-primary text-primary")} />{on ? "Saved to library" : "Favourite"}</button>;
}

export function AvatarPicker({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  return (
    <div className="space-y-4">
      {EMOJI_GROUPS.map(([g, ids]) => (
        <div key={g} role="radiogroup" aria-label={g}>
          <p className="mb-2 text-sm font-semibold text-foreground/72">{g}</p>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(3.5rem,1fr))] gap-2">
            {ids.map((id) => (
              <button key={id} type="button" role="radio" aria-label={id.replace(/_/g, " ")} aria-checked={value === id} onClick={() => onChange(id)}
                className={cn("beam grid place-items-center rounded-full p-1 transition-transform duration-200 ease-dusk", value === id ? "scale-105" : "opacity-80 hover:opacity-100")}>
                <Avatar id={id} size={52} ring={value === id} />
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
