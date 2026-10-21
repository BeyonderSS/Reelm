"use client";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { get, loadSession, storeSession, type Session } from "./core";

type Ctx = { s: Session; ready: boolean; set: (s: Session) => void };
const C = createContext<Ctx>({ s: {}, ready: false, set: () => {} });
export const useSession = () => useContext(C);
const PUBLIC = ["/welcome", "/signin", "/signup", "/pair"];

export function SessionProvider({ children }: { children: ReactNode }) {
  const [s, setS] = useState<Session>({});
  const [ready, setReady] = useState(false);
  const path = usePathname(), router = useRouter();
  useEffect(() => { setS(loadSession()); setReady(true); }, []);
  const set = (n: Session) => { storeSession(n); setS(n); };
  useEffect(() => {
    if (!ready || PUBLIC.some((p) => path.startsWith(p))) return;
    if (!s.token && !s.guest) router.replace("/welcome");
    else if (!s.profile && path !== "/profiles") router.replace("/profiles");
  }, [ready, s, path, router]);
  return <C.Provider value={{ s, ready, set }}>{children}</C.Provider>;
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
