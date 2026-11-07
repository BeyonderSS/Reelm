"use client";
import { useEffect, useState, type RefObject } from "react";
import { allowRead } from "./bridge";
import { iso1 } from "./langs";

const MAX_DRIFT = 0.08, AHEAD = 10; // s: re-anchor above 80 ms; decode this far past the playhead
type Chunk = { t: number; buf: AudioBuffer; node?: AudioBufferSourceNode };
type Graph = { ctx: AudioContext; mute: GainNode };

/**
 * Plays a file's audio through libav.js (in a worker) when the browser can't: the stream is flagged `dolby`, or a plain
 * file plays but Chrome decodes no audio bytes. The <video> stays the clock; its own (silent) output is swallowed by an
 * unconnected MediaElementSource so the skin's volume/mute drive our GainNode instead. Returns a toast message, or "".
 */
export function useLibavAudio(video: RefObject<HTMLVideoElement | null>, url: string | undefined, dolby: boolean, key: string, track?: number, onTracks?: (t: AudioTrack[]) => void) {
  const [note, setNote] = useState("");
  useEffect(() => {
    const v = video.current;
    if (!v || !url) return;
    let w: Worker | undefined, ctx: AudioContext | undefined, gain: GainNode, seekId = 0, queue: Chunk[] = [];
    let anchor: { ctx: number; media: number } | null = null, timer = 0, dead = false, got = false; // got: current seek delivered audio
    const stats = { codec: "", samples: 0, drift: 0, reanchors: 0, scheduled: 0, startMs: 0, lastChunk: 0 }; // startMs: seek/open → first sound
    let asked = performance.now(), head = v.currentTime, lastChunkAt = 0; // head: end of the newest decoded audio
    let graph: Graph | undefined;
    const say = (m: string) => { setNote(m); setTimeout(() => setNote(""), 5000); };
    const stopAll = () => { for (const c of queue) { c.node?.stop(); c.node = undefined; } anchor = null; };
    const volume = () => gain && (gain.gain.value = v.muted ? 0 : v.volume);
    const fail = (e: unknown) => {
      if (dead) return;
      console.warn("[libav audio]", e); stop(); say("Couldn't decode this audio");
    };

    const pump = () => {
      if (!ctx || !w) return;
      const now = v.currentTime;
      w.postMessage({ type: "want", until: now + AHEAD });
      queue = queue.filter((c) => c.t + c.buf.duration > now - 1);
      stats.scheduled = queue.filter((c) => c.node).length;
      if (v.paused || v.seeking || v.readyState < 3) return stopAll(); // stall/pause: silence until the video moves again
      if (ctx.state !== "running") ctx.resume();
      // decoding fell behind the video (slow host, or it started at 0 after a resume): skip ahead instead of trailing,
      // but only when it stalled (no chunk for 6 s) or is hopelessly behind (20 s). A seek lands on the keyframe before
      // the target and needs a few seconds of fresh reads, so re-seeking whenever it was merely behind restarted it
      // before its second chunk, forever (a picked English track on a Hindi-first MKV never played). Behind-but-moving
      // chunks are skipped below while the worker races ahead. 30 s safety net in case a seek truly hangs.
      const idle = performance.now() - (got ? lastChunkAt : asked);
      if (head < now - 2 && (idle > (got ? 6000 : 30000) || head < now - 20)) return jump(now + 5);
      const lat = ctx.baseLatency + (ctx.outputLatency || 0);
      if (anchor) {
        stats.drift = now - (anchor.media + ctx.currentTime - anchor.ctx); // video clock vs audio clock
        if (Math.abs(stats.drift) > MAX_DRIFT) { stats.reanchors++; stopAll(); }
      }
      anchor ??= { ctx: ctx.currentTime, media: now };
      for (const c of queue) {
        if (c.node || c.t > now + 2) continue;
        const when = anchor.ctx - lat + (c.t - anchor.media), late = Math.max(0, ctx.currentTime - when);
        if (late >= c.buf.duration) continue;
        c.node = ctx.createBufferSource(); c.node.buffer = c.buf; c.node.connect(gain);
        c.node.start(when + late, late);
        if (graph && graph.mute.gain.value) graph.mute.gain.value = 0;
        if (asked) { stats.startMs = Math.round(performance.now() - asked); asked = 0; }
      }
    };
    const jump = (t: number) => { asked = performance.now(); got = false; head = t; stopAll(); queue = []; w?.postMessage({ type: "seek", t, id: ++seekId }); };
    const onSeek = () => jump(v.currentTime);
    const onRate = () => { if (v.playbackRate !== 1) { v.playbackRate = 1; say("Speed changes aren't available with this audio"); } };
    const events: [string, () => void][] = [["seeking", onSeek], ["volumechange", volume], ["ratechange", onRate], ["play", pump], ["pause", pump], ["waiting", pump], ["playing", pump]];

    const start = () => {
      try {
        // one graph per <video>: createMediaElementSource works once per element, and switching tracks restarts this hook
        // The element's own sound goes through a gain node, muted while we play the decoded track. Disconnected instead,
        // a file whose own track IS playable froze: Chrome advances the video clock by pulling the element's audio.
        const g: Graph = ((v as any).reelmGraph ??= (() => {
          const c = new AudioContext(), el = c.createMediaElementSource(v), mute = c.createGain();
          el.connect(mute); mute.connect(c.destination);
          return { ctx: c, mute };
        })());
        ctx = g.ctx; graph = g; // muted only once our first chunk plays: switching tracks keeps the old one until then
        gain = g.ctx.createGain(); gain.connect(g.ctx.destination); volume();
        w = new Worker(new URL("./libav-audio.worker.ts", import.meta.url));
      } catch (e) { return fail(e); }
      w.onerror = (e) => fail(e.message);
      w.onmessage = ({ data: m }) => {
        if (m.type === "error") return fail(m.message);
        if (m.type === "info") { stats.codec = m.codec; console.info("[libav audio] codec", m.codec); }
        if (m.type === "warn") console.debug("[libav audio]", m.message);
        if (m.type === "tracks") onTracks?.(m.tracks); // the decoder lists them anyway: no second reader on the same host
        if (m.type !== "pcm" || m.id !== seekId) return;
        const buf = ctx!.createBuffer(2, m.l.length, m.sr);
        buf.copyToChannel(m.l, 0); buf.copyToChannel(m.r, 1);
        queue.push({ t: m.t, buf }); got = true; lastChunkAt = performance.now(); stats.lastChunk = +m.t.toFixed(2); stats.samples += m.l.length; head = m.t + buf.duration;
        pump();
      };
      (v as any).reelmAudio = stats; // read by devtools / tests
      allowRead(url).then(() => w?.postMessage({ type: "open", url, track }));
      w.postMessage({ type: "seek", t: v.currentTime, id: seekId });
      onRate();
      for (const [e, f] of events) v.addEventListener(e, f);
      timer = window.setInterval(pump, 250);
    };
    function stop() {
      dead = true; clearInterval(timer); stopAll(); w?.terminate(); gain?.disconnect();
      const g: Graph | undefined = (v as any).reelmGraph; if (g) g.mute.gain.value = 1; // the browser's own track is heard again
      for (const [e, f] of events) v!.removeEventListener(e, f);
    }

    // Only flagged Dolby/DTS streams. An auto-detect on webkitAudioDecodedByteCount === 0 also fired on normal
    // cross-origin (non-CORS) files, where Chrome hides that counter, and doubled their audio.
    // ...or a non-default audio track was picked: the browser only ever plays a file's first track
    if (dolby || track !== undefined) start();
    return () => { if (w && !dead) stop(); };
  }, [url, dolby, key, track]); // eslint-disable-line react-hooks/exhaustive-deps
  return note;
}

