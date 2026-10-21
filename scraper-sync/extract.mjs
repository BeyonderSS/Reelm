// Pulls the scraper constants and a "logic fingerprint" out of the ani-cli and MovieBox-Tui sources.
// Every pattern is strict: if upstream changes shape, this throws and sync.mjs reports it instead of guessing.
import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const must = (v, what) => { if (v == null || (Array.isArray(v) && !v.length)) throw new Error(`extractor: ${what} not found`); return v; };
const one = (src, re, what) => must(src.match(re), what)[1];
const strings = (s) => [...s.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
const tuples = (s) => [...s.matchAll(/\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)/g)].map((m) => [m[1], m[2]]);
const sha = (s) => createHash("sha256").update(s).digest("hex").slice(0, 16);

// --- bash (ani-cli) -------------------------------------------------------------------------------------------
function bashFn(src, name) {
  const lines = src.split("\n");
  const i = lines.findIndex((l) => l.startsWith(`${name}() `));
  if (i < 0) throw new Error(`extractor: bash function ${name} not found`);
  const end = lines.findIndex((l, k) => k > i && /^[})]$/.test(l));
  if (end < 0) throw new Error(`extractor: bash function ${name} not closed`);
  return lines.slice(i, end + 1);
}
// whitespace, comments and the rotating key line don't count as logic
const norm = (lines) => lines.map((l) => l.trim()).filter((l) => l && !l.startsWith("#") && !/^set -- [\d ]+$/.test(l)).join("\n");

const ANI_FNS = ["hianime_search", "hianime_episodes", "hianime_m3u8", "deobfuscate_blob", "select_quality"];

export function extractAniCli(src) {
  const key = String.fromCharCode(...one(bashFn(src, "deobfuscate_blob").join("\n"), /set -- ([\d ]+)\n/, "xor key bytes").trim().split(/\s+/).map(Number));
  const base = one(src, /^base_api="([^"]+)"/m, "base_api");
  const config = {
    base,
    key,
    server: one(bashFn(src, "hianime_m3u8").join("\n"), /data-server-name="([^"]+)"/, "preferred server"),
    userAgent: one(src, /^agent="([^"]+)"/m, "agent"),
    // api templates relative to base, %s is the id
    search: one(src, /^search_api="\$\{base_api\}([^"]+)"/m, "search_api"),
    episodes: one(src, /^episodes_api="\$\{base_api\}([^"]+)"/m, "episodes_api"),
    servers: one(src, /^servers_api="\$\{base_api\}([^"]+)"/m, "servers_api"),
  };
  const logic = Object.fromEntries(ANI_FNS.map((n) => [n, sha(norm(bashFn(src, n)))]));
  return { config, logic };
}

// --- rust (MovieBox-Tui) ----------------------------------------------------------------------------------------
function rustFn(src, name) {
  const at = src.search(new RegExp(`fn ${name}\\b`));
  if (at < 0) throw new Error(`extractor: rust function ${name} not found`);
  let depth = 0, i = src.indexOf("{", at);
  for (const start = i; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(at, i + 1);
    if (i - start > 20000) break;
  }
  throw new Error(`extractor: rust function ${name} not closed`);
}
const rustArr = (src, decl, what) => one(src, new RegExp(`${decl}[^=]*=\\s*&?\\[([\\s\\S]*?)\\];`), what);
const code = (src) => src.split("#[cfg(test)]")[0];

export function extractMovieBox({ client, crypto, endpoints }) {
  const raw = one(crypto, /const DEFAULT_SECRET_BYTES[^=]*=\s*b"([^"]+)"/, "signing key");
  if (!raw || raw.replace(/\\x[0-9a-f]{2}/gi, "")) throw new Error("extractor: signing key is not pure \\x escapes");
  const secret = [...raw.matchAll(/\\x([0-9a-f]{2})/gi)].map((m) => parseInt(m[1], 16));
  const gen = rustFn(crypto, "generate_client_info_and_ua");
  const config = {
    hosts: strings(rustArr(client, "const HOST_POOL", "HOST_POOL")),
    retryStatus: must([...rustArr(client, "const RETRY_STATUS_CODES", "retry codes").matchAll(/\d+/g)].map((m) => +m[0]), "retry codes"),
    secret,
    versionCodes: must([...rustArr(gen, "let version_codes", "version_codes").matchAll(/\d+/g)].map((m) => +m[0]), "version_codes"),
    androidVersions: must(tuples(rustArr(gen, "let android_versions", "android_versions")), "android_versions"),
    devices: must(tuples(rustArr(gen, "let redmi_devices", "redmi_devices")), "redmi_devices"),
    timezones: must(strings(rustArr(gen, "let timezones", "timezones")), "timezones"),
    networks: must(strings(rustArr(gen, "let network_types", "network_types")), "network_types"),
    packageName: one(gen, /"package_name":"([^"]+)"/, "package_name"),
    versionName: one(gen, /"version_name":"([^"]+)"/, "version_name"),
    spCode: one(gen, /"sp_code":"([^"]+)"/, "sp_code"),
    cronet: one(gen, /Cronet\/([\d.]+)/, "Cronet version"),
  };
  const paths = must([...new Set(endpoints.flatMap((s) => [...code(s).matchAll(/"(\/wefeed-mobile-bff\/[^"?{}]+)/g)].map((m) => m[1])))].sort(), "endpoints");
  const logic = {
    sign_headers: sha(rustFn(crypto, "build_signed_headers").replace(/\s+/g, " ")),
    sorted_query: sha(rustFn(crypto, "sorted_query_string").replace(/\s+/g, " ")),
    client_token: sha(rustFn(crypto, "generate_x_client_token").replace(/\s+/g, " ")),
    endpoints: sha(paths.join("\n")),
  };
  return { config, logic, endpoints: paths };
}

