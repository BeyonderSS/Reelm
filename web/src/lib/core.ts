import { epsKey, frac, mergeEps, type Eps } from "./eps";
export const API = process.env.NEXT_PUBLIC_REELM_API_URL || "https://reelm-api.ssbeyonder.workers.dev";
export const WS = API.replace(/^http/, "ws");

/** Server-side content routes (same origin). Catalog reads are shared per page load (home asked for anime/home four times);
 *  stream URLs carry expiring tokens, so they are never cached. */
const memo = new Map<string, Promise<any>>();
export function get<T = any>(path: string): Promise<T> {
  const hit = memo.get(path);
  if (hit) return hit;
  const [key, qs = ""] = path.split("?");
  if (typeof window !== "undefined" && /^movies\/(search|detail|stream)$/.test(key)) {
    const p = import("./movies").then((m) => m.movieRoutes[key](new URLSearchParams(qs))) as Promise<T>;
    if (key !== "movies/stream") { memo.set(path, p); p.catch(() => memo.delete(path)); }
    return p;
  }
  const p = fetch(`/api/${path}`).then(async (r) => {
    const d = await r.json().catch(() => ({ error: `HTTP ${r.status}` }));
    if (!r.ok) throw new Error(d.error ?? `HTTP ${r.status}`);
    return d as T;
  });
  if (!/stream/.test(path)) { memo.set(path, p); p.catch(() => memo.delete(path)); }
  return p;
}

/** Reelm account API (server/API.md). */
export async function reelm<T = any>(path: string, o: { method?: string; json?: unknown; token?: string } = {}): Promise<T> {
  const r = await fetch(API + path, {
    method: o.method ?? (o.json ? "POST" : "GET"),
    headers: { ...(o.json ? { "content-type": "application/json" } : {}), ...(o.token ? { Authorization: `Bearer ${o.token}` } : {}) },
    body: o.json ? JSON.stringify(o.json) : undefined,
  });
  if (r.status === 204) return undefined as T;
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(d.error ?? `HTTP ${r.status}`);
  return d;
}

/** Route a stream through the Worker proxy (adds Referer + CORS). */
export const proxied = (u: string, referer = "", any = false) =>
  `${API}/v1/proxy?u=${encodeURIComponent(u)}&r=${encodeURIComponent(referer)}${any ? "&any=1" : ""}`;

// ---- Fluent Emoji avatars (ids and URL format from android core/account Models.kt) ----
const FOLDERS = [
  "Grinning face", "Smiling face with sunglasses", "Star-struck", "Nerd face", "Partying face", "Winking face",
  "Cat face", "Dog face", "Fox", "Panda", "Koala", "Lion", "Unicorn", "Frog", "Monkey face", "Penguin", "Owl", "Rabbit face",
  "Pizza", "Hamburger", "Cupcake", "Doughnut", "Avocado", "Popcorn",
];
const idOf = (f: string) => f.toLowerCase().replace(/ /g, "_");
export const EMOJI_IDS = FOLDERS.map(idOf);
export const EMOJI_GROUPS: [string, string[]][] = [["Faces", EMOJI_IDS.slice(0, 6)], ["Animals", EMOJI_IDS.slice(6, 18)], ["Snacks", EMOJI_IDS.slice(18)]];
export const avatarUrl = (id: string) => {
  const f = FOLDERS.find((x) => idOf(x) === id) ?? FOLDERS[0];
  return `https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/${f.replace(/ /g, "%20")}/3D/${idOf(f)}_3d.png`;
};

// ---- Kids policy (port of android domain KidsPolicy) ----
const ANIME_GENRES = ["hentai", "ecchi", "horror", "gore"];
const MEDIA_GENRES = ["horror", "thriller", "crime", "war", "adult", "erotic"];
const FAMILY_GENRES = ["animation", "family", "kids", "comedy", "adventure"];
const ADULT_CERTS = ["R", "NC-17", "NC17", "X", "TV-MA", "18", "18+", "R18", "R+", "RX"];
export const KIDS_BLOCKED = "Not available on this profile";
export const kids = {
  animeRatingBlocked: (r?: string) => /^(R|18|NC)/.test((r ?? "").trim().toUpperCase()),
  animeCard: (c: { rating?: string }) => !kids.animeRatingBlocked(c.rating),
  anime: (rating: string | undefined, genres: string[]) =>
    !kids.animeRatingBlocked(rating) && !genres.some((g) => ANIME_GENRES.includes(g.trim().toLowerCase())) && (!!rating?.trim() || genres.length > 0),
  media(genres: string[] = [], cert?: string) {
    if (cert && ADULT_CERTS.includes(cert.trim().toUpperCase())) return false;
    const g = genres.map((x) => x.trim().toLowerCase());
    return !g.some((x) => MEDIA_GENRES.includes(x)) && g.some((x) => FAMILY_GENRES.includes(x));
  },
};

