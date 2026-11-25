"use client";
/* eslint-disable @next/next/no-img-element -- static screenshots from /public, no optimiser in this build */
import Link from "next/link";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ReelMark } from "@/components/reel";
import { pill } from "@/components/app";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

type Box = [number, number, number, number];
const TABS = [["web", "Web browser"], ["tv", "TV"], ["ph", "Phone"]] as const;

/** Monotone-chain convex hull: the beam is the hull of the lens and the box it lights. */
function hull(pts: number[][]) {
  pts = pts.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const cross = (o: number[], a: number[], b: number[]) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lo: number[][] = [], up: number[][] = [];
  for (const p of pts) { while (lo.length > 1 && cross(lo[lo.length - 2], lo[lo.length - 1], p) <= 0) lo.pop(); lo.push(p); }
  for (const p of pts.slice().reverse()) { while (up.length > 1 && cross(up[up.length - 2], up[up.length - 1], p) <= 0) up.pop(); up.push(p); }
  return lo.slice(0, -1).concat(up.slice(0, -1));
}

/** The dark room: the beam follows a mouse and snaps onto a screen it touches; clicking a screen chooses it. */
function Room({ chosen, onChoose }: { chosen: "tv" | "ph"; onChoose: (w: "tv" | "ph") => void }) {
  const room = useRef<HTMLDivElement>(null), poly = useRef<SVGPolygonElement>(null), grad = useRef<SVGRadialGradientElement>(null), spot = useRef<HTMLDivElement>(null);
  const reel = useRef<SVGSVGElement>(null), tv = useRef<HTMLImageElement>(null), ph = useRef<HTMLImageElement>(null), grain = useRef<HTMLCanvasElement>(null);
  const pointer = useRef<number[] | null>(null), pick = useRef(chosen);
  useEffect(() => { pick.current = chosen; pointer.current = null; }, [chosen]);
  useEffect(() => {
    const el = room.current!, reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
    let cur: Box | null = null, live = true, gt = 0;
    const rectOf = (img: HTMLElement): Box => { const r = el.getBoundingClientRect(), b = img.getBoundingClientRect(); return [b.left - r.left, b.top - r.top, b.width, b.height]; };
    const inside = (x: number, y: number, [rx, ry, w, h]: Box, pad: number) => x > rx - pad && x < rx + w + pad && y > ry - pad && y < ry + h + pad;
    const goal = () => { // the box the beam should cover: a screen, or a round spot at the pointer
      const t = rectOf(tv.current!), f = rectOf(ph.current!), p = pointer.current;
      if (p) {
        if (inside(p[0], p[1], t, 24)) return { box: t, on: "tv" };
        if (inside(p[0], p[1], f, 24)) return { box: f, on: "ph" };
        return { box: [p[0] - 70, p[1] - 70, 140, 140] as Box, on: null };
      }
      return { box: pick.current === "tv" ? t : f, on: pick.current };
    };
    const frame = () => {
      if (!live) return;
      const g = goal(), k = reduce ? 1 : 0.16;
      cur = cur ? (cur.map((v, i) => v + (g.box[i] - v) * k) as Box) : g.box;
      const [x, y, w, h] = cur, r = el.getBoundingClientRect(), lens = reel.current!.getBoundingClientRect();
      const ox = lens.left - r.left + lens.width / 2, oy = lens.top - r.top + lens.height / 2;
      poly.current!.setAttribute("points", hull([[ox, oy - 3], [ox, oy + 3], [x, y], [x + w, y], [x + w, y + h], [x, y + h]]).map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" "));
      grad.current!.setAttribute("cx", String(ox)); grad.current!.setAttribute("cy", String(oy));
      const cx = x + w / 2, cy = y + h / 2;
      spot.current!.style.left = `${cx}px`; spot.current!.style.top = `${cy}px`; spot.current!.style.opacity = g.on ? "0" : "1";
      el.style.setProperty("--mx", `${cx}px`); el.style.setProperty("--my", `${cy}px`);
      el.classList.toggle("lit-tv", g.on === "tv"); el.classList.toggle("lit-ph", g.on === "ph");
      requestAnimationFrame(frame);
    };
    // film grain, a few frames a second
    const gx = grain.current!.getContext("2d")!, img = gx.createImageData(160, 90);
    const noise = (t: number) => {
      if (!live) return;
      if (!reduce && t - gt > 90) { gt = t; for (let i = 0; i < img.data.length; i += 4) { const v = Math.random() * 255; img.data[i] = img.data[i + 1] = img.data[i + 2] = v; img.data[i + 3] = 255; } gx.putImageData(img, 0, 0); }
      requestAnimationFrame(noise);
    };
    requestAnimationFrame(frame); requestAnimationFrame(noise);
    return () => { live = false; };
  }, []);
  const move = (e: React.PointerEvent) => { if (e.pointerType === "mouse") { const r = room.current!.getBoundingClientRect(); pointer.current = [e.clientX - r.left, e.clientY - r.top]; } };
  return (
    <div className="room-wrap">
      <div ref={room} className="room" onPointerMove={move} onPointerLeave={() => (pointer.current = null)}>
        <div className="wall" /><div className="floor" /><div className="spill" />
        <div className="words" aria-hidden><span>Point me<br />at a screen</span></div>
        <div ref={spot} className="spot" />
        <svg className="beam2" aria-hidden>
          <defs>
            <radialGradient ref={grad} id="apps-beam" gradientUnits="userSpaceOnUse" cx="0" cy="0" r="900"><stop offset="0" stopColor="#FFE2B0" stopOpacity=".55" /><stop offset=".55" stopColor="#F2B45A" stopOpacity=".12" /><stop offset="1" stopColor="#F2B45A" stopOpacity=".03" /></radialGradient>
            <filter id="apps-soft"><feGaussianBlur stdDeviation="4" /></filter>
          </defs>
          <polygon ref={poly} fill="url(#apps-beam)" filter="url(#apps-soft)" points="0,0 0,0 0,0" />
        </svg>
        <div className="proj"><svg ref={reel} viewBox="0 0 24 24" aria-hidden className="size-16 animate-spin [animation-duration:9s]"><circle cx="12" cy="12" r="11" fill="#272B37" stroke="#F2B45A" strokeWidth="1.2" /><g fill="#15171F"><circle cx="12" cy="6.5" r="2.6" /><circle cx="12" cy="17.5" r="2.6" /><circle cx="6.5" cy="12" r="2.6" /><circle cx="17.5" cy="12" r="2.6" /></g><circle cx="12" cy="12" r="1.4" fill="#F2B45A" /></svg></div>
        <div className="room-copy">
          <h1>Point Reelm at another screen.</h1>
          <p>Move the light onto your TV or your phone. Your history, your list and watch parties come along.</p>
        </div>
        <div className="console">
          <button type="button" className="target" aria-pressed={chosen === "tv"} aria-label="Set up Reelm on my TV" onClick={() => onChoose("tv")}><div className="tv-frame"><img ref={tv} src="/apps/app-tv.jpg" alt="The Reelm TV app home screen" /></div></button>
          <div className="plinth" />
        </div>
        <div className="sofa"><i /></div>
        <div className="phone2"><button type="button" className="target" aria-pressed={chosen === "ph"} aria-label="Set up Reelm on my phone" onClick={() => onChoose("ph")}><div className="ph-frame"><img ref={ph} src="/apps/app-phone.jpg" alt="The Reelm phone app home screen" /></div></button></div>
        <canvas ref={grain} className="grain" width={160} height={90} aria-hidden />
        <p className="hint"><span className="pointer-coarse:hidden">Move your pointer to aim. Click a screen to choose it.</span><span className="hidden pointer-coarse:inline">Tap a screen to choose it.</span></p>
      </div>
    </div>
  );
}

