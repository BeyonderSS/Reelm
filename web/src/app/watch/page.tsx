"use client";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { selectControls, usePlayer } from "@videojs/react";
import { HlsJsVideo } from "@videojs/react/media/hlsjs-video";
import { Video, VideoPlayer, VideoSkin } from "@videojs/react/video";
import "@videojs/react/video/skin.css";
import { ArrowLeft, Check, Copy, MonitorPlay, RotateCcw, SkipForward, Users, X, Menu as MenuIcon } from "lucide-react";
import { Avatar, pill } from "@/components/app";
import { ReelMark } from "@/components/reel";
import { deviceId, get, getHistory, epKey, playKey, proxied, reelm, saveProgress, toPlay, WS, type Play as P } from "@/lib/core";
import { helperOld } from "@/lib/bridge";
import { useApi, useSession } from "@/lib/session";
import { cn } from "@/lib/utils";
type Source = { src: string; hls: boolean; label: string; codec?: string };
type Sub = { url: string; label: string; lang: string; default?: boolean };
type Member = { id: string; name: string; avatar: string };
type Device = { deviceId: string; deviceName: string; kind: string };

const parse = (v: string | null) => { try { return toPlay(JSON.parse(v ?? "")); } catch { return null; } };
const href = (p: P, extra = "") => `/watch?p=${encodeURIComponent(JSON.stringify(p))}${extra}`;
/** Raw errors from the stream hosts are not for people; say what happened and what to try. */
// MovieBox's video host only serves app user agents; the Reelm Helper extension sets one for that host alone.
const HELPER_URL = () => /firefox/i.test(navigator.userAgent)
  ? process.env.NEXT_PUBLIC_HELPER_FIREFOX || "/reelm-helper.zip"
  : process.env.NEXT_PUBLIC_HELPER_CHROME || "/reelm-helper.zip";
const friendly = (m: string) =>
  m === "helper" ? { t: "One quick install to watch movies", b: "Movies need the free Reelm Helper browser extension. Install it, then press Retry. Anime and Live TV work without it." }
  : m === "hevc" ? { t: "Your browser can't play this format", b: "This title is only available in HEVC (H.265). Open it in Edge or Safari, or in the Reelm phone or TV app." }
  : /isn't available|not available|no stream|no streams|release/i.test(m) ? { t: "This one isn't streaming yet", b: "No source has it right now. Try again later, or pick another episode." }
  : /network|fetch|manifest|frag|level|load/i.test(m) ? { t: "The stream stopped loading", b: "The video host didn't answer. Retry, or switch quality in settings." }
  : /4\d\d|5\d\d|440/.test(m) ? { t: "The video host turned us away", b: "This usually clears up in a minute. Retry to ask again." }
  : { t: "This video didn't start", b: "Something went wrong while starting playback. Retry, or go back and pick it again." };

/** Fades the overlay with the skin's own controls (they hide while playing). */
function Fade({ className, children }: { className?: string; children: React.ReactNode }) {
  const on = usePlayer(selectControls)?.controlsVisible ?? true;
  return <div className={cn("transition-opacity duration-300", on ? "opacity-100" : "pointer-events-none opacity-0", className)}>{children}</div>;
}