// ---- Local library: history (per profile) + favorites ----
export type Play = { kind: "anime" | "movie" | "live"; id: string; title: string; poster?: string; ep?: number; epId?: number; season?: number; episode?: number; lang?: string; type?: string; year?: number; url?: string };
export type Entry = { key: string; payload: Play; position: number; duration: number; updatedAt: number; deleted: boolean; /** per-episode progress merged across every row of the title */ eps?: Eps };
export type CardData = { href: string; title: string; poster?: string; sub?: string; rating?: string; genres?: string[]; kind?: "anime" | "media"; progress?: number; onRemove?: () => void };

/** Same key scheme as Android's HistoryPayload.key: one row per title, `anime:<slug>` / `media:<id>` (the row holds the latest episode). */
export const playKey = (p: Play) => p.kind === "anime" ? `anime:${p.id}` : `media:${mediaSlug(p) || p.id}`;
/**
 * Catalog-independent history slug `<movie|series>:<normalized title>`; the key is `media:` + it. Same rules as Android's mediaSlug (domain/model/WatchHistory.kt).
 * ponytail: same-title remakes collide; include the year when every source provides it.
 */
export const normTitle = (t: string) => t.toLowerCase().replace(/&/g, "and").replace(/^\s*the\b/, "").replace(/[^\p{L}\p{N}]/gu, "");
const mediaSlug = (p: Play) => normTitle(p.title ?? "") && `${p.type === "series" ? "series" : "movie"}:${normTitle(p.title)}`;
/** Per-episode id, for episode grids and for telling whether a resume row is this episode. */
export const epKey = (p: Play) => p.kind === "anime" ? `anime:${p.id}:${p.ep}` : `movie:${p.id}:${p.season ?? 0}:${p.episode ?? 0}`;
/** Keys the web wrote before it matched Android (per-episode); they showed up as duplicates and are retired on sync. */
const legacy = (k: string) => k.startsWith("movie:") || /^media:(?!(movie|series):)/.test(k) || /^anime:[^:]+:\d+$/.test(k);
/** `f` resumes at a fraction of the duration (entries hold seconds; older app rows hold permille of 1000, so only the ratio is shared). */
export const watchHref = (p: Play, f = 0) => `/watch?p=${encodeURIComponent(JSON.stringify(p))}${f > 0 && f < 0.95 ? `&f=${f.toFixed(4)}` : ""}`;
export const entryHref = (e: Entry) => watchHref(e.payload, frac(e.position, e.duration));
export const entrySub = (p: Play) => p.kind === "anime" ? `Episode ${p.ep}` : p.season ? `Season ${p.season}, episode ${p.episode}` : "Movie";

const num = (v: unknown) => (Number.isFinite(Number(v)) ? Number(v) : 0);
const str = (v: unknown) => (v == null ? "" : String(v).trim());
/**
 * Turn any payload the apps write into a web `Play`: web payloads ({kind:"anime",id,ep,epId} / {kind:"movie",id,season,episode,type}),
 * Android/TV HistoryPayload ({slug,title,kind:"anime"|"media",episode,season,mediaId,poster,progress}) and room payloads.
 * Anime without an `epId` is resolved by episode number on the watch page.
 */
export function toPlay(r: any): Play | null {
  if (!r || typeof r !== "object") return null;
  const title = str(r.title ?? r.animeTitle), poster = str(r.poster ?? r.posterUrl) || undefined;
  if (r.kind === "live") return r.url ? { kind: "live", id: str(r.id) || r.url, title, poster, url: r.url } : null;
  if (r.kind === "anime" || (!r.kind && !r.mediaId)) {
    const id = str(r.id ?? r.slug ?? r.animeSlug);
    if (!id) return null;
    return { kind: "anime", id, title, poster, ep: Math.max(1, num(r.ep ?? r.episode ?? r.lastEpisodeWatched)), epId: num(r.epId) || undefined, lang: r.lang === "dub" ? "dub" : "sub" };
  }
  const id = str(r.kind === "media" ? r.mediaId || r.slug : r.id || r.mediaId).replace(/:s\d+e\d+$/, "");
  if (!id) return null;
  const season = num(r.season), episode = num(r.episode);
  const type = r.type === "series" || r.type === "movie" ? r.type : /^series:/.test(r.slug ?? "") || season > 0 || episode > 0 ? "series" : "movie";
  return { kind: "movie", id, title, poster, season, episode, type, year: num(r.year) || undefined };
}

