"use client";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { selectControls, usePlayer } from "@videojs/react";
import { HlsJsVideo } from "@videojs/react/media/hlsjs-video";
import { Video, VideoPlayer, VideoSkin } from "@videojs/react/video";
import "@videojs/react/video/skin.css";
import { ArrowLeft, Check, Copy, MonitorPlay, RotateCcw, Settings2, SkipForward, Users, X } from "lucide-react";
import { Avatar, Chips, Marquee, pill, timeLeft } from "@/components/app";
import { ReelMark } from "@/components/reel";
import { SeekPreview } from "@/components/seek-preview";
import { frac } from "@/lib/eps";
import { deviceId, get, getHistory, epKey, playKey, proxied, reelm, saveProgress, syncHistory, toPlay, WS, type Play as P } from "@/lib/core";
import { track } from "@/lib/telemetry";
import { helperVega, helperVersion } from "@/lib/bridge";
import { bitrate, getBandwidth, getPin, pickStream, saveBandwidth, setPin } from "@/lib/pick-stream";
import { useApi, useSession } from "@/lib/session";
import { useLibavAudio } from "@/lib/use-libav-audio";
import { cn } from "@/lib/utils";
type Source = { src: string; hls: boolean; label: string; codec?: string; dolby?: boolean; height?: number };
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

type Pref = { size: keyof typeof SUB_SIZE; pos: keyof typeof SUB_LINE; fit: keyof typeof FIT; autoSkip: boolean };
const PREF_KEY = "reelm.player";
const SUB_SIZE = { S: "0.85em", M: "1.05em", L: "1.35em", XL: "1.7em" };
/** VTT line: "auto" is the bottom; negative counts lines up from the bottom (Raised clears TV overscan), 0 is the top. */
const SUB_LINE = { Bottom: "auto", Raised: -3, Higher: -5, Top: 0 } as const;
const FIT = { Fit: "contain", Fill: "cover", Stretch: "fill" };
const DEFAULT_PREF: Pref = { size: "M", pos: "Bottom", fit: "Fit", autoSkip: false };
const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
type Tab = "watch" | "subs" | "picture" | "playback" | "together";

/** A row of option pills; the chosen one is lit amber. */
function Opts<T extends string | number>({ label, items, value, onPick, name = String }: { label: string; items: readonly T[]; value: T; onPick: (v: T) => void; name?: (v: T) => string }) {
  return (
    <div>
      <p className="mt-2 mb-1.5 text-sm text-[#a3a1ae]">{label}</p>
      <div role="radiogroup" aria-label={label} className="flex flex-wrap gap-1.5">
        {items.map((v) => <button key={String(v)} role="radio" aria-checked={v === value} onClick={() => onPick(v)} className="beam opt">{name(v)}</button>)}
      </div>
    </div>
  );
}

