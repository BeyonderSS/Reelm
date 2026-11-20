"use client";
// Watch party: faces and voices in a room (Cloudflare Realtime through /v1/rtc/*), reactions, date night.
// Room sync itself (play/pause/seek/lights/source) stays in the watch page; this owns everything people-shaped.
import { useCallback, useEffect, useLayoutEffect, useReducer, useRef, useState, type RefObject } from "react";
import { selectControls, usePlayer } from "@videojs/react";
import { Check, Heart, Lightbulb, Mic, MicOff, Plus, Video, VideoOff } from "lucide-react";
import { Avatar, pill } from "@/components/app";
import { clock, duck0, duckGain, duckStep, REACTS, VIDEO_SEATS, type ReactKind } from "@/lib/party";
import { Call, rtcIce, type Kind, type Pub } from "@/lib/rtc";
import { cn } from "@/lib/utils";

export type Member = { id: string; name: string; avatar: string; device?: string; cam?: boolean; mic?: boolean; rtc?: { sessionId: string | null; tracks: Pub[] } | null };
export type Mode = "friends" | "date";
type Float = { k: number; by: string; kind: ReactKind };

export function useParty(room: string, send: (m: object) => void, video: RefObject<HTMLVideoElement | null>, at: () => number) {
  const [members, setMembers] = useState<Member[]>([]), [you, setYou] = useState("");
  const [mode, setModeState] = useState<Mode>("friends");
  const [ok, setOk] = useState<boolean | null>(null); // video+voice: null probing, false = not configured (503): sync only
  const [cam, setCam] = useState(false), [mic, setMic] = useState(false), [busy, setBusy] = useState<Kind | null>(null);
  const [note, setNote] = useState(""), [hidden, setHidden] = useState(false), [tray, setTray] = useState(false);
  const [floats, setFloats] = useState<Float[]>([]), [marks, setMarks] = useState<{ at: number; kind: ReactKind }[]>([]);
  const [burst, setBurst] = useState<number | null>(null);
  const [, bump] = useReducer((x: number) => x + 1, 0);
  const call = useRef<Call | null>(null), ice = useRef<RTCIceServer[]>([]), warned = useRef(false);
  const member = `${room}:${you}`;

  // a new room (or none): start clean, and leaving stops camera and mic and ends the session at once
  useEffect(() => {
    return () => { call.current?.stop(); call.current = null; setMembers([]); setYou(""); setOk(null); setCam(false); setMic(false); setMarks([]); setNote(""); };
  }, [room]);
  useEffect(() => {
    if (!room || !you) return;
    let live = true;
    rtcIce(`${room}:${you}`).then((s) => { if (!live) return; ice.current = s ?? []; setOk(!!s); }).catch(() => live && setOk(false));
    return () => { live = false; };
  }, [room, you]);
  const ensure = () => (call.current ??= new Call(member, ice.current, bump));

  // pull everyone's published tracks; drop what they stopped
  const synced = useRef(new Set<string>());
  useEffect(() => {
    if (!ok || !you) return;
    const others = members.filter((m) => m.id !== you);
    for (const m of others) if (m.rtc?.sessionId && m.rtc.tracks.length) { synced.current.add(m.id); ensure().sync(m.id, m.rtc).catch((e) => console.debug("[party] pull", e)); }
    for (const id of synced.current) {
      const m = others.find((x) => x.id === id);
      if (!m?.rtc?.sessionId || !m.rtc.tracks.length) { synced.current.delete(id); call.current?.sync(id, null).catch(() => {}); }
    }
  }, [members, ok, you]); // eslint-disable-line react-hooks/exhaustive-deps

  const seatsTaken = members.filter((m) => m.id !== you && m.rtc?.tracks.some((t) => t.kind === "video")).length;
  const toggle = useCallback(async (kind: Kind, want?: boolean) => {
    if (!ok || busy) return;
    const on = want ?? !(kind === "video" ? cam : mic);
    if (kind === "video" && on && seatsTaken >= VIDEO_SEATS) return setNote(`${VIDEO_SEATS} people already share video, so you're on voice. A seat frees up when someone turns their camera off.`);
    setBusy(kind); setNote("");
    try {
      const c = ensure(), tracks = await c.set(kind, on);
      setCam(c.live("video")); setMic(c.live("audio"));
      send({ type: "rtc", sessionId: tracks.length ? await c.sessionId() : null, tracks });
      send({ type: "media", cam: c.live("video"), mic: c.live("audio") });
      if (kind === "audio" && on && !warned.current) { warned.current = true; setNote("Headphones keep the film out of your mic."); }
    } catch (e) {
      const n = (e as Error).name;
      setNote(n === "NotAllowedError" ? `Reelm can't use your ${kind === "video" ? "camera" : "microphone"}. Allow it in the browser's site settings, then try again.`
        : n === "NotFoundError" ? `No ${kind === "video" ? "camera" : "microphone"} found.` : "Video and voice didn't connect. Try again in a moment.");
    } finally { setBusy(null); }
  }, [ok, busy, cam, mic, seatsTaken, member]); // eslint-disable-line react-hooks/exhaustive-deps

  const setMode = (m: Mode) => { setModeState(m); send({ type: "mode", mode: m }); };
  const react = (kind: ReactKind) => { send({ type: "react", kind, at: at() }); setTray(false); };

  /** room messages for people; returns nothing, the page handles playback ones */
  const onMsg = (m: any) => { // eslint-disable-line @typescript-eslint/no-explicit-any
    if (m.type === "state") { setYou(m.you); setMembers(m.members ?? []); setModeState(m.mode === "date" ? "date" : "friends"); }
    else if (m.type === "members") setMembers(m.members ?? []);
    else if (m.type === "media") setMembers((ms) => ms.map((x) => (x.id === m.from ? { ...x, cam: !!m.cam, mic: !!m.mic } : x)));
    else if (m.type === "rtc") setMembers((ms) => ms.map((x) => (x.id === m.from ? { ...x, rtc: { sessionId: m.sessionId ?? null, tracks: m.tracks ?? [] } } : x)));
    else if (m.type === "mode") setModeState(m.mode === "date" ? "date" : "friends");
    else if (m.type === "rtc_full") { // the server kept our voice, not our camera
      call.current?.set("video", false).then(() => { setCam(false); send({ type: "media", cam: false, mic: !!call.current?.live("audio") }); });
      setNote(`${VIDEO_SEATS} people already share video, so you're on voice.`);
    } else if (m.type === "react" && m.kind in REACTS) {
      const f = { k: Math.random(), by: m.by, kind: m.kind as ReactKind };
      setFloats((x) => [...x, f]); setTimeout(() => setFloats((x) => x.filter((y) => y !== f)), 2400);
      if (Number.isFinite(m.at)) setMarks((x) => [...x.slice(-60), { at: m.at, kind: f.kind }]);
    } else if (m.type === "sync_heart") { setBurst(m.at); setTimeout(() => setBurst(null), 4000); }
  };

  // film ducking: any remote voice ducks the film to 35 %; the viewer's own volume is the base it ducks from
  const hasVoice = members.some((m) => m.id !== you && m.mic);
  useEffect(() => {
    if (!hasVoice) return;
    let d = duck0(), base = video.current?.volume ?? 1, gain = 1;
    const i = setInterval(() => {
      const v = video.current, c = call.current;
      if (!v || !c) return;
      if (Math.abs(v.volume - base * gain) > 0.02) base = Math.min(1, v.volume / gain); // the viewer moved the slider
      const now = performance.now();
      d = duckStep(d, Math.max(0, ...[...c.streams.keys()].map((id) => c.level(id))), now);
      const g = duckGain(d, now);
      if (g !== gain) { gain = g; v.volume = base * g; }
    }, 50);
    return () => { clearInterval(i); const v = video.current; if (v && gain !== 1) v.volume = base; }; // eslint-disable-line react-hooks/exhaustive-deps -- the element playing now, not the one at start
  }, [hasVoice]); // eslint-disable-line react-hooks/exhaustive-deps

  // M mic, V camera, R react, H hide faces (M is the film's mute when there's no call)
  useEffect(() => {
    if (!room || !you) return;
    const on = (e: KeyboardEvent) => {
      if (/INPUT|TEXTAREA/.test((e.target as HTMLElement).tagName) || e.metaKey || e.ctrlKey || e.altKey) return;
      const k = e.key.toLowerCase();
      if ((k === "m" || k === "v") && ok) toggle(k === "m" ? "audio" : "video");
      else if (k === "r") setTray((t) => !t);
      else if (k === "h") setHidden((h) => !h);
      else return;
      e.preventDefault(); e.stopImmediatePropagation(); // before the skin's own m
    };
    addEventListener("keydown", on, true);
    return () => removeEventListener("keydown", on, true);
  }, [room, you, ok, toggle]);

  return { room, members, you, mode, setMode, ok, cam, mic, busy, toggle, note, hidden, setHidden, tray, setTray, react, floats, marks, burst, onMsg, call, seatsTaken };
}
export type Party = ReturnType<typeof useParty>;