const ls = {
  get<T>(k: string, d: T): T { try { return JSON.parse(localStorage.getItem(k) ?? "") ?? d; } catch { return d; } },
  set(k: string, v: unknown) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};
// ---- Favourites: per profile, synced like history (key `anime:<slug>` / `media:<movie|series>:<normtitle>`, LWW on updatedAt, tombstones) ----
export type FavEntry = { key: string; payload: any; updatedAt: number; deleted: boolean };
const fKey = (pid: string) => `reelm.favs.${pid}`;
/** Local favourites of a profile. The pre-sync flat list (`reelm.fav`) joins the first profile that reads it, and syncFavs uploads it. */
function rawFavs(pid: string): Record<string, FavEntry> {
  const h = ls.get<Record<string, FavEntry>>(fKey(pid), {}), old = ls.get<CardData[]>("reelm.fav", []);
  if (old.length) {
    const now = Date.now();
    old.forEach((c, i) => { const e = favEntry(c, false, now - i); if (!h[e.key]) h[e.key] = e; });
    ls.set(fKey(pid), h); ls.set("reelm.fav", []);
  }
  return h;
}
function favEntry(c: CardData, deleted: boolean, updatedAt: number): FavEntry {
  const u = new URL(c.href, "http://x"), id = decodeURIComponent(u.pathname.split("/").pop() ?? ""), anime = c.kind === "anime" || u.pathname.startsWith("/anime/");
  const type = u.searchParams.get("type") === "series" ? "series" : "movie", year = Number(/\b(\d{4})\b/.exec(c.sub ?? "")?.[1]) || undefined;
  const key = anime ? `anime:${id}` : `media:${type}:${normTitle(c.title)}`;
  return { key, deleted, updatedAt, payload: { id, mediaId: anime ? undefined : id, slug: anime ? id : undefined, kind: anime ? "anime" : "media", type: anime ? undefined : type, year,
    title: c.title, poster: c.poster, sub: c.sub, rating: c.rating, genres: c.genres } };
}
/** Saved cards of a profile, newest first. Entries from the Android apps ({id|mediaId|slug, title, poster, type, year}) become web cards. */
export function getFavs(pid: string): CardData[] {
  return Object.values(rawFavs(pid)).filter((e) => !e.deleted && e.payload).sort((a, b) => b.updatedAt - a.updatedAt).map(({ payload: p }) => {
    const anime = p.kind === "anime", id = String(anime ? p.id ?? p.slug : p.mediaId ?? p.id ?? ""), type = p.type === "series" ? "series" : "movie";
    return { href: anime ? `/anime/${id}` : `/title/${encodeURIComponent(id)}?type=${type}`, title: p.title ?? "", poster: p.poster || undefined, kind: anime ? "anime" : "media",
      sub: p.sub ?? (anime ? undefined : [type === "series" ? "Series" : "Movie", p.year].filter(Boolean).join(", ")), rating: p.rating, genres: p.genres } as CardData;
  });
}
const pushFavs = (pid: string, token: string | undefined, entries: FavEntry[]) => token && entries.length
  ? Promise.all(Array.from({ length: Math.ceil(entries.length / 500) }, (_, i) => reelm(`/v1/profiles/${pid}/favorites`, { method: "PUT", token, json: { entries: entries.slice(i * 500, i * 500 + 500) } }))).catch(() => {})
  : Promise.resolve();
