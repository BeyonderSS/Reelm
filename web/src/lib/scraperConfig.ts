import { verifySignature } from "./scraperVerify";

// Scraper constants (hosts, keys, versions) published by .github/workflows/scraper-sync.yml. Every scraper keeps its
// built-in values as the default, so a missing, stale, unsigned or tampered file simply leaves the app as it was shipped.
const URL_ = "https://raw.githubusercontent.com/BeyonderSS/Reelm/main/scrapers/scrapers.json";
const PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEw0hwDabVPIDxew9Agk+tBkl1eSu9an4/527zHo7NI7Nj0x/RygIf6cqh0VSTNLvVjuQCy3qQ9KZu3T34IxycWw==";
const TTL = 30 * 60 * 1000;

type Doc = Record<string, { config?: Record<string, unknown> } | undefined>;
let loaded: { at: number; doc: Doc | null } | undefined;
let inflight: Promise<Doc | null> | undefined;

async function fetchDoc(): Promise<Doc | null> {
  try {
    const [body, sig] = await Promise.all([fetch(URL_), fetch(URL_ + ".sig")]);
    if (!body.ok || !sig.ok) return null;
    const text = await body.text();
    return (await verifySignature(text, await sig.text(), PUBLIC_KEY)) ? (JSON.parse(text) as Doc) : null;
  } catch {
    return null;
  }
}

async function doc(): Promise<Doc | null> {
  if (loaded && Date.now() - loaded.at < TTL) return loaded.doc;
  inflight ??= fetchDoc().then((d) => { loaded = { at: Date.now(), doc: d ?? loaded?.doc ?? null }; inflight = undefined; return loaded.doc; });
  return inflight;
}

// same JSON shape as the default (type, array-ness, element type), so a malformed value can never replace a good one
const sameShape = (a: unknown, b: unknown): boolean =>
  Array.isArray(a) ? Array.isArray(b) && b.length > 0 && (a.length === 0 || sameShape(a[0], b[0])) : typeof a === typeof b && a !== null;

/** `defaults` merged with the published config for `source`; only keys the default already has, and only when the shape matches. */
export async function scraperConfig<T extends object>(source: string, defaults: T): Promise<T> {
  const remote = (await doc())?.[source]?.config;
  if (!remote) return defaults;
  const base = defaults as Record<string, unknown>, out = { ...base };
  for (const k of Object.keys(base)) if (k in remote && sameShape(base[k], remote[k])) out[k] = remote[k];
  return out as T;
}
