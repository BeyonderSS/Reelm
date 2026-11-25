"use client";
/* eslint-disable @next/next/no-img-element -- static screenshots from /public, no optimiser in this build */
import Link from "next/link";
import { useEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Check, Copy as CopyIcon, Download, Monitor, Smartphone, Tv } from "lucide-react";
import { ReelMark } from "@/components/reel";
import { pill } from "@/components/app";
import { AnimatedBeam } from "@/components/ui/animated-beam";
import { BorderBeam } from "@/components/ui/border-beam";
import { LightRays } from "@/components/ui/light-rays";
import { Safari } from "@/components/ui/safari";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

type Dev = "tv" | "ph" | "web";
const DEVS: { k: Dev; label: string; icon: ReactNode }[] = [
  { k: "tv", label: "TV", icon: <Tv /> },
  { k: "ph", label: "Phone", icon: <Smartphone /> },
  { k: "web", label: "Web browser", icon: <Monitor /> },
];
const EASE = [0.2, 0, 0, 1] as const;

/* ---------- frames: the same three screens the hero shows, reused by the guide ---------- */

const TvFrame = ({ src, alt, children }: { src?: string; alt?: string; children?: ReactNode }) => (
  <div className="w-full">
    <div className="rounded-[14px] bg-[#0a0b0f] p-[7px] shadow-[0_0_0_1px_rgb(233_234_240/0.09),0_40px_80px_-30px_#000]">
      <div className="relative aspect-video overflow-hidden rounded-[8px] bg-[#101116]">
        {src ? <img src={src} alt={alt} className="block size-full object-cover" /> : children}
      </div>
    </div>
    <div className="mx-auto h-2.5 w-[18%] rounded-b-md bg-gradient-to-b from-[#22242c] to-[#16171d]" />
  </div>
);

const PhoneFrame = ({ src, alt, className }: { src: string; alt: string; className?: string }) => (
  <div className={cn("rounded-[22px] bg-[#0a0b0f] p-[4px] shadow-[0_0_0_1px_rgb(233_234_240/0.1),0_30px_60px_-20px_#000]", className)}>
    <img src={src} alt={alt} className="block aspect-[9/20] w-full rounded-[18px] object-cover object-top" />
  </div>
);

/* ---------- hero: the reel is the lamp; light runs from it to every screen ---------- */

const LABEL: Record<Dev, string> = { web: "a web browser", tv: "my TV", ph: "my phone" };
function Screen({ d, i, chosen, onChoose, end, className, children }: { d: Dev; i: number; chosen: Dev; onChoose: (d: Dev) => void; end: RefObject<HTMLSpanElement | null>; className: string; children: ReactNode }) {
  return (
    <div className={cn("relative z-10", className)}>
      <motion.div initial={{ opacity: 0, y: 40 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.9, delay: 0.25 + i * 0.12, ease: EASE }}>
      <button type="button" onClick={() => onChoose(d)} aria-pressed={chosen === d} aria-label={`Set up Reelm on ${LABEL[d]}`}
        className={cn("block w-full rounded-2xl transition-[filter,opacity] duration-500 ease-dusk focus-visible:outline-offset-8",
          chosen === d ? "drop-shadow-[0_0_38px_rgb(242_180_90/0.32)]" : "opacity-70 saturate-[0.6] hover:opacity-95 hover:saturate-100")}>
        {children}
      </button>
      </motion.div>
      <span ref={end} className="absolute bottom-0 left-1/2 size-px" />
    </div>
  );
}