/** srcObject can't be a prop */
function Feed({ stream, self, className }: { stream: MediaStream; self?: boolean; className?: string }) {
  const ref = useCallback((el: HTMLVideoElement | null) => { if (el && el.srcObject !== stream) el.srcObject = stream; }, [stream]);
  return <video ref={ref} autoPlay playsInline muted className={cn("size-full object-cover", self && "-scale-x-100", className)} />;
}

/** Talking ring + level bars, written straight to the DOM ~8 times a second (no re-render of the player). */
function useMeter(party: Party, id: string) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let d = duck0();
    const i = setInterval(async () => {
      const c = party.call.current, el = ref.current;
      if (!c || !el) return;
      const l = id === party.you ? await c.selfLevel() : c.level(id), now = performance.now();
      d = duckStep(d, l, now); // the same 150 ms / 400 ms hysteresis keeps the ring from flickering
      el.dataset.talking = String(d.ducked);
      el.style.setProperty("--lvl", String(Math.min(1, l * 4)));
    }, 120);
    return () => clearInterval(i);
  }, [party.call, party.you, id]);
  return ref;
}
const ring = "data-[talking=true]:shadow-[0_0_0_2.5px_#F2B45A,0_0_18px_rgb(242_180_90/0.45)]";
function Bars() {
  return <span aria-hidden className="absolute top-1.5 left-1.5 flex h-3.5 items-end gap-0.5 opacity-0 transition-opacity group-data-[talking=true]:opacity-100">
    {[0.6, 1, 0.75].map((k, i) => <i key={i} className="w-[3px] rounded-full bg-primary" style={{ height: `calc(max(0.2, var(--lvl, 0) * ${k}) * 14px)` }} />)}
  </span>;
}
function Floats({ party, id, className }: { party: Party; id: string; className?: string }) {
  return <>{party.floats.filter((f) => f.by === id).map((f) => <span key={f.k} aria-hidden className={cn("float-up pointer-events-none absolute left-1/2 -ml-4 text-3xl", className)}>{REACTS[f.kind]}</span>)}</>;
}

