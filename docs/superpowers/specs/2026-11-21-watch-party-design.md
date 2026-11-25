# Watch party: faces and voices in Watch together (design + shared contract)

Approved concepts: https://claude.ai/artifact/CsrZrhB2bheVo1AEAtRSKm (built on real screenshots of the app).

## Why
The user wants movie date nights and friends' nights where people who allow camera and mic see and hear each other
while the film plays in sync. It must feel fun and native to Reelm, not a video call bolted onto a player.
Scope now: **Android phone + web**. TV keeps today's sync and shows nothing new (later: watch-only faces).

## Decisions (user)
- Media relay: **Cloudflare Realtime** (SFU) + Cloudflare TURN. App token stays on the server.
- **Up to 4 people send video**; more join with voice only. Everyone can join without camera/mic (just sync).
- **Everyone controls playback** (as today), with a short "Kabir paused" note on others' screens.

## Non-goals / anti-goals
No recording, no chat history, no accounts required (guests work). Faces must never cover the picture when black
bars exist; never auto-enable camera or mic; never leave the mic hot after leaving the room.

## Approaches considered
1. **Cloudflare Realtime SFU (picked)**: one upload per person, works behind CGNAT, same account as the Worker.
2. LiveKit Cloud: richer SDKs, but a new vendor and keys. 3. P2P mesh with TURN only: no server, but N² uploads and
fragile on mobile. Rejected 2 and 3.

## Bugs fixed as part of this (found 2026-11-20)
1. **Position units**: Android sends ms, web sends seconds (float). Web paused → phone ignored it; phone paused → web
   jumped to the end. **Contract: every `position` in room messages is integer milliseconds.**
2. **Late join rewinds the room**: web applies `state` before its video exists, autoplays at 0 and broadcasts
   `play 0`. Contract: a client applies `state` only once its player is ready, and never broadcasts during the first
   2 s after join or while applying a remote event (echo guard).
3. **Different copies of the film** (cuts differed by ~6 min). Contract: the room stores the first player's `source`
   descriptor; joiners try that source first (same provider + same quality label/host), and only fall back to
   their own pick if it fails, showing "Playing a different copy — scenes may be off by a few minutes".

## Room protocol additions (server `Room` DO, `/v1/rooms/:code` WebSocket)
Existing: `join`, `state`, `play|pause|seek {position}`, `load {payload}`. Changes and additions:
- `position`: integer **ms** everywhere. Server rounds `Math.round(Number(position))` and rejects non-finite.
- `join` gains optional `{device:"phone"|"web"|"tv", cam:boolean, mic:boolean}`; `members[]` entries gain
  `{device, cam, mic, rtc?: {sessionId, tracks:[{trackName, kind:"audio"|"video"}]}}`.
- `state` gains `{source?, mode:"friends"|"date", countdownAt?}`.
- `source {source}`: any member may set it once per payload if none is stored (first player wins); broadcast.
  `source` is an opaque JSON object ≤1 KB: `{provider, quality, host?, label?}`.
- `rtc {sessionId, tracks}`: sender published tracks to Realtime; server stores on the member and broadcasts
  `{type:"rtc", from, sessionId, tracks}`. `rtc {sessionId:null}` = stopped. Server refuses a video track when 4
  members already publish video: replies `{type:"rtc_full"}` (client stays voice-only).
- `media {cam, mic}`: toggles, broadcast as `{type:"media", from, cam, mic}`.
- `react {kind, at}`: `kind` ∈ heart|laugh|gasp|cry; `at` = media position ms; broadcast with `by`; not stored.
  Two hearts from different members within 1.5 s → server also broadcasts `{type:"sync_heart", at}`.
- `lights {}`: anyone; server sets `countdownAt = now()+3500`, `playing=true`, `position` unchanged, `at=countdownAt`,
  broadcasts `{type:"lights", countdownAt, position}`; clients show 3-2-1 and start at `countdownAt` (server clock;
  clients already estimate offset from `at`).
- `mode {mode}`: friends|date; stored, broadcast.
- `member_left` / `member_joined` broadcasts keep their current shape plus the new fields.