function Stage({ chosen, onChoose }: { chosen: Dev; onChoose: (d: Dev) => void }) {
  const stage = useRef<HTMLDivElement>(null), hub = useRef<HTMLDivElement>(null);
  const web = useRef<HTMLSpanElement>(null), tv = useRef<HTMLSpanElement>(null), ph = useRef<HTMLSpanElement>(null);
  const calm = useReducedMotion();
  return (
    <section className="relative isolate overflow-hidden" aria-labelledby="apps-title">
      {!calm && <LightRays color="rgba(242, 180, 90, 0.16)" count={6} blur={44} speed={16} length="85vh" className="-z-10" />}
      <div className="absolute inset-x-0 bottom-0 -z-[5] h-40 bg-gradient-to-b from-transparent to-dusk" />
      <div className="absolute inset-0 -z-20 bg-[radial-gradient(70%_55%_at_50%_100%,rgb(242_180_90/0.10),transparent_70%),radial-gradient(60%_40%_at_50%_0%,#1d2029,transparent)]" />

      <div className="mx-auto max-w-[1200px] px-4 pt-10 text-center md:px-8 md:pt-16">
        <motion.h1 id="apps-title" initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.7, ease: EASE }}
          className="mx-auto max-w-[14ch] font-display text-[clamp(44px,7.4vw,96px)] leading-[0.92] font-extrabold tracking-[-0.035em] text-balance">
          One Reelm. Every screen in the house.
        </motion.h1>
        <motion.p initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.7, delay: 0.15 }}
          className="mx-auto mt-5 max-w-[46ch] text-[17px] leading-relaxed text-foreground/72">
          Start an episode in the browser, finish it on the couch, catch up in bed. Your history, your list and watch parties come along.
        </motion.p>
      </div>

      {/* the screens, then the reel beneath them sending light up to each */}
      <div ref={stage} className="relative mx-auto mt-12 max-w-[1100px] px-4 pb-10 md:mt-16 md:px-8">
        <div className="flex items-end justify-center gap-[3%]">
          <Screen d="web" i={0} chosen={chosen} onChoose={onChoose} end={web} className="w-[30%]"><Safari url="reelm-tv.vercel.app" imageSrc="/apps/app-web.jpg" /></Screen>
          <Screen d="tv" i={1} chosen={chosen} onChoose={onChoose} end={tv} className="w-[44%]"><TvFrame src="/apps/app-tv.jpg" alt="" /></Screen>
          <Screen d="ph" i={2} chosen={chosen} onChoose={onChoose} end={ph} className="w-[12%]"><PhoneFrame src="/apps/app-phone.jpg" alt="" /></Screen>
        </div>
        <motion.div ref={hub} initial={{ opacity: 0, scale: 0.6 }} animate={{ opacity: 1, scale: 1 }} transition={{ duration: 0.8, delay: 0.1, ease: EASE }}
          className="relative z-10 mx-auto mt-[clamp(44px,6vw,80px)] grid size-16 place-items-center rounded-full bg-haze shadow-[0_0_0_1px_rgb(242_180_90/0.35),0_0_60px_10px_rgb(242_180_90/0.22)] md:size-20">
          <ReelMark className={cn("size-10 md:size-12", !calm && "animate-spin [animation-duration:12s]")} />
        </motion.div>
        {([["web", web], ["tv", tv], ["ph", ph]] as const).map(([d, end], i) => (
          <AnimatedBeam key={d} containerRef={stage} fromRef={hub} toRef={end} curvature={d === "tv" ? 0 : 40}
            duration={calm ? 0 : 3.2} delay={0.9 + i * 0.35} repeat={calm ? 0 : Infinity}
            pathColor="#F2B45A" pathOpacity={chosen === d ? 0.45 : 0.16} pathWidth={chosen === d ? 3 : 2}
            gradientStartColor="#FFE2B0" gradientStopColor="#F2B45A" />
        ))}
        <p className="mt-6 text-center text-sm text-foreground/52">Pick a screen to see how to set it up.</p>
      </div>
    </section>
  );
}

/* ---------- quick start for each screen ---------- */

function Copy({ text }: { text: string }) {
  const [done, setDone] = useState<"" | "Copied" | "Selected">(""), ref = useRef<HTMLButtonElement>(null);
  const copy = () => navigator.clipboard?.writeText(text).then(() => { setDone("Copied"); setTimeout(() => setDone(""), 1600); }, () => {
    // no clipboard: select the address so it can be copied by hand
    const r = document.createRange(), s = getSelection(); r.selectNodeContents(ref.current!.previousElementSibling!); s?.removeAllRanges(); s?.addRange(r); setDone("Selected");
  });
  return (
    <button ref={ref} type="button" onClick={copy} className={cn(pill.secondary, "h-10 px-4 text-sm")}>
      {done === "Copied" ? <Check className="text-signal" /> : <CopyIcon />}{done || "Copy"}
    </button>
  );
}