// --- dramachi ---------------------------------------------------------------------------------------------------
const DRAMACHI_FNS = ["search", "fetch_episodes", "fetch_file_stream", "episode_streams", "parse_episode_number", "extract_leading_season_number"];
const rustSha = (src, name) => sha(rustFn(src, name).replace(/\s+/g, " "));

export function extractDramachi(client) {
  const code_ = code(client);
  const interfaces = [...new Set([...code_.matchAll(/interface=(\w+)/g)].map((m) => m[1]))].sort();
  for (const need of ["search", "title_v2", "eplist", "getFile"]) if (!interfaces.includes(need)) throw new Error(`extractor: dramachi interface ${need} not found`);
  return {
    config: {
      base: one(code_, /const DEFAULT_BASE_URL: &str = "([^"]+)"/, "dramachi base url"),
      imageCdn: one(code_, /const IMAGE_CDN_BASE: &str = "([^"]+)"/, "dramachi image cdn"),
      interfaces,
      cdnPath: one(code_, /format!\(\s*"(https:\/\/\{\}\/cdn\/\{\})"/, "dramachi cdn url format"),
    },
    logic: Object.fromEntries(DRAMACHI_FNS.map((n) => [n, rustSha(client, n)])),
  };
}

// --- 4khdhub ----------------------------------------------------------------------------------------------------
const FK_HUBCLOUD_FNS = ["resolve", "resolve_hubdrive", "resolve_greenmotors", "decode_greenmotors_payload", "extract_greenmotors_payload", "extract_hubcloud_drive_url", "unwrap_watch_online_url", "extract_script_pixeldrain_urls", "pixeldrain_api_url", "rot13", "score"];
const FK_PARSER_FNS = ["parse_search", "parse_details", "parse_releases", "parse_season_episode", "normalize_filename"];

export function extractFourKHdHub({ client, hubcloud, parser, net }) {
  const mirrors = [...new Set([...code(hubcloud).matchAll(/host\.contains\("([a-z0-9-]+\.)"\)/g)].map((m) => m[1]))].sort();
  const pixel = [...new Set([...code(hubcloud).matchAll(/search_prefix\("https:\/\/([a-z.]+)\//g)].map((m) => m[1]))].sort();
  const selectors = [...new Set([...(code(parser) + code(hubcloud)).matchAll(/Selector::parse\("([^"]+)"\)/g)].map((m) => m[1]))].sort();
  return {
    config: {
      base: one(code(client), /const DEFAULT_BASE_URL: &str = "([^"]+)"/, "4khdhub base url"),
      userAgent: one(net, /pub const DEFAULT_BROWSER_USER_AGENT: &str = "([^"]+)"/, "browser user agent"),
      searchParam: one(code(client), /append_pair\("(\w+)", query\)/, "4khdhub search param"),
      mirrors: must(mirrors, "4khdhub mirror hosts"),
      pixeldrain: must(pixel, "pixeldrain hosts"),
    },
    // DOM selectors are the contract with the site, so a changed selector counts as logic drift
    logic: {
      selectors: sha(must(selectors, "4khdhub selectors").join("\n")),
      ...Object.fromEntries(FK_HUBCLOUD_FNS.map((n) => [n, rustSha(hubcloud, n)])),
      ...Object.fromEntries(FK_PARSER_FNS.map((n) => [n, rustSha(parser, n)])),
    },
  };
}

// --- from disk --------------------------------------------------------------------------------------------------
const rd = (p) => readFileSync(p, "utf8");
export const fromDisk = {
  dramachi: (mbDir) => extractDramachi(rd(join(mbDir, "src/providers/dramachi/client.rs"))),
  fourkhdhub(mbDir) {
    const dir = join(mbDir, "src/providers/fourkhdhub");
    return extractFourKHdHub({ client: rd(join(dir, "client.rs")), hubcloud: rd(join(dir, "hubcloud.rs")), parser: rd(join(dir, "parser.rs")), net: rd(join(mbDir, "src/net.rs")) });
  },
  hianime: (aniDir) => extractAniCli(rd(join(aniDir, "ani-cli"))),
  moviebox(mbDir) {
    const dir = join(mbDir, "src/providers/moviebox");
    return extractMovieBox({
      client: rd(join(dir, "client.rs")), crypto: rd(join(dir, "crypto.rs")),
      endpoints: readdirSync(dir).filter((f) => f.endsWith(".rs")).map((f) => rd(join(dir, f))),
    });
  },
};

if (process.argv[1]?.endsWith("extract.mjs") && process.argv[2]) {
  console.log(JSON.stringify({ hianime: fromDisk.hianime(process.argv[2]), moviebox: fromDisk.moviebox(process.argv[3]), dramachi: fromDisk.dramachi(process.argv[3]), fourkhdhub: fromDisk.fourkhdhub(process.argv[3]) }, null, 2));
}
