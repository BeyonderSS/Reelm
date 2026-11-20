package com.anistream.feature.party

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.anistream.core.media.MediaHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Faces and voices for the room [PartyClient] is in, through the server's `/v1/rtc` proxy to Cloudflare Realtime:
 * one PeerConnection per member; our camera and mic go up once (`audio`/`video` tracks), and each other member's
 * published tracks are pulled as their `rtc` info arrives. Phone only: TV builds lack libwebrtc (see [rtcAvailable]).
 * Everything stops when the room goes (leave, socket drop, the player screen closing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PartyRtc @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val http: MediaHttp,
    private val client: PartyClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lock = Mutex()

    /** null = not asked yet; false = the server has no Realtime secrets (503): camera and mic stay hidden */
    val configured = MutableStateFlow<Boolean?>(null)
    val cam = MutableStateFlow(false)
    val mic = MutableStateFlow(false)
    val frontCamera = MutableStateFlow(true)
    /** member id → their camera; our own is under our member id */
    val videos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    /** member id → voice level 0..1 */
    val levels = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** film volume: [DUCK_LEVEL] while someone talks */
    val duck = MutableStateFlow(1f)
    val notes = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val egl: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        // VOICE_COMMUNICATION + the platform echo canceller: the film playing from the speaker stays out of the mic
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var pc: PeerConnection? = null
    private var session: String? = null
    private var ice: List<PeerConnection.IceServer>? = null
    private var audioT: RtpTransceiver? = null
    private var videoT: RtpTransceiver? = null
    private var audio: Pair<org.webrtc.AudioSource, org.webrtc.AudioTrack>? = null
    private var camera: Camera? = null
    private var whispering = false
    private var hinted = false
    private var commMode = false
    /** "session/trackName" → (member id, kind, mid) of what we pulled */
    private val pulled = mutableMapOf<String, Triple<String, String, String>>()
    private var levelsJob: Job? = null
    private val ducker = Ducker()

    private class Camera(val capturer: CameraVideoCapturer, val helper: SurfaceTextureHelper, val source: org.webrtc.VideoSource, val track: VideoTrack)

    init {
        scope.launch { client.room.collect { r -> guarded { if (r == null) stopLocked() else pullLocked(r) } } }
        scope.launch {
            client.messages.collect { m ->
                // the server kept our audio and dropped the video: four people already show their faces
                if ((m["type"] as? JsonPrimitive)?.contentOrNull == "rtc_full") {
                    guarded { camLocked(false) }
                    notes.emit("Four people are already on camera, so you're on voice")
                }
            }
        }
    }

    fun setCam(on: Boolean) = scope.launch { guarded { camLocked(on) } }
    fun setMic(on: Boolean) = scope.launch { guarded { whispering = false; micLocked(on) } }
    /** Date night: holding the partner's bubble opens a muted mic until release. */
    fun whisper(hold: Boolean) = scope.launch {
        guarded {
            if (hold && !mic.value) { whispering = true; micLocked(true) }
            else if (!hold && whispering) { whispering = false; micLocked(false) }
        }
    }
    fun flip() {
        val c = camera ?: return
        c.capturer.switchCamera(null)
        frontCamera.value = !frontCamera.value
    }

    private suspend fun guarded(block: suspend () -> Unit) = lock.withLock {
        try {
            block()
        } catch (e: NotConfigured) {
            stopLocked()
        } catch (e: Exception) {
            Log.w("PartyRtc", "rtc failed", e)
        }
    }

    private object NotConfigured : IOException("video not configured")

    // ---- server (/v1/rtc proxy) ----

    private fun call(method: String, path: String, body: JsonObject? = null): JsonObject {
        val r = client.room.value ?: throw IOException("no room")
        val me = client.selfId ?: throw IOException("no member id")
        val req = Request.Builder().url("${BuildConfig.REELM_API_URL}/v1/rtc$path").header("X-Room-Member", "${r.code}:$me")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        http.client.newCall(req).execute().use { res ->
            if (res.code == 503) { configured.value = false; throw NotConfigured }
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("rtc $method $path: ${res.code} $text")
            return if (text.isBlank()) JsonObject(emptyMap()) else MediaHttp.json.parseToJsonElement(text).jsonObject
        }
    }

    /** GET /v1/rtc/ice once per room: TURN credentials, and whether video is set up at all. */
    private fun iceServers(): List<PeerConnection.IceServer> = ice ?: (call("GET", "/ice")["iceServers"] as? JsonArray).orEmpty().mapNotNull { s ->
        val o = s as? JsonObject ?: return@mapNotNull null
        val urls = when (val u = o["urls"]) {
            is JsonArray -> u.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(u.contentOrNull)
            else -> emptyList()
        }.ifEmpty { return@mapNotNull null }
        PeerConnection.IceServer.builder(urls)
            .setUsername((o["username"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            .setPassword((o["credential"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            .createIceServer()
    }.also { ice = it; configured.value = true }

    private fun sdp(o: JsonObject?): SessionDescription? {
        val d = o?.get("sessionDescription") as? JsonObject ?: return null
        val type = if ((d["type"] as? JsonPrimitive)?.contentOrNull == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        return SessionDescription(type, (d["sdp"] as? JsonPrimitive)?.contentOrNull ?: return null)
    }

    private fun JsonObject.sdpBody(d: SessionDescription) = buildJsonObject {
        this@sdpBody.forEach { (k, v) -> put(k, v) }
        putJsonObject("sessionDescription") { put("type", d.type.canonicalForm()); put("sdp", d.description) }
    }

    // ---- peer connection ----

    private fun ensurePc(): PeerConnection {
        pc?.let { return it }
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val p = factory.createPeerConnection(config, Observer) ?: throw IOException("no peer connection")
        pc = p
        session = (call("POST", "/sessions")["sessionId"] as? JsonPrimitive)?.contentOrNull ?: throw IOException("no session")
        levelsJob = scope.launch { pollLevels() }
        return p
    }

    /** Our audio + video transceivers, pushed once; turning camera or mic on/off later only swaps their tracks. */
    private suspend fun ensurePushed() {
        if (audioT != null) return
        val p = ensurePc()
        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        val a = p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init)
        val v = p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
        val offer = p.create(true)
        p.set(true, offer)
        val res = call("POST", "/sessions/$session/tracks", buildJsonObject {
            putJsonArray("tracks") {
                addJsonObject { put("location", "local"); put("mid", a.mid); put("trackName", "audio") }
                addJsonObject { put("location", "local"); put("mid", v.mid); put("trackName", "video") }
            }
        }.sdpBody(offer))
        p.set(false, sdp(res) ?: throw IOException("no answer"))
        audioT = a; videoT = v
    }

    /** Tell the room what we publish: tracks for what is on (others pull those), and the cam/mic flags. */
    private fun announce() {
        val s = session ?: return
        client.send("rtc") {
            put("sessionId", s)
            putJsonArray("tracks") {
                if (mic.value) addJsonObject { put("trackName", "audio"); put("kind", "audio") }
                if (cam.value) addJsonObject { put("trackName", "video"); put("kind", "video") }
            }
        }
        client.send("media") { put("cam", cam.value); put("mic", mic.value) }
    }

    private suspend fun micLocked(on: Boolean) {
        if (on == mic.value) return
        if (on) {
            ensurePushed()
            val src = factory.createAudioSource(MediaConstraints())
            val track = factory.createAudioTrack("audio", src)
            audio = src to track
            audioT?.sender?.setTrack(track, false)
        } else {
            // no track = no sending stream: the recorder stops and the mic indicator goes off
            audioT?.sender?.setTrack(null, false)
            audio?.let { (s, t) -> t.dispose(); s.dispose() }
            audio = null
        }
        mic.value = on
        voiceMode(on)
        announce()
    }

    private suspend fun camLocked(on: Boolean) {
        if (on == cam.value) return
        val me = client.selfId.orEmpty()
        if (on) {
            val others = client.room.value?.members.orEmpty().count { it.id != me && it.rtc?.tracks?.containsKey("video") == true }
            if (others >= 4) { notes.emit("Four people are already on camera, so you're on voice"); return }
            ensurePushed()
            val names = Camera2Enumerator(ctx)
            val name = names.deviceNames.firstOrNull { names.isFrontFacing(it) } ?: names.deviceNames.firstOrNull()
            if (name == null) { notes.emit("No camera found"); return }
            frontCamera.value = names.isFrontFacing(name)
            val capturer = names.createCapturer(name, null)
            val helper = SurfaceTextureHelper.create("party-cam", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            capturer.initialize(helper, ctx, source.capturerObserver)
            capturer.startCapture(640, 480, 24)
            val track = factory.createVideoTrack("video", source)
            camera = Camera(capturer, helper, source, track)
            videoT?.sender?.setTrack(track, false)
            videos.value = videos.value + (me to track)
        } else {
            videoT?.sender?.setTrack(null, false)
            releaseCamera()
            videos.value = videos.value - me
        }
        cam.value = on
        announce()
    }

    private fun releaseCamera() {
        camera?.let { c ->
            runCatching { c.capturer.stopCapture() }
            c.capturer.dispose(); c.track.dispose(); c.source.dispose(); c.helper.dispose()
        }
        camera = null
    }

    /** Pull every track other members publish that we don't have yet; drop the faces of those who stopped or left. */
    private suspend fun pullLocked(r: Room) {
        if (client.selfId == null || configured.value == false) return
        if (configured.value == null) iceServers()
        val me = client.selfId
        val want = r.members.filter { it.id != me }.flatMap { m ->
            m.rtc?.tracks.orEmpty().map { (kind, name) -> Triple(m.id, kind, m.rtc!!.sessionId to name) }
        }
        val live = want.map { "${it.third.first}/${it.third.second}" }.toSet()
        // a track pulled once stays pulled (a camera turned back on shows again without a new pull)
        // ponytail: transceivers of members who left stay open (dead); close them via /tracks/close if rooms churn a lot
        val fresh = want.filter { "${it.third.first}/${it.third.second}" !in pulled }
        if (fresh.isNotEmpty()) {
            val p = ensurePc()
            val res = call("POST", "/sessions/$session/tracks", buildJsonObject {
                putJsonArray("tracks") {
                    fresh.forEach { (_, _, t) -> addJsonObject { put("location", "remote"); put("sessionId", t.first); put("trackName", t.second) } }
                }
            })
            val offer = sdp(res)
            if (offer != null && offer.type == SessionDescription.Type.OFFER) {
                p.set(false, offer)
                val answer = p.create(false)
                p.set(true, answer)
                call("PUT", "/sessions/$session/renegotiate", JsonObject(emptyMap()).sdpBody(answer))
            }
            (res["tracks"] as? JsonArray).orEmpty().forEach { t ->
                val o = t as? JsonObject ?: return@forEach
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val mid = s("mid") ?: return@forEach
                val hit = fresh.firstOrNull { it.third.first == s("sessionId") && it.third.second == s("trackName") } ?: return@forEach
                pulled["${hit.third.first}/${hit.third.second}"] = Triple(hit.first, hit.second, mid)
            }
        }
        // faces: each member's pulled video track
        val faces = pulled.filterKeys { it in live }.values.filter { it.second == "video" }.mapNotNull { (id, _, mid) ->
            (pc?.transceivers?.firstOrNull { it.mid == mid }?.receiver?.track() as? VideoTrack)?.let { id to it }
        }.toMap()
        val own = camera?.let { mapOf(me.orEmpty() to it.track) }.orEmpty()
        videos.value = faces + own
    }

    private suspend fun pollLevels() {
        while (scope.isActive) {
            delay(100)
            val p = pc ?: return
            val report = suspendCancellableCoroutine<RTCStatsReport> { c -> p.getStats { c.resume(it) } }
            val owners = pulled.values.filter { it.second == "audio" }.associate { it.third to it.first }
            val me = client.selfId.orEmpty()
            val lv = HashMap<String, Float>()
            report.statsMap.values.forEach { s ->
                val m = s.members
                if (m["kind"] != "audio") return@forEach
                val level = (m["audioLevel"] as? Number)?.toFloat() ?: return@forEach
                when (s.type) {
                    "inbound-rtp" -> owners[m["mid"] as? String]?.let { lv[it] = maxOf(lv[it] ?: 0f, level) }
                    "media-source" -> if (mic.value) lv[me] = level
                }
            }
            levels.value = lv
            duck.value = ducker.volume(SystemClock.elapsedRealtime(), lv.any { (id, l) -> id != me && l > TALK_THRESHOLD })
        }
    }

    /** Mic on: call audio mode, so the platform echo canceller hears what the speaker plays (the film too). */
    private suspend fun voiceMode(on: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        @Suppress("DEPRECATION")
        if (on) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = !headset
            commMode = true
            if (!headset && !hinted) { hinted = true; notes.emit("Headphones keep the film out of your mic") }
        } else if (commMode) {
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
            commMode = false
        }
    }

    private suspend fun stopLocked() {
        levelsJob?.cancel(); levelsJob = null
        releaseCamera()
        audio?.let { (s, t) -> t.dispose(); s.dispose() }
        audio = null
        pc?.dispose()
        pc = null; session = null; ice = null; audioT = null; videoT = null; whispering = false
        pulled.clear()
        if (cam.value || mic.value) voiceMode(false)
        cam.value = false; mic.value = false
        videos.value = emptyMap(); levels.value = emptyMap(); duck.value = 1f
        if (configured.value == true) configured.value = null
    }

    private object Observer : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { Log.d("PartyRtc", "ice $s") }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }
}

/** createOffer ([offer]) / createAnswer as a suspend call. */
private suspend fun PeerConnection.create(offer: Boolean): SessionDescription = suspendCancellableCoroutine { c ->
    val o = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) = c.resume(d)
        override fun onCreateFailure(e: String?) = c.resumeWithException(IOException("create: $e"))
        override fun onSetSuccess() {}
        override fun onSetFailure(e: String?) {}
    }
    if (offer) createOffer(o, MediaConstraints()) else createAnswer(o, MediaConstraints())
}

/** setLocalDescription ([local]) / setRemoteDescription as a suspend call. */
private suspend fun PeerConnection.set(local: Boolean, d: SessionDescription): Unit = suspendCancellableCoroutine { c ->
    val o = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetSuccess() = c.resume(Unit)
        override fun onSetFailure(e: String?) = c.resumeWithException(IOException("set: $e"))
    }
    if (local) setLocalDescription(o, d) else setRemoteDescription(o, d)
}
