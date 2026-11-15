import { exports } from "cloudflare:workers";
import { afterEach, describe, expect, it, vi } from "vitest";

const call = (qs: string) => exports.default.fetch(new Request(`https://api.test/v1/trailer?${qs}`)).then((r) => r.json() as Promise<any>);
const META = { meta: { logo: "https://x/logo.png", background: "https://x/bg.jpg",
  trailers: [{ source: "BBBBBBBBBBB", type: "Teaser" }, { source: "AAAAAAAAAAA", type: "Trailer" }], trailerStreams: [{ ytId: "AAAAAAAAAAA" }, { ytId: "CCCCCCCCCCC" }] } };
const mock = (fn: (u: string) => Response | undefined) =>
  vi.spyOn(globalThis, "fetch").mockImplementation(async (i: any) => fn(String(i instanceof Request ? i.url : i)) ?? new Response("no", { status: 404 }));

afterEach(() => vi.restoreAllMocks());

describe("GET /v1/trailer", () => {
  it("imdb path: ordered, de-duplicated ids with logo and backdrop", async () => {
    mock((u) => (u.endsWith("/meta/movie/tt1000001.json") ? Response.json(META) : undefined));
    expect(await call("imdb=tt1000001")).toEqual({ ytIds: ["AAAAAAAAAAA", "BBBBBBBBBBB", "CCCCCCCCCCC"],
      logo: "https://x/logo.png", backdrops: ["https://x/bg.jpg"], startAt: 12, imdb: "tt1000001" });
  });

  it("title search picks the year match, not the first hit", async () => {
    mock((u) => u.includes("/catalog/series/top/search=Show%20X.json")
      ? Response.json({ metas: [{ imdb_id: "tt1", releaseInfo: "1999" }, { imdb_id: "tt2000002", releaseInfo: "2020–2024" }] })
      : u.endsWith("/meta/series/tt2000002.json") ? Response.json(META) : undefined);
    const r = await call("title=Show%20X&year=2021&type=series");
    expect(r.imdb).toBe("tt2000002");
    expect(r.ytIds).toHaveLength(3);
    expect(await call("title=Show%20X&year=1900&type=series")).toMatchObject({ imdb: null, ytIds: [] });
  });

  it("mal adds Jikan's id once, after Cinemeta's", async () => {
    mock((u) => u.endsWith("/meta/series/tt3000003.json") ? Response.json(META)
      : u.endsWith("/v4/anime/16498") ? Response.json({ data: { trailer: { youtube_id: "DDDDDDDDDDD" } } }) : undefined);
    expect((await call("imdb=tt3000003&type=series&mal=16498")).ytIds).toEqual(["AAAAAAAAAAA", "BBBBBBBBBBB", "CCCCCCCCCCC", "DDDDDDDDDDD"]);
    mock((u) => u.endsWith("/v4/anime/16499") ? Response.json({ data: { trailer: { youtube_id: "DDDDDDDDDDD" } } }) : undefined);
    expect((await call("mal=16499")).ytIds).toEqual(["DDDDDDDDDDD"]);
  });

  it("upstream failure returns the empty shape with 200", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("down"));
    expect(await call("imdb=tt4000004&mal=7")).toEqual({ ytIds: [], logo: null, backdrops: [], startAt: 12, imdb: "tt4000004" });
  });
});