const QUICK: Record<Dev, { title: string; body: ReactNode }> = {
  tv: {
    title: "Fire TV or Android TV",
    body: <>
      <p className="text-foreground/72">Install <b className="text-foreground">Downloader</b> from the TV&apos;s app store, open it and type:</p>
      <div className="mt-4 flex flex-wrap items-center gap-3"><span className="font-display text-[clamp(28px,4.4vw,48px)] leading-none font-extrabold tracking-[-0.02em] break-all text-primary">reelm-tv.vercel.app/tv</span><Copy text="https://reelm-tv.vercel.app/tv" /></div>
      <p className="mt-4 text-sm text-foreground/52">Then sign in by scanning the code on the TV with your phone. No typing passwords with a remote.</p>
    </>,
  },
  ph: {
    title: "Android phone",
    body: <div className="flex flex-wrap items-center gap-6">
      <div className="grid size-[132px] flex-none place-items-center rounded-2xl bg-primary shadow-[0_0_40px_rgb(242_180_90/0.25)]"><img src="/apps/qr-phone.png" alt="QR code that downloads the Reelm phone app" width={112} height={112} className="[image-rendering:pixelated]" /></div>
      <div className="flex min-w-0 flex-[1_1_220px] flex-col items-start gap-3">
        <p className="text-foreground/72">Scan with your phone&apos;s camera, or open <b className="text-foreground">reelm-tv.vercel.app/app</b> on it.</p>
        <a className={cn(pill.primary, "font-bold")} href="https://reelm-api.ssbeyonder.workers.dev/dl/Reelm.apk"><Download />Download for Android</a>
        <p className="text-sm text-foreground/52">Android 8 or newer, 16 MB. Updates arrive inside the app.</p>
      </div>
    </div>,
  },
  web: {
    title: "Chrome, Edge or Brave",
    body: <>
      <p className="max-w-[52ch] text-foreground/72">Anime and live TV play right away. Movies and series need Reelm Helper, a small extension you load once.</p>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <a className={cn(pill.primary, "font-bold")} href="/reelm-helper.zip" download><Download />Download Reelm Helper</a>
        <span className="text-sm text-foreground/52">Version 1.4.0. The steps below show where it goes.</span>
      </div>
    </>,
  },
};

/* ---------- the guide: steps on the left, the screen you'll see pinned on the right ---------- */

type Art = { kind: "web" | "tv" | "ph"; src?: string; alt?: string; illus?: ReactNode };
type StepT = { title: string; body: ReactNode; art: Art };
const Lbl = ({ children }: { children: ReactNode }) => <span className="rounded-md bg-haze px-1.5 py-0.5 text-[0.9em] font-semibold whitespace-nowrap text-foreground">{children}</span>;
const Illus = ({ children }: { children: ReactNode }) => <div className="flex size-full flex-col items-center justify-center gap-3 p-6 text-center">{children}</div>;

