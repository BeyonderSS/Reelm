"use client";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { get, GUEST_HIDE, kindOf, loadSession, reelm, shows, storeSession, type Kind, type Session } from "./core";

type Ctx = { s: Session; ready: boolean; set: (s: Session) => void; setHide: (h: Kind[]) => Promise<void> };
const C = createContext<Ctx>({ s: {}, ready: false, set: () => {}, setHide: async () => {} });
export const useSession = () => useContext(C);
const PUBLIC = ["/welcome", "/signin", "/signup", "/pair"];

export function SessionProvider({ children }: { children: ReactNode }) {
  const [s, setS] = useState<Session>({});
  const [ready, setReady] = useState(false);
  const path = usePathname(), router = useRouter();
  useEffect(() => { setS(loadSession()); setReady(true); }, []);
  const set = (n: Session) => { storeSession(n); setS(n); };
  // "What you watch": applies at once, then saves (guests: this browser; accounts: the profile on the server). A failed save puts it back.
  const setHide = async (hide: Kind[]) => {
    const cur = loadSession(), p = cur.profile;
    if (!p) return;
    set({ ...cur, profile: { ...p, hide } });
    if (!cur.token) { try { localStorage.setItem(GUEST_HIDE, JSON.stringify(hide)); } catch {} return; }
    try { await reelm(`/v1/profiles/${p.id}`, { token: cur.token, method: "PATCH", json: { hide } }); } catch (e) { set({ ...loadSession(), profile: p }); throw e; }
  };
  // admin flag lives server-side: refresh it once per sign-in so the admin switch shows (or disappears) without a re-login
  useEffect(() => {
    if (!s.token || !s.user) return;
    reelm("/v1/me", { token: s.token }).then((r) => {
      const cur = loadSession();
      if (cur.token === s.token && !!cur.user?.is_admin !== !!r.user?.is_admin) set({ ...cur, user: { ...cur.user!, is_admin: !!r.user.is_admin } });
    }).catch(() => {});
  }, [s.token]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!ready || PUBLIC.some((p) => path.startsWith(p))) return;
    if (!s.token && !s.guest) router.replace("/welcome");
    else if (!s.profile && path !== "/profiles") router.replace("/profiles");
  }, [ready, s, path, router]);
  // a page whose kind this profile hid sends them Home (title pages by their ?type=)
  useEffect(() => {
    if (!ready || !s.profile) return;
    const p = s.profile, type = new URLSearchParams(location.search).get("type") ?? undefined;
    const off = path.startsWith("/live") ? !shows(p, "live") : path.startsWith("/schedule") || path.startsWith("/anime") ? !shows(p, "anime")
      : path.startsWith("/title") ? !shows(p, kindOf({ kind: "movie", type })) : false;
    if (off) router.replace("/");
  }, [ready, s.profile, path, router]);
  return <C.Provider value={{ s, ready, set, setHide }}>{children}</C.Provider>;
}

/** Fetch a content route; `null` path skips. */
export function useApi<T = any>(path: string | null) {
  const [st, setSt] = useState<{ data?: T; error?: string; loading: boolean }>({ loading: !!path });
  useEffect(() => {
    if (!path) return setSt({ loading: false });
    let live = true;
    setSt({ loading: true });
    get<T>(path).then((data) => live && setSt({ data, loading: false })).catch((e) => live && setSt({ error: e.message, loading: false }));
    return () => { live = false; };
  }, [path]);
  return st;
}