/** One person: their camera, or their avatar when it's off. */
function Face({ party, m, className, big }: { party: Party; m: Member; className?: string; big?: boolean }) {
  const self = m.id === party.you, c = party.call.current;
  const stream = self ? c?.self : c?.streams.get(m.id);
  const camOn = self ? party.cam : !!m.cam && !!stream?.getVideoTracks().length;
  const meter = useMeter(party, m.id);
  const tag = self ? "You" : `${m.name}${m.device === "phone" ? " · on phone" : !m.cam && m.mic ? " · voice" : ""}`;
  return (
    <div ref={meter} className={cn("group relative overflow-hidden bg-[#1E2129] transition-shadow", ring, className)}>
      {camOn && stream ? <Feed stream={stream} self={self} /> : <div className="grid size-full place-items-center"><Avatar id={m.avatar} size={big ? 56 : 34} /></div>}
      <Bars />
      {(self ? !party.mic : !m.mic) && (self ? party.cam : m.cam) && <MicOff aria-label="Muted" className="absolute top-1.5 right-1.5 size-4 rounded-full bg-[#15171f]/70 p-0.5" />}
      <span className="absolute bottom-1 left-1.5 max-w-[calc(100%-12px)] truncate rounded-full bg-[#15171f]/75 px-2 py-px text-[11px] font-bold">{tag}</span>
    </div>
  );
}

/** Remote voices play here, whatever the faces are doing (hidden, date night, panel open). */
export function Voices({ party }: { party: Party }) {
  const c = party.call.current;
  if (!c) return null;
  return <>{[...c.streams].map(([id, s]) => <audio key={id} autoPlay ref={(el) => { if (el && el.srcObject !== s) el.srcObject = s; }} />)}</>;
}

