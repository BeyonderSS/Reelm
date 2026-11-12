"use client";
import { useEffect, useMemo, useState } from "react";
import { kidsOk, Rail } from "@/components/app";
import { getFavs, recSeeds, reelm, toPlay, type CardData } from "@/lib/core";
import type { ServerRec } from "@/lib/recs-rank";
import { useSession } from "@/lib/session";

type Rec = CardData & { by?: string[] };
type Srv = { items: ServerRec[]; popular: (ServerRec & { viewers: number })[]; confidence: number };
/** A /v1/recs payload (history-payload shape) as a card. */
const srvCard = (r: Srv["popular"][number]): CardData[] => {
  const p = toPlay(r.payload);
  return p && p.kind !== "live" && p.title ? [{ href: p.kind === "anime" ? `/anime/${p.id}` : `/title/${encodeURIComponent(p.id)}?type=${p.type}`, title: p.title, poster: p.poster, kind: p.kind === "anime" ? "anime" : "media", sub: `${r.viewers} watching this week` }] : [];
};
export const audioLang = () => { try { return localStorage.getItem("reelm.audioLang") ?? ""; } catch { return ""; } };
const TTL = 6 * 36e5;
// one /v1/recs call per profile+session a minute: re-renders, remounts and strict mode reuse it
let srvMemo: { k: string; at: number; pr: Promise<Srv | null> } | undefined;
const srvRecs = (pid: string, token: string) => {
  const k = `${pid}:${token}`;
  if (srvMemo?.k !== k || Date.now() - srvMemo.at > 60_000) srvMemo = { k, at: Date.now(), pr: reelm<Srv>(`/v1/recs?profile=${pid}&limit=30`, { token }).catch(() => null) };
  return srvMemo.pr;
};
const hash = (s: string) => { let h = 5381; for (let i = 0; i < s.length; i++) h = (h * 33) ^ s.charCodeAt(i); return (h >>> 0).toString(36); };

/** History seeds plus favourites: a favourite counts 1.5x, and seeds on its own (as half-watched) when never played (as Android's seedsFrom). */
function body(pid: string) {
  const b = recSeeds(pid), favs = getFavs(pid).map((c) => {
    const m = c.href.match(/^\/anime\/([^?]+)|^\/title\/([^?]+)\?type=(\w+)/);
    return m && { id: decodeURIComponent(m[1] ?? m[2]), kind: m[1] ? "anime" : "media", title: c.title, type: m[3], year: undefined, weight: 0.65 * 1.5 };
  }).filter((f) => !!f);
  const ids = new Set(favs.map((f) => f.id));
  const seeds = [...b.seeds.map((s) => (ids.has(s.id) ? { ...s, weight: s.weight * 1.5 } : s)), ...favs.filter((f) => !b.seeds.some((s) => s.id === f.id))]
    .sort((x, y) => y.weight - x.weight).slice(0, 24);
  return { seeds, seen: [...b.seen, ...ids], seenTitles: [...b.seenTitles, ...favs.map((f) => f.title)] };
}

/** "Top picks for you" plus up to two "Because you watched X" rows, cached per profile until history changes or 6 h pass. */
export function HomeRecs() {
  const { s } = useSession(), p = s.profile;
  const c = useMemo(() => { try { return p ? JSON.parse(localStorage.getItem(`reelm.recs.${p.id}`) ?? "null") as { sig: string; at: number; items: Rec[] } | null : null; } catch { return null; } }, [p]);
  const [got, setGot] = useState<{ pid: string; items: Rec[] }>(), items = got && got.pid === p?.id ? got.items : c?.items ?? []; // stale picks while fresh ones load
  const [pop, setPop] = useState<{ pid: string; items: CardData[] }>();
  useEffect(() => {
    if (!p) return;
    let live = true;
    // signed in: Reelm's own co-watch picks join the ranking (weighted by their confidence) and feed "Popular on Reelm"; any failure → local picks only
    (s.token ? srvRecs(p.id, s.token) : Promise.resolve(null)).then((d) => {
      if (!live) return;
      // payloads without a poster get one looked up; any still without one are dropped
      const popular = d?.popular ?? [], bare = popular.filter((r) => !toPlay(r.payload)?.poster);
      (bare.length ? fetch("/api/recs", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ posters: bare }) }).then((r) => (r.ok ? r.json() : {})).catch(() => ({})) : Promise.resolve({}))
        .then((pm: Record<string, string>) => live && setPop({ pid: p.id, items: popular.map((r) => (pm[r.item] ? { ...r, payload: { ...r.payload, poster: pm[r.item] } } : r)).flatMap(srvCard).filter((c) => c.poster) }));
      const server = d?.items?.length ? { items: d.items, confidence: d.confidence } : undefined;
      const req = { ...body(p.id), kids: !!p.kids, mature: !!p.mature && !p.kids, lang: audioLang(), server };
      if (!req.seeds.length) return;
      const key = `reelm.recs.${p.id}`, sig = hash(JSON.stringify([req.seeds.map((x) => [x.id, x.weight.toFixed(1)]), req.kids, req.mature, req.lang, server?.items.map((x) => x.item), server?.confidence.toFixed(1)]));
      if (c?.sig === sig && Date.now() - c.at < TTL) return;
      fetch("/api/recs", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(req) })
        .then((r) => (r.ok ? r.json() : []))
        .then((d: Rec[]) => {
          if (!live || !d.length) return;
          setGot({ pid: p.id, items: d });
          try { localStorage.setItem(key, JSON.stringify({ sig, at: Date.now(), items: d })); } catch {}
        })
        .catch(() => {});
    });
    return () => { live = false; };
  }, [p, c, s.token]);
  const shown = items.filter((c) => kidsOk(c, p?.kids, p));
  const top = shown.slice(0, 15), used = new Set(top.map((c) => c.href)), because: { t: string; items: Rec[] }[] = [];
  for (const t of new Set(shown.flatMap((c) => c.by?.[0] ?? []))) { // seeds in the order their picks lead
    if (because.length === 2) break;
    const rest = shown.filter((c) => !used.has(c.href) && c.by?.includes(t));
    if (rest.length < 4) continue;
    rest.forEach((c) => used.add(c.href));
    because.push({ t, items: rest });
  }
  return (
    <>
      <Rail title="Top picks for you" items={top} />
      {pop && pop.pid === p?.id && <Rail title="Popular on Reelm" items={pop.items.filter((c) => kidsOk(c, p?.kids, p))} />}
      {because.map((b) => <Rail key={b.t} title={`Because you watched ${b.t}`} items={b.items} />)}
    </>
  );
}