/** Flip a favourite (a fresh updatedAt; removal keeps a tombstone) and push it when signed in. Returns whether it is now saved. */
export function toggleFav(pid: string, token: string | undefined, c: CardData) {
  const h = rawFavs(pid), n = favEntry(c, false, 0), cur = h[n.key], on = !cur || cur.deleted;
  const e: FavEntry = { ...(on ? n : cur), deleted: !on, updatedAt: Math.max(Date.now(), (cur?.updatedAt ?? 0) + 1) };
  ls.set(fKey(pid), { ...h, [e.key]: e });
  void pushFavs(pid, token, [e]);
  return on;
}
/** Pull the profile's favourites (last write wins), then upload whatever is newer here, which covers the first sync of pre-existing local favourites. */
export async function syncFavs(pid: string, token: string) {
  const { entries } = await reelm<{ entries: FavEntry[] }>(`/v1/profiles/${pid}/favorites?since=0`, { token }); // ponytail: full pull, a few KB; keep the returned `now` as `since` if lists get large
  const h = rawFavs(pid), remote = new Map(entries.map((e) => [e.key, e]));
  for (const e of entries) if (!h[e.key] || h[e.key].updatedAt < e.updatedAt) h[e.key] = e;
  ls.set(fKey(pid), h);
  await pushFavs(pid, token, Object.values(h).filter((e) => (remote.get(e.key)?.updatedAt ?? -1) < e.updatedAt));
}

const hKey = (pid: string) => `reelm.hist.${pid}`;
const rawHist = (pid: string) => ls.get<Record<string, Entry>>(hKey(pid), {});
/** The payload an entry carries: the web `Play` plus the fields Android's HistoryPayload.decode reads (`secs` = real length, for "N min left"). */
const wire = (p: Play, position: number, duration: number, eps?: Eps, secs?: number): Play => ({
  ...p, slug: p.kind === "anime" ? p.id : playKey(p).slice(6), mediaId: p.id, kind: p.kind === "anime" ? "anime" : "media", episode: p.kind === "anime" ? p.ep : p.episode,
  progress: frac(position, duration), subtitle: entrySub(p), ...(secs ? { duration: Math.round(secs) } : {}), ...(eps && Object.keys(eps).length ? { eps } : {}),
} as any);
/** History with every payload normalized to `Play`. Rows for the same title (kind + id) collapse to the newest, so old per-episode duplicates vanish. */
export function getHistory(pid: string): Record<string, Entry> {
  const out: Record<string, Entry> = {};
  for (const e of Object.values(rawHist(pid))) {
    const payload = toPlay(e?.payload);
    if (!payload) continue;
    const k = playKey(payload);
    if (e.deleted && e.key !== k) continue; // a retired per-id key's tombstone must not shadow the canonical row
    const eps = mergeEps(out[k]?.eps, (e.payload as any)?.eps); // title-level last-write-wins must not drop episodes
    if (!out[k] || out[k].updatedAt < e.updatedAt) out[k] = { ...e, key: k, payload };
    out[k].eps = eps;
  }
  return out;
}
/** Progress (0..1) per episode of one title (keys from epsKey: `e3`, `s1e2`), for episode grids. The latest-episode row fills in when `eps` has no entry for it. */
export function epProgress(pid: string, p: Play): Record<string, number> {
  const e = getHistory(pid)[playKey(p)];
  if (!e || e.deleted) return {};
  const out: Record<string, number> = Object.fromEntries(Object.entries(e.eps ?? {}).map(([k, v]) => [k, v.p]));
  const k = epsKey(e.payload);
  if (k && !(k in out) && e.duration) out[k] = frac(e.position, e.duration);
  return out;
}
/** Latest entry per show, unfinished first-class citizens of Continue Watching. */
export function continueWatching(pid: string): Entry[] {
  const seen = new Set<string>();
  return Object.values(getHistory(pid)).filter((e) => !e.deleted && e.payload.kind !== "live").sort((a, b) => b.updatedAt - a.updatedAt)
    .filter((e) => { const s = `${e.payload.kind}:${e.payload.id}`; return !seen.has(s) && !!seen.add(s); })
    .filter((e) => !e.duration || frac(e.position, e.duration) < 0.95);
}
const push = (pid: string, token: string | undefined, entries: Entry[]) => token && entries.length
  ? Promise.all(Array.from({ length: Math.ceil(entries.length / 100) }, (_, i) => reelm(`/v1/profiles/${pid}/history`, { method: "PUT", token, json: { entries: entries.slice(i * 100, i * 100 + 100) } }))).catch(() => {})
  : Promise.resolve();
