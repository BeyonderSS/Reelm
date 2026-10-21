// ECDSA P-256 / SHA-256 check for scrapers.json (signature is base64 raw r||s, see scraper-sync/sign.mjs).
// No imports, so node can load it directly in tests.
const bytes = (b64: string) => Uint8Array.from(atob(b64.trim()), (c) => c.charCodeAt(0));

export async function verifySignature(data: string, sigB64: string, publicKeyB64: string): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey("spki", bytes(publicKeyB64), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, bytes(sigB64), new TextEncoder().encode(data));
  } catch {
    return false;
  }
}
