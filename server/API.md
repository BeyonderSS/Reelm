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
Profile: `{id, name, avatar, kids:boolean, mature:boolean, createdAt}`. `mature` (default false, always false on a kids profile) shows erotic titles, music videos and sports clips in search. `avatar` is a Fluent Emoji id like `"grinning_face"`,
rendered from `https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/<Folder Name>/3D/<file>_3d.png`
(the client keeps the id → path table; the server stores the id only).
- `GET    /v1/profiles` → `{profiles:[…]}` (a new account starts with one profile named after the user)
- `POST   /v1/profiles` `{name, avatar, kids?}` → profile
- `PATCH  /v1/profiles/:id` `{name?, avatar?, kids?, mature?}` → profile
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
- `{type:"history", profileId}` is broadcast after a history PUT so other devices pull immediately.

## Watch together (Durable Object `Room`, keyed by room code; no account needed)
- `POST /v1/rooms` `{payload}` → `{code}` (6 chars, A–Z/2–9). `payload` = what to play (same opaque shape as history).
- `GET  /v1/rooms/:code` WebSocket. Client sends `{type:"join", name, avatar}`; server replies
  `{type:"state", you, payload, playing, position, at, members:[{id,name,avatar}]}`.
- Any member sends `{type:"play"|"pause"|"seek", position}`; server stamps `at` (server ms) and broadcasts
  `{type, position, at, by}`. Clients apply `position + (now - at)` when playing. Room dies 1 h after the last member leaves.

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
- `POST /v1/admin/providers` `{commit?, enabled?}` (admin) → the new `pinned`. `commit` must be a 40-hex sha, `enabled` up to 80 slugs `[a-zA-Z0-9_-]{1,40}` (else `400`). Logs `admin_sources`.

## App updates
No server needed: the app reads `https://api.github.com/repos/BeyonderSS/Reelm/releases/latest`, compares the tag
`v1.0.N` (versionCode `100+N`) to its own, and downloads `Reelm-v*.apk` (phone) or `Reelm-TV-v*.apk` (tv).