export async function saveProgress(pid: string, token: string | undefined, p: Play, position: number, duration: number) {
  if (p.kind === "live") return;
  const key = playKey(p), cur = getHistory(pid)[key], now = Date.now(), f = frac(position, duration), k = epsKey(p);
  // no real change (paused, or the unmount save right after the 10 s one) must not bump updatedAt: that would beat a newer write from another device
  if (cur && !cur.deleted && epKey(cur.payload) === epKey(p) && Math.abs(frac(cur.position, cur.duration) - f) * duration < 2) return;
  const eps = mergeEps(cur?.eps, k && f > 0 ? { [k]: { p: f, at: now } } : undefined);
  const e: Entry = { key, payload: wire(p, position, duration, eps, duration), position, duration, updatedAt: Math.max(now, (cur?.updatedAt ?? 0) + 1), deleted: false };
  ls.set(hKey(pid), { ...rawHist(pid), [e.key]: e });
  await push(pid, token, [e]);
}
/** Tombstone (deleted + fresh updatedAt) every row of this profile whose title matches `only` (all rows when omitted); the other devices drop them on their next pull. */
export async function removeHistory(pid: string, token: string | undefined, only?: Play) {
  if (token) await syncHistory(pid, token).catch(() => {});
  const h = rawHist(pid), now = Date.now(), out: Entry[] = [];
  const hit = new Set<string>();
  for (const [k, e] of Object.entries(h)) {
    const p = toPlay(e?.payload);
    if (only && (!p || playKey(p) !== playKey(only))) continue;
    if (p) hit.add(playKey(p));
    if (!e.deleted) out.push(h[k] = { ...e, deleted: true, updatedAt: Math.max(now, e.updatedAt + 1) });
  }
  // the canonical key must carry the newest tombstone even when only legacy rows existed
  for (const k of hit) if (!h[k]) { const src = Object.values(h).find((e) => { const p = toPlay(e.payload); return p && playKey(p) === k; })!; out.push(h[k] = { ...src, key: k, deleted: true, updatedAt: now }); }
  ls.set(hKey(pid), h);
  await push(pid, token, out);
}
/** Pull the profile's synced history into the local copy (last write wins), then retire per-episode keys from before the web matched Android. */
export async function syncHistory(pid: string, token: string) {
  const { entries } = await reelm<{ entries: Entry[] }>(`/v1/profiles/${pid}/history?since=0`, { token });
  const h = rawHist(pid);
  for (const e of entries) {
    const cur = h[e.key], win = !cur || cur.updatedAt < e.updatedAt ? e : cur, eps = mergeEps((cur?.payload as any)?.eps, (e.payload as any)?.eps);
    h[e.key] = Object.keys(eps).length ? { ...win, payload: { ...win.payload, eps } as any } : win;
  }
  const out: Entry[] = [], now = Date.now();
  for (const [k, e] of Object.entries(h)) {
    const p = legacy(k) && !e.deleted && toPlay(e.payload);
    if (!p) continue;
    const c = playKey(p);
    if (!h[c] || h[c].updatedAt < e.updatedAt) out.push(h[c] = { ...e, key: c, payload: wire(p, e.position, e.duration, (e.payload as any)?.eps, (e.payload as any)?.duration), updatedAt: now });
    out.push(h[k] = { ...e, deleted: true, updatedAt: now });
  }
  ls.set(hKey(pid), h);
  await push(pid, token, out);
}

export type Profile = { id: string; name: string; avatar: string; kids: boolean };
export type Session = { token?: string; user?: { id: string; username: string; is_admin?: boolean }; profile?: Profile; guest?: boolean };
export const loadSession = () => ls.get<Session>("reelm.session", {});
export const storeSession = (s: Session) => ls.set("reelm.session", s);
export const GUEST: Profile = { id: "guest", name: "Guest", avatar: "grinning_face", kids: false };
export const deviceId = () => { let d = ls.get<string>("reelm.device", ""); if (!d) ls.set("reelm.device", (d = crypto.randomUUID())); return d; };

/** Taste seeds from this profile's history for /api/recs: weight = (0.3 + 0.7 * completion) halved every 30 days (as Android's Ranking.seedWeight). */
export function recSeeds(pid: string) {
  const live = Object.values(getHistory(pid)).filter((e) => !e.deleted && e.payload.kind !== "live"), now = Date.now();
  return {
    seeds: live.map((e) => ({
      id: e.payload.id, kind: e.payload.kind === "anime" ? "anime" : "media", title: e.payload.title, type: e.payload.type, year: e.payload.year,
      weight: (0.3 + 0.7 * frac(e.position, e.duration)) * 0.5 ** (Math.max(0, now - e.updatedAt) / 864e5 / 30),
    })).sort((a, b) => b.weight - a.weight).slice(0, 24),
    seen: live.map((e) => e.payload.id), seenTitles: live.map((e) => e.payload.title),
  };
}
