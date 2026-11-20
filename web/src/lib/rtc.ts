// Faces and voices for a watch party: the browser's WebRTC against Cloudflare Realtime (an SFU), through the
// Worker's /v1/rtc/* proxy so the app token never reaches us. One PeerConnection and one Realtime session per
// member: our camera/mic are pushed on it, everyone else's tracks are pulled on it.
import { API } from "./core";

export type Kind = "audio" | "video";
export type Pub = { trackName: string; kind: Kind };
type Desc = { type: RTCSdpType; sdp: string };

/** `{iceServers}`, or null when the server answers 503 (video not configured: sync only) */
export async function rtcIce(member: string): Promise<RTCIceServer[] | null> {
  const r = await fetch(`${API}/v1/rtc/ice`, { headers: { "X-Room-Member": member } });
  if (r.status === 503) return null;
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return (await r.json()).iceServers ?? [];
}

const MEDIA: Record<Kind, MediaStreamConstraints> = {
  audio: { audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } },
  video: { video: { width: { ideal: 320 }, height: { ideal: 240 }, frameRate: { ideal: 24 }, facingMode: "user" } },
};

export class Call {
  private pc: RTCPeerConnection;
  private id: Promise<string>;
  private q: Promise<unknown> = Promise.resolve();
  private mine: Partial<Record<Kind, RTCRtpTransceiver>> = {};
  /** mid -> who it belongs to, for the tracks we pulled */
  private mids = new Map<string, { member: string; key: string }>();
  /** `${sessionId}/${trackName}` -> mid */
  private keys = new Map<string, string>();
  /** each other member's incoming audio+video */
  readonly streams = new Map<string, MediaStream>();
  readonly self = new MediaStream();
  closed = false;

  constructor(private member: string, ice: RTCIceServer[], private onChange: () => void) {
    this.pc = new RTCPeerConnection({ iceServers: ice, bundlePolicy: "max-bundle" });
    this.pc.ontrack = (e) => {
      const who = this.mids.get(e.transceiver.mid ?? "");
      if (!who) return;
      const s = this.streams.get(who.member) ?? new MediaStream();
      s.getTracks().filter((t) => t.kind === e.track.kind).forEach((t) => s.removeTrack(t));
      s.addTrack(e.track);
      this.streams.set(who.member, s);
      this.onChange();
    };
    this.id = this.api("/sessions", "POST").then((r) => r.sessionId as string);
    this.id.catch(() => {});
  }

  private async api(path: string, method: string, body?: unknown) {
    const r = await fetch(`${API}/v1/rtc${path}`, { method, headers: { "X-Room-Member": this.member, ...(body ? { "content-type": "application/json" } : {}) }, body: body ? JSON.stringify(body) : undefined });
    const d = await r.json().catch(() => ({}));
    if (!r.ok || d.errorCode) throw new Error(d.error ?? d.errorDescription ?? `HTTP ${r.status}`);
    return d;
  }
  /** offers/answers must not interleave: every negotiation waits for the one before */
  private run<T>(fn: () => Promise<T>): Promise<T> { const p = this.q.then(fn); this.q = p.catch(() => {}); return p; }
  private async offer(): Promise<Desc> { const o = await this.pc.createOffer(); await this.pc.setLocalDescription(o); return { type: "offer", sdp: o.sdp! }; }
  /** Realtime sent an offer (pulls, closes): answer it */
  private async answer(d: Desc) {
    await this.pc.setRemoteDescription(d);
    const a = await this.pc.createAnswer(); await this.pc.setLocalDescription(a);
    await this.api(`/sessions/${await this.id}/renegotiate`, "PUT", { sessionDescription: { type: "answer", sdp: a.sdp } });
  }

  sessionId() { return this.id; }