/** Faces in the black bar above the film (top right, under the header while the controls show), or date night's bubbles. */
export function Faces({ party }: { party: Party }) {
  const controls = usePlayer(selectControls)?.controlsVisible ?? true;
  if (!party.you) return null;
  const others = party.members.filter((m) => m.id !== party.you), me = party.members.find((m) => m.id === party.you);
  const shown = others.filter((m) => m.cam || m.mic);
  const lastFloat = party.floats.at(-1), who = lastFloat && party.members.find((m) => m.id === lastFloat.by);
  const said = lastFloat && (lastFloat.by === party.you ? "You" : who?.name ?? "Someone") + { heart: " sent a heart", laugh: " laughed", gasp: " gasped", cry: " cried" }[lastFloat.kind];
  const date = party.mode === "date" && others.length > 0, partner = others.find((m) => m.cam) ?? others[0];
  return (
    <>
      {!party.hidden && date && <DateNight party={party} partner={partner} me={me} />}
      {!party.hidden && !date && (shown.length > 0 || party.cam || party.mic) && (
        <div className={cn("absolute right-2 z-20 flex gap-1.5 transition-[top] duration-300 sm:right-4 sm:gap-2", controls ? "top-[68px] md:top-[76px]" : "top-2.5")}>
          {shown.slice(0, 4).map((m, i) => <div key={m.id} className={cn("relative", i >= 3 && "max-sm:hidden")}><Face party={party} m={m} className="h-[52px] w-[78px] rounded-[12px] sm:h-[74px] sm:w-[112px] sm:rounded-[14px]" /><Floats party={party} id={m.id} className="top-6" /></div>)}
          {shown.length > 4 && <span className="grid h-[52px] place-items-center rounded-[12px] bg-[#1E2129] px-2 text-sm font-semibold sm:h-[74px]">+{shown.length - 4}</span>}
          {me && (party.cam || party.mic) && <div className="relative"><Face party={party} m={me} className="size-[52px] rounded-[12px] opacity-90 sm:size-[74px] sm:rounded-[14px]" /><Floats party={party} id={me.id} className="top-6" /></div>}
        </div>
      )}
      {party.tray && (
        <div role="toolbar" aria-label="React" className={cn("page-in absolute right-2 z-30 flex gap-1 rounded-full bg-[#101016]/90 p-1 ring-1 ring-white/10 backdrop-blur sm:right-4", controls ? "top-[132px] md:top-[160px]" : "top-[70px] sm:top-[92px]")}>
          {(Object.keys(REACTS) as ReactKind[]).map((k) => <button key={k} onClick={() => party.react(k)} aria-label={k} className="beam grid size-10 place-items-center rounded-full text-2xl hover:bg-white/10">{REACTS[k]}</button>)}
        </div>
      )}
      {said && (party.hidden || date || !shown.some((m) => m.id === lastFloat.by)) && (
        <div key={lastFloat.k} className="pointer-events-none absolute bottom-32 left-4 z-20 flex flex-col items-start gap-1.5 md:left-8">
          <span className="float-up relative ml-4 text-3xl">{REACTS[lastFloat.kind]}</span>
          <span className="page-in rounded-full bg-[#15171f]/75 px-2.5 py-1 text-xs font-semibold">{said}</span>
        </div>
      )}
      {party.burst !== null && (
        <div role="status" className="pointer-events-none absolute inset-0 z-30 grid place-items-center">
          <div className="flex flex-col items-center gap-3"><Heart className="burst size-28 fill-primary text-primary" /><span className="page-in rounded-full bg-[#15171f]/80 px-3 py-1.5 text-sm font-semibold">You both tapped at {clock(party.burst)}</span></div>
        </div>
      )}
      {controls && <p className="pointer-events-none absolute right-4 bottom-[88px] z-20 hidden text-xs text-foreground/60 md:block">{party.ok ? "M mute · V camera · " : ""}R react · faces hide with H</p>}
    </>
  );
}

