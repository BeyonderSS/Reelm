"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AvatarCluster } from "@/components/auth";
import { pill } from "@/components/app";
import { Marquee } from "@/components/ui/marquee";
import { guestProfile } from "@/lib/core";
import { useApi, useSession } from "@/lib/session";

/** A slow wall of real posters behind the welcome, dimmed so it reads as atmosphere. */
function PosterWall() {
  const { data } = useApi("anime/home"), m = useApi("movies/catalog?key=movie/top").data;
  const posters: string[] = [...((data?.trending ?? []) as any[]), ...((data?.popular ?? []) as any[]), ...((m ?? []) as any[])].map((x) => x.poster).filter(Boolean).slice(0, 36);
  if (posters.length < 12) return null;
  const rows = [posters.slice(0, 12), posters.slice(12, 24), posters.slice(24, 36)].filter((r) => r.length);
  return (
    <div aria-hidden className="pointer-events-none absolute inset-0 -rotate-6 scale-125 opacity-25">
      {rows.map((r, i) => (
        <Marquee key={i} reverse={i % 2 === 1} repeat={2} className="[--duration:90s] [--gap:0.75rem]">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {r.map((src) => <img key={src} src={src} alt="" referrerPolicy="no-referrer" className="aspect-[2/3] w-28 rounded-xl object-cover md:w-36" />)}
        </Marquee>
      ))}
    </div>
  );
}

export default function Welcome() {
  const { set } = useSession(), router = useRouter();
  return (
    <main className="relative flex min-h-dvh flex-col items-center overflow-hidden px-6 pb-[max(env(safe-area-inset-bottom),24px)]">
      <PosterWall />
      <div className="absolute inset-0 bg-[radial-gradient(80%_70%_at_50%_40%,rgb(21_23_31/0.5),rgb(21_23_31/0.92))]" />
      <div className="lamp absolute inset-0" />
      <div className="page-in relative flex w-full max-w-sm flex-1 flex-col items-center text-center md:justify-center">
        <div className="flex-1 md:hidden" />
        <AvatarCluster />
        <h1 className="mt-12 font-display text-4xl font-bold tracking-tight md:text-5xl">Welcome to Reelm</h1>
        <p className="mt-3 max-w-xs text-foreground/72">Sign in to keep your profiles and watch history in sync on your phone, TV and the web.</p>
        <div className="flex-1 md:hidden" />
        <div className="mt-10 flex w-full flex-col gap-3">
          <Link href="/signin" className={pill.primary}>Sign in</Link>
          <Link href="/signup" className={pill.secondary}>Create account</Link>
          <button className={pill.ghost} onClick={() => { set({ guest: true, profile: guestProfile() }); router.replace("/"); }}>Continue as guest</button>
          <p className="text-xs text-foreground/52">As a guest, your history stays in this browser.</p>
        </div>
      </div>
    </main>
  );
}