function Copy({ text }: { text: string }) {
  const [said, setSaid] = useState("Copy"), ref = useRef<HTMLButtonElement>(null);
  const copy = () => navigator.clipboard?.writeText(text).then(() => { setSaid("Copied"); setTimeout(() => setSaid("Copy"), 1600); }, () => {
    // no clipboard: select the address so it can be copied by hand
    const r = document.createRange(), s = getSelection(); r.selectNodeContents(ref.current!.previousElementSibling!); s?.removeAllRanges(); s?.addRange(r); setSaid("Selected");
  });
  return <button ref={ref} type="button" onClick={copy} className="copy beam">{said}</button>;
}

const Lbl = ({ children }: { children: ReactNode }) => <span className="lbl">{children}</span>;
const Shot = ({ src, alt, kind }: { src: string; alt: string; kind?: "ph" | "tvs" }) => <div className={cn("shot", kind)}><img src={`/apps/g/${src}.jpg`} alt={alt} loading="lazy" /></div>;
const Illus = ({ children }: { children: ReactNode }) => <div className="illus" aria-hidden><span className="tag">Illustration</span>{children}</div>;
const Step = ({ n, title, art, phone, children }: { n: number; title: string; art: ReactNode; phone?: boolean; children: ReactNode }) => (
  <li className={cn("step", phone && "phn")}><span className="n">{n}</span><div><h3>{title}</h3><p>{children}</p></div>{art}</li>
);

