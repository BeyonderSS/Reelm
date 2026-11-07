/// <reference lib="webworker" />
// Decodes the audio track of a remote MKV/MP4 (E-AC-3, AC-3, DTS, TrueHD: what Chrome can't play) to stereo PCM with
// libav.js. Reads the file through HTTP Range requests on demand (libav block-reader device), never the whole file.
// public/libav holds a custom libav.js 6.10.9 build ("reelm" variant): mkconfig.js reelm '["avformat","avcodec","avfilter",
// "swresample","demuxer-matroska","demuxer-mov","parser-ac3","decoder-ac3","decoder-eac3","parser-dca","decoder-dca",
// "parser-mlp","decoder-truehd","decoder-mlp","parser-aac","decoder-aac","parser-opus","decoder-opus","parser-flac",
// "decoder-flac","zlib","filter-aformat","filter-anull"]', then make dist/libav-6.10.9.0-reelm{,.wasm}.js.
// Also picks any audio track: multi-audio releases (Hindi + English MKVs) whose other tracks the browser won't switch to.
// In: {type:"open",url,track?,probe?} | {type:"seek",t,id} | {type:"want",until}.
// Out: {type:"tracks",tracks} | {type:"info",codec} | {type:"pcm",id,t,sr,l,r} | {type:"error",message}. probe: post tracks, then stop.
declare const LibAV: any;
// ponytail: 2 MB blocks, 6 in flight: startup needs 3 serial reads (header, cues at the end, seek target), which took
// ~45 s with 8 MB blocks on Pixeldrain; more parallel requests keep the sequential rate. Tune if a host throttles connections.
const BLOCK = 2 << 20, AHEAD_BLOCKS = 6; // bytes per Range request (libav asks for 32 KB at a time), blocks fetched ahead
let want = 0, seek: { t: number; id: number } | null = null, id = 0, wake = () => {};

onmessage = ({ data: m }) => {
  if (m.type === "open") run(m.url, m.track, m.probe).catch((e) => postMessage({ type: "error", message: String(e?.message ?? e) }));
  if (m.type === "seek") { seek = m; want = m.t + 10; }
  if (m.type === "want") want = m.until;
  wake();
};

