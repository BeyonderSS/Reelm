/** Dev-only: `/admin/providers?fixture` data, trimmed from a real GitHub compare (vega-providers 4ecbc98...988c7b6). Never loaded in production. */
import d from "./admin-provider-fixtures.json";

export function providerFixture(path: string): unknown {
  const u = new URL(path, "http://x/");
  if (u.pathname === "/providers/diff") {
    const p = u.searchParams.get("provider") ?? "", x = (d.diffs as Record<string, { files: unknown[]; commits: unknown[] }>)[p];
    return { from: d.from, to: d.to, provider: p, files: x?.files ?? [], commits: x?.commits ?? [] };
  }
  return { pinned: { commit: d.from, enabled: ["vega", "drive", "4khdhub", "mod", "autoEmbed", "castle"], approvedBy: "beyonder", approvedAt: Date.now() - 12 * 86_400_000 },
    upstream: d.upstream, behind: d.behind, changed: d.changed, compareUrl: `https://github.com/Zenda-Cross/vega-providers/compare/${d.from}...${d.to}`, manifest: d.manifest };
}
