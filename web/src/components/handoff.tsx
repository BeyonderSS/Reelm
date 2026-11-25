"use client";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { clock } from "@/lib/party";

const OFF = "reelm.handoff.off", SEEN = "reelm.handoff.";
const ss = { get: (k: string) => { try { return sessionStorage.getItem(k); } catch { return null; } }, set: (k: string) => { try { sessionStorage.setItem(k, "1"); } catch {} } };

/** The dark modules of a QR code as one SVG path (uqr loads only when the card shows, about 4 kB gzipped). */
function Qr({ text, label }: { text: string; label: string }) {
  const [qr, setQr] = useState<{ d: string; n: number } | null>(null);
  useEffect(() => {
    let live = true;
    import("uqr").then(({ encode }) => {
      const { data, size } = encode(text, { ecc: "L", border: 0 });
      let d = "";
      data.forEach((row, y) => row.forEach((on, x) => { if (on) d += `M${x} ${y}h1v1h-1z`; }));
      if (live) setQr({ d, n: size });
    });
    return () => { live = false; };
  }, [text]);
  return (
    // two whole pixels per module (a watch URL is ~57 modules): smaller blurs on 1x screens and cameras miss it
    <div className="handoff-qr grid min-h-[84px] min-w-[84px] flex-none place-items-center rounded-xl bg-primary p-2">
      {qr && <svg role="img" aria-label={label} viewBox={`0 0 ${qr.n} ${qr.n}`} width={qr.n * 2} height={qr.n * 2} shapeRendering="crispEdges"><path d={qr.d} fill="#1a1408" /></svg>}
    </div>
  );
}

/**
 * "Finish it on the couch": paused for 2 s on a wide screen with a mouse, the paused frame shrinks into a phone and a QR code
 * continues this title at this second there. Once per title per browser session; "Not now" ends it for the session.
 * `on`: the page's own conditions (paused, not in a room, not driving a TV...). `id`: the title. `path`: the watch URL the QR opens (this site).
 */
export function Handoff({ on, id, path, title, at, dur, video, tv }: { on: boolean; id: string; path: string; title: string; at: number; dur: number; video: () => HTMLVideoElement | null; tv: ReactNode }) {
  const [show, setShow] = useState(false), frame = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (!on) return;
    // a paused start (autoplay blocked) is not a pause: something must have played
    const t = !video()?.played.length || !matchMedia("(min-width: 768px) and (hover: hover) and (pointer: fine)").matches || ss.get(OFF) || ss.get(SEEN + id)
      ? 0 : window.setTimeout(() => { ss.set(SEEN + id); setShow(true); }, 2000);
    return () => { clearTimeout(t); setShow(false); };
  }, [on, id]); // eslint-disable-line react-hooks/exhaustive-deps
  // the paused frame, drawn once (a cross-origin frame still draws; it only can't be read back)
  useEffect(() => {
    const v = video(), c = frame.current;
    if (!show || !v || !c || !v.videoWidth) return;
    c.width = 320; c.height = Math.round((320 * v.videoHeight) / v.videoWidth);
    try { c.getContext("2d")?.drawImage(v, 0, 0, c.width, c.height); } catch {}
  }, [show]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!show) return null;
  const time = clock(at * 1000);
  return (
    <div role="dialog" aria-label="Keep watching on your phone" className="handoff absolute right-10 bottom-24 z-20 flex w-[430px] max-w-[calc(100%-2rem)] gap-[18px] rounded-[26px] bg-slate/94 p-[18px] shadow-[0_30px_60px_-20px_rgb(0_0_0/0.9),0_0_0_1px_rgb(233_234_240/0.07)] backdrop-blur">
      <div aria-hidden className="relative h-[204px] w-[98px] flex-none rounded-[20px] bg-[#0a0b0f] p-[5px] ring-1 ring-white/10">
        <div className="relative size-full overflow-hidden rounded-[15px] bg-dusk">
          <canvas ref={frame} className="handoff-fly absolute inset-x-1 top-[62px] h-[50px] w-[calc(100%-8px)] rounded-[10px] bg-haze object-cover" />
          <span className="absolute inset-x-0 top-[120px] text-center text-[11px] font-bold text-primary">{time}</span>
          <span className="absolute inset-x-3 top-[140px] h-[3px] rounded-full bg-white/20"><span className="block h-full rounded-full bg-primary" style={{ width: `${dur ? Math.min(100, (at / dur) * 100) : 0}%` }} /></span>
        </div>
      </div>
      <div className="flex min-w-0 flex-col gap-2.5">
        <h2 className="font-display text-[22px] leading-[1.1] font-extrabold">Finish it on the couch</h2>
        <p className="text-sm text-foreground/70">Scan to pick up {title} at {time} on your phone.</p>
        <div className="mt-0.5 flex items-center gap-3">
          <Qr text={location.origin + path} label={`QR code to continue ${title} at ${time} on your phone`} />
          <div className="flex min-w-0 flex-col gap-2">
            {tv}
            <button onClick={() => { ss.set(OFF); setShow(false); }} className="beam rounded-full px-1 py-1.5 text-[13.5px] font-semibold text-foreground/70 hover:text-foreground">Not now</button>
          </div>
        </div>
      </div>
    </div>
  );
}
