"use client";
/* eslint-disable @typescript-eslint/no-explicit-any -- admin API rows are untyped JSON, shapes documented in server/API.md */
import { useCallback, useEffect, useState } from "react";
import { reelm } from "./core";
import { useSession } from "./session";

/** Dev-only: `/admin?fixture` renders mocked data (screenshots, design work). Dead code in production builds. */
export function fixtureMode() {
  if (process.env.NODE_ENV === "production" || typeof window === "undefined") return false;
  try {
    if (new URLSearchParams(location.search).has("fixture")) sessionStorage.setItem("reelm.fixture", "1");
    return sessionStorage.getItem("reelm.fixture") === "1";
  } catch { return false; }
}

export async function adminGet<T>(path: string, token?: string): Promise<T> {
  if (process.env.NODE_ENV !== "production" && fixtureMode()) return (await import("./admin-fixtures")).fixture(path) as T;
  return reelm<T>(`/v1/admin/${path}`, { token });
}

/** GET an admin route; `every` re-polls in ms. `reload` refetches (after an action). */
export function useAdmin<T = any>(path: string | null, every?: number) {
  const { s } = useSession();
  const [st, setSt] = useState<{ data?: T; error?: string; at?: number }>({});
  const reload = useCallback(() => {
    if (!path) return;
    adminGet<T>(path, s.token).then((data) => setSt({ data, at: Date.now() })).catch((e) => setSt((o) => ({ ...o, error: e.message })));
  }, [path, s.token]);
  useEffect(() => {
    reload();
    if (!every) return;
    const i = setInterval(reload, every);
    return () => clearInterval(i);
  }, [reload, every]);
  return { ...st, loading: !st.data && !st.error, reload };
}

/** POST/DELETE an admin action. */
/** POST /v1/admin/users: create an account, optionally an admin. */
export const adminCreate = (token: string | undefined, json: { username: string; password: string; admin: boolean }) =>
  reelm<{ user: { id: string; username: string; is_admin: boolean } }>("/v1/admin/users", { method: "POST", token, json });
export function adminAct<T = any>(token: string | undefined, path: string, method = "POST"): Promise<T> {
  if (process.env.NODE_ENV !== "production" && fixtureMode()) return Promise.resolve({ ok: true, password: "k7mq2xp9rh4t" } as T);
  return reelm<T>(`/v1/admin/${path}`, { method, token, json: method === "POST" ? {} : undefined });
}

/** POST /v1/admin/providers {commit?, enabled?}: approve the pinned provider commit and/or the enabled list. */
export const adminPin = (token: string | undefined, json: { commit?: string; enabled?: string[] }) =>
  reelm<{ commit: string; enabled: string[]; approvedBy: string; approvedAt: number }>("/v1/admin/providers", { method: "POST", token, json });
