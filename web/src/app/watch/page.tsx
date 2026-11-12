"use client";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { selectControls, usePlayer } from "@videojs/react";
import { HlsJsVideo } from "@videojs/react/media/hlsjs-video";
import { Video, VideoPlayer, VideoSkin } from "@videojs/react/video";
import "@videojs/react/video/skin.css";
import { ArrowLeft, Check, Copy, RotateCcw, Settings2, SkipForward, Users, X } from "lucide-react";
import { Avatar, Chips, Marquee, pill, timeLeft } from "@/components/app";
import { ReelMark } from "@/components/reel";
import { SeekPreview } from "@/components/seek-preview";
import { hereFor, PlayOnTv, RemotePanel } from "@/components/play-on-tv";
import { setRemoteHere, useHub, type Now } from "@/lib/hub";
import { frac } from "@/lib/eps";
import { get, getHistory, epKey, playKey, proxied, reelm, saveProgress, syncHistory, toPlay, WS, type Play as P } from "@/lib/core";
import { track } from "@/lib/telemetry";
import { helperVega, helperVersion } from "@/lib/bridge";
import { bitrate, dv, getBandwidth, getPin, pickStream, risky, saveBandwidth, setPin } from "@/lib/pick-stream";
import { fallbackOrder, loadStats, qualities, recordStat } from "@/lib/src-rank";
import { useApi, useSession } from "@/lib/session";
import { languageName, trackLabel, useAudioTracks, useLibavAudio } from "@/lib/use-libav-audio";
import { iso1 } from "@/lib/langs";
import { cn } from "@/lib/utils";
type Source = { src: string; hls: boolean; label: string; codec?: string; dolby?: boolean; height?: number; langs?: string[] };
type Sub = { url: string; label: string; lang: string; default?: boolean };
type Member = { id: string; name: string; avatar: string };
type Skip = { s: number; e: number; type: string };

// remembered audio language, ISO 639-1 ("hi"); older builds stored the track's 639-2 tag ("hin")
const LANG_KEY = "reelm.audioLang";
const savedLang = () => { try { return iso1(localStorage.getItem(LANG_KEY) ?? ""); } catch { return ""; } };
/** pickStream over the sources that carry `lang` (all of them when none do), as an index into `all` */
const pickFor = (all: Source[], lang: string, pin?: number) => {
  const ix = all.map((_, i) => i).filter((i) => !lang || all[i].langs?.includes(lang)), pool = ix.length ? ix : all.map((_, i) => i);
  return pool[pickStream(pool.map((i) => all[i]), { bandwidthBps: getBandwidth(), pinnedHeight: pin })];
};
const ALL_KEY = "reelm.allSources";
/** AniSkip and TheIntroDB (same shape from /api/introdb); no end = the end of the video */
const toSkips = (d: any): Skip[] => (d.results ?? []).map((x: any) => ({ s: x.interval.startTime, e: x.interval.endTime ?? Infinity, type: x.skipType }));
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
  const parts = x.label.split(" · "), q = x.height ? `${x.height}p` : parts.find((t) => /^(\d{3,4}p|auto|4k)$/i.test(t)) ?? parts[parts.length - 1];
  const host = parts.length > 1 ? parts[0] : "Stream", extra = parts.slice(1).map((t) => t.replace(/\s*\b\d{3,4}p$/, "")).filter((t) => t && t !== q); // "CF Worker 720p" -> "CF Worker"
  return (
    <button onClick={onPick} aria-pressed={on} className={cn("beam grid w-full grid-cols-[auto_1fr] items-center gap-x-4 gap-y-1 rounded-xl border-l-2 border-dashed border-white/25 bg-gradient-to-br from-[#25222f] to-[#121219] px-4 py-3 text-left", on && "shadow-[inset_0_0_0_2px_#f2b45a]")}>
      <span className="row-span-2 min-w-16 font-display text-2xl leading-none font-extrabold">{q}{x.codec && <small className="mt-1 block font-sans text-xs font-medium text-[#a3a1ae]">{x.codec}</small>}</span>
      <b className="text-[15px]">{host}{on && <span className="ml-2 text-xs font-semibold text-signal">Playing</span>}{tag && <span className="ml-2 text-[10px] font-semibold text-[#a3a1ae]">{tag}</span>}</b>
      <span className="flex flex-wrap gap-1 text-xs text-[#cfcbd9]">
        {x.hls && <span className="rounded-full bg-white/8 px-2 py-0.5">Adaptive</span>}
        {extra.map((t) => <span key={t} className={cn("rounded-full px-2 py-0.5", /dolby/i.test(t) ? "bg-primary/16 text-primary" : "bg-white/8")}>{t}</span>)}
        {x.dolby && !extra.some((t) => /dolby/i.test(t)) && <span className="rounded-full bg-primary/16 px-2 py-0.5 text-primary">Dolby audio</span>}
        {!!x.langs?.length && <span className="rounded-full bg-white/8 px-2 py-0.5">{x.langs.map(languageName).join(" · ")}</span>}
      </span>
    </button>
  );
}