export type AudioTrack = { index: number; lang: string; title: string; codec: string };
/** A direct file's audio tracks, read with the same libav worker (header and index only). Waits a few seconds so it
 * doesn't compete with the video's own startup. Empty for HLS or when the file can't be read cross-origin. */
export function useAudioTracks(url: string | undefined, key: string, decoding: boolean) {
  const [got, setGot] = useState<{ k: string; tracks: AudioTrack[] } | null>(null);
  const k = `${url}|${key}`;
  useEffect(() => {
    if (!url || decoding) return; // the running decoder reports them through `report`
    let w: Worker | undefined;
    const t = setTimeout(() => {
      try { w = new Worker(new URL("./libav-audio.worker.ts", import.meta.url)); } catch { return; }
      w.onmessage = ({ data: m }) => {
        if (m.type === "tracks") { setGot({ k, tracks: m.tracks }); console.debug("[audio tracks]", m.tracks); }
        if (m.type === "error") console.debug("[audio tracks] unreadable:", m.message); // e.g. a host without CORS
        if (m.type === "tracks" || m.type === "error") w?.terminate();
      };
      w.onerror = () => w?.terminate();
      allowRead(url).then(() => w?.postMessage({ type: "open", url, probe: true }));
    }, 4000);
    return () => { clearTimeout(t); w?.terminate(); };
  }, [url, key, decoding]); // eslint-disable-line react-hooks/exhaustive-deps
  return { tracks: got?.k === k ? got.tracks : [], report: (tracks: AudioTrack[]) => setGot({ k, tracks }) };
}

export const languageName = (code: string) => {
  const c = iso1(code);
  if (!c || c === "und") return "";
  try { const n = new Intl.DisplayNames(["en"], { type: "language" }).of(c); return n && n.toLowerCase() !== c ? n : ""; } catch { return ""; }
};
/** "Hindi", "English (5.1 DDP)", else "Track 2" */
export const trackLabel = (t: AudioTrack, i: number) => {
  const name = languageName(t.lang), title = t.title.trim();
  if (name && title && !title.toLowerCase().includes(name.toLowerCase())) return `${name} (${title})`;
  return title || name || `Track ${i + 1}`;
};
