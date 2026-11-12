import { posters, recommend } from "@/lib/server/recs";

// Server side so TMDB_API_KEY (a Worker secret) never reaches the browser.
export async function POST(req: Request) {
  try {
    const b = await req.json();
    if (Array.isArray(b?.posters)) return Response.json(await posters(b.posters.filter((r: any) => typeof r?.item === "string" && r.payload)), { headers: { "cache-control": "no-store" } });
    if (!Array.isArray(b?.seeds)) return Response.json({ error: "bad request" }, { status: 400 });
    return Response.json(await recommend({ seeds: b.seeds, seen: b.seen ?? [], seenTitles: b.seenTitles ?? [], kids: !!b.kids, mature: !!b.mature, lang: typeof b.lang === "string" ? b.lang : "", server: b.server && typeof b.server === "object" ? b.server : undefined }), { headers: { "cache-control": "no-store" } });
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
