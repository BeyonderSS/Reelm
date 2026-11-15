import { env, exports } from "cloudflare:workers";
import { afterEach, describe, expect, it, vi } from "vitest";

const APKS = (env as any).APKS as R2Bucket;
const call = (path: string, headers: Record<string, string> = {}, method = "GET") =>
  exports.default.fetch(new Request(`https://api.test${path}`, { method, headers, redirect: "manual" }));
const bytes = (n: number, seed: number) => Uint8Array.from({ length: n }, (_, i) => (i * 7 + seed) & 255);

afterEach(() => vi.restoreAllMocks());

describe("app downloads from R2", () => {
  it("serves latest.json and Range-able APKs", async () => {
    const apk = bytes(1000, 1);
    await APKS.put("Reelm-v1.0.50.apk", apk);
    await APKS.put("latest.json", JSON.stringify({ version: "1.0.50", notes: "n",
      phone: { file: "Reelm-v1.0.50.apk", size: 1000, sha256: "ab" }, tv: { file: "Reelm-TV-v1.0.50.apk", size: 9, sha256: null } }));

    const l = (await (await call("/v1/app/latest")).json()) as any;
    expect(l).toEqual({ version: "1.0.50", notes: "n", phone: { url: "https://api.test/dl/Reelm-v1.0.50.apk", size: 1000, sha256: "ab" },
      tv: { url: "https://api.test/dl/Reelm-TV-v1.0.50.apk", size: 9, sha256: null } });

    const full = await call("/dl/Reelm-v1.0.50.apk");
    expect(full.status).toBe(200);
    expect(full.headers.get("content-type")).toBe("application/vnd.android.package-archive");
    expect(full.headers.get("content-length")).toBe("1000");
    expect(full.headers.get("accept-ranges")).toBe("bytes");
    expect(full.headers.get("cache-control")).toContain("immutable");
    expect(new Uint8Array(await full.arrayBuffer())).toEqual(apk);

    const part = await call("/dl/Reelm-v1.0.50.apk", { range: "bytes=600-" });
    expect(part.status).toBe(206);
    expect(part.headers.get("content-range")).toBe("bytes 600-999/1000");
    expect(part.headers.get("content-length")).toBe("400");
    expect(new Uint8Array(await part.arrayBuffer())).toEqual(apk.slice(600));

    const mid = await call("/dl/Reelm-v1.0.50.apk", { range: "bytes=10-19" });
    expect(mid.headers.get("content-range")).toBe("bytes 10-19/1000");
    expect(new Uint8Array(await mid.arrayBuffer())).toEqual(apk.slice(10, 20));

    const done = await call("/dl/Reelm-v1.0.50.apk", { range: "bytes=1000-" });
    expect(done.status).toBe(416);
    expect(done.headers.get("content-range")).toBe("bytes */1000");

    const head = await call("/dl/Reelm-v1.0.50.apk", {}, "HEAD");
    expect(head.status).toBe(200);
    expect(head.headers.get("content-length")).toBe("1000");

    const alias = await call("/dl/Reelm.apk", {}, "GET");
    expect(alias.status).toBe(302);
    expect(alias.headers.get("location")).toBe("https://api.test/dl/Reelm-v1.0.50.apk");
    expect((await call("/dl/evil.apk")).status).toBe(404);
    await APKS.delete("latest.json");
  });
});

describe("app downloads fall back to GitHub", () => {
  it("proxies a missing APK with Range passthrough, then mirrors it into R2", async () => {
    const apk = bytes(500, 3), seen: string[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input: any, init?: any) => {
      const u = String(input instanceof Request ? input.url : input), range = new Headers(init?.headers).get("range");
      seen.push(`${u} ${range ?? ""}`);
      if (u.endsWith("/releases/latest")) return Response.json({ tag_name: "v1.0.45", body: "notes", assets: [
        { name: "Reelm-v1.0.45.apk", size: 500, digest: "sha256:" + "a".repeat(64) }, { name: "Reelm-TV-v1.0.45.apk", size: 7 }] });
      if (!u.startsWith("https://github.com/BeyonderSS/Reelm/releases/download/v1.0.45/Reelm-v1.0.45.apk")) return new Response("nope", { status: 404 });
      if (range) {
        const from = Number(/bytes=(\d+)-/.exec(range)![1]);
        return new Response(apk.slice(from), { status: 206, headers: { "content-length": String(500 - from), "content-range": `bytes ${from}-499/500` } });
      }
      return new Response(apk, { headers: { "content-length": "500" } });
    });

    const l = (await (await call("/v1/app/latest")).json()) as any;
    expect(l.version).toBe("1.0.45");
    expect(l.phone).toEqual({ url: "https://api.test/dl/Reelm-v1.0.45.apk", size: 500, sha256: "a".repeat(64) });
    expect(l.tv.sha256).toBeNull();

    const part = await call("/dl/Reelm-v1.0.45.apk", { range: "bytes=100-" });
    expect(part.status).toBe(206);
    expect(part.headers.get("content-range")).toBe("bytes 100-499/500");
    expect(part.headers.get("content-type")).toBe("application/vnd.android.package-archive");
    expect(new Uint8Array(await part.arrayBuffer())).toEqual(apk.slice(100));
    expect(seen.some((s) => s.endsWith("Reelm-v1.0.45.apk bytes=100-"))).toBe(true);

    // the background full fetch lands the APK in R2, which then serves it
    await vi.waitFor(async () => expect(await APKS.head("Reelm-v1.0.45.apk")).not.toBeNull());
    expect((await call("/dl/Reelm-v1.0.45.apk")).headers.get("content-length")).toBe("500");

    expect((await call("/dl/Reelm-v1.0.99.apk")).status).toBe(404);
  });
});
