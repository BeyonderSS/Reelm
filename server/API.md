# Reelm API (v1)

Cloudflare Worker (`server/`), D1 for data, Durable Objects for realtime. All JSON. Base URL comes from the
Android BuildConfig `REELM_API_URL` (local.properties / CI var), e.g. `https://reelm-api.<sub>.workers.dev`.

Auth: `Authorization: Bearer <token>`. Tokens are random 32 bytes (base64url), stored server-side as SHA-256.
Errors: `{ "error": "message" }` with a 4xx/5xx status.

## Account
- `POST /v1/auth/register` `{username, password}` → `{token, user:{id, username}}` (username 3–32 `[a-z0-9_]`, password ≥ 8)
- `POST /v1/auth/login` `{username, password}` → same
- `POST /v1/auth/logout` → `204`
- `GET  /v1/me` → `{user:{id, username, is_admin}}`
- Disabled accounts get `401` on every authed route and `403 account disabled` on login.

## Profiles (max 5 per account, Netflix-style)
Profile: `{id, name, avatar, kids:boolean, mature:boolean, hide:string[], createdAt}`. `hide` (default `[]`) lists kinds of content the profile doesn't want: any of `"anime"`, `"movies"`, `"series"`, `"live"`, never all four (400 "Keep at least one kind of content"); unknown values are 400. `mature` (default false, always false on a kids profile) shows erotic titles, music videos and sports clips in search. `avatar` is a Fluent Emoji id like `"grinning_face"`,
rendered from `https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/<Folder Name>/3D/<file>_3d.png`
(the client keeps the id → path table; the server stores the id only).
- `GET    /v1/profiles` → `{profiles:[…]}` (a new account starts with one profile named after the user)
- `POST   /v1/profiles` `{name, avatar, kids?, hide?}` → profile
- `PATCH  /v1/profiles/:id` `{name?, avatar?, kids?, mature?, hide?}` → profile
- `DELETE /v1/profiles/:id` → `204` (not the last one)

## Watch history sync (per profile, last-write-wins)
Entry: `{key, payload, position, duration, updatedAt, deleted}`.
`key` is the client's stable id for the item (e.g. `anime:<slug>:<ep>` / `movie:<id>:<s>:<e>`); `payload` is
opaque JSON the client uses to rebuild the row (title, poster, kind, season, episode, …). Times are epoch ms.
- `GET /v1/profiles/:id/history?since=<ms>` → `{entries:[…], now}` (includes tombstones; client stores `now` as next `since`)
- `PUT /v1/profiles/:id/history` `{entries:[…]}` → `{now}`; server keeps an entry only if its `updatedAt` is newer

## Favourites sync (per profile, last-write-wins)
Entry: `{key, payload, updatedAt, deleted}`. `key` is the canonical title key, same scheme as history titles:
`anime:<slug>` or `media:<movie|series>:<normtitle>`. `payload` carries what a card needs: `{title, poster, kind, id, mediaId, type, year}`.
- `GET /v1/profiles/:id/favorites?since=<ms>` → `{entries:[…], now}` (includes tombstones; `since` compares server `synced_at`)
- `PUT /v1/profiles/:id/favorites` `{entries:[…]}` → `{now}`; an entry wins only if its `updatedAt` is newer. Broadcasts `{type:"favorites", profileId}` on the hub.

## TV sign-in with a QR (device-code flow)
1. TV: `POST /v1/pair` → `{code, expiresAt}` (8 chars, 10 min). TV shows a QR of `<base>/pair/<code>` plus the code as text.
2. Phone (signed in): opens the QR link (deep link into the app, or types the code) → `POST /v1/pair/:code/claim` (Bearer) → `204`.
3. TV polls `GET /v1/pair/:code` every 3 s → `{status:"pending"}` or `{status:"claimed", token, user}` (one-shot; the code dies after).
- `GET /pair/:code` (HTML) is a small fallback page: "Open in Reelm" link `reelm://pair/<code>`.