  /** Camera or mic on (asks permission now, never before) or off (the device light goes out at once). Returns what we publish. */
  set(kind: Kind, on: boolean): Promise<Pub[]> {
    return this.run(async () => {
      const t = this.mine[kind];
      if (!on) {
        const old = t?.sender.track;
        await t?.sender.replaceTrack(null);
        if (old) { old.stop(); this.self.removeTrack(old); }
        this.onChange();
        return this.published();
      }
      const track = (await navigator.mediaDevices.getUserMedia(MEDIA[kind])).getTracks()[0];
      if (this.closed) { track.stop(); return []; }
      this.self.getTracks().filter((x) => x.kind === kind).forEach((x) => { x.stop(); this.self.removeTrack(x); });
      this.self.addTrack(track);
      this.onChange();
      if (t) { await t.sender.replaceTrack(track); return this.published(); } // already published once: no renegotiation
      const tr = this.pc.addTransceiver(track, { direction: "sendonly" });
      const o = await this.offer();
      const r = await this.api(`/sessions/${await this.id}/tracks`, "POST", { sessionDescription: o, tracks: [{ location: "local", mid: tr.mid, trackName: kind }] });
      await this.pc.setRemoteDescription(r.sessionDescription);
      this.mine[kind] = tr;
      return this.published();
    });
  }
  /** what others can pull: every track we pushed and still send */
  published(): Pub[] { return (["audio", "video"] as const).filter((k) => this.mine[k]?.sender.track).map((k) => ({ trackName: k, kind: k })); }
  live(kind: Kind) { return !!this.mine[kind]?.sender.track; }

  /** Pull a member's tracks (only the ones we don't have yet) and drop the ones they stopped publishing. */
  sync(member: string, rtc: { sessionId: string | null; tracks: Pub[] } | null | undefined): Promise<void> {
    return this.run(async () => {
      const want = new Set(rtc?.sessionId ? rtc.tracks.map((t) => `${rtc.sessionId}/${t.trackName}`) : []);
      const gone = [...this.mids].filter(([, w]) => w.member === member && !want.has(w.key)).map(([mid]) => mid);
      if (gone.length) await this.close(gone);
      const add = rtc?.sessionId ? rtc.tracks.filter((t) => !this.keys.has(`${rtc.sessionId}/${t.trackName}`)) : [];
      if (!add.length || !rtc?.sessionId) return;
      const r = await this.api(`/sessions/${await this.id}/tracks`, "POST", { tracks: add.map((t) => ({ location: "remote", sessionId: rtc.sessionId, trackName: t.trackName })) });
      for (const t of r.tracks ?? []) if (t.mid && !t.error) { const key = `${rtc.sessionId}/${t.trackName}`; this.mids.set(t.mid, { member, key }); this.keys.set(key, t.mid); }
      if (r.requiresImmediateRenegotiation && r.sessionDescription) await this.answer(r.sessionDescription);
    });
  }

  private async close(mids: string[]) {
    for (const mid of mids) {
      const w = this.mids.get(mid)!, s = this.streams.get(w.member);
      const tr = this.pc.getTransceivers().find((t) => t.mid === mid);
      if (s && tr) s.removeTrack(tr.receiver.track);
      if (s && !s.getTracks().length) this.streams.delete(w.member);
      tr?.stop(); this.mids.delete(mid); this.keys.delete(w.key);
    }
    this.onChange();
    await this.api(`/sessions/${await this.id}/tracks/close`, "PUT", { tracks: mids.map((mid) => ({ mid })), force: true });
  }

  /** speaking level 0..1 of a member's incoming audio (the RTP audio-level header: no Web Audio needed) */
  level(member: string) {
    const a = this.streams.get(member)?.getAudioTracks()[0];
    const r = a && this.pc.getReceivers().find((x) => x.track === a);
    return r?.getSynchronizationSources?.()[0]?.audioLevel ?? 0;
  }
  /** our own mic level, from the sender's media-source stats (ponytail: Firefox has none, so no own ring there; an AnalyserNode if it matters) */
  async selfLevel() {
    if (!this.live("audio")) return 0;
    let l = 0;
    (await this.pc.getStats(this.mine.audio!.sender.track)).forEach((s) => { if (s.type === "media-source" && s.kind === "audio") l = s.audioLevel ?? 0; });
    return l;
  }

  /** Leaving: camera and mic off now, and the session ends with the connection. */
  stop() {
    this.closed = true;
    this.self.getTracks().forEach((t) => t.stop());
    this.pc.getSenders().forEach((s) => s.track?.stop());
    this.pc.close();
  }
}