/** A skip button shows while inside its interval, for 8 s after it appears, then only with the player's controls. */
function SkipButton({ k, onSkip, children }: { k: string; onSkip: () => void; children: React.ReactNode }) {
  const controls = usePlayer(selectControls)?.controlsVisible ?? true, [fresh, setFresh] = useState(true);
  useEffect(() => { setFresh(true); const t = setTimeout(() => setFresh(false), 8000); return () => clearTimeout(t); }, [k]);
  return <button onClick={onSkip} className={cn(pill.secondary, "absolute right-4 bottom-28 z-10 bg-dusk/80 ring-1 ring-white/15 backdrop-blur transition-opacity duration-300 md:right-8", !fresh && !controls && "pointer-events-none opacity-0")}>{children}</button>;
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
  const wsRef = useRef<WebSocket | null>(null);
  const applying = useRef(false), youId = useRef("");
  const [sources, setSources] = useState<Source[]>([]), [si, setSi] = useState(0), [tag, setTag] = useState({ i: -1, pin: 0 });
  const [subs, setSubs] = useState<Sub[]>([]), [subIdx, setSubIdx] = useState(-1);
  const [err, setErr] = useState(""), [nonce, setNonce] = useState(0), [embed, setEmbed] = useState(false);
  // same VidSrc fallback as the apps' WebEmbedPlayer; needs an IMDb id (Cinemeta titles have one, MovieBox ids don't)
  // TMDB-only titles ("tmdb:movie:<n>") use ?tmdb=
  const ext = p && p.kind === "movie" ? (/^tt\d+$/.test(p.id) ? `imdb=${p.id}` : /^tmdb:(movie|tv):\d+$/.test(p.id) ? `tmdb=${p.id.split(":")[2]}` : "") : "";
  const embedUrl = ext && (p!.type === "series" ? `https://vsembed.ru/embed/tv?${ext}&season=${p!.season ?? 1}&episode=${p!.episode ?? 1}` : `https://vsembed.ru/embed/movie?${ext}`);
  const [members, setMembers] = useState<Member[]>([]), [remote, setRemote] = useState<string | null>(null); // remote: the TV this page handed off to
  const [eps, setEps] = useState<any[]>([]), [skips, setSkips] = useState<Skip[]>([]);
  const [cur, setCur] = useState(0), [dur, setDur] = useState(0), [paused, setPaused] = useState(true), [panel, setPanel] = useState<Tab | null>(null);
  const [copied, setCopied] = useState(false), [roomErr, setRoomErr] = useState("");
  const [want, setWant] = useState(savedLang); // the audio language to prefer, "" = any
  // quality-first: the chip picked (0 = Auto), the height fallbacks aim for, sources already tried, a note when it had to go lower
  const [qsel, setQsel] = useState(0), [note, setNote] = useState(""), [toast, setToast] = useState(""), [more, setMore] = useState(false);
  const [allSrc, setAllSrc] = useState(false);
  useEffect(() => { try { setAllSrc(localStorage.getItem(ALL_KEY) === "1"); } catch {} }, []);
  const target = useRef(0), tried = useRef(new Set<number>()), exhausted = useRef(""), failRef = useRef<(m: string) => void>(() => {}), stalled = useRef("");
  /** best-ranked source of height `h` (fallbacks start over from there) */
  const choose = (list: Source[], lang: string, h: number) => { target.current = h; tried.current = new Set(); exhausted.current = ""; setNote(""); return fallbackOrder(list, { lang, height: h, stats: loadStats() })[0]; };
  const pid = s.profile?.id ?? "guest", pkey = p ? JSON.stringify(p) : "";
  const extra = room ? `&room=${room}` : "";

  // resolve the stream (re-run by Retry)
  useEffect(() => {
    if (!p) return;
    let live = true;
    setErr(""); setSources([]); setSubs([]); setSkips([]); setSi(-1); setTag({ i: -1, pin: 0 }); setQsel(0); setMore(false); keep.current = 0;
    target.current = 0; tried.current = new Set(); exhausted.current = ""; setNote("");
    // TheIntroDB for movies and series: by IMDb id (Cinemeta), else the TMDB id
    const sid = p.kind === "movie" ? (/^tt\d+$/.test(p.id) ? p.id : p.id.match(/^tmdb:(?:movie|tv):(\d+)$/)?.[1]) : undefined;
    if (sid) get(`introdb?id=${sid}&s=${p.season ?? 0}&e=${p.episode ?? 0}`).then((d) => live && setSkips(toSkips(d))).catch(() => {});
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
        if (r.malId) get(`aniskip?mal=${r.malId}&ep=${p.ep}`).then((d) => live && setSkips(toSkips(d))).catch(() => {});
        return [[{ src: proxied(r.master, r.referer), hls: true, label: "Auto" }], r.subtitles.map((x: Sub) => ({ ...x, url: proxied(x.url, r.referer) }))] as const;
      }
      if (!document.documentElement.dataset.reelmHelper) throw new Error("helper");
      // progressive: sources join as each provider lands; play once the first ones are in plus a 4 s grace (or all are done), never switch for late ones.
      // After the grace, wait for one this browser can surely decode (not HEVC/MKV/Dolby Vision), up to 15 s.
      let acc: Source[] = [], started = false, grace: ReturnType<typeof setTimeout> | undefined, subbed = false, waited = false;
      const ready = () => waited && acc.some((x) => !risky({ ...x, url: x.src }) && !dv(x));
      const tl = Date.now();
      const begin = () => {
        if (started || !live || !acc.length) return;
        started = true; clearTimeout(grace);
        const pin = getPin(playKey(p)) ?? 0, lang = savedLang(), h = acc[pickFor(acc, lang, pin)].height ?? 0, i = choose(acc, lang, h);
        setQsel(pin ? h : 0); setSi(i); setTag({ i, pin: pin && acc[i].height === pin ? pin : 0 });
        console.debug(`[stream] start ${acc[i].label} (of ${acc.length}) after ${Date.now() - tl} ms`);
      };
      setMore(true);
      await (await import("@/lib/movies")).streamAll(new URLSearchParams({ id: p.id, type: p.type ?? "movie", title: p.title, year: String(p.year ?? ""), s: String(p.season ?? 0), e: String(p.episode ?? 0) }), (part) => {
        if (!live) return;
        acc = [...acc, ...part.map((x) => ({ src: x.url, hls: /\.m3u8(\?|$)/.test(x.url), label: x.label, codec: x.codec, dolby: x.dolby, height: x.height, langs: x.langs }))];
        setSources(acc);
        console.debug(`[stream] +${part.length} = ${acc.length} at ${Date.now() - tl} ms`);
        const sb = part.find((x) => x.subtitles?.length)?.subtitles;
        if (sb && !subbed) { subbed = true; const b = sb.map((x: Sub) => ({ ...x, url: `/api/sub?u=${encodeURIComponent(x.url)}` })), d = b.findIndex((x: Sub) => x.default); setSubs(b); setSubIdx(d >= 0 ? d : 0); }
        if (!grace) { grace = setTimeout(begin, 15000); setTimeout(() => { waited = true; if (ready()) begin(); }, 4000); }
        else if (ready()) begin();
      }).finally(() => live && setMore(false));
      begin();
      return null;
    })().then((r) => {
      if (!live || !r) return;
      const [a, b] = r;
      setSources([...a]); setSubs([...b]); setSi(0);
      const d = b.findIndex((x: Sub) => x.default);
      setSubIdx(b.length ? (d >= 0 ? d : 0) : -1);
    }).catch((e) => live && setErr(e.message));
    if (p.kind === "anime") get(`anime/episodes?slug=${p.id}`).then((d) => live && setEps(d)).catch(() => {});
    return () => { live = false; };
  }, [pkey, nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  const src = sources[si], fileUrl = src && !src.hls ? src.src : undefined, srcKey = `${si}${pkey}${nonce}`;
  // Multi-audio files (Hindi + English releases): list the tracks; a remembered language is picked again automatically
  const [pick, setPick] = useState<{ k: string; i: number } | null>(null); // a pick only holds for the source it was made on
  const { tracks: raw, report } = useAudioTracks(fileUrl, srcKey, !!src?.dolby || (pick?.k === srcKey && pick.i !== undefined));
  // many releases leave their tracks untagged ("Stereo", "Stereo"): then the release name's order ("Hindi & English") is the track order
  const untagged = src?.langs?.length === raw.length && raw.every((t) => !languageName(t.lang));
  const tracks = untagged ? raw.map((t, i) => ({ ...t, lang: src!.langs![i] })) : raw;
  const chosen = pick?.k === srcKey ? pick.i : tracks.find((t) => want && iso1(t.lang) === want)?.index ?? tracks[0]?.index;
  const atrack = chosen === undefined || chosen === tracks[0]?.index ? undefined : chosen; // the first track plays natively
  const pickTrack = (index: number) => {
    setPick({ k: srcKey, i: index }); saveLang(iso1(tracks.find((t) => t.index === index)?.lang ?? ""));
  };
  const saveLang = (l: string) => { setWant(l); try { localStorage.setItem(LANG_KEY, l); } catch {} };
  // every language a source is tagged with or the playing file carries; picking one moves to a source that has it
  const langList = [...new Set([...sources.flatMap((x) => x.langs ?? []), ...tracks.map((t) => iso1(t.lang)).filter((l) => languageName(l))])];
  const pickLang = (l: string) => {
    saveLang(l); setPick(null); // the track follows `want` (chosen above) once the file's tracks are read
    if (!l || src?.langs?.includes(l) || tracks.some((t) => iso1(t.lang) === l) || !sources.some((x) => x.langs?.includes(l))) return;
    setEmbed(false); setSi(choose(sources, l, sources[pickFor(sources, l, p?.kind === "movie" ? getPin(playKey(p)) : undefined)].height ?? 0));
  };
  // a quality chip (0 = Auto, the connection-aware pick); remembered like a picked source
  const pickQ = (h: number) => {
    if (p?.kind !== "movie" || !sources.length) return;
    setPin(playKey(p), h); setQsel(h); setEmbed(false); setPick(null);
    const i = choose(sources, want, h || (sources[pickFor(sources, want, 0)].height ?? 0));
    setSi(i); setTag({ i, pin: h });
  };
  const pickOne = (i: number) => {
    const h = sources[i].height ?? 0;
    setEmbed(false); choose(sources, want, h); setSi(i); setQsel(h);
    if (p?.kind === "movie" && h) { setPin(playKey(p), h); setTag({ i, pin: h }); }
  };
  const quals = p?.kind === "movie" ? qualities(sources, langList.includes(want) ? want : "") : [];
  // "Show all sources": every source, grouped under its height (best first), ranked within it
  const listed = panel === "watch" && allSrc ? fallbackOrder(sources, { height: Infinity, stats: loadStats() }) : [];
  // Dolby/DTS audio Chrome can't decode, or a track the browser won't switch to: libav.js decodes it in a worker in sync with the video
  const audioNote = useLibavAudio(video, fileUrl, !!src?.dolby, srcKey, atrack, report);
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
    setErr(""); // a source that plays (a fallback or a manual pick) clears the last one's error overlay
    // a codec the browser can't decode (Dramachi's HEVC) still plays the sound over a black picture: treat that as a failure
    const v = video.current;
    if (v && p?.kind !== "live") setTimeout(() => { if (video.current === v && !v.paused && !v.videoWidth) failRef.current("This source plays sound but no picture in this browser."); }, 3000);
    if (!startedAt.current) return;
    track("play", info());
    track("source_used", { ...info(), ttff: Date.now() - startedAt.current });
    recordStat(src?.label, { ok: true, ttff: Date.now() - startedAt.current });
    startedAt.current = 0;
  };
  useEffect(() => {
    const i = setInterval(() => { const v = video.current; if (src && v && !v.paused) track("heartbeat", { ...info(), position: Math.round(v.currentTime), duration: Math.round(v.duration || 0) }); }, 60_000);
    return () => clearInterval(i);
  }); // eslint-disable-line react-hooks/exhaustive-deps
  // fall over: the next-ranked source of the same height, then the nearest lower one (with a note); late sources count too
  const next1 = () => fallbackOrder(sources, { lang: want, height: target.current, stats: loadStats() }).find((i) => !tried.current.has(i));
  const fail = (m: string) => {
    if (src) { track("play_error", { ...info(), error: m }); recordStat(src.label, { fail: true }); }
    tried.current.add(si);
    const hevc = /hevc|h265/i.test(src?.codec ?? "") && !document.createElement("video").canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"');
    const nx = next1();
    if (nx === undefined) { exhausted.current = hevc ? "hevc" : m; return more ? setToast("Looking for another source…") : setErr(exhausted.current); } // late sources may still land
    const h = sources[nx].height ?? 0, t = target.current;
    if (t && h < t && h !== (src?.height ?? 0)) { const n = `${t}p didn't load — playing ${h ? `${h}p` : "another source"}`; setNote(n); setToast(n); }
    console.debug(`[stream] fallback ${src?.label} -> ${sources[nx].label}: ${m}`);
    setSi(nx);
  };
  failRef.current = fail;
  // every source failed, then a late one landed: try it; nothing more coming: say so
  useEffect(() => {
    const nx = exhausted.current ? next1() : undefined;
    if (nx !== undefined) { exhausted.current = ""; setErr(""); setSi(nx); } else if (exhausted.current && !more) setErr(exhausted.current);
  }, [sources.length, more]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (!toast) return; const t = setTimeout(() => setToast(""), 6000); return () => clearTimeout(t); }, [toast]);
  // no first frame in 40 s (a host that hangs instead of failing): next source. Not when autoplay was blocked (the data is there).
  useEffect(() => {
    if (!src || p?.kind === "live" || remote || embed) return;
    const t = setTimeout(() => { if (startedAt.current && (video.current?.readyState ?? 0) < 3) failRef.current("No picture after 40 s."); }, 40_000);
    return () => clearTimeout(t);
  }, [srcKey, remote, embed]); // eslint-disable-line react-hooks/exhaustive-deps
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

  // play on TV: hand off (ms), pause here and become its remote; the global "Playing on" bar hides for that TV meanwhile
  const { tvs } = useHub();
  useEffect(() => { setRemoteHere(remote); return () => setRemoteHere(null); }, [remote]);
  const toTv = () => ({ payload: p!, positionMs: pos() * 1000, source: p?.kind === "movie" ? src?.label : undefined });
  const sentTo = (tv: string) => { video.current?.pause(); setPanel(null); setRemote(tv); };
  const playHere = (ms: number, n: Now | null) => {
    setRemote(null);
    const np = n && p && hereFor(p, n); // the TV may have moved on to another episode
    if (np && epKey(np) !== epKey(p!)) return router.replace(href(np, `${extra}&t=${Math.round(ms / 1000)}`));
    keep.current = ms / 1000;
    const v = video.current;
    if (v) { v.currentTime = ms / 1000; v.play().catch(() => {}); }
  };
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
  
  // a skip inside its interval; credits that run to the end are only worth a button when a next episode follows (the card below)
  const skip = dur > 0 ? skips.find((k) => cur >= k.s && cur < Math.min(k.e, dur) - 1 && (k.type !== "ed" || k.e < dur - 1 || !!next)) : undefined;
  const credits = skip?.type === "ed" && !!next;
  const [watchCredits, setWatchCredits] = useState(""), [count, setCount] = useState(10);
  const nextCard = credits && watchCredits !== pkey && !err && !remote;
  useEffect(() => { setCount(10); }, [skip?.s]);
  useEffect(() => {
    if (!nextCard || paused) return;
    if (count <= 0) { go(next!); return; }
    const t = setTimeout(() => setCount((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [nextCard, paused, count]); // eslint-disable-line react-hooks/exhaustive-deps

  // settings ticket: subtitle size and position, picture fit, speed, auto-skip (all but speed remembered per browser)
  const [pref, setPrefState] = useState<Pref>(DEFAULT_PREF);
  useEffect(() => { try { setPrefState({ ...DEFAULT_PREF, ...JSON.parse(localStorage.getItem(PREF_KEY) ?? "{}") }); } catch {} }, []);
  const setPref = (n: Partial<Pref>) => setPrefState((o) => { const v = { ...o, ...n }; try { localStorage.setItem(PREF_KEY, JSON.stringify(v)); } catch {} return v; });
  const [speed, setSpeed] = useState(1);
  useEffect(() => { if (video.current) video.current.playbackRate = speed; }, [speed, src]);
  useEffect(() => { if (pref.autoSkip && skip && /op|recap/.test(skip.type) && video.current) video.current.currentTime = Math.min(skip.e, dur); }, [skip?.s, pref.autoSkip]); // eslint-disable-line react-hooks/exhaustive-deps
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
      else if (e.key === "s" && skip && !credits && video.current) video.current.currentTime = Math.min(skip.e, dur);
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
    onPlay: (e: React.SyntheticEvent<HTMLVideoElement>) => { if (remote) return e.currentTarget.pause(); setPaused(false); if (!applying.current) sendRoom({ type: "play", position: pos() }); },
    onPause: () => { setPaused(true); save(); if (!applying.current) sendRoom({ type: "pause", position: pos() }); },
    onSeeked: () => !applying.current && sendRoom({ type: "seek", position: pos() }),
    onTimeUpdate: (e: React.SyntheticEvent<HTMLVideoElement>) => { keep.current = e.currentTarget.currentTime; setCur(keep.current); },
    onWaiting: (e: React.SyntheticEvent<HTMLVideoElement>) => { const v = e.currentTarget; if (!startedAt.current && !v.seeking && v.currentTime > 1 && stalled.current !== srcKey) { stalled.current = srcKey; recordStat(src?.label, { stall: true }); } },
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
          {src && <Media key={`v${si}${pkey}${nonce}`} src={src.src} crossOrigin={src.hls ? "anonymous" : undefined} {...(common as object)} />}
          {src && !src.dolby && p?.kind !== "live" && <SeekPreview key={`p${si}${pkey}${nonce}`} src={src.src} hls={src.hls} duration={() => video.current?.duration ?? 0} />}
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

          {toast && <p role="status" className="pointer-events-none absolute top-20 left-1/2 z-30 -translate-x-1/2 rounded-full bg-dusk/80 px-4 py-2 text-sm ring-1 ring-white/15 backdrop-blur">{toast}</p>}
          {audioNote && <p role="status" className="pointer-events-none absolute bottom-28 left-1/2 z-10 -translate-x-1/2 rounded-full bg-dusk/80 px-4 py-2 text-sm ring-1 ring-white/15 backdrop-blur">{audioNote}</p>}
          {skip && !credits && !err && !(pref.autoSkip && /op|recap/.test(skip.type)) && (
            <SkipButton k={`${pkey}${skip.s}`} onSkip={() => { video.current!.currentTime = Math.min(skip.e, dur); }}>
              <SkipForward />Skip {skip.type.includes("op") ? "intro" : skip.type === "ed" ? "credits" : skip.type}
            </SkipButton>
          )}
          {nextCard && (
            <div role="status" className="page-in absolute right-4 bottom-28 z-10 w-[min(22rem,calc(100%-2rem))] rounded-[22px] bg-[#101016]/90 p-3 ring-1 ring-white/10 backdrop-blur-xl md:right-8">
              <button onClick={() => go(next!)} className="beam grid w-full grid-cols-[7rem_1fr] items-center gap-3 rounded-xl text-left">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {nextEp?.still || p?.poster ? <img src={nextEp?.still || p?.poster} alt="" referrerPolicy="no-referrer" className="aspect-video w-full rounded-md object-cover" /> : <span className="aspect-video rounded-md bg-haze" />}
                <span className="min-w-0"><small className="block text-xs font-semibold text-primary">Next episode in {count}</small><b className="line-clamp-2 font-display text-lg leading-tight">{nextName}</b></span>
              </button>
              <div className="track-bar mt-3"><i className="transition-[width] duration-1000 ease-linear" style={{ width: `${(10 - count) * 10}%` }} /></div>
              <div className="mt-2 flex gap-2">
                <button onClick={() => go(next!)} className={cn(pill.primary, "h-9 flex-1 px-4 text-sm")}><SkipForward />Next episode</button>
                <button onClick={() => setWatchCredits(pkey)} className={cn(pill.ghost, "h-9 px-4 text-sm")}>Watch credits</button>
              </div>
            </div>
          )}

          <Fade className="absolute inset-x-0 top-0 z-20 flex items-center gap-2 bg-gradient-to-b from-black/80 to-transparent px-3 pt-[max(env(safe-area-inset-top),12px)] pb-10 max-sm:pr-28 md:px-6">
            <Link href={back} aria-label="Back" className={ctl}><ArrowLeft /></Link>
            <div className={cn("min-w-0 flex-1 transition-opacity", showMq && "opacity-0")}>
              <h1 className="truncate font-display text-lg font-semibold md:text-xl">{p?.title ?? "Watch together"}</h1>
              {sub && <p className="truncate text-xs text-foreground/72">{sub}</p>}
            </div>
            {p && p.kind !== "live" && <PlayOnTv icon get={toTv} onSent={sentTo} className={ctl} />}
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
                    {langList.length > 0 && <Opts label="Audio language" items={["", ...langList]} value={langList.includes(want) ? want : ""} onPick={pickLang} name={(l) => (l ? languageName(l) || l : "Any")} />}
                    {quals.length > 0 && <div>
                      <p className="mt-2 mb-1.5 text-sm text-[#a3a1ae]">Quality</p>
                      <div role="radiogroup" aria-label="Quality" className="flex flex-wrap gap-1.5">
                        {[0, ...quals.map((x) => x.h)].map((h) => { const n = quals.find((x) => x.h === h)?.n ?? 0; return (
                          <button key={h} role="radio" aria-checked={qsel === h} onClick={() => pickQ(h)} className="beam opt">{h ? `${h}p` : "Auto"}
                            {n > 1 && <small className="font-normal opacity-60">· {n}</small>}{!!h && !embed && src?.height === h && <small className="text-signal">Playing</small>}</button>); })}
                      </div>
                    </div>}
                    {note && <p className="text-xs text-[#a3a1ae]">{note}</p>}
                    {more && <p className="text-xs text-[#a3a1ae]">Looking for more sources…</p>}
                    {allSrc && sources.length > 1 ? <div className="space-y-2">{listed.map((i, k) => { const h = sources[i].height ?? 0; return <div key={i} className="space-y-2">
                      {(k === 0 || (sources[listed[k - 1]].height ?? 0) !== h) && <p className="mt-3 text-sm font-semibold text-[#a3a1ae]">{h ? `${h}p` : "Other"}</p>}
                      <SourceStub x={sources[i]} on={si === i && !embed} tag={tag.i === i ? (tag.pin ? `Pinned ${tag.pin}p` : "Auto") : undefined} onPick={() => pickOne(i)} /></div>; })}</div>
                    : src && <>
                      <p className="mt-2 text-sm text-[#a3a1ae]">{sources.length > 1 ? "Playing from" : "Source (only one available)"}</p>
                      <SourceStub x={src} on={!embed} onPick={() => setPanel(null)} />
                    </>}
                    {sources.length > 1 && <Opts label="Show all sources" items={["off", "on"]} value={allSrc ? "on" : "off"} name={(v) => (v === "on" ? "On" : "Off")}
                      onPick={(v) => { setAllSrc(v === "on"); try { localStorage.setItem(ALL_KEY, v === "on" ? "1" : "0"); } catch {} }} />}
                    {embedUrl && <button onClick={() => { setEmbed(true); setErr(""); setPanel(null); }} aria-pressed={embed} className={cn("beam w-full rounded-xl border-l-2 border-dashed border-white/25 bg-white/5 px-4 py-3 text-left", embed && "shadow-[inset_0_0_0_2px_#f2b45a]")}>
                      <b className="font-display text-lg">Web player</b><span className="block text-xs text-[#a3a1ae]">Plays in an embedded page when no source works here</span></button>}
                    {!!helperVersion() && !helperVega() && <a href={HELPER_URL()} target="_blank" rel="noreferrer" className="block text-xs text-foreground/52 underline">Update Reelm Helper for more sources</a>}
                    {tracks.length > 1 && <Opts label="Audio track" items={tracks.map((t) => t.index)} value={chosen ?? tracks[0].index} onPick={pickTrack} name={(ix) => trackLabel(tracks.find((t) => t.index === ix)!, tracks.findIndex((t) => t.index === ix))} />}
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
                    <p className="text-xs text-[#a3a1ae]">Works where the episode's intro is known: most anime and many series.</p>
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
                    {tvs.length > 0 && <div className="pt-2"><PlayOnTv get={toTv} onSent={sentTo} className={pill.secondary} /></div>}
                    {roomErr && <p role="alert" className="text-sm text-destructive">{roomErr}</p>}
                  </>}
                </div>
              </aside>
            </div>
          )}
        </VideoSkin>
      </VideoPlayer>
      {remote && p && (
        <div className="absolute inset-0 z-[60] flex items-end justify-center bg-[#06060a]/80 p-2 backdrop-blur-md md:items-center">
          <RemotePanel tv={remote} fallback={{ title: p.title, subtitle: sub, poster: p.poster }} onPlayHere={playHere} onClose={() => setRemote(null)} />
        </div>
      )}
      {embed && embedUrl && <iframe src={embedUrl} allowFullScreen allow="autoplay; fullscreen; encrypted-media" referrerPolicy="no-referrer" className="absolute inset-x-0 bottom-0 top-16 z-40 h-[calc(100%-4rem)] w-full border-0 bg-black" />}
          {fe && (
            <div className="absolute inset-0 z-50 grid place-items-center bg-dusk/80 p-6 backdrop-blur-md">
              <div role="alert" className="page-in flex max-w-sm flex-col items-center gap-3 rounded-[24px] bg-haze p-7 text-center shadow-[0_24px_60px_rgb(10_12_18/0.6)]">
                <ReelMark className="size-10 opacity-70" />
                <h2 className="font-display text-xl font-semibold">{fe.t}</h2>
                <p className="text-sm text-foreground/72">{fe.b}</p>
                <div className="mt-2 flex gap-2">
                  {err === "helper" && <a href={HELPER_URL()} target="_blank" rel="noreferrer" className={pill.primary}>Install Reelm Helper</a>}
                  <button className={err === "helper" ? pill.secondary : pill.primary} onClick={() => { setErr(""); setSi(-1); setNonce(nonce + 1); }}><RotateCcw />Retry</button>
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