## Realtime hub per account (Durable Object `UserHub`, keyed by user id)
`GET /v1/hub` WebSocket (Bearer via `?token=` since WS can't set headers on all clients).
Hello on connect: `{type:"hello", deviceId, deviceName, kind:"phone"|"tv"}` → server broadcasts `{type:"devices", devices:[…]}`.
- Continue on TV: `{type:"handoff", to:deviceId, profileId, playback:{payload, position}}` → delivered to that device,
  which opens the player at `position`.
  `playback` may also carry `positionMs` (ms; receivers fall back to legacy `position`), `autoplay:true` (go straight into the player) and `source` (label hint, e.g. "Vega · VegaMovies 1080p").
- Remote: `{type:"control", to:deviceId, action:"play"|"pause"|"toggle"|"seekTo"|"seekBy"|"next"|"prev"|"stop", value?:ms}` → relayed to that device with `from` added.
  Unknown `action` or a non-finite `value` is dropped. Not logged.
  Remote v2 adds actions `describe`, `skip`, `cancelNext`, `subs`, `subSize`, `subPos`, `audio`, `audioLang`, `quality`, `source`, `episode`,
  `volume`, `speed`, `sleep`, `key` and an optional `arg` (any JSON, dropped if its JSON is over 1024 chars; `value` stays a finite number).
  `describe` makes the TV answer with an `info` message.
- Info: `{type:"info", to:deviceId, info:{…}}` from a TV → relayed only to the socket(s) with that deviceId, with `from` added.
  Dropped if the sender never said hello or `info` JSON is over 8192 chars. Not stored, not logged. `info.rev` matches `now.rev`.
- Now playing: a TV sends `{type:"state", now:null | {title≤120, subtitle?≤120, poster?, playing, positionMs, durationMs, hasNext, hasPrev, at, rev?:int≥0, skip?:{label≤40, endMs}|null, nextInMs?:number|null}}`.
  The server sanitizes it, keeps it in that socket's attachment (so `devices` entries carry `now`) and broadcasts `{type:"state", from:deviceId, now}` to the other sockets. Not logged.
- `{type:"history", profileId}` is broadcast after a history PUT so other devices pull immediately.

## Watch together (Durable Object `Room`, keyed by room code; no account needed)
- `POST /v1/rooms` `{payload}` → `{code}` (6 chars, A–Z/2–9). `payload` = what to play (same opaque shape as history).
- `GET  /v1/rooms/:code` WebSocket. Client sends `{type:"join", name, avatar, device?:"phone"|"web"|"tv", cam?, mic?}`; server replies
  `{type:"state", you, payload, playing, position, at, source?, mode:"friends"|"date", countdownAt?, now (server ms), members:[{id, name, avatar, device, cam, mic, rtc?}]}`
  (`device` defaults to `"phone"`, `cam`/`mic` to false). Everyone else gets `{type:"members", members}` on every join/leave.
- Every `position` is integer **ms**: the server stores and broadcasts `Math.round(Number(position))` and drops non-finite ones.
- Any member sends `{type:"play"|"pause"|"seek", position}`; server stamps `at` (server ms) and broadcasts
  `{type, position, at, by}`. Clients apply `position + (now - at)` when playing. Room dies 1 h after the last member leaves.
- Watch party (all broadcasts go to every member, sender included):
  - `{type:"source", source}`: `source` is an object ≤ 1 KB JSON (`{provider, quality, host?, label?}`); kept only if none is stored for
    this payload (first player wins), then broadcast `{type:"source", source, by}`. `load` clears it.
  - `{type:"mode", mode:"friends"|"date"}` → stored, broadcast `{type:"mode", mode, by}`.
  - `{type:"media", cam, mic}` → stored on the member, broadcast `{type:"media", from, cam, mic}`.
  - `{type:"rtc", sessionId, tracks:[{trackName, kind:"audio"|"video"}]}` (≤ 4 tracks) → stored on the member, broadcast
    `{type:"rtc", from, sessionId, tracks}`. `sessionId:null` = stopped (broadcast with `tracks:[]`). When 4 other members already
    publish video the sender gets `{type:"rtc_full"}` and its video tracks are dropped (audio still stored and broadcast).
  - `{type:"react", kind:"heart"|"laugh"|"gasp"|"cry", at}` (`at` = media ms) → broadcast `{type:"react", kind, at, by}`, not stored.
    A heart from a different member within 1.5 s of the previous heart also broadcasts `{type:"sync_heart", at}` (the second heart's `at`).
  - `{type:"lights"}` → `countdownAt = now + 3500`; stores `playing:true`, `at:countdownAt`, keeps `position`; broadcasts
    `{type:"lights", countdownAt, position}`. Clients count down and start at `countdownAt` (server clock). `load` clears it.

### Watch party video (Cloudflare Realtime proxy; no account needed)
Every call needs `X-Room-Member: <code>:<memberId>` (`memberId` = `state.you`, still connected), else `403 not in room`.
Secrets `REALTIME_APP_ID` + `REALTIME_APP_TOKEN` missing → `503 {error:"video not configured"}` (clients hide camera/mic, keep sync).
Bodies and responses pass through unchanged from `https://rtc.live.cloudflare.com/v1/apps/<appId>/…` (status included).
- `GET  /v1/rtc/ice` → `{iceServers:[…]}` for `RTCPeerConnection` (TURN creds, TTL 6 h, from `TURN_KEY_ID`/`TURN_KEY_TOKEN`;
  STUN only `[{urls:["stun:stun.cloudflare.com:3478"]}]` when those are missing or TURN fails).
- `POST /v1/rtc/sessions` → `201 {sessionId}` (`sessions/new`).
- `POST /v1/rtc/sessions/:id/tracks` → `tracks/new`. Push: `{sessionDescription:{type:"offer", sdp}, tracks:[{location:"local", mid, trackName}]}`;
  pull: `{tracks:[{location:"remote", sessionId, trackName}]}`. Reply `{requiresImmediateRenegotiation, sessionDescription?, tracks:[{mid, trackName, errorCode?}]}`.
- `PUT  /v1/rtc/sessions/:id/renegotiate` `{sessionDescription:{type:"answer", sdp}}` (after `requiresImmediateRenegotiation`).
- `PUT  /v1/rtc/sessions/:id/tracks/close` `{tracks:[{mid}], sessionDescription?, force}`.

- Any member switching media (episode, season, movie, new title) sends `{type:"load", payload, position:0}`; the server stores
  `payload`, resets `position` to 0 and `playing` to false, stamps `at`, and broadcasts `{type:"load", payload, at, by}` to everyone
  including the sender (ignore `by == you`). Members open the payload and stay in the room; late joiners get it in `state`.

## Telemetry (signed-in clients)
- `POST /v1/telemetry` `{events:[{kind, detail}], profileId?, platform?:"phone"|"tv"|"web", appVersion?, helper?}` → `204`.
  `kind` ∈ `play`, `play_error`, `crash`, `source_used`, `heartbeat`, `search_empty`, `update_offered`, `update_installed`.
  `detail` is an object ≤ 4000 chars JSON (title, key, kind, episode, source, host, quality, codec, error, ttff ms, position/duration s, query, trace).
  At most 20 events per call and 30 per user per minute (`429` beyond). Clients batch and flush every 60 s; `heartbeat` = one per minute of playback.
- The server also logs its own events (register, login, login_failed, logout, active (once a day), profile_*, history_push {count},
  pair_start/claim, room_create/join/load, hub_connect, handoff, admin_*) into the D1 `events` table.

## Recommendations (Reelm's own collaborative filtering)
- `GET /v1/recs?profile=<id>&limit=30` (Bearer; profile must be the caller's, else `404`; `limit` 1–100) →
  ```
  { items:   [{ item, score, because, becauseTitle, payload }],   // best first, at most `limit`
    popular: [{ item, score, viewers, payload }],                  // ≤20, only titles with ≥3 viewers this week (else [])
    coverage:{ seeds, related_items }, confidence }                // confidence = min(1, related_items/50)
  ```
  `item` is the canonical history key (`anime:<slug>` / `media:<movie|series>:<normtitle>`), `because` the seed key that
  contributed most and `becauseTitle` its title. `payload` = `{kind, id, slug, mediaId, title, poster, type, year}` (any may be null;
  same field names as history payloads, so `toPlay`/HistoryPayload parsing opens it; no per-viewer progress). Anything in the profile's
  history (tombstones too) is excluded; `hide` kinds are dropped; kids profiles only get titles a kids profile has watched; profiles
  without `mature` lose titles matching the MaturePolicy adult-title list. `Cache-Control: private, max-age=300`.
  Clients blend by `confidence` (few co-viewers → lean on their own rows / popular).
- Model, rebuilt nightly (cron `17 3 * * *`) and by `POST /v1/admin/recs/rebuild` (admin) → `{items, related, popular, ms}` (logs `recs_rebuild`):
  history rows (non-deleted, canonical keys, last 365 days, each profile's latest 200), weight `0.3 + 0.7·completion`;
  `related(item, other)` = `Σ_p w_pi·w_pj / sqrt(n_i·n_j)` over profiles that watched both, needs ≥2 co-viewers, top 30 per item;
  `popular` = viewers in the last 7 days, each weighted `0.5^(age/2 days)`. Per-request seeds: the profile's top 30 rows by
  `completion · 0.5^(days/30)`; candidate score = `Σ seed weight · related.score`.

## Admin (Bearer token of a user with `users.is_admin = 1`, else `403`)
- `GET /v1/admin/overview` → `{totals:{users, profiles, devices, history, activeRooms, watchingNow, onlineNow}, daily:[{day, signups, dau, wau}]×30, topTitles, sources, codecs}`
- `GET /v1/admin/live` → `{watching:[latest heartbeat per user+device, last 2 min], rooms:[{code, title, members}]}`
- `GET /v1/admin/engagement` → `{perUser, perProfile, heatmap:[{dow, hour, minutes}] (UTC), platforms, completion, binge}`
- `GET /v1/admin/content` → `{watched, abandoned (<20% watched), kinds, searches (search_empty)}`
- `GET /v1/admin/sources` → `{bySource (source, host, ok, errors, ttff), daily, alerts (last hour, ≥5 tries, <50% ok), codecs, hevcFailures, dolbyFailures, recent}`
- `GET /v1/admin/app` → `{crashes, versions, updates, helpers}`
- `GET /v1/admin/security` → `{failed, newDevices (first seen ≤7 d), sessions, kids}`
- `GET /v1/admin/ops` → `{connected, cloudflare (GraphQL Analytics, cached 5 min; needs secret CF_ANALYTICS_TOKEN + var CF_ACCOUNT_ID), deploys (GitHub runs/releases, cached 10 min), limits}`
- `POST /v1/admin/users` `{username, password, admin?:boolean}` → `201 {user:{id, username, is_admin}}` (same rules as register; `409` if taken)
- `GET /v1/admin/users?q=&limit=&offset=` → `{users, total}`
- `GET /v1/admin/users/:id` → `{user, profiles, devices, history (latest 50), events (latest 50), sessions:[{id, created_at, last_used_at, device, ua}]}`
- `GET /v1/admin/events?kind=&user=<id or username>&before=<id>&limit=` → `{events, next}` (cursor = `next` as `before`)
- `GET /v1/admin/history?profile=<id>` → `{entries}`
- `POST /v1/admin/users/:id/{disable|enable|revoke|reset_password|make_admin|remove_admin}` → `{ok}` (`reset_password` adds `{password}`;
  disable/reset also sign out every session). Disable, delete and admin changes refuse your own account (`400`).
- `DELETE /v1/admin/users/:id` → `204` (profiles, history, sessions go too). `DELETE /v1/admin/sessions/:id` → `204` (sign out one session).

## Provider sources (Vega providers; apps run code only from the pinned commit)
- `GET /v1/sources` (public, `Cache-Control: public, max-age=300`) → `{vega:{commit, enabled:[slug], base:"https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@<commit>/dist"}}`. Files load from `<base>/<provider>/<file>.js`.
- `GET /v1/admin/providers` (admin; `/v1/admin/sources` is already the playback-stats route) → `{pinned:{commit, enabled, approvedBy, approvedAt}, upstream:{commit, date, message}, behind, changed:[{provider, files, status}], compareUrl, manifest:[{value, name, type, version, disabled}]}`.
  `upstream` is GitHub main (cached 5 min), `changed` = dist/ providers differing from the pin, `manifest` is read at the pinned commit. If GitHub fails: `{pinned, error}` (still `200`).
- `GET /v1/admin/providers/diff?provider=<slug>` (admin) → `{from, to, provider, files:[{filename, status, additions, deletions, patch?}], commits:[{sha, message, date, author}]}`: pin..upstream main for one provider.
  `providers/<slug>/` files (readable source) carry `patch`; `dist/<slug>/` (minified) are listed without it. Bad slug `400`; GitHub failure `{error}` (still `200`).
- `POST /v1/admin/providers` `{commit?, enabled?}` (admin) → the new `pinned`. `commit` must be a 40-hex sha, `enabled` up to 80 slugs `[a-zA-Z0-9_-]{1,40}` (else `400`). Logs `admin_sources`.

## App updates
APKs are served from Cloudflare because GitHub's release CDN crawls on some Indian ISPs (measured ~2 KB/s on Airtel).
- `GET /v1/app/latest` (public, `Cache-Control: public, max-age=300`) → `{version:"1.0.N", phone:{url, size, sha256}|null, tv:{…}|null, notes}`.
  Read from R2 `latest.json` (written by release.yml) when the `APKS` bucket is bound, else from GitHub `releases/latest`
  (cached 5 min; `sha256` from GitHub's asset digest, may be `null`). `url` points at `/dl/…` on this worker.
- `GET|HEAD /dl/Reelm-v1.0.N.apk`, `/dl/Reelm-TV-v1.0.N.apk` → the APK, `application/vnd.android.package-archive`,
  `Accept-Ranges: bytes` (`Range: bytes=a-b|a-|-n` → `206` + `Content-Range`; a start past the end → `416 bytes */size`),
  `Cache-Control: public, max-age=31536000, immutable`. From R2 when there; otherwise proxied from the GitHub release
  asset through Cloudflare's cache (Range passed through) and mirrored into R2 when bound. Unknown version `404`.
- `GET /dl/Reelm.apk`, `/dl/Reelm-TV.apk` → `302` to the latest versioned file (stable links for the README / Fire TV Downloader).
- The app asks `/v1/app/latest` first and falls back to `https://api.github.com/repos/BeyonderSS/Reelm/releases/latest`;
  tag `v1.0.N` = versionCode `100+N`. Downloads resume with `Range` and are checked against `size`/`sha256`.

## Trailers
- `GET /v1/trailer?imdb=tt…` or `?title=…&year=2024&type=movie|series` (public) → `{ytIds:[…], logo:string|null, backdrops:[…], startAt:12, imdb:string|null}`.
  `title` is searched on Cinemeta and the hit must match `year` ±1 (first hit when no year). `mal=<id>` (anime) adds Jikan's PV id; send `title` too for a logo.
  `ytIds` are ordered (Cinemeta trailers, then `trailerStreams`, then Jikan) and de-duplicated; the app resolves them to a stream itself.
  Always `200`: upstream failures or no match give the empty shape (`ytIds:[]`). Cached at the edge 7 days, 1 day when empty.
