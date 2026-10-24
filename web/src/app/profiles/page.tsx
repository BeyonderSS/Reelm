"use client";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2, Pencil, Plus, X } from "lucide-react";
import { AuthShell } from "@/components/auth";
import { Avatar, AvatarPicker, pill } from "@/components/app";
import { BlurFade } from "@/components/ui/blur-fade";
import { GUEST, reelm, syncFavs, syncHistory, type Profile } from "@/lib/core";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

export default function Profiles() {
  const { s, set, ready } = useSession(), router = useRouter();
  const [list, setList] = useState<Profile[] | null>(null), [manage, setManage] = useState(false), [edit, setEdit] = useState<Partial<Profile> | null>(null);
  const [err, setErr] = useState(""), [busy, setBusy] = useState<string | null>(null);
  const load = () => s.token && reelm("/v1/profiles", { token: s.token }).then((d) => setList(d.profiles)).catch((e) => { setErr(e.message); setList([]); });
  useEffect(() => { if (ready) s.token ? void load() : setList([GUEST]); }, [ready, s.token]); // eslint-disable-line react-hooks/exhaustive-deps
  async function pick(p: Profile) {
    setBusy(p.id);
    // pull synced history first so Continue watching is right on the first paint
    if (s.token) await Promise.race([Promise.all([syncHistory(p.id, s.token), syncFavs(p.id, s.token)]).catch(() => {}), new Promise((r) => setTimeout(r, 2500))]);
    set({ ...s, profile: p }); router.replace("/");
  }
  async function save() {
    if (!edit?.name?.trim() || !s.token) return;
    const body = { name: edit.name.trim(), avatar: edit.avatar ?? "cat_face", kids: !!edit.kids };
    try {
      await (edit.id ? reelm(`/v1/profiles/${edit.id}`, { method: "PATCH", token: s.token, json: body }) : reelm("/v1/profiles", { token: s.token, json: body }));
      if (edit.id && s.profile?.id === edit.id) set({ ...s, profile: { ...s.profile, ...body } });
      setEdit(null); await load();
    } catch (e) { setErr((e as Error).message); }
  }
  async function del() {
    try { await reelm(`/v1/profiles/${edit!.id}`, { method: "DELETE", token: s.token }); setEdit(null); await load(); } catch (e) { setErr((e as Error).message); }
  }
  const tiles: (Profile | null)[] = list ? [...list, ...(s.token && list.length < 5 ? [null] : [])] : [];
  return (
    <AuthShell wide>
      <div className="flex flex-col items-center gap-10 text-center">
        <h1 className="font-display text-4xl font-bold tracking-tight md:text-5xl">{manage ? "Manage profiles" : "Who's watching?"}</h1>
        {!list ? <Loader2 aria-label="Loading profiles" className="size-7 animate-spin text-primary" /> : (
          <div className="grid grid-cols-2 gap-x-6 gap-y-8 sm:grid-cols-3">
            {tiles.map((p, i) => (
              <BlurFade key={p?.id ?? "add"} delay={i * 0.06}>
                <button onClick={() => (p ? (manage ? setEdit(p) : pick(p)) : setEdit({ name: "", avatar: "cat_face", kids: false }))} disabled={!!busy}
                  className="group flex w-32 flex-col items-center gap-3 rounded-[24px] p-2 outline-none">
                  <span className={cn("relative grid size-28 place-items-center rounded-[28px] bg-gradient-to-b from-haze to-slate ring-1 ring-white/8 transition-all duration-200 ease-dusk group-hover:-translate-y-1 group-hover:ring-2 group-hover:ring-primary group-hover:shadow-[0_0_36px_rgb(242_180_90/0.35)] group-focus-visible:ring-2 group-focus-visible:ring-primary group-focus-visible:shadow-[0_0_36px_rgb(242_180_90/0.35)]",
                    p && s.profile?.id === p.id && !manage && "ring-2 ring-primary/60")}>
                    {p ? <Avatar id={p.avatar} size={96} /> : <Plus className="size-8 text-foreground/60" />}
                    {manage && p && <span className="absolute inset-0 grid place-items-center rounded-[28px] bg-dusk/60"><Pencil className="size-6" /></span>}
                    {busy === p?.id && <span className="absolute inset-0 grid place-items-center rounded-[28px] bg-dusk/60"><Loader2 className="size-6 animate-spin text-primary" /></span>}
                  </span>
                  <span className="text-sm font-medium">{p ? p.name : "Add profile"}</span>
                  {p?.kids && <span className="-mt-2 rounded-full bg-signal/15 px-2.5 py-0.5 text-xs font-medium text-signal">Kids</span>}
                </button>
              </BlurFade>
            ))}
          </div>
        )}
        {err && <p role="alert" className="text-sm text-destructive">{err}</p>}
        <div className="flex flex-col items-center gap-2">
          {s.user && <p className="text-xs text-foreground/52">Signed in as @{s.user.username}</p>}
          <div className="flex gap-2">
            {s.token && <button className={manage ? pill.primary : pill.secondary} onClick={() => setManage(!manage)}>{manage ? "Done" : "Manage profiles"}</button>}
            <button className={pill.ghost} onClick={() => { set({}); router.replace("/welcome"); }}>{s.token ? "Sign out" : "Leave guest mode"}</button>
          </div>
        </div>
      </div>
      {edit && (
        <div role="dialog" aria-modal aria-label={edit.id ? "Edit profile" : "New profile"} className="fixed inset-0 z-50 flex items-end justify-center bg-dusk/70 backdrop-blur-sm sm:items-center" onClick={(e) => e.target === e.currentTarget && setEdit(null)}>
          <div className="page-in flex max-h-[90dvh] w-full max-w-lg flex-col gap-4 rounded-t-[24px] bg-haze p-5 text-left sm:rounded-[24px]">
            <div className="flex items-center"><h2 className="flex-1 font-display text-2xl font-semibold">{edit.id ? "Edit profile" : "New profile"}</h2><button aria-label="Close" onClick={() => setEdit(null)} className="beam grid size-10 place-items-center rounded-full hover:bg-white/8"><X /></button></div>
            <div className="flex items-center gap-4"><Avatar id={edit.avatar ?? "cat_face"} size={72} ring />
              <input aria-label="Name" placeholder="Name" autoFocus value={edit.name ?? ""} maxLength={24} onChange={(e) => setEdit({ ...edit, name: e.target.value })} className="beam h-12 flex-1 rounded-2xl bg-slate px-4 outline-none ring-1 ring-white/8" /></div>
            <div className="min-h-0 flex-1 overflow-y-auto pr-1"><AvatarPicker value={edit.avatar ?? "cat_face"} onChange={(avatar) => setEdit({ ...edit, avatar })} /></div>
            <label className="flex items-center gap-3 rounded-2xl bg-slate px-4 py-3 text-sm"><input type="checkbox" className="size-4 accent-[#f2b45a]" checked={!!edit.kids} onChange={(e) => setEdit({ ...edit, kids: e.target.checked })} /><span><span className="font-medium">Kids profile</span><span className="block text-xs text-foreground/52">Filters anime, movies and live TV</span></span></label>
            <div className="flex justify-between gap-2">
              {edit.id && (list?.length ?? 0) > 1 ? <button className={`${pill.ghost} text-destructive`} onClick={del}>Delete profile</button> : <span />}
              <button className={pill.primary} onClick={save} disabled={!edit.name?.trim()}>Save</button>
            </div>
          </div>
        </div>
      )}
    </AuthShell>
  );
}