async function run(url: string, track?: number, probe = false) {
  (self as any).LibAV = { base: "/libav" };
  importScripts("/libav/libav-6.10.9.0-reelm.js");
  const av = await LibAV.LibAV({ noworker: true, nothreads: true, wasmurl: "/libav/libav-6.10.9.0-reelm.wasm.wasm" });
  // file size: Content-Range isn't CORS-exposed by these hosts, but Content-Length of an open-ended range is; drop the body
  const ac = new AbortController(), head = await fetch(url, { headers: { Range: "bytes=0-" }, signal: ac.signal });
  const size = head.status === 206 ? +(head.headers.get("content-length") ?? 0) : 0;
  ac.abort();
  if (!size) throw new Error(`no range support (HTTP ${head.status})`);
  // Big block-aligned Range reads, the next ones fetched in parallel: Pixeldrain takes ~1.5-2 s to first byte per request
  // and then streams ~2-3 MB/s, so small or serial reads fall behind a 1080p file's bitrate.
  const blocks = new Map<number, Promise<Uint8Array>>();
  const block = (i: number) => {
    if (!blocks.has(i) && i * BLOCK < size) {
      blocks.set(i, fetch(url, { headers: { Range: `bytes=${i * BLOCK}-${Math.min(size, (i + 1) * BLOCK) - 1}` } })
        .then(async (r) => { if (r.status !== 206) throw new Error(`HTTP ${r.status}`); return new Uint8Array(await r.arrayBuffer()); }));
      blocks.get(i)!.catch((e) => { blocks.delete(i); postMessage({ type: "warn", message: `block ${i}: ${e?.message ?? e}` }); });
      if (blocks.size > AHEAD_BLOCKS + 2) blocks.delete(blocks.keys().next().value!);
    }
    return blocks.get(i)!;
  };
  av.onblockread = async (name: string, pos: number) => {
    const i = Math.floor(pos / BLOCK);
    for (let k = 1; k <= AHEAD_BLOCKS; k++) block(i + k);
    await av.ff_block_reader_dev_send(name, i * BLOCK, await block(i));
  };
  await av.mkblockreaderdev("in", size);
  const [fmt, streams] = await av.ff_init_demuxer_file("in");
  const audio = streams.filter((x: any) => x.codec_type === av.AVMEDIA_TYPE_AUDIO);
  if (!audio.length) throw new Error("no audio track");
  const tracks = await Promise.all(audio.map(async (x: any) => {
    const md = await av.ff_copyout_dict(await av.AVStream_metadata(await av.AVFormatContext_streams_a(fmt, x.index))).catch(() => ({}));
    return { index: x.index, lang: String(md?.language ?? ""), title: String(md?.title ?? ""), codec: await av.avcodec_get_name(x.codec_id) };
  }));
  postMessage({ type: "tracks", tracks });
  if (probe) return;
  const s = audio.find((x: any) => x.index === track) ?? audio[0];
  // AVDISCARD_ALL (48) on the other streams so video packets are never copied out; lifted while seeking, since a
  // discarded default (video) stream makes matroska ignore its cues and scan the file linearly
  const others = await Promise.all(streams.filter((x: any) => x !== s).map((x: any) => av.AVFormatContext_streams_a(fmt, x.index)));
  const discard = (d: number) => Promise.all(others.map((p) => av.AVStream_discard_s(p, d)));
  await discard(48);
  const [, c, pkt, frame] = await av.ff_init_decoder(s.codec_id, { codecpar: s.codecpar });
  postMessage({ type: "info", codec: await av.avcodec_get_name(s.codec_id) });
  const tb = s.time_base_num / s.time_base_den;
  let graph: [number, number, number] | null = null, at = 0, base = NaN, done = 0, sr = 0, eof = false;

  for (;;) {
    if (seek) {
      const [lo, hi] = av.f64toi64(Math.floor(seek.t * 1e6)); // stream -1 = AV_TIME_BASE units, seeks on the video's cues
      id = seek.id; at = seek.t; seek = null;
      await discard(0);
      await av.avformat_seek_file_max(fmt, -1, lo, hi, 0);
      await discard(48);
      await av.avcodec_flush_buffers(c);
      base = NaN; done = 0; eof = false;
    }
    if (eof || (!isNaN(base) && base + done / sr >= want)) { await new Promise<void>((r) => (wake = r)); continue; }
    const [res, pk] = await av.ff_read_frame_multi(fmt, pkt, { limit: 32 * 1024 } /* small, so a seek is picked up quickly */);
    eof = res === av.AVERROR_EOF;
    const frames = await av.ff_decode_multi(c, pkt, frame, pk[s.index] ?? [], { fin: eof, ignoreErrors: true });
    if (!frames.length || seek) continue;
    const f0 = frames[0];
    const pts = av.i64tof64(f0.pts ?? 0, f0.ptshi ?? 0);
    // decoded frames carry their own time base (1/sample_rate), not the stream's
    if (isNaN(base)) base = pts === av.AV_NOPTS_VALUE ? at : pts * (f0.time_base_den ? f0.time_base_num / f0.time_base_den : tb);
    // downmix + convert any sample format to stereo float planar (swresample's standard matrix)
    graph ??= await av.ff_init_filter_graph("anull",
      { sample_rate: (sr = f0.sample_rate), sample_fmt: f0.format, channel_layout: f0.channel_layout },
      { sample_rate: sr, sample_fmt: av.AV_SAMPLE_FMT_FLTP, channel_layout: 3 });
    const out = await av.ff_filter_multi(graph![1], graph![2], frame, frames); // no fin: the graph must survive a seek after EOF
    const n = out.reduce((a: number, f: any) => a + f.data[0].length, 0);
    if (!n) continue;
    const l = new Float32Array(n), r = new Float32Array(n);
    let o = 0;
    for (const f of out) { l.set(f.data[0], o); r.set(f.data[1], o); o += f.data[0].length; }
    postMessage({ type: "pcm", id, t: base + done / sr, sr, l, r }, [l.buffer, r.buffer]);
    done += n;
  }
}