function Guide({ tab, setTab }: { tab: number; setTab: (i: number) => void }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const key = (e: React.KeyboardEvent, i: number) => {
    const to = e.key === "ArrowRight" ? (i + 1) % 3 : e.key === "ArrowLeft" ? (i + 2) % 3 : -1;
    if (to < 0) return;
    e.preventDefault(); setTab(to); refs.current[to]?.focus();
  };
  return (
    <section className="guide" id="guide" aria-labelledby="guide-title">
      <div className="guide-head">
        <div>
          <h2 id="guide-title">Setup guide</h2>
          <p>Every step, with the screens you&apos;ll actually see. Pick where you&apos;re setting Reelm up.</p>
        </div>
        <div className="tabs" role="tablist" aria-label="Device">
          {TABS.map(([k, label], i) => (
            <button key={k} ref={(el) => { refs.current[i] = el; }} role="tab" id={`tab-${k}`} aria-controls={`g-${k}`} aria-selected={tab === i} tabIndex={tab === i ? 0 : -1}
              type="button" className="beam" onClick={() => setTab(i)} onKeyDown={(e) => key(e, i)}>{label}</button>
          ))}
        </div>
      </div>

      <div id="g-web" role="tabpanel" aria-labelledby="tab-web" hidden={tab !== 0}>
        <ol className="steplist">
          <Step n={1} title="Get the Reelm Helper" art={<Shot src="web-04" alt="Reelm asking for the Helper, with an Install Reelm Helper button" />}>Movies on the web need a small browser extension. Play any film and Reelm asks for it. Click <Lbl>Install Reelm Helper</Lbl> to download a zip.</Step>
          <Step n={2} title="Unzip it into a folder" art={<Illus><svg width="96" height="78" viewBox="0 0 24 20"><path d="M1 3a2 2 0 0 1 2-2h6l2 2h10a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2z" fill="#F2B45A" /></svg><span className="font-bold">reelm-helper</span><span className="text-sm text-foreground/55">manifest.json, bg.js, content.js, rules.json, icon.png</span></Illus>}>Make a new folder called <Lbl>reelm-helper</Lbl> and unzip the download into it. The zip has no folder of its own, so the files would otherwise spill into Downloads.</Step>
          <Step n={3} title="Turn on Developer mode" art={<Shot src="web-06" alt="Chrome extensions page with Developer mode on, showing Load unpacked" />}>Open <Lbl>chrome://extensions</Lbl> in Chrome, Edge or Brave and switch on <Lbl>Developer mode</Lbl> at the top right.</Step>
          <Step n={4} title="Load the folder" art={<Shot src="web-07" alt="The Reelm Helper 1.4.0 card on the extensions page" />}>Click <Lbl>Load unpacked</Lbl> and pick the <Lbl>reelm-helper</Lbl> folder. Reelm Helper 1.4.0 shows up in the list.</Step>
          <Step n={5} title="Pin it" art={<Shot src="web-10" alt="Reelm Helper details with Pin to toolbar switched on" />}>Open its <Lbl>Details</Lbl> and turn on <Lbl>Pin to toolbar</Lbl>, or pin it from the puzzle-piece menu, so you can see it&apos;s on.</Step>
          <Step n={6} title="Back to Reelm" art={<Shot src="web-01" alt="Reelm welcome screen with Sign in, Create account and Continue as guest" />}>Press <Lbl>Retry</Lbl> on the film, or reload. Sign in to keep your history across devices, or continue as a guest.</Step>
        </ol>
      </div>

      <div id="g-tv" role="tabpanel" aria-labelledby="tab-tv" hidden={tab !== 1}>
        <ol className="steplist">
          <Step n={1} title="Install Downloader" art={<Illus><svg width="70" height="70" viewBox="0 0 24 24" fill="none" stroke="#F2B45A" strokeWidth="2" strokeLinecap="round"><path d="M12 3v12M7 10l5 5 5-5M5 21h14" /></svg><span className="font-bold">Downloader</span></Illus>}>On Fire TV, search for <Lbl>Downloader</Lbl> in the Appstore. On Android TV, find it on Google Play. It&apos;s free.</Step>
          <Step n={2} title="Type the short address" art={<Illus><div className="field"><span>reelm-tv.vercel.app/tv</span><b>Go</b></div></Illus>}>Open Downloader, type <Lbl>reelm-tv.vercel.app/tv</Lbl> and press Go. It downloads the latest Reelm for TV.</Step>
          <Step n={3} title="Allow the install" art={<Shot kind="tvs" src="tv-01" alt="Android TV Install unknown apps settings" />}>The first time, the TV asks you to allow Downloader to install apps. Turn it on under <Lbl>Install unknown apps</Lbl>, then go back and press Install.</Step>
          <Step n={4} title="Open Reelm" art={<Shot kind="tvs" src="tv-03" alt="Reelm on TV: Welcome to Reelm with Sign in with phone and Continue as guest" />}>Choose <Lbl>Sign in with phone</Lbl>. No typing passwords with the remote. Or pick <Lbl>Continue as guest</Lbl>.</Step>
          <Step n={5} title="Scan the code with your phone" art={<Shot kind="tvs" src="tv-04" alt="Reelm on TV showing a sign-in code and QR, waiting for your phone" />}>Point your phone&apos;s camera at the code on the TV, open the link, tap <Lbl>Open in Reelm</Lbl> and approve the TV. It signs in on its own.</Step>
        </ol>
      </div>

      <div id="g-ph" role="tabpanel" aria-labelledby="tab-ph" hidden={tab !== 2}>
        <ol className="steplist">
          <Step phone n={1} title="Download the app" art={<Shot kind="ph" src="ph-01" alt="Chrome on Android: File might be harmful, with Download anyway" />}>Scan the QR code above, or open <Lbl>reelm-tv.vercel.app/app</Lbl> on your phone. Chrome warns about any app file; tap <Lbl>Download anyway</Lbl>.</Step>
          <Step phone n={2} title="Let Chrome install it" art={<Shot kind="ph" src="ph-04" alt="Android dialog saying installs from Chrome are blocked, with Settings" />}>Open the download. The first time, Android blocks it; tap <Lbl>Settings</Lbl>.</Step>
          <Step phone n={3} title="Allow from this source" art={<Shot kind="ph" src="ph-06" alt="Install unknown apps with Allow from this source switched on" />}>Turn on <Lbl>Allow from this source</Lbl>, then go back.</Step>
          <Step phone n={4} title="Install" art={<Shot kind="ph" src="ph-07" alt="Android installer asking Do you want to install this app" />}>Tap <Lbl>Install</Lbl>, then <Lbl>Open</Lbl>. Updates arrive inside the app later, no repeat of this.</Step>
          <Step phone n={5} title="Sign in" art={<Shot kind="ph" src="ph-10" alt="Reelm phone app welcome screen" />}>Sign in to bring your history and list, create an account, or continue as a guest.</Step>
          <Step phone n={6} title="Signing in a TV later" art={<Shot kind="ph" src="ph-13" alt="Sign in your TV page with an Open in Reelm button" />}>When a TV shows its code, scan it with your camera and tap <Lbl>Open in Reelm</Lbl>. Then approve the TV.</Step>
        </ol>
      </div>
    </section>
  );
}