function Player() {
  const q = useSearchParams(), router = useRouter(), { s } = useSession();
  const room = (q.get("room") ?? "").toUpperCase();
  const p = useMemo(() => parse(q.get("p")), [q]);
  const t0 = Number(q.get("t")) || 0, f0 = Number(q.get("f")) || 0;
  const video = useRef<HTMLVideoElement>(null), keep = useRef(0);
  const wsRef = useRef<WebSocket | null>(null), hub = useRef<WebSocket | null>(null);
  const applying = useRef(false), youId = useRef("");
  const [sources, setSources] = useState<Source[]>([]), [si, setSi] = useState(0);
  const [subs, setSubs] = useState<Sub[]>([]), [subIdx, setSubIdx] = useState(-1);
  const [err, setErr] = useState(""), [nonce, setNonce] = useState(0), [embed, setEmbed] = useState(false);
  // same VidSrc fallback as the apps' WebEmbedPlayer; needs an IMDb id (Cinemeta titles have one, MovieBox ids don't)
  const imdb = p && p.kind === "movie" && /^tt\d+$/.test(p.id) ? p.id : "";
  const embedUrl = imdb && (p!.type === "series" ? `https://vsembed.ru/embed/tv?imdb=${imdb}&season=${p!.season ?? 1}&episode=${p!.episode ?? 1}` : `https://vsembed.ru/embed/movie?imdb=${imdb}`);
  const [members, setMembers] = useState<Member[]>([]), [devices, setDevices] = useState<Device[]>([]);
  const [eps, setEps] = useState<any[]>([]), [skips, setSkips] = useState<{ s: number; e: number; type: string }[]>([]);
  const [cur, setCur] = useState(0), [drawer, setDrawer] = useState(false);
  const [copied, setCopied] = useState(false), [roomErr, setRoomErr] = useState("");
  const pid = s.profile?.id ?? "guest", pkey = p ? JSON.stringify(p) : "";
  const extra = room ? `&room=${room}` : "";

  // resolve the stream (re-run by Retry)
  useEffect(() => {
    if (!p) return;
    let live = true;
    setErr(""); setSources([]); setSubs([]); setSkips([]); setSi(0); keep.current = 0;
    (async () => {
      if (p.kind === "live") return [[{ src: proxied(p.url!, "", true), hls: true, label: "Live" }], []] as const;
      if (p.kind === "anime") {
        if (!p.epId) {
          // synced payloads from the apps carry the episode number only: find its id (and the right slug, by title if needed)
          let slug = p.id, list: any[] = await get<any[]>(`anime/episodes?slug=${slug}`).catch(() => [] as any[]);
          if (!list.length && p.title) {
            slug = (await get<any[]>(`anime/search?q=${encodeURIComponent(p.title)}`).catch(() => []))[0]?.slug ?? "";
            if (slug) list = await get<any[]>(`anime/episodes?slug=${slug}`).catch(() => []);
          }
          const e = list.find((x) => x.number === p.ep) ?? list[0];
          if (!e) throw new Error("This isn't available to stream.");
          if (live) router.replace(href({ ...p, id: slug, ep: e.number, epId: e.id }, `${extra}${f0 ? `&f=${f0}` : ""}`));
          return null;
        }
        const r = await get(`anime/stream?ep=${p.epId}&lang=${p.lang ?? "sub"}`);
        if (r.malId) get(`aniskip?mal=${r.malId}&ep=${p.ep}`).then((d) => live && setSkips((d.results ?? []).map((x: any) => ({ s: x.interval.startTime, e: x.interval.endTime, type: x.skipType })))).catch(() => {});
        return [[{ src: proxied(r.master, r.referer), hls: true, label: "Auto" }], r.subtitles.map((x: Sub) => ({ ...x, url: proxied(x.url, r.referer) }))] as const;
      }
      const list = await get<any[]>(`movies/stream?id=${encodeURIComponent(p.id)}&type=${p.type}&title=${encodeURIComponent(p.title)}&year=${p.year ?? ""}&s=${p.season ?? 0}&e=${p.episode ?? 0}`);
      if (!list.length) throw new Error("No streams found for this title.");
      return [list.map((x) => ({ src: x.url, hls: /\.m3u8(\?|$)/.test(x.url), label: x.label, codec: x.codec })), (list.find((x) => x.subtitles?.length)?.subtitles ?? []).map((x: Sub) => ({ ...x, url: `/api/sub?u=${encodeURIComponent(x.url)}` }))] as const;
    })().then((r) => {
      if (!live || !r) return;
      const [a, b] = r;
      setSources([...a]); setSubs([...b]);
      const d = b.findIndex((x: Sub) => x.default);
      setSubIdx(b.length ? (d >= 0 ? d : 0) : -1);
    }).catch((e) => live && setErr(e.message));
    if (p.kind === "anime") get(`anime/episodes?slug=${p.id}`).then((d) => live && setEps(d)).catch(() => {});
    return () => { live = false; };
  }, [pkey, nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  const src = sources[si];
  // MovieBox often ships HEVC only; browsers without HEVC decode (most Chrome/Firefox on Linux) can't play it
  const fail = (m: string) => {
    const hevc = /hevc|h265/i.test(src?.codec ?? "") && !document.createElement("video").canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"');
    if (si + 1 < sources.length) setSi(si + 1); else setErr(hevc ? "hevc" : m);
  };
  const onMeta = (v: HTMLVideoElement) => {
    if (p?.kind === "live") return;
    const hist = p && epKey(getHistory(pid)[playKey(p)]?.payload ?? p) === epKey(p) ? getHistory(pid)[playKey(p)] : undefined;
    const at = keep.current || t0 || (f0 && v.duration ? f0 * v.duration : 0) || (hist && hist.duration && hist.position / hist.duration < 0.95 ? hist.position : 0);
    if (at > 5) v.currentTime = at;
  };

  // progress: every 10 s and on leave
  const save = useCallback(() => { const v = video.current; if (p && v && v.currentTime > 5 && isFinite(v.duration)) saveProgress(pid, s.token, p, v.currentTime, v.duration); }, [p, pid, s.token]);
  useEffect(() => { const i = setInterval(save, 10000); return () => { clearInterval(i); save(); }; }, [save]);

  // watch together
  const sendRoom = (m: object) => wsRef.current?.readyState === 1 && wsRef.current.send(JSON.stringify(m));
  const apply = (fn: () => void) => { applying.current = true; fn(); setTimeout(() => (applying.current = false), 400); };
  useEffect(() => {
    if (!room) return;
    const ws = new WebSocket(`${WS}/v1/rooms/${room}`);
    wsRef.current = ws;
    ws.onopen = () => ws.send(JSON.stringify({ type: "join", name: s.profile?.name ?? "Guest", avatar: s.profile?.avatar ?? "grinning_face" }));
    ws.onerror = () => setRoomErr("Couldn't join the room. Check the code and try again.");
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data), v = video.current;
      if (m.type === "state") {
        youId.current = m.you; setMembers(m.members);
        const theirs = toPlay(m.payload);
        if (theirs && JSON.stringify(theirs) !== pkey) return router.replace(href(theirs, `&room=${room}`));
        if (v) apply(() => { v.currentTime = m.position + (m.playing ? (Date.now() - m.at) / 1000 : 0); if (m.playing) v.play().catch(() => {}); });
      } else if (m.type === "members") setMembers(m.members);
      else if (m.type === "load") { const np = toPlay(m.payload); if (np && m.by !== youId.current) router.replace(href(np, `&room=${room}`)); }
      else if (v && m.by !== youId.current) apply(() => {
        if (m.type === "seek") v.currentTime = m.position;
        if (m.type === "play") { v.currentTime = m.position + (Date.now() - m.at) / 1000; v.play().catch(() => {}); }
        if (m.type === "pause") { v.pause(); v.currentTime = m.position; }
      });
    };
    return () => ws.close();
  }, [room]); // eslint-disable-line react-hooks/exhaustive-deps
  const startRoom = async () => {
    setRoomErr("");
    try { const { code } = await reelm("/v1/rooms", { json: { payload: p } }); router.replace(href(p!, `&room=${code}`)); }
    catch (e) { setRoomErr((e as Error).message); }
  };
  const roomLink = () => `${location.origin}/watch?room=${room}${p ? `&p=${encodeURIComponent(pkey)}` : ""}`;
  const pos = () => video.current?.currentTime ?? 0;

  // continue on TV: this browser is a "phone" on the account hub
  useEffect(() => {
    if (!s.token) return;
    const ws = new WebSocket(`${WS}/v1/hub?token=${s.token}`);
    hub.current = ws;
    ws.onopen = () => ws.send(JSON.stringify({ type: "hello", deviceId: deviceId(), deviceName: "Reelm Web", kind: "phone" }));
    ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.type === "devices") setDevices(m.devices); };
    return () => ws.close();
  }, [s.token]);
  const handoff = (to: string) => { hub.current?.send(JSON.stringify({ type: "handoff", to, profileId: pid, playback: { payload: p, position: pos() } })); video.current?.pause(); };
  // next episode
  const series = useApi(p?.kind === "movie" && p.type === "series" ? `movies/detail?id=${encodeURIComponent(p.id)}&type=series` : null).data;
  const next: P | null = useMemo(() => {
    if (!p) return null;
    if (p.kind === "anime") { const n = eps.find((e) => e.number === (p.ep ?? 0) + 1); return n ? { ...p, ep: n.number, epId: n.id } : null; }
    if (p.kind === "movie" && series?.seasons) {
      const se = series.seasons.find((x: any) => x.season === p.season);
      if (se && (p.episode ?? 0) < se.episodes) return { ...p, episode: (p.episode ?? 0) + 1 };
      const ns = series.seasons.find((x: any) => x.season === (p.season ?? 0) + 1);
      if (ns) return { ...p, season: ns.season, episode: 1 };
    }
    return null;
  }, [p, eps, series]);
  const go = (np: P) => { save(); if (room) sendRoom({ type: "load", payload: np, position: 0 }); router.replace(href(np, extra)); };
  
  const skip = skips.find((k) => cur >= k.s && cur < k.e - 1);

  // the skin handles space, arrows, j/k/l, m, f, c; only the app-specific keys are ours
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (/INPUT|TEXTAREA/.test((e.target as HTMLElement).tagName) || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === "n" && next) go(next);
      else if (e.key === "s" && skip && video.current) video.current.currentTime = skip.e;
    };
    addEventListener("keydown", on);
    return () => removeEventListener("keydown", on);
  });

  if (!p && !room) return (
    <main className="grid min-h-dvh place-items-center p-6">
      <div className="flex max-w-sm flex-col items-center gap-3 text-center"><ReelMark className="size-12 opacity-60" /><h1 className="font-display text-2xl font-semibold">Nothing to play</h1>
        <p className="text-foreground/60">This link doesn't point to a title. Pick something from home.</p><Link href="/" className={pill.primary}>Go home</Link></div>
    </main>
  );

  const back = !p ? "/" : p.kind === "anime" ? `/anime/${p.id}` : p.kind === "live" ? "/live" : `/title/${encodeURIComponent(p.id)}?type=${p.type}`;
  const sub = !p ? "Joining room" : p.kind === "anime" ? `Episode ${p.ep}${p.lang === "dub" ? ", dubbed" : ""}` : p.kind === "live" ? "Live" : p.season ? `Season ${p.season}, episode ${p.episode}` : "";
  const fe = err && friendly(err);
  const ctl = "beam grid size-11 shrink-0 place-items-center rounded-full text-foreground hover:bg-white/12 [&_svg]:size-5";
  const row = (on: boolean) => cn("beam flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm hover:bg-white/8", on && "text-primary");
  const common = {
    ref: video, autoPlay: true, playsInline: true, referrerPolicy: "no-referrer", className: "size-full",
    onLoadedMetadata: (e: React.SyntheticEvent<HTMLVideoElement>) => onMeta(e.currentTarget),
    onError: () => fail("The video failed to load."),
    onPlay: () => { if (!applying.current) sendRoom({ type: "play", position: pos() }); },
    onPause: () => { save(); if (!applying.current) sendRoom({ type: "pause", position: pos() }); },
    onSeeked: () => !applying.current && sendRoom({ type: "seek", position: pos() }),
    onTimeUpdate: (e: React.SyntheticEvent<HTMLVideoElement>) => { keep.current = e.currentTarget.currentTime; setCur(keep.current); },
    onEnded: () => { save(); if (next) go(next); },
    children: subs.map((t, i) => <track key={t.url} kind="subtitles" src={t.url} label={t.label} srcLang={t.lang || "en"} default={i === subIdx} />),
  };
  const Media = src?.hls ? HlsJsVideo : Video;

  return (
    <main className="relative h-dvh w-full overflow-hidden bg-black">
      <VideoPlayer>
        <VideoSkin className="reelm-skin size-full" style={{ "--media-accent-color": "#F2B45A", "--media-accent-text-color": "#15171F", "--media-border-radius": "0", "--media-border-color": "transparent", "--media-object-fit": "contain" } as React.CSSProperties}>
          {src && <Media key={si + pkey + nonce} src={src.src} crossOrigin={src.hls ? "anonymous" : undefined} {...(common as object)} />}
          {!src && !err && <div className="pointer-events-none absolute inset-0 grid place-items-center"><div className="flex flex-col items-center gap-4"><ReelMark className="size-14 animate-spin [animation-duration:1.6s]" /><p className="text-sm text-foreground/72">{p ? "Finding a stream" : "Joining the room"}</p></div></div>}

          {skip && !err && (
            <button onClick={() => { video.current!.currentTime = skip.e; }} className={cn(pill.secondary, "absolute right-4 bottom-28 z-10 bg-dusk/80 ring-1 ring-white/15 backdrop-blur md:right-8")}>
              <SkipForward />Skip {skip.type.includes("op") ? "intro" : skip.type === "ed" ? "outro" : "recap"}
            </button>
          )}

          <Fade className="absolute inset-x-0 top-0 z-10 flex items-center gap-2 bg-gradient-to-b from-black/80 to-transparent px-3 pt-[max(env(safe-area-inset-top),12px)] pb-10 md:px-6">
            <Link href={back} aria-label="Back" className={ctl}><ArrowLeft /></Link>
            <div className="min-w-0 flex-1">
              <h1 className="truncate font-display text-lg font-semibold md:text-xl">{p?.title ?? "Watch together"}</h1>
              {sub && <p className="truncate text-xs text-foreground/72">{sub}</p>}
            </div>
            {devices.filter((d) => d.kind === "tv").map((d) => <button key={d.deviceId} className={cn(pill.ghost, "h-10")} onClick={() => handoff(d.deviceId)}><MonitorPlay className="size-4" /><span className="hidden sm:inline">Continue on {d.deviceName}</span></button>)}
            {next && <button onClick={() => go(next)} className={cn(pill.ghost, "h-10 px-3")}><SkipForward className="size-4" /><span className="hidden sm:inline">Next episode</span></button>}
            {p?.kind !== "live" && (
              <button onClick={() => setDrawer(true)} className={cn(pill.ghost, "h-10 px-4", room && "text-primary")} aria-label="Watch together">
                <Users className="size-4" /><span className="hidden sm:inline">{room ? `Room ${room}` : "Watch together"}</span>
                {room && members.length > 0 && <span className="rounded-full bg-primary/20 px-1.5 text-xs">{members.length}</span>}
              </button>
            )}
            {(sources.length > 0 || p?.kind === "anime") && (
              <details className="relative">
                {sources.length > 0
                  ? <summary aria-label="Quality, audio and episodes" className={cn(pill.ghost, "h-10 list-none cursor-pointer px-4 font-semibold")}>{sources[si]?.label ?? "Quality"}<MenuIcon className="size-4" /></summary>
                  : <summary aria-label="Audio and episodes" className={cn(ctl, "list-none cursor-pointer")}><MenuIcon /></summary>}
                <div className="page-in absolute right-0 mt-2 max-h-[60vh] w-72 overflow-y-auto rounded-[24px] bg-haze/95 p-2 shadow-[0_24px_60px_rgb(10_12_18/0.6)] ring-1 ring-white/8 backdrop-blur-xl">
                  {sources.length > 0 && <>
                    <p className="px-3 pt-2 pb-1 text-xs font-semibold text-foreground/52">{sources.length > 1 ? "Quality" : "Quality (only one available)"}</p>
                    {sources.map((x, i) => <button key={i} onClick={() => setSi(i)} className={row(si === i)}><Check className={cn("size-4", si !== i && "invisible")} />{x.label}</button>)}
                    {helperOld() && <a href={HELPER_URL()} target="_blank" rel="noreferrer" className="block px-3 pt-2 pb-1 text-xs text-foreground/52 underline">Update Reelm Helper for more sources</a>}
                  </>}
                  {p?.kind === "anime" && <>
                    <p className="px-3 pt-3 pb-1 text-xs font-semibold text-foreground/52">Audio</p>
                    {(["sub", "dub"] as const).map((l) => <button key={l} onClick={() => go({ ...p, lang: l })} className={row((p.lang ?? "sub") === l)}><Check className={cn("size-4", (p.lang ?? "sub") !== l && "invisible")} />{l === "sub" ? "Japanese with subtitles" : "English dub"}</button>)}
                    {eps.length > 1 && <>
                      <p className="px-3 pt-3 pb-1 text-xs font-semibold text-foreground/52">Episode</p>
                      <div className="grid grid-cols-5 gap-1 px-2 pb-2">{eps.map((e) => <button key={e.id} onClick={() => go({ ...p, ep: e.number, epId: e.id })} className={cn("beam rounded-lg py-1.5 text-sm", e.number === p.ep ? "bg-primary text-primary-foreground" : "bg-white/8 hover:bg-white/12")}>{e.number}</button>)}</div>
                    </>}
                  </>}
                  {p?.kind === "movie" && series?.seasons?.length > 0 && <>
                    <p className="px-3 pt-3 pb-1 text-xs font-semibold text-foreground/52">Season</p>
                    <div className="flex flex-wrap gap-1 px-2">{series.seasons.map((x: any) => <button key={x.season} onClick={() => x.season !== p.season && go({ ...p, season: x.season, episode: 1 })} className={cn("beam rounded-lg px-3 py-1.5 text-sm", x.season === p.season ? "bg-primary text-primary-foreground" : "bg-white/6 hover:bg-white/12")}>{x.season}</button>)}</div>
                    <p className="px-3 pt-3 pb-1 text-xs font-semibold text-foreground/52">Episode</p>
                    <div className="grid grid-cols-5 gap-1 px-2 pb-2">{Array.from({ length: series.seasons.find((x: any) => x.season === p.season)?.episodes ?? 0 }, (_, i) => i + 1).map((n) => <button key={n} onClick={() => go({ ...p, episode: n })} className={cn("beam rounded-lg py-1.5 text-sm", n === p.episode ? "bg-primary text-primary-foreground" : "bg-white/6 hover:bg-white/12")}>{n}</button>)}</div>
                  </>}
                </div>
              </details>
            )}
          </Fade>

      {/* watch together drawer */}
      {drawer && (
        <div className="absolute inset-0 z-40 flex justify-end bg-black/50" onClick={(e) => e.target === e.currentTarget && setDrawer(false)}>
          <aside aria-label="Watch together" className="page-in flex h-full w-full max-w-sm flex-col gap-5 bg-haze p-5 pt-[max(env(safe-area-inset-top),20px)] shadow-[-24px_0_60px_rgb(10_12_18/0.6)]">
            <div className="flex items-center"><h2 className="flex-1 font-display text-2xl font-semibold">Watch together</h2><button aria-label="Close" onClick={() => setDrawer(false)} className={ctl}><X /></button></div>
            {room ? <>
              <div className="rounded-[18px] bg-slate p-5 text-center">
                <p className="text-sm text-foreground/60">Room code</p>
                <p className="mt-1 font-display text-4xl font-bold tracking-[0.18em] text-primary">{room}</p>
                <button className={cn(pill.primary, "mt-4 w-full")} onClick={() => { navigator.clipboard?.writeText(roomLink()); setCopied(true); setTimeout(() => setCopied(false), 1800); }}>{copied ? <Check /> : <Copy />}{copied ? "Link copied" : "Copy invite link"}</button>
                <p className="mt-3 text-xs text-foreground/52">Friends open the link, or enter the code from their avatar menu. Play, pause and seeking stay in sync.</p>
              </div>
              <div>
                <p className="mb-2 text-sm font-semibold">{members.length} watching</p>
                <ul className="space-y-1">{members.map((m) => <li key={m.id} className="flex items-center gap-3 rounded-xl px-2 py-2"><Avatar id={m.avatar} size={36} /><span className="flex-1">{m.name}</span>{m.id === youId.current && <span className="text-xs text-foreground/52">You</span>}</li>)}</ul>
              </div>
              <button className={cn(pill.ghost, "mt-auto")} onClick={() => { setDrawer(false); router.replace(p ? href(p) : "/"); }}>Leave room</button>
            </> : <>
              <p className="text-foreground/72">Start a room and share the code. Everyone in it plays, pauses and seeks together.</p>
              <button className={pill.primary} onClick={startRoom} disabled={!p}><Users />Start a room</button>
            </>}
            {roomErr && <p role="alert" className="text-sm text-destructive">{roomErr}</p>}
          </aside>
        </div>
      )}
        </VideoSkin>
      </VideoPlayer>
      {embed && embedUrl && <iframe src={embedUrl} allowFullScreen allow="autoplay; fullscreen; encrypted-media" referrerPolicy="no-referrer" className="absolute inset-x-0 bottom-0 top-16 z-40 h-[calc(100%-4rem)] w-full border-0 bg-black" />}
          {fe && (
            <div className="absolute inset-0 z-50 grid place-items-center bg-dusk/80 p-6 backdrop-blur-md">
              <div role="alert" className="page-in flex max-w-sm flex-col items-center gap-3 rounded-[24px] bg-haze p-7 text-center shadow-[0_24px_60px_rgb(10_12_18/0.6)]">
                <ReelMark className="size-10 opacity-70" />
                <h2 className="font-display text-xl font-semibold">{fe.t}</h2>
                <p className="text-sm text-foreground/72">{fe.b}</p>
                <div className="mt-2 flex gap-2">
                  {err === "helper" && <a href={HELPER_URL()} target="_blank" rel="noreferrer" className={pill.primary}>Install Reelm Helper</a>}
                  <button className={err === "helper" ? pill.secondary : pill.primary} onClick={() => { setErr(""); setSi(0); setNonce(nonce + 1); }}><RotateCcw />Retry</button>
                  <Link href={back} className={pill.secondary}>Back</Link>
                </div>
                {next && <button className="mt-1 text-sm text-primary hover:underline" onClick={() => go(next)}>Play the next episode instead</button>}
                {embedUrl && <button className="mt-1 text-sm text-primary hover:underline" onClick={() => { setEmbed(true); setErr(""); }}>Try the web player</button>}
              </div>
            </div>
          )}
    </main>
  );
}
export default function Page() { return <Suspense><Player /></Suspense>; }