/** Date night: the partner as one big round bubble, yourself small. Double-click = heart, hold = whisper (mic only while held). */
function DateNight({ party, partner, me }: { party: Party; partner: Member; me?: Member }) {
  const held = useRef(0), whisper = useRef<Promise<void> | null>(null), [hint, setHint] = useState(true);
  useEffect(() => { const t = setTimeout(() => setHint(false), 8000); return () => clearTimeout(t); }, []);
  const down = () => {
    if (!party.ok || party.mic) return;
    held.current = window.setTimeout(() => { whisper.current = party.toggle("audio", true); }, 350); // a double-click never opens the mic
  };
  const up = () => {
    clearTimeout(held.current);
    const w = whisper.current; whisper.current = null;
    w?.then(() => party.toggle("audio", false)); // released before the mic was up: it closes once it is
  };
  return (
    <div className="absolute right-4 bottom-28 z-20 md:right-8">
      <div className="relative" onDoubleClick={() => party.react("heart")} onPointerDown={down} onPointerUp={up} onPointerLeave={up} onContextMenu={(e) => e.preventDefault()}>
        <div className="absolute -inset-1.5 rounded-full shadow-[0_0_0_2px_rgb(242_180_90/0.7),0_0_30px_rgb(242_180_90/0.35)]" />
        <Face party={party} m={partner} big className="size-[104px] cursor-pointer touch-none rounded-full select-none sm:size-[140px] [&>span:last-of-type]:hidden" />
        <span className="absolute -bottom-2 left-1/2 -translate-x-1/2 rounded-full bg-primary px-2.5 py-px text-xs font-bold text-primary-foreground">{partner.name}</span>
        <Floats party={party} id={partner.id} className="top-4" />
      </div>
      {me && (party.cam || party.mic) && <div className="absolute -top-2 -left-12"><Face party={party} m={me} className="size-[46px] rounded-full ring-2 ring-white/55 [&>span:last-of-type]:hidden" /><Floats party={party} id={me.id} className="top-0" /></div>}
      {hint && <p className="page-in absolute right-0 -bottom-9 w-max max-w-[80vw] rounded-full bg-[#15171f]/70 px-3 py-1 text-xs text-foreground/75">Double-click {partner.name} to send a heart{party.ok ? " · hold to whisper" : ""}</p>}
    </div>
  );
}

/** Reactions marked on the progress bar, where they happened (shown with the controls). */
export function Marks({ party, dur }: { party: Party; dur: number }) {
  const controls = usePlayer(selectControls)?.controlsVisible ?? true, box = useRef<HTMLDivElement>(null);
  const [r, setR] = useState<{ left: number; width: number; bottom: number } | null>(null);
  useLayoutEffect(() => {
    const measure = () => {
      const root = box.current?.parentElement, sl = root?.querySelector(".media-time-slider");
      if (!root || !sl) return setR(null);
      const a = root.getBoundingClientRect(), b = sl.getBoundingClientRect();
      setR({ left: b.left - a.left, width: b.width, bottom: a.bottom - b.top + 2 });
    };
    measure(); addEventListener("resize", measure);
    return () => removeEventListener("resize", measure);
  }, [controls, party.marks.length]);
  return <div ref={box} aria-hidden className={cn("pointer-events-none absolute z-20 h-4 transition-opacity duration-300", controls && r && dur > 0 ? "opacity-100" : "opacity-0")} style={r ? { left: r.left, width: r.width, bottom: r.bottom } : undefined}>
    {dur > 0 && party.marks.map((m, i) => <span key={i} className="absolute -translate-x-1/2 text-xs leading-none" style={{ left: `${Math.min(100, (m.at / 1000 / dur) * 100)}%` }}>{REACTS[m.kind]}</span>)}
  </div>;
}

/** Lights down: 3-2-1 over a dimmed film, then it starts for everyone at `countdownAt`. */
export function Countdown({ until }: { until: number }) {
  const [n, setN] = useState(0);
  useEffect(() => {
    const t = () => setN(Math.ceil((until - Date.now()) / 1000));
    t(); const i = setInterval(t, 100);
    return () => clearInterval(i);
  }, [until]);
  if (n <= 0) return null;
  return <div role="status" aria-live="assertive" className="pointer-events-none absolute inset-0 z-30 grid place-items-center bg-black/60 transition-opacity">
    <div className="flex flex-col items-center gap-2"><span key={n} className="tick font-display text-[clamp(96px,18vw,200px)] leading-none font-extrabold text-primary">{Math.min(3, n)}</span><span className="text-sm font-semibold tracking-[0.2em] text-foreground/72 uppercase">Lights down</span></div>
  </div>;
}