/** A source as a stub: quality big, then the host, codec and audio; the one playing says so. */
function SourceStub({ x, on, tag, onPick }: { x: Source; on: boolean; tag?: string; onPick: () => void }) {
  const parts = x.label.split(" · "), q = parts.find((t) => /^(\d{3,4}p|auto|4k)$/i.test(t)) ?? parts[parts.length - 1];
  const host = parts.length > 1 ? parts[0] : "Stream", extra = parts.slice(1).filter((t) => t !== q);
  return (
    <button onClick={onPick} aria-pressed={on} className={cn("beam grid w-full grid-cols-[auto_1fr] items-center gap-x-4 gap-y-1 rounded-xl border-l-2 border-dashed border-white/25 bg-gradient-to-br from-[#25222f] to-[#121219] px-4 py-3 text-left", on && "shadow-[inset_0_0_0_2px_#f2b45a]")}>
      <span className="row-span-2 min-w-16 font-display text-2xl leading-none font-extrabold">{q}{x.codec && <small className="mt-1 block font-sans text-xs font-medium text-[#a3a1ae]">{x.codec}</small>}</span>
      <b className="text-[15px]">{host}{on && <span className="ml-2 text-xs font-semibold text-signal">Playing</span>}{tag && <span className="ml-2 text-[10px] font-semibold text-[#a3a1ae]">{tag}</span>}</b>
      <span className="flex flex-wrap gap-1 text-xs text-[#cfcbd9]">
        {x.hls && <span className="rounded-full bg-white/8 px-2 py-0.5">Adaptive</span>}
        {extra.map((t) => <span key={t} className={cn("rounded-full px-2 py-0.5", /dolby/i.test(t) ? "bg-primary/16 text-primary" : "bg-white/8")}>{t}</span>)}
        {x.dolby && !extra.some((t) => /dolby/i.test(t)) && <span className="rounded-full bg-primary/16 px-2 py-0.5 text-primary">Dolby audio</span>}
      </span>
    </button>
  );
}

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
  const [sources, setSources] = useState<Source[]>([]), [si, setSi] = useState(0), [tag, setTag] = useState({ i: -1, pin: 0 });
  const [subs, setSubs] = useState<Sub[]>([]), [subIdx, setSubIdx] = useState(-1);
  const [err, setErr] = useState(""), [nonce, setNonce] = useState(0), [embed, setEmbed] = useState(false);
  // same VidSrc fallback as the apps' WebEmbedPlayer; needs an IMDb id (Cinemeta titles have one, MovieBox ids don't)
  // TMDB-only titles ("tmdb:movie:<n>") use ?tmdb=
  const ext = p && p.kind === "movie" ? (/^tt\d+$/.test(p.id) ? `imdb=${p.id}` : /^tmdb:(movie|tv):\d+$/.test(p.id) ? `tmdb=${p.id.split(":")[2]}` : "") : "";
  const embedUrl = ext && (p!.type === "series" ? `https://vsembed.ru/embed/tv?${ext}&season=${p!.season ?? 1}&episode=${p!.episode ?? 1}` : `https://vsembed.ru/embed/movie?${ext}`);
  const [members, setMembers] = useState<Member[]>([]), [devices, setDevices] = useState<Device[]>([]);
  const [eps, setEps] = useState<any[]>([]), [skips, setSkips] = useState<{ s: number; e: number; type: string }[]>([]);
  const [cur, setCur] = useState(0), [dur, setDur] = useState(0), [paused, setPaused] = useState(true), [panel, setPanel] = useState<Tab | null>(null);
  const [copied, setCopied] = useState(false), [roomErr, setRoomErr] = useState("");
  const pid = s.profile?.id ?? "guest", pkey = p ? JSON.stringify(p) : "";
  const extra = room ? `&room=${room}` : "";

  // resolve the stream (re-run by Retry)
  useEffect(() => {
    if (!p) return;
    let live = true;
    setErr(""); setSources([]); setSubs([]); setSkips([]); setSi(0); setTag({ i: -1, pin: 0 }); keep.current = 0;
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
      if (!document.documentElement.dataset.reelmHelper) throw new Error("helper");
      const list = await get<any[]>(`movies/stream?id=${encodeURIComponent(p.id)}&type=${p.type}&title=${encodeURIComponent(p.title)}&year=${p.year ?? ""}&s=${p.season ?? 0}&e=${p.episode ?? 0}`);
      if (!list.length) throw new Error("No streams found for this title.");
      return [list.map((x) => ({ src: x.url, hls: /\.m3u8(\?|$)/.test(x.url), label: x.label, codec: x.codec, dolby: x.dolby, height: x.height })), (list.find((x) => x.subtitles?.length)?.subtitles ?? []).map((x: Sub) => ({ ...x, url: `/api/sub?u=${encodeURIComponent(x.url)}` }))] as const;
    })().then((r) => {
      if (!live || !r) return;
      const [a, b] = r;
      setSources([...a]); setSubs([...b]);
      const sa = a as Source[];
      if (p.kind === "movie" && sa.length > 1) { const pin = getPin(playKey(p)) ?? 0, i = pickStream(sa, { bandwidthBps: getBandwidth(), pinnedHeight: pin }); setSi(i); setTag({ i, pin: pin && sa[i].height === pin ? pin : 0 }); }
      const d = b.findIndex((x: Sub) => x.default);
      setSubIdx(b.length ? (d >= 0 ? d : 0) : -1);
    }).catch((e) => live && setErr(e.message));
    if (p.kind === "anime") get(`anime/episodes?slug=${p.id}`).then((d) => live && setEps(d)).catch(() => {});
    return () => { live = false; };
  }, [pkey, nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  const src = sources[si];
  // Dolby/DTS audio Chrome can't decode: libav.js decodes it in a worker and plays it in sync with the video
  const audioNote = useLibavAudio(video, src && !src.hls ? src.src : undefined, !!src?.dolby, `${si}${pkey}${nonce}`);
  // MovieBox often ships HEVC only; browsers without HEVC decode (most Chrome/Firefox on Linux) can't play it
  // admin telemetry: what played, from where, how fast, and what failed
  const info = () => {
    let host = "";
    try { const u = new URL(src.src, location.href); host = new URL(u.searchParams.get("u") ?? u.href).host; } catch {}
    return { title: p?.title, key: p ? epKey(p) : undefined, kind: p?.kind, episode: p?.ep ?? p?.episode, source: p?.kind === "movie" ? src?.label.split(/[\s·(]/)[0] : p?.kind,
      quality: src?.label, codec: src?.dolby ? `${src.codec ?? ""} dolby`.trim() : src?.codec, host };
  };
  const startedAt = useRef(0);
  useEffect(() => { startedAt.current = src ? Date.now() : 0; }, [src]);
  // refine the bandwidth estimate ~10 s into a direct file: buffered seconds x bitrate / wall time
  useEffect(() => {
    if (!src || src.hls || p?.kind !== "movie") return;
    const t = setTimeout(() => {
      const v = video.current, b = v?.buffered;
      if (!v || v.paused || !b?.length) return;
      const end = b.end(b.length - 1), est = end * bitrate(src) / 10;
      if (est > 0) saveBandwidth(est, end - v.currentTime >= 3); // ponytail: a full buffer only proves a lower bound, so it can raise the estimate but not drop it
    }, 10_000);
    return () => clearTimeout(t);
  }, [src]); // eslint-disable-line react-hooks/exhaustive-deps
  const onPlaying = () => {
    if (!startedAt.current) return;
    track("play", info());
    track("source_used", { ...info(), ttff: Date.now() - startedAt.current });
    startedAt.current = 0;
  };
  useEffect(() => {
    const i = setInterval(() => { const v = video.current; if (src && v && !v.paused) track("heartbeat", { ...info(), position: Math.round(v.currentTime), duration: Math.round(v.duration || 0) }); }, 60_000);
    return () => clearInterval(i);
  }); // eslint-disable-line react-hooks/exhaustive-deps
  const fail = (m: string) => {
    if (src) track("play_error", { ...info(), error: m });
    const hevc = /hevc|h265/i.test(src?.codec ?? "") && !document.createElement("video").canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"');
    if (si + 1 < sources.length) setSi(si + 1); else setErr(hevc ? "hevc" : m);
  };
  const onMeta = (v: HTMLVideoElement) => {
    if (p?.kind === "live") return;
    const hist = p && epKey(getHistory(pid)[playKey(p)]?.payload ?? p) === epKey(p) ? getHistory(pid)[playKey(p)] : undefined;
    const at = keep.current || t0 || (f0 && v.duration ? f0 * v.duration : 0) || (hist && frac(hist.position, hist.duration) < 0.95 ? frac(hist.position, hist.duration) * v.duration : 0); // entries hold seconds (older app rows: permille of 1000); the ratio is what is shared
    if (at > 5) v.currentTime = at;
  };

  // pull what other devices watched so a later save compares against it
  useEffect(() => { if (s.token) syncHistory(pid, s.token).catch(() => {}); }, [pid, s.token]);
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
  // what the pause marquee shows: the title's detail, and for series the season's episodes (same cached paths as the show page)
  const det = useApi(p?.kind === "movie" ? `movies/detail?id=${encodeURIComponent(p.id)}&type=${p.type ?? "movie"}` : p?.kind === "anime" ? `anime/detail?slug=${p.id}` : null).data;
  const series = p?.kind === "movie" && p.type === "series" ? det : null;
  const season = useApi(series ? `movies/episodes?id=${encodeURIComponent(p!.id)}&season=${p!.season}&title=${encodeURIComponent(series.title)}&year=${series.year ?? ""}` : null).data;
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

  // settings ticket: subtitle size and position, picture fit, speed, auto-skip (all but speed remembered per browser)
  const [pref, setPrefState] = useState<Pref>(DEFAULT_PREF);
  useEffect(() => { try { setPrefState({ ...DEFAULT_PREF, ...JSON.parse(localStorage.getItem(PREF_KEY) ?? "{}") }); } catch {} }, []);
  const setPref = (n: Partial<Pref>) => setPrefState((o) => { const v = { ...o, ...n }; try { localStorage.setItem(PREF_KEY, JSON.stringify(v)); } catch {} return v; });
  const [speed, setSpeed] = useState(1);
  useEffect(() => { if (video.current) video.current.playbackRate = speed; }, [speed, src]);
  useEffect(() => { if (pref.autoSkip && skip && video.current) video.current.currentTime = skip.e; }, [skip?.s, pref.autoSkip]); // eslint-disable-line react-hooks/exhaustive-deps
  // subtitles: our pick shows (by label, so hls.js tracks don't shift it), the rest stay off; each cue is placed at the chosen height
  useEffect(() => {
    const v = video.current;
    if (!v) return;
    const want = subs[subIdx]?.label;
    const apply = () => Array.from(v.textTracks).filter((t) => t.kind === "subtitles" || t.kind === "captions").forEach((t) => {
      t.mode = want && t.label === want ? "showing" : "disabled";
      const place = () => Array.from(t.activeCues ?? []).forEach((c) => { (c as VTTCue).line = SUB_LINE[pref.pos]; });
      t.oncuechange = place; place();
    });
    apply();
    v.textTracks.onaddtrack = apply;
    return () => { v.textTracks.onaddtrack = null; };
  }, [subIdx, subs, pref.pos, src]);

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
  const named = (t?: string) => (t && !/^episode \d+$/i.test(t) ? t : "");

  // the pause marquee: the episode's name huge (second word in outline), the show above it in amber, synopsis and chips below
  const tmdb: any[] = season?.episodes ?? [];
  const epNow = series ? tmdb.find((e) => e.n === p!.episode) : undefined;
  const animeEp = p?.kind === "anime" ? eps.find((e) => e.number === p.ep) : undefined;
  const big = !p ? "" : series ? named(epNow?.title) || `Episode ${p.episode}` : p.kind === "anime" ? named(animeEp?.title) || `Episode ${p.ep}` : p.title;
  const show = !p ? "" : series ? `${p.title}, season ${p.season}, episode ${p.episode}` : p.kind === "anime" ? `${p.title}, episode ${p.ep}${p.lang === "dub" ? ", dubbed" : ""}` : p.kind === "live" ? "Live TV" : "";
  const synopsis: string | undefined = epNow?.overview || det?.synopsis || det?.description;
  const left = p?.kind !== "live" ? timeLeft(cur, dur) : null;
  const chips = [epNow?.runtime ? `${epNow.runtime} min` : p?.kind === "movie" && !series ? det?.runtime : undefined, epNow?.air && new Date(epNow.air + "T00:00").toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }), left && left.join(" ")].filter(Boolean) as string[];
  const nextEp = next && (series ? tmdb.find((e) => next.season === p!.season && e.n === next.episode) : undefined);
  const nextName = !next ? "" : next.kind === "anime" ? named(eps.find((e) => e.number === next.ep)?.title) || `Episode ${next.ep}` : named(nextEp?.title) || `Episode ${next.episode}`;
  const nextLabel = !next ? "" : next.kind === "anime" ? `Next, episode ${next.ep}` : next.season !== p?.season ? `Next, season ${next.season}` : `Next, episode ${next.episode}`;
  const showMq = !!p && !!src && paused && !err && !embed;

  const tabs: [Tab, string, string][] = ([
    ["watch", "Watch", src?.label ?? (p?.kind === "anime" ? (p.lang === "dub" ? "English dub" : "Subtitled") : "Source")],
    ["subs", "Subtitles", subIdx < 0 || !subs[subIdx] ? "Off" : `${subs[subIdx].label}, ${pref.size}`],
    ["picture", "Picture", pref.fit],
    ["playback", "Playback", `${speed}x, auto-skip ${pref.autoSkip ? "on" : "off"}`],
    ["together", "Together", room ? `${members.length} watching` : "Off"],
  ] as [Tab, string, string][]).filter(([k]) => p?.kind !== "live" || k === "watch" || k === "subs" || k === "picture");
  const h3 = "font-display text-2xl font-extrabold";

  const common = {
    ref: video, autoPlay: true, playsInline: true, referrerPolicy: "no-referrer", className: "size-full",
    onLoadedMetadata: (e: React.SyntheticEvent<HTMLVideoElement>) => { onMeta(e.currentTarget); e.currentTarget.playbackRate = speed; setDur(e.currentTarget.duration || 0); },
    onError: () => fail("The video failed to load."),
    onPlaying: () => { setPaused(false); onPlaying(); },
    onPlay: () => { setPaused(false); if (!applying.current) sendRoom({ type: "play", position: pos() }); },
    onPause: () => { setPaused(true); save(); if (!applying.current) sendRoom({ type: "pause", position: pos() }); },
    onSeeked: () => !applying.current && sendRoom({ type: "seek", position: pos() }),
    onTimeUpdate: (e: React.SyntheticEvent<HTMLVideoElement>) => { keep.current = e.currentTarget.currentTime; setCur(keep.current); },
    onDurationChange: (e: React.SyntheticEvent<HTMLVideoElement>) => setDur(e.currentTarget.duration || 0),
    onEnded: () => { save(); if (next) go(next); },
    children: subs.map((t, i) => <track key={t.url} kind="subtitles" src={t.url} label={t.label} srcLang={t.lang || "en"} default={i === subIdx} />),
  };
  const Media = src?.hls ? HlsJsVideo : Video;

  return (
    <main className="relative h-dvh w-full overflow-hidden bg-black" style={{ "--cue-size": SUB_SIZE[pref.size] } as React.CSSProperties}
      onKeyDown={(e) => { if (e.key === "Escape" && panel) { e.stopPropagation(); setPanel(null); } }}>
      <VideoPlayer>
        <VideoSkin className="reelm-skin size-full" style={{ "--media-accent-color": "#F2B45A", "--media-accent-text-color": "#15171F", "--media-border-radius": "0", "--media-border-color": "transparent", "--media-object-fit": FIT[pref.fit], "--media-font-family": "var(--font-figtree), sans-serif" } as React.CSSProperties}>
          {src && <Media key={si + pkey + nonce} src={src.src} crossOrigin={src.hls ? "anonymous" : undefined} {...(common as object)} />}
          {src && !src.dolby && p?.kind !== "live" && <SeekPreview key={si + pkey + nonce} src={src.src} hls={src.hls} duration={() => video.current?.duration ?? 0} />}
          {!src && !err && <div className="pointer-events-none absolute inset-0 grid place-items-center"><div className="flex flex-col items-center gap-4"><ReelMark className="size-14 animate-spin [animation-duration:1.6s]" /><p className="text-sm text-foreground/72">{p ? "Finding a stream" : "Joining the room"}</p></div></div>}

          {/* pause marquee */}
          {showMq && (
            <div className="page-in pointer-events-none absolute inset-0 z-10 bg-gradient-to-r from-[#06060a]/85 via-[#06060a]/55 to-[#06060a]/70">
              <div className="absolute top-20 left-4 w-[min(46rem,calc(100%-2rem))] md:top-24 md:left-[5%] md:w-[min(46rem,46%)]">
                {show && <p className="mb-3 font-semibold text-primary md:text-lg">{show}</p>}
                <Marquee as="h2" word text={big} className="text-[clamp(40px,6.4vw,104px)]" />
                {synopsis && <p className="mt-4 line-clamp-3 max-w-[40rem] text-[15px] leading-relaxed text-[#cfcbd9] md:text-base">{synopsis}</p>}
                {chips.length > 0 && <div className="mt-4"><Chips items={chips} /></div>}
              </div>
              <div className="pointer-events-auto absolute bottom-24 left-4 flex w-[min(26rem,calc(100%-2rem))] flex-col gap-3 md:bottom-28 md:left-[5%]">
                {room && members.length > 0 && (
                  <button onClick={() => setPanel("together")} className="beam flex items-center gap-2 self-start rounded-full bg-[#101016]/80 py-1 pr-3.5 pl-1 text-sm text-[#dad6e4]">
                    <span className="flex">{members.slice(0, 4).map((m, i) => <span key={m.id} className={cn(i > 0 && "-ml-2.5")}><Avatar id={m.avatar} size={28} /></span>)}</span>{members.length} watching
                  </button>
                )}
                {next && (
                  <button onClick={() => go(next)} className="beam grid grid-cols-[7.5rem_1fr] items-center gap-3 rounded-xl bg-white/7 p-2 text-left backdrop-blur hover:bg-white/12">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    {nextEp?.still || p?.poster ? <img src={nextEp?.still || p?.poster} alt="" referrerPolicy="no-referrer" className="aspect-video w-full rounded-md object-cover" /> : <span className="aspect-video rounded-md bg-haze" />}
                    <span className="min-w-0"><small className="block text-xs text-[#a3a1ae]">{nextLabel}</small><b className="line-clamp-2 font-display text-lg leading-tight">{nextName}</b></span>
                  </button>
                )}
              </div>
            </div>
          )}

          {audioNote && <p role="status" className="pointer-events-none absolute bottom-28 left-1/2 z-10 -translate-x-1/2 rounded-full bg-dusk/80 px-4 py-2 text-sm ring-1 ring-white/15 backdrop-blur">{audioNote}</p>}
          {skip && !err && !pref.autoSkip && (
            <button onClick={() => { video.current!.currentTime = skip.e; }} className={cn(pill.secondary, "absolute right-4 bottom-28 z-10 bg-dusk/80 ring-1 ring-white/15 backdrop-blur md:right-8")}>
              <SkipForward />Skip {skip.type.includes("op") ? "intro" : skip.type === "ed" ? "outro" : "recap"}
            </button>
          )}

          <Fade className="absolute inset-x-0 top-0 z-20 flex items-center gap-2 bg-gradient-to-b from-black/80 to-transparent px-3 pt-[max(env(safe-area-inset-top),12px)] pb-10 max-sm:pr-28 md:px-6">
            <Link href={back} aria-label="Back" className={ctl}><ArrowLeft /></Link>
            <div className={cn("min-w-0 flex-1 transition-opacity", showMq && "opacity-0")}>
              <h1 className="truncate font-display text-lg font-semibold md:text-xl">{p?.title ?? "Watch together"}</h1>
              {sub && <p className="truncate text-xs text-foreground/72">{sub}</p>}
            </div>
            {devices.filter((d) => d.kind === "tv").map((d) => <button key={d.deviceId} className={cn(pill.ghost, "h-10")} onClick={() => handoff(d.deviceId)}><MonitorPlay className="size-4" /><span className="hidden sm:inline">Continue on {d.deviceName}</span></button>)}
            {next && <button onClick={() => go(next)} className={cn(pill.ghost, "h-10 px-3")}><SkipForward className="size-4" /><span className="hidden sm:inline">Next episode</span></button>}
            {p?.kind !== "live" && (
              <button onClick={() => setPanel("together")} className={cn(pill.ghost, "h-10 px-4", room && "text-primary")} aria-label="Watch together">
                <Users className="size-4" /><span className="hidden sm:inline">{room ? `Room ${room}` : "Watch together"}</span>
                {room && members.length > 0 && <span className="rounded-full bg-primary/20 px-1.5 text-xs">{members.length}</span>}
              </button>
            )}
            {p && <button onClick={() => setPanel(panel ? null : "watch")} aria-expanded={!!panel} aria-label="Settings: source, subtitles, picture and playback" className={cn(pill.ghost, "h-10 px-4 font-semibold")}>
              <span className="hidden sm:inline">{src?.label.split(" · ").find((t) => /^(\d{3,4}p|auto)$/i.test(t)) ?? "Settings"}</span><Settings2 className="size-4" /></button>}
          </Fade>

          {/* settings: the side ticket, groups on the stub, options on the body */}
          {panel && p && (
            <div className="absolute inset-0 z-40" onClick={(e) => e.target === e.currentTarget && setPanel(null)}>
              <aside aria-label="Player settings" className="page-in absolute inset-x-2 bottom-2 flex max-h-[80%] flex-col overflow-hidden rounded-[22px] bg-[#101016]/95 shadow-[-12px_0_48px_rgb(0_0_0/0.5)] ring-1 ring-white/8 backdrop-blur-xl md:inset-x-auto md:top-20 md:right-6 md:bottom-24 md:grid md:max-h-none md:w-[min(44rem,calc(100%-3rem))] md:grid-cols-[11rem_1fr]">
                <div role="tablist" aria-orientation="vertical" aria-label="Settings" className="no-scrollbar flex shrink-0 gap-1 overflow-x-auto border-b-2 border-dashed border-white/15 bg-white/3 p-2 md:flex-col md:overflow-visible md:border-r-2 md:border-b-0 md:p-3">
                  {tabs.map(([k, label, hint]) => (
                    <button key={k} role="tab" aria-selected={panel === k} onClick={() => setPanel(k)} className={cn("beam grid shrink-0 rounded-xl px-3 py-2 text-left", panel === k ? "bg-primary text-primary-foreground font-semibold" : "text-[#cfcbd9] hover:bg-white/6")}>
                      {label}<small className={cn("hidden max-w-[9rem] truncate text-xs font-normal md:block", panel === k ? "text-[#4a3818]" : "text-[#a3a1ae]")}>{hint}</small>
                    </button>
                  ))}
                </div>
                <div role="tabpanel" className="relative min-h-0 space-y-2 overflow-y-auto p-4 md:p-6">
                  <button aria-label="Close settings" onClick={() => setPanel(null)} className={cn(ctl, "absolute top-2 right-2 size-9")}><X /></button>
                  {panel === "watch" && <>
                    <h3 className={h3}>Watch</h3>
                    {sources.length > 0 && <p className="mt-2 text-sm text-[#a3a1ae]">{sources.length > 1 ? "Source" : "Source (only one available)"}</p>}
                    <div className="space-y-2">{sources.map((x, i) => <SourceStub key={i} x={x} on={si === i && !embed} tag={tag.i === i ? (tag.pin ? `Pinned ${tag.pin}p` : "Auto") : undefined} onPick={() => { setEmbed(false); setSi(i); if (p?.kind === "movie" && x.height) { setPin(playKey(p), x.height); setTag({ i, pin: x.height }); } }} />)}</div>
                    {embedUrl && <button onClick={() => { setEmbed(true); setErr(""); setPanel(null); }} aria-pressed={embed} className={cn("beam w-full rounded-xl border-l-2 border-dashed border-white/25 bg-white/5 px-4 py-3 text-left", embed && "shadow-[inset_0_0_0_2px_#f2b45a]")}>
                      <b className="font-display text-lg">Web player</b><span className="block text-xs text-[#a3a1ae]">Plays in an embedded page when no source works here</span></button>}
                    {!!helperVersion() && !helperVega() && <a href={HELPER_URL()} target="_blank" rel="noreferrer" className="block text-xs text-foreground/52 underline">Update Reelm Helper for more sources</a>}
                    {p.kind === "anime" && <Opts label="Audio" items={["sub", "dub"] as const} value={(p.lang ?? "sub") as "sub" | "dub"} onPick={(l) => go({ ...p, lang: l })} name={(l) => (l === "sub" ? "Japanese, subtitled" : "English dub")} />}
                    {p.kind === "anime" && eps.length > 1 && <Opts label="Episode" items={eps.map((e) => e.number as number)} value={p.ep ?? 0} onPick={(n) => { const e = eps.find((x) => x.number === n); if (e && n !== p.ep) go({ ...p, ep: n, epId: e.id }); }} />}
                    {series?.seasons?.length > 0 && <>
                      <Opts label="Season" items={series.seasons.map((x: any) => x.season as number)} value={p.season ?? 0} onPick={(n) => n !== p.season && go({ ...p, season: n, episode: 1 })} />
                      <Opts label="Episode" items={Array.from({ length: series.seasons.find((x: any) => x.season === p.season)?.episodes ?? 0 }, (_, i) => i + 1)} value={p.episode ?? 0} onPick={(n) => n !== p.episode && go({ ...p, episode: n })} />
                    </>}
                  </>}
                  {panel === "subs" && <>
                    <h3 className={h3}>Subtitles</h3>
                    <Opts label="Track" items={[-1, ...subs.map((_, i) => i)]} value={subIdx} onPick={setSubIdx} name={(i) => (i < 0 ? "Off" : subs[i].label || subs[i].lang)} />
                    {!subs.length && <p className="text-sm text-[#a3a1ae]">This stream has no separate subtitle files.</p>}
                    <Opts label="Size" items={Object.keys(SUB_SIZE) as Pref["size"][]} value={pref.size} onPick={(size) => setPref({ size })} />
                    <Opts label="Position" items={Object.keys(SUB_LINE) as Pref["pos"][]} value={pref.pos} onPick={(pos) => setPref({ pos })} />
                  </>}
                  {panel === "picture" && <>
                    <h3 className={h3}>Picture</h3>
                    <Opts label="Fit" items={Object.keys(FIT) as Pref["fit"][]} value={pref.fit} onPick={(fit) => setPref({ fit })} />
                  </>}
                  {panel === "playback" && <>
                    <h3 className={h3}>Playback</h3>
                    <Opts label="Speed" items={SPEEDS} value={speed} onPick={setSpeed} name={(v) => `${v}x`} />
                    <Opts label="Skip intros and recaps on their own" items={[true, false].map(String)} value={String(pref.autoSkip)} onPick={(v) => setPref({ autoSkip: v === "true" })} name={(v) => (v === "true" ? "On" : "Off")} />
                    <p className="text-xs text-[#a3a1ae]">Works where the episode's openings are known, mostly anime.</p>
                  </>}
                  {panel === "together" && <>
                    <h3 className={h3}>Together</h3>
                    {room ? <>
                      <div className="rounded-[18px] border-l-2 border-dashed border-white/25 bg-white/5 p-5 text-center">
                        <p className="text-sm text-foreground/60">Room code</p>
                        <p className="mt-1 font-display text-4xl font-bold tracking-[0.18em] text-primary">{room}</p>
                        <button className={cn(pill.primary, "mt-4 w-full")} onClick={() => { navigator.clipboard?.writeText(roomLink()); setCopied(true); setTimeout(() => setCopied(false), 1800); }}>{copied ? <Check /> : <Copy />}{copied ? "Link copied" : "Copy invite link"}</button>
                        <p className="mt-3 text-xs text-foreground/52">Friends open the link, or enter the code from their avatar menu. Play, pause and seeking stay in sync.</p>
                      </div>
                      <p className="pt-2 text-sm font-semibold">{members.length} watching</p>
                      <ul className="space-y-1">{members.map((m) => <li key={m.id} className="flex items-center gap-3 rounded-xl px-2 py-1.5"><Avatar id={m.avatar} size={34} /><span className="flex-1">{m.name}</span>{m.id === youId.current && <span className="text-xs text-foreground/52">You</span>}</li>)}</ul>
                      <button className={pill.ghost} onClick={() => { setPanel(null); router.replace(href(p)); }}>Leave room</button>
                    </> : <>
                      <p className="text-foreground/72">Start a room and share the code. Everyone in it plays, pauses and seeks together.</p>
                      <button className={pill.primary} onClick={startRoom}><Users />Start a room</button>
                    </>}
                    {devices.some((d) => d.kind === "tv") && <div className="flex flex-wrap gap-2 pt-2">{devices.filter((d) => d.kind === "tv").map((d) => <button key={d.deviceId} className={pill.secondary} onClick={() => handoff(d.deviceId)}><MonitorPlay />Continue on {d.deviceName}</button>)}</div>}
                    {roomErr && <p role="alert" className="text-sm text-destructive">{roomErr}</p>}
                  </>}
                </div>
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
