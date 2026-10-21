# Reelm API (v1)

Cloudflare Worker (`server/`), D1 for data, Durable Objects for realtime. All JSON. Base URL comes from the
Android BuildConfig `REELM_API_URL` (local.properties / CI var), e.g. `https://reelm-api.<sub>.workers.dev`.

Auth: `Authorization: Bearer <token>`. Tokens are random 32 bytes (base64url), stored server-side as SHA-256.
Errors: `{ "error": "message" }` with a 4xx/5xx status.

## Account
- `POST /v1/auth/register` `{username, password}` → `{token, user:{id, username}}` (username 3–32 `[a-z0-9_]`, password ≥ 8)
- `POST /v1/auth/login` `{username, password}` → same
- `POST /v1/auth/logout` → `204`
- `GET  /v1/me` → `{user:{id, username}}`

## Profiles (max 5 per account, Netflix-style)
Profile: `{id, name, avatar, kids:boolean, createdAt}`. `avatar` is a Fluent Emoji id like `"grinning_face"`,
rendered from `https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/<Folder Name>/3D/<file>_3d.png`
(the client keeps the id → path table; the server stores the id only).
- `GET    /v1/profiles` → `{profiles:[…]}` (a new account starts with one profile named after the user)
- `POST   /v1/profiles` `{name, avatar, kids?}` → profile
- `PATCH  /v1/profiles/:id` `{name?, avatar?, kids?}` → profile
- `DELETE /v1/profiles/:id` → `204` (not the last one)

## Watch history sync (per profile, last-write-wins)
Entry: `{key, payload, position, duration, updatedAt, deleted}`.
`key` is the client's stable id for the item (e.g. `anime:<slug>:<ep>` / `movie:<id>:<s>:<e>`); `payload` is
opaque JSON the client uses to rebuild the row (title, poster, kind, season, episode, …). Times are epoch ms.
- `GET /v1/profiles/:id/history?since=<ms>` → `{entries:[…], now}` (includes tombstones; client stores `now` as next `since`)
- `PUT /v1/profiles/:id/history` `{entries:[…]}` → `{now}`; server keeps an entry only if its `updatedAt` is newer

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

## App updates
No server needed: the app reads `https://api.github.com/repos/BeyonderSS/Reelm/releases/latest`, compares the tag
`v1.0.N` (versionCode `100+N`) to its own, and downloads `Reelm-v*.apk` (phone) or `Reelm-TV-v*.apk` (tv).