const STEPS: Record<Dev, StepT[]> = {
  tv: [
    { title: "Install Downloader", body: <>On Fire TV, search for <Lbl>Downloader</Lbl> in the Appstore. On Android TV, find it on Google Play. It&apos;s free.</>,
      art: { kind: "tv", illus: <Illus><Download className="size-16 text-primary" strokeWidth={1.6} /><span className="font-display text-2xl font-bold">Downloader</span></Illus> } },
    { title: "Type the short address", body: <>Open Downloader, type <Lbl>reelm-tv.vercel.app/tv</Lbl> and press Go. It downloads the latest Reelm for TV.</>,
      art: { kind: "tv", illus: <Illus><div className="flex w-[82%] gap-2 text-left"><span className="flex-1 rounded-md bg-foreground px-3 py-2.5 font-bold text-dusk">reelm-tv.vercel.app/tv</span><b className="rounded-md bg-primary px-4 py-2.5 text-primary-foreground">Go</b></div></Illus> } },
    { title: "Allow the install", body: <>The first time, the TV asks you to allow Downloader to install apps. Turn it on under <Lbl>Install unknown apps</Lbl>, then go back and press Install.</>,
      art: { kind: "tv", src: "tv-01", alt: "Android TV Install unknown apps settings" } },
    { title: "Open Reelm", body: <>Choose <Lbl>Sign in with phone</Lbl>, or pick <Lbl>Continue as guest</Lbl>.</>,
      art: { kind: "tv", src: "tv-03", alt: "Reelm on TV: Welcome to Reelm with Sign in with phone and Continue as guest" } },
    { title: "Scan the code with your phone", body: <>Point your phone&apos;s camera at the code, open the link, tap <Lbl>Open in Reelm</Lbl> and approve the TV. It signs in on its own.</>,
      art: { kind: "tv", src: "tv-04", alt: "Reelm on TV showing a sign-in code and QR, waiting for your phone" } },
  ],
  ph: [
    { title: "Download the app", body: <>Scan the QR code above, or open <Lbl>reelm-tv.vercel.app/app</Lbl> on your phone. Chrome warns about any app file; tap <Lbl>Download anyway</Lbl>.</>,
      art: { kind: "ph", src: "ph-01", alt: "Chrome on Android: File might be harmful, with Download anyway" } },
    { title: "Let Chrome install it", body: <>Open the download. The first time, Android blocks it; tap <Lbl>Settings</Lbl>.</>,
      art: { kind: "ph", src: "ph-04", alt: "Android dialog saying installs from Chrome are blocked, with Settings" } },
    { title: "Allow from this source", body: <>Turn on <Lbl>Allow from this source</Lbl>, then go back.</>,
      art: { kind: "ph", src: "ph-06", alt: "Install unknown apps with Allow from this source switched on" } },
    { title: "Install", body: <>Tap <Lbl>Install</Lbl>, then <Lbl>Open</Lbl>. Updates arrive inside the app later, so you only do this once.</>,
      art: { kind: "ph", src: "ph-07", alt: "Android installer asking Do you want to install this app" } },
    { title: "Sign in", body: <>Sign in to bring your history and list, create an account, or continue as a guest.</>,
      art: { kind: "ph", src: "ph-10", alt: "Reelm phone app welcome screen" } },
    { title: "Signing in a TV later", body: <>When a TV shows its code, scan it with your camera and tap <Lbl>Open in Reelm</Lbl>. Then approve the TV.</>,
      art: { kind: "ph", src: "ph-13", alt: "Sign in your TV page with an Open in Reelm button" } },
  ],
  web: [
    { title: "Get the Reelm Helper", body: <>Use the download button above, or play any film and click <Lbl>Install Reelm Helper</Lbl> when Reelm asks.</>,
      art: { kind: "web", src: "web-04", alt: "Reelm asking for the Helper, with an Install Reelm Helper button" } },
    { title: "Unzip it into a folder", body: <>Make a folder called <Lbl>reelm-helper</Lbl> and unzip into it. The zip has no folder of its own, so the files would otherwise spill into Downloads.</>,
      art: { kind: "web", illus: <Illus><svg width="88" height="72" viewBox="0 0 24 20" aria-hidden><path d="M1 3a2 2 0 0 1 2-2h6l2 2h10a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2z" fill="#F2B45A" /></svg><span className="font-display text-xl font-bold">reelm-helper</span><span className="text-sm text-foreground/55">manifest.json, bg.js, content.js, rules.json, icon.png</span></Illus> } },
    { title: "Turn on Developer mode", body: <>Open <Lbl>chrome://extensions</Lbl> and switch on <Lbl>Developer mode</Lbl> at the top right.</>,
      art: { kind: "web", src: "web-06", alt: "Chrome extensions page with Developer mode on, showing Load unpacked" } },
    { title: "Load the folder", body: <>Click <Lbl>Load unpacked</Lbl> and pick the <Lbl>reelm-helper</Lbl> folder. Reelm Helper 1.4.0 shows up in the list.</>,
      art: { kind: "web", src: "web-07", alt: "The Reelm Helper 1.4.0 card on the extensions page" } },
    { title: "Pin it", body: <>Open its <Lbl>Details</Lbl> and turn on <Lbl>Pin to toolbar</Lbl> so you can see it&apos;s on.</>,
      art: { kind: "web", src: "web-10", alt: "Reelm Helper details with Pin to toolbar switched on" } },
    { title: "Back to Reelm", body: <>Press <Lbl>Retry</Lbl> on the film, or reload. Sign in to keep your history across devices, or continue as a guest.</>,
      art: { kind: "web", src: "web-01", alt: "Reelm welcome screen with Sign in, Create account and Continue as guest" } },
  ],
};