/** Get the apps: aim the projector at a screen, follow its card, or read the full setup guide. */
export default function Apps() {
  const { s } = useSession(), [chosen, setChosen] = useState<"tv" | "ph">("tv"), [tab, setTab] = useState(0);
  const tvCard = useRef<HTMLDivElement>(null), phCard = useRef<HTMLDivElement>(null);
  const choose = (w: "tv" | "ph") => {
    setChosen(w); setTab(w === "tv" ? 1 : 2); // choosing a screen also opens its guide tab
    (w === "tv" ? tvCard : phCard).current?.scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "nearest" });
  };
  return (
    <main className="apps pb-8">
      {/* signed out (a short link brought them here): the app header isn't shown, so give them a way home */}
      {!s.profile && <div className="mx-auto max-w-[1500px] px-4 py-5 md:px-10"><Link href="/" className="beam inline-flex items-center gap-2 rounded-full pr-2 font-display text-2xl font-extrabold tracking-tight text-primary"><ReelMark className="size-7" />Reelm</Link></div>}
      <Room chosen={chosen} onChoose={choose} />
      <div className="mx-auto max-w-[1200px] px-4 md:px-5">
        <div className="below">
          <div ref={tvCard} className={cn("card", chosen !== "tv" && "off")}>
            <h2>On your TV</h2>
            <ol className="steps">
              <li>On Fire TV or Android TV, install <b>Downloader</b> from the app store.</li>
              <li>Open it and type:
                <div className="mt-2 flex flex-wrap items-center gap-2.5"><span className="big-url">reelm-tv.vercel.app/tv</span><Copy text="https://reelm-tv.vercel.app/tv" /></div></li>
              <li>Install, open Reelm, and sign in by scanning the code on the TV with your phone.</li>
            </ol>
          </div>
          <div ref={phCard} className={cn("card", chosen !== "ph" && "off")}>
            <h2>On your phone</h2>
            <div className="flex flex-wrap items-center gap-5">
              <div className="qr size-[124px]"><img src="/apps/qr-phone.png" alt="QR code that downloads the Reelm phone app" width={106} height={106} /></div>
              <div className="flex min-w-0 flex-[1_1_180px] flex-col gap-2.5">
                <p className="text-foreground/72">Scan with your phone&apos;s camera, or open <b className="text-foreground">reelm-tv.vercel.app/app</b> on it.</p>
                <a className={cn(pill.primary, "font-bold")} href="https://reelm-api.ssbeyonder.workers.dev/dl/Reelm.apk">Download for Android</a>
                <p className="text-sm text-foreground/55">Android 8 or newer, 16 MB</p>
              </div>
            </div>
          </div>
        </div>
        <Guide tab={tab} setTab={setTab} />
      </div>
    </main>
  );
}
