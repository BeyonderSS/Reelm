import { india } from "@/lib/server/recs";

// Its own route so it can read the visitor's country (Vercel or Cloudflare header); private because the answer varies by it.
export async function GET(req: Request) {
  const country = (req.headers.get("x-vercel-ip-country") ?? req.headers.get("cf-ipcountry") ?? "").toUpperCase();
  try {
    return Response.json({ country, rows: await india() }, { headers: { "cache-control": "private, max-age=1800" } });
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
