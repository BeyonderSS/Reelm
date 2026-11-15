import { exports } from "cloudflare:workers";
import { afterEach, describe, expect, it, vi } from "vitest";

const call = (qs: string) => exports.default.fetch(new Request(`https://api.test/v1/trailer?${qs}`)).then((r) => r.json() as Promise<any>);
const META = { meta: { name: "Show X", logo: "https://x/logo.png", background: "https://x/bg.jpg",
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
      ? Response.json({ metas: [{ imdb_id: "tt1", name: "Show X", releaseInfo: "1999" }, { imdb_id: "tt2000002", name: "Show X", releaseInfo: "2020–2024" }] })
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

  it("imdb without a type: the series wins over Cinemeta's bogus movie answer, unless the title names the movie", async () => {
    const named = (name: string) => Response.json({ meta: { ...META.meta, name, trailers: [{ source: name.padEnd(11, "x").slice(0, 11), type: "Trailer" }] } });
    mock((u) => u.endsWith("/meta/series/tt5000005.json") ? named("Numb3rs") : u.endsWith("/meta/movie/tt5000005.json") ? named("Jaws") : undefined);
    expect((await call("imdb=tt5000005")).ytIds[0]).toBe("Numb3rsxxxx");
    expect((await call("imdb=tt5000005&title=Jaws")).ytIds[0]).toBe("Jawsxxxxxxx");
    expect((await call("imdb=tt5000005&title=Numbers")).ytIds[0]).toBe("Numb3rsxxxx");
    // Cinemeta's series endpoint answers a movie id with {}: the movie is used
    mock((u) => u.endsWith("/meta/series/tt5000006.json") ? Response.json({}) : u.endsWith("/meta/movie/tt5000006.json") ? named("Dune") : undefined);
    expect((await call("imdb=tt5000006")).ytIds[0]).toBe("Dunexxxxxxx");
  });

  it("title search needs a name match; anime with an exact name ignores the year", async () => {
    const hits = { metas: [{ imdb_id: "tt6000001", name: "Some Other Show", releaseInfo: "2020" }, { imdb_id: "tt6000002", name: "Black Clover", releaseInfo: "2017–2021" }] };
    mock((u) => u.includes("/catalog/series/top/search=") ? Response.json(hits) : u.includes("/meta/series/tt600000") ? Response.json(META) : undefined);
    expect((await call("title=Panchayat&year=2020&type=series")).imdb).toBeNull();
    expect((await call("title=Black%20Clover&year=2026&type=series&mal=34572")).imdb).toBe("tt6000002");
    expect((await call("title=Black%20Clover&year=2026&type=series")).imdb).toBeNull();
    expect((await call("title=black%20clover%3A%20Season%202&year=2018&type=series")).imdb).toBe("tt6000002");
    expect((await call("title=Black%20Clovr&year=2026&type=series&mal=34573")).imdb).toBeNull();
  });

  it("errors are not cached, a genuine 'no trailers' is", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("timeout"));
    expect((await call("imdb=tt7000001&type=movie")).ytIds).toEqual([]);
    vi.restoreAllMocks();
    mock((u) => (u.endsWith("/meta/movie/tt7000001.json") ? Response.json(META) : undefined));
    expect((await call("imdb=tt7000001&type=movie")).ytIds).toHaveLength(3);

    mock(() => new Response("busy", { status: 503 }));
    expect((await call("mal=8000001")).ytIds).toEqual([]);
    mock((u) => (u.endsWith("/v4/anime/8000001") ? Response.json({ data: { trailer: { youtube_id: "DDDDDDDDDDD" } } }) : undefined));
    expect((await call("mal=8000001")).ytIds).toEqual(["DDDDDDDDDDD"]);

    mock(() => undefined); // 404 everywhere: genuinely nothing, cached
    expect((await call("imdb=tt7000002&type=movie")).ytIds).toEqual([]);
    mock((u) => (u.endsWith("/meta/movie/tt7000002.json") ? Response.json(META) : undefined));
    expect((await call("imdb=tt7000002&type=movie")).ytIds).toEqual([]);
  });
});