## Realtime endpoints (Worker; secrets `REALTIME_APP_ID`, `REALTIME_APP_TOKEN`, `TURN_KEY_ID`, `TURN_KEY_TOKEN`)
All require a valid room code + member id (header `X-Room-Member: <code>:<memberId>` checked against the DO), no
account needed. They proxy `https://rtc.live.cloudflare.com/v1/apps/{appId}/…` so the token never reaches clients.
- `GET  /v1/rtc/ice` → `{iceServers:[…]}` (TURN creds, TTL 6 h; STUN-only fallback if TURN secrets are missing).
- `POST /v1/rtc/sessions` → `{sessionId}`.
- `POST /v1/rtc/sessions/:id/tracks` body passthrough (push local tracks with an offer, or pull remote tracks by
  `{location:"remote", sessionId, trackName}`) → Realtime's answer/renegotiation info.
- `PUT  /v1/rtc/sessions/:id/renegotiate` passthrough. `PUT /v1/rtc/sessions/:id/tracks/close` passthrough.
- Missing secrets → `503 {error:"video not configured"}`; clients then hide camera/mic and keep sync only.

## Client behaviour (both platforms)
- **Party sheet / Together panel** (concept "Party sheet", "Together panel"): own camera preview ("This is how X sees
  you"), mic/cam/flip, Friends | Date night, code + "Send invite link", seats with ready state, **Lights down**,
  Leave. Camera/mic permissions are asked only when the user turns one on here.
- **Phone landscape** (concept "Pillar seats"): video tiles (80×104 dp, 18 dp corners) live in the black side bars
  when the film is pillarboxed; with no bars they become draggable round bubbles in a corner. Talking = amber ring +
  level bars. Controls showing: the old avatar strip is replaced by the party pill (mic, cam, react, "Party · N")
  centred at the top; Settings gear no longer covered.
- **Phone portrait** ("The couch"): the empty area under the video becomes a 2×2 grid of tiles + mic/cam + reactions.
  Each tile has "Pop out": the face floats over the video as a bubble.
- **Moving faces (phone)**: every face (tiles, bubbles, date-night partner, self) drags anywhere over the player,
  clamped on screen, snapping flush to an edge or bar edge when dropped within 16 dp. Pinch resizes (72 dp to 60 %
  of the width, aspect kept); with the controls up a corner button steps small / medium / big. Position and size
  are saved per seat (self, partner, then others) as fractions of the player; "Reset faces" in the party sheet.
- **Date night** (`mode=date`): partner as one big round bubble (140 dp), self small; double-tap partner = heart,
  hold = whisper (mic opens only while held if muted); synced hearts burst ("You both tapped at 1:02:14").
- **Web**: faces in the black bar above the film (112×74 tiles, top-right), keyboard M mute, V camera, R react, H hide;
  the Together panel becomes a live tile grid with "Save a seat". Each tile (and date night's bubbles) can be dragged
  anywhere over the player (snaps to an edge within 24 px), resized from a corner handle, by pinch or + − (96 px to 50 %
  of the player width), double-clicked to big (40 %; date night: a "Make bigger" button), moved with the arrows when
  Tab-focused; spots are kept per slot (self, partner, o0…) as fractions of the player in `localStorage["reelm.faces"]`,
  cleared by "Reset faces" in the Together panel.
- **Film ducking**: while any remote audio level is above threshold for >150 ms, film volume goes to 35 %, back over
  400 ms after silence. Local echo cancellation on (`echoCancellation`, Android `VOICE_COMMUNICATION`); suggest
  headphones once if echo is likely (speaker output + mic on).
- Reactions float up from the reactor's tile; on web they also mark the progress bar.
- Leaving the room or the player stops camera and mic immediately and closes the Realtime session.

## What's left to the builders
Exact layout code, animation curves, the Android WebRTC library choice (prebuilt libwebrtc, e.g.
`io.getstream:stream-webrtc-android`, mind APK size), echo-cancellation tuning, and how a joiner matches a `source`.

## Round 2 (2026-11-21, after the first E2E)
- **Same cut, not same file**: `source` gains `durationMs` (the film's length as played) and `codec`. A joiner that can't play
  the room's exact copy (missing, or undecodable e.g. HEVC on web) prefers any copy whose duration is within ±3 s of
  `durationMs` (same cut) before falling back; only then show "different copy". Joiners wait at most 12 s for the room's
  source, never longer. If `durationMs` differs by >3 s, show the note.
- **Drift correction while playing** (no server change): while the room is playing and no remote event is being applied,
  every 5 s each client compares its position with the room timeline `position + (serverNow − at)` (clock offset from
  `state.now`/events). Drift 0.25–1.5 s → playback rate 1.05 (behind) or 0.95 (ahead) until within 0.1 s; > 1.5 s →
  quiet local seek (never broadcast). Skip while buffering or in the first 5 s after any event.