/** The Together tab in a room: a live tile grid, Friends | Date night, Save a seat, Lights down, the code, Leave. */
export function TogetherPanel({ party, link, onLights, onLeave }: { party: Party; link: () => string; onLights: () => void; onLeave: () => void }) {
  const [copied, setCopied] = useState(false);
  const copy = () => { navigator.clipboard?.writeText(link()); setCopied(true); setTimeout(() => setCopied(false), 1800); };
  const me = party.members.find((m) => m.id === party.you), others = party.members.filter((m) => m.id !== party.you);
  const btn = "beam grid size-9 place-items-center rounded-full bg-[#15171f]/80 [&_svg]:size-4";
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-3 pr-9">
        <h3 className="font-display text-2xl font-extrabold">Together</h3>
        <div role="radiogroup" aria-label="Mode" className="flex rounded-full bg-[#22242B] p-[3px]">
          {(["friends", "date"] as const).map((m) => <button key={m} role="radio" aria-checked={party.mode === m} onClick={() => party.setMode(m)}
            className={cn("rounded-full px-3 py-1.5 text-[13px]", party.mode === m ? "bg-[#33363F] font-bold" : "font-semibold text-foreground/60")}>{m === "friends" ? "Friends" : "Date night"}</button>)}
        </div>
      </div>
      <div className="grid grid-cols-2 gap-2">
        {me && <div className="relative"><Face party={party} m={me} className="h-[104px] rounded-2xl sm:h-[132px]" />
          {party.ok && <div className="absolute right-2 bottom-2 flex gap-1.5">
            <button aria-label={party.mic ? "Mute mic (M)" : "Turn mic on (M)"} aria-pressed={party.mic} disabled={!!party.busy} onClick={() => party.toggle("audio")} className={cn(btn, party.mic && "text-primary")}>{party.mic ? <Mic /> : <MicOff />}</button>
            <button aria-label={party.cam ? "Turn camera off (V)" : "Turn camera on (V)"} aria-pressed={party.cam} disabled={!!party.busy} onClick={() => party.toggle("video")} className={cn(btn, party.cam && "text-primary")}>{party.cam ? <Video /> : <VideoOff />}</button>
          </div>}</div>}
        {others.map((m) => <Face key={m.id} party={party} m={m} className="h-[104px] rounded-2xl sm:h-[132px]" />)}
        <button onClick={copy} className="beam flex h-[104px] flex-col items-center justify-center gap-1 rounded-2xl border-[1.5px] border-dashed border-white/20 sm:h-[132px]">
          {copied ? <Check className="size-5 text-primary" /> : <Plus className="size-5" />}
          <span className="text-[13px] font-semibold">{copied ? "Link copied" : "Save a seat"}</span>
          <span className="text-xs text-foreground/55">Copies the invite link</span>
        </button>
      </div>
      {party.ok && !party.cam && !party.mic && me && <p className="text-xs text-foreground/55">Camera and mic stay off until you turn them on.</p>}
      {party.note && <p role="status" className="text-sm text-foreground/72">{party.note}</p>}
      <div role="toolbar" aria-label="React (R)" className="flex justify-between gap-1 rounded-full bg-[#1F2127] p-1">
        {(Object.keys(REACTS) as ReactKind[]).map((k) => <button key={k} onClick={() => party.react(k)} aria-label={k} className="beam grid h-10 flex-1 place-items-center rounded-full text-2xl hover:bg-white/8">{REACTS[k]}</button>)}
      </div>
      <button className={cn(pill.primary, "w-full")} onClick={onLights}><Lightbulb />Lights down</button>
      <div className="flex flex-wrap items-center gap-x-3.5 gap-y-1 rounded-2xl bg-[#1F2127] px-3.5 py-3">
        <div className="flex flex-col"><span className="text-xs text-foreground/55">Room code</span>
          <span className="font-display text-[22px] font-extrabold tracking-[0.2em] text-primary">{party.room}</span></div>
        <span className="max-w-[200px] flex-1 text-xs text-foreground/55">Everyone plays the same copy of the film, so a pause lands on the same frame.</span>
        <button onClick={onLeave} className="ml-auto px-1 py-2 text-[13px] font-semibold text-foreground/75 hover:text-foreground">Leave room</button>
      </div>
    </div>
  );
}
