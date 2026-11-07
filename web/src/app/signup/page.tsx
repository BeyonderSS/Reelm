"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { AuthShell, Field, StepBar } from "@/components/auth";
import { Avatar, AvatarPicker, pill, WhatYouWatch } from "@/components/app";
import { reelm, type Kind, type Profile, type Session } from "@/lib/core";
import { useSession } from "@/lib/session";

const userErr = (u: string) => !u ? "" : !/^[a-z0-9_]+$/.test(u) ? "Use only lowercase letters, numbers and _" : u.length < 3 ? "At least 3 characters" : u.length > 32 ? "At most 32 characters" : "";
const passErr = (p: string) => p && p.length < 8 ? "At least 8 characters" : "";
const TITLES = [["Create your account", "You'll use these to sign in on your phone and the web, and to approve your TV."], ["Choose your avatar", "It shows on your profile tile. You can change it any time."], ["Name your profile", "This is you. You can add up to four more profiles for the rest of your home later."]];

/** Mirrors the Android sign-up: the account is created after step 1, so going back from step 2 leaves for "Who's watching?" instead of registering twice. */
export default function SignUp() {
  const { set } = useSession(), router = useRouter();
  const [step, setStep] = useState(0), [acct, setAcct] = useState<{ s: Session; profile: Profile } | null>(null);
  const [u, setU] = useState(""), [p, setP] = useState(""), [avatar, setAvatar] = useState("cat_face"), [name, setName] = useState(""), [hide, setHide] = useState<Kind[]>([]);
  const [err, setErr] = useState(""), [busy, setBusy] = useState(false);
  async function register() {
    setBusy(true); setErr("");
    try {
      const r = await reelm("/v1/auth/register", { json: { username: u, password: p } });
      const { profiles } = await reelm("/v1/profiles", { token: r.token });
      setAcct({ s: { token: r.token, user: r.user }, profile: profiles[0] }); setName(profiles[0]?.name && profiles[0].name !== r.user.username ? profiles[0].name : "");
      setStep(1);
    } catch (x) { const m = (x as Error).message; setErr(/taken|exists|409/i.test(m) ? "That username is taken. Try another one." : m); }
    setBusy(false);
  }
  async function finish() {
    if (!acct) return;
    setBusy(true); setErr("");
    try {
      const body = { name: name.trim(), avatar, kids: false, hide };
      await reelm(`/v1/profiles/${acct.profile.id}`, { method: "PATCH", token: acct.s.token, json: body });
      set({ ...acct.s, profile: { ...acct.profile, ...body } }); router.replace("/");
    } catch (x) { setErr((x as Error).message); setBusy(false); }
  }
  const ok = [!!u && !userErr(u) && p.length >= 8, true, !!name.trim()][step];
  const back = step === 0 ? "/welcome" : step === 1 ? () => { if (acct) { set(acct.s); router.replace("/profiles"); } } : () => setStep(1);
  return (
    <AuthShell back={back} wide={step === 1}>
      <form onSubmit={(e) => { e.preventDefault(); if (!ok || busy) return; [register, () => setStep(2), finish][step](); }} className="flex flex-col gap-2">
        <StepBar step={step} />
        <div key={step} className="page-in flex flex-col gap-2">
          <h1 className="font-display text-3xl font-bold tracking-tight">{TITLES[step][0]}</h1>
          <p className="mb-6 text-foreground/72">{TITLES[step][1]}</p>
          {step === 0 && <>
            <Field label="Username" autoFocus autoCapitalize="none" autoComplete="username" value={u} onChange={(e) => setU(e.target.value.trim().toLowerCase())} error={userErr(u)} hint="3 to 32 characters: letters, numbers and _" />
            <Field label="Password" type="password" autoComplete="new-password" value={p} onChange={(e) => setP(e.target.value)} error={passErr(p)} hint="At least 8 characters" />
          </>}
          {step === 1 && <>
            <div className="mb-4 flex items-center gap-4"><Avatar id={avatar} size={88} ring /><p className="text-sm text-foreground/60">Selected: <span className="text-foreground">{avatar.replace(/_/g, " ")}</span></p></div>
            <div className="max-h-[46vh] overflow-y-auto rounded-[18px] bg-slate/60 p-4 ring-1 ring-white/6"><AvatarPicker value={avatar} onChange={setAvatar} /></div>
          </>}
          {step === 2 && <>
            <div className="mb-4 flex flex-col items-center gap-3 rounded-[18px] bg-slate/60 py-6"><Avatar id={avatar} size={104} ring /><span className="font-semibold">{name.trim() || "Your name"}</span></div>
            <Field label="Profile name" autoFocus maxLength={24} autoCapitalize="words" value={name} onChange={(e) => setName(e.target.value)} />
            <WhatYouWatch hide={hide} onChange={setHide} />
          </>}
        </div>
        {err && <p role="alert" className="rounded-2xl bg-destructive/12 px-4 py-3 text-sm text-destructive">{err}</p>}
        <button type="submit" disabled={!ok || busy} className={`${pill.primary} mt-4`}>{busy && <Loader2 className="animate-spin" />}{step === 0 ? (busy ? "Creating account" : "Continue") : step === 1 ? "Continue" : "Start watching"}</button>
      </form>
    </AuthShell>
  );
}