function ArtView({ art }: { art: Art }) {
  const src = art.src && `/apps/g/${art.src}.jpg`;
  if (art.kind === "ph") return <PhoneFrame src={src!} alt={art.alt!} className="mx-auto w-[min(200px,58%)]" />;
  if (art.kind === "tv") return <TvFrame src={src} alt={art.alt}>{art.illus}</TvFrame>;
  return (
    <div className="relative">
      <Safari url={art.src ? (art.src === "web-01" || art.src === "web-04" ? "reelm-tv.vercel.app" : "chrome://extensions") : "Downloads"} imageSrc={src} />
      {!src && <div className="absolute inset-x-px top-[7%] bottom-px rounded-b-[11px] bg-[#101116]">{art.illus}</div>}
      {src && <span className="sr-only">{art.alt}</span>}
    </div>
  );
}

function Guide({ dev }: { dev: Dev }) {
  const steps = STEPS[dev], [on, setOn] = useState(0), items = useRef<(HTMLLIElement | null)[]>([]);
  useEffect(() => {
    // the last step whose top has passed the middle of the screen; at the bottom of the page, the final step (it can't reach the middle)
    const pick = () => {
      const els = items.current.filter(Boolean) as HTMLLIElement[];
      let i = 0;
      els.forEach((el, k) => { if (el.getBoundingClientRect().top < innerHeight * 0.5) i = k; });
      if (innerHeight + scrollY >= document.documentElement.scrollHeight - 8) i = els.length - 1;
      setOn(i);
    };
    const raf = requestAnimationFrame(pick);
    addEventListener("scroll", pick, { passive: true });
    return () => { cancelAnimationFrame(raf); removeEventListener("scroll", pick); };
  }, []); // the panel is keyed by device, so each device gets a fresh Guide
  return (
    <div className="grid gap-10 md:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)] md:gap-14">
      <ol className="relative">
        {/* the rail fills with light up to the step you're on */}
        <span aria-hidden className="absolute top-5 bottom-5 left-[19px] w-px bg-white/10" />
        <motion.span aria-hidden className="absolute top-5 left-[19px] w-px origin-top bg-primary shadow-[0_0_12px_#F2B45A]"
          animate={{ height: `calc(${(on / Math.max(steps.length - 1, 1)) * 100}% - ${(on / Math.max(steps.length - 1, 1)) * 40}px)` }} transition={{ duration: 0.5, ease: EASE }} />
        {steps.map((s, i) => (
          <li key={s.title} ref={(el) => { items.current[i] = el; }} className="relative pb-10 pl-14 last:pb-0 md:min-h-[260px] md:pb-0">
            <button type="button" onClick={() => setOn(i)} className="absolute top-0 left-0 rounded-full" aria-label={`Show step ${i + 1}`}>
              <span className={cn("grid size-10 place-items-center rounded-full font-display text-lg font-bold tabular-nums transition-all duration-300 ease-dusk",
                i <= on ? "bg-primary text-primary-foreground shadow-[0_0_24px_rgb(242_180_90/0.45)]" : "bg-slate text-foreground/52 ring-1 ring-white/10")}>{i + 1}</span>
            </button>
            <h3 className={cn("pt-1.5 font-display text-[22px] leading-tight font-bold tracking-[-0.01em] transition-colors duration-300", i === on ? "text-foreground" : "text-foreground/60")}>{s.title}</h3>
            <p className={cn("mt-2 max-w-[44ch] leading-relaxed transition-colors duration-300", i === on ? "text-foreground/78" : "text-foreground/45")}>{s.body}</p>
            <div className="mt-5 md:hidden"><ArtView art={s.art} /></div>
          </li>
        ))}
      </ol>
      <div className="hidden md:block">
        <div className="sticky top-[calc(50vh-210px)]">
          <AnimatePresence mode="popLayout" initial={false}>
            <motion.div key={`${dev}-${on}`} initial={{ opacity: 0, y: 18, filter: "blur(6px)" }} animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
              exit={{ opacity: 0, y: -12, filter: "blur(6px)" }} transition={{ duration: 0.45, ease: EASE }}>
              <ArtView art={steps[on].art} />
            </motion.div>
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
}

/** Get the apps: pick a screen in the hero or the switch, grab it fast, or follow the guide. */
export default function Apps() {
  const { s } = useSession(), [dev, setDev] = useState<Dev>("tv"), setup = useRef<HTMLElement>(null), tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const choose = (d: Dev) => { setDev(d); setup.current?.scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" }); };
  const key = (e: React.KeyboardEvent, i: number) => {
    const to = e.key === "ArrowRight" ? (i + 1) % 3 : e.key === "ArrowLeft" ? (i + 2) % 3 : -1;
    if (to < 0) return;
    e.preventDefault(); setDev(DEVS[to].k); tabs.current[to]?.focus();
  };
  return (
    <main className="pb-24">
      {/* signed out (a short link brought them here): the app header isn't shown, so give them a way home */}
      {!s.profile && <div className="mx-auto max-w-[1500px] px-4 py-5 md:px-10"><Link href="/" className="beam inline-flex items-center gap-2 rounded-full pr-2 font-display text-2xl font-extrabold tracking-tight text-primary"><ReelMark className="size-7" />Reelm</Link></div>}
      <Stage chosen={dev} onChoose={choose} />

      <section ref={setup} id="guide" aria-labelledby="setup-title" className="mx-auto max-w-[1200px] scroll-mt-20 px-4 pt-16 md:px-8">
        <div className="flex flex-wrap items-end justify-between gap-6">
          <h2 id="setup-title" className="font-display text-[clamp(34px,4.6vw,56px)] leading-none font-extrabold tracking-[-0.03em]">Set it up</h2>
          <div role="tablist" aria-label="Device" className="inline-flex gap-1 rounded-full bg-slate p-1.5 ring-1 ring-white/6">
            {DEVS.map(({ k, label, icon }, i) => (
              <button key={k} ref={(el) => { tabs.current[i] = el; }} role="tab" id={`tab-${k}`} aria-controls="setup-panel" aria-selected={dev === k} tabIndex={dev === k ? 0 : -1}
                type="button" onClick={() => setDev(k)} onKeyDown={(e) => key(e, i)}
                className={cn("relative flex h-11 items-center gap-2 rounded-full px-4 font-semibold transition-colors duration-200 sm:px-5 [&_svg]:size-4.5", dev === k ? "text-primary-foreground" : "text-foreground/65 hover:text-foreground")}>
                {dev === k && <motion.span layoutId="apps-tab" className="absolute inset-0 -z-0 rounded-full bg-primary shadow-[0_0_24px_rgb(242_180_90/0.35)]" transition={{ type: "spring", bounce: 0.18, duration: 0.5 }} />}
                <span className="relative flex items-center gap-2">{icon}{label}</span>
              </button>
            ))}
          </div>
        </div>

        <div id="setup-panel" role="tabpanel" aria-labelledby={`tab-${dev}`}>
          <AnimatePresence mode="wait" initial={false}>
            <motion.div key={dev} initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.3, ease: EASE }}>
              <div className="relative mt-8 overflow-hidden rounded-[24px] bg-slate p-6 ring-1 ring-white/6 md:p-8">
                <h3 className="mb-4 font-display text-2xl font-bold tracking-[-0.01em]">{QUICK[dev].title}</h3>
                {QUICK[dev].body}
                <BorderBeam size={160} duration={9} colorFrom="#FFE2B0" colorTo="#F2B45A" />
              </div>
              <h3 className="mt-16 mb-8 font-display text-[28px] font-bold tracking-[-0.015em]">Every step, with the screens you&apos;ll see</h3>
              <Guide dev={dev} />
            </motion.div>
          </AnimatePresence>
        </div>
      </section>
    </main>
  );
}
