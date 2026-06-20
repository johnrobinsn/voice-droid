package com.voicedroid.service.realtime

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebRTC-based client to OpenAI's Realtime API. Audio flows over RTP/Opus
 * (no more Base64 PCM over WebSocket), events flow over a DataChannel.
 *
 * Drop-in replacement for [RealtimeClient] — same outbound helpers, same
 * SharedFlow of inbound events. Key differences:
 *   - No appendAudio() — mic audio flows automatically via the local audio track.
 *   - No playback queue — remote audio track plays through the system automatically.
 */
class WebRtcRealtimeClient(
    private val apiKey: String,
    private val context: Context,
    /** Initial voice; can be changed via session.update mid-session before audio starts. */
    private val currentVoice: String = SessionConfig.DEFAULT_VOICE,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var localAudioTrack: org.webrtc.AudioTrack? = null
    private var localAudioSource: org.webrtc.AudioSource? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var reconnectJob: Job? = null

    /**
     * Sideband WebSocket connection to the same Realtime session. WebRTC's data channel
     * silently drops messages larger than ~64 KB, so we route large payloads (images)
     * through this parallel WebSocket. Opened after the SDP exchange completes, using
     * the call_id from the Location response header.
     */
    private var sideband: WebSocket? = null

    /** The most recent session.update payload; resent on reconnect. */
    private val lastSessionUpdate = AtomicReference<JsonObject?>(null)

    /** True once the caller has asked us to be connected. */
    @Volatile private var shouldBeConnected = false

    /** Inbound parsed events. */
    val events: MutableSharedFlow<JsonObject> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 64)

    @Volatile var isOpen: Boolean = false
        private set

    /** Last-requested mic state; reapplied on reconnect. */
    @Volatile private var micEnabledState: Boolean = true

    /** Toggle the local audio track. When disabled, no audio leaves the device. */
    fun setMicEnabled(enabled: Boolean) {
        micEnabledState = enabled
        localAudioTrack?.setEnabled(enabled)
        Log.i(TAG, "mic enabled=$enabled")
    }

    fun connect() {
        shouldBeConnected = true
        scope.launch { establishConnection() }
    }

    fun close(reason: String = "client close") {
        shouldBeConnected = false
        isOpen = false
        reconnectJob?.cancel(); reconnectJob = null
        tearDownPeerConnection()
    }

    fun release() {
        close()
        audioDeviceModule?.release()
        audioDeviceModule = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        scope.cancel()
        Log.i(TAG, "released")
    }

    // ---- outbound helpers (via data channel) -----------------------------

    fun send(obj: JsonObject) {
        val dc = dataChannel ?: run {
            Log.w(TAG, "send: data channel not open, dropping")
            return
        }
        if (dc.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "send: data channel state=${dc.state()}, dropping")
            return
        }
        val bytes = json.encodeToString(JsonObject.serializer(), obj)
            .toByteArray(Charset.forName("UTF-8"))
        val buf = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
        val ok = dc.send(buf)
        if (!ok) Log.w(TAG, "DataChannel send failed")
    }

    fun sendSessionUpdate(
        turnMode: TurnMode,
        toolsJson: JsonArray,
        instructions: String = com.voicedroid.storage.Settings.DEFAULT_SYSTEM_PROMPT,
        pttSilenceMs: Int = SessionConfig.DEFAULT_PTT_SILENCE_MS,
        vadThreshold: Double = SessionConfig.DEFAULT_VAD_THRESHOLD,
        voice: String = currentVoice,
    ) {
        val update = SessionConfig.build(
            turnMode, toolsJson, webrtc = true,
            instructions = instructions, pttSilenceMs = pttSilenceMs,
            vadThreshold = vadThreshold, voice = voice,
        )
        lastSessionUpdate.set(update)
        send(update)
    }

    fun commitAudio() = send(buildJsonObject { put("type", "input_audio_buffer.commit") })
    fun clearAudio() = send(buildJsonObject { put("type", "input_audio_buffer.clear") })
    fun createResponse() = send(buildJsonObject { put("type", "response.create") })
    fun cancelResponse() = send(buildJsonObject { put("type", "response.cancel") })

    fun sendToolResult(callId: String, output: String) {
        send(buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "function_call_output")
                put("call_id", callId)
                put("output", output)
            })
        })
    }

    fun sendImage(jpegBytes: ByteArray) {
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val msg = buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", "data:image/jpeg;base64,$b64")
                        // Force high-detail processing. Default "auto" can fall back to
                        // a ~256px thumbnail which makes precise icon localization
                        // impossible on a 1080x2340 phone screen.
                        put("detail", "high")
                    })
                })
            })
        }
        val payload = json.encodeToString(JsonObject.serializer(), msg)
        val sb = sideband
        if (sb != null) {
            val ok = sb.send(payload)
            if (ok) {
                Log.i(TAG, "image sent via sideband WS (${payload.length} chars)")
                return
            }
            Log.w(TAG, "sideband send rejected, falling back to data channel")
        } else {
            Log.w(TAG, "sideband not open, falling back to data channel (may be dropped if >64KB)")
        }
        send(msg)
    }

    // ---- connection / reconnect -------------------------------------------

    private fun ensureFactory() {
        if (peerConnectionFactory != null) return
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        audioDeviceModule = adm

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    private suspend fun establishConnection() {
        try {
            ensureFactory()

            // 1. Get ephemeral token
            val ephemeralToken = fetchEphemeralToken()
            if (ephemeralToken == null) {
                Log.e(TAG, "Failed to get ephemeral token")
                scheduleReconnect()
                return
            }

            // 2. Create peer connection with data channel and local audio
            val factory = peerConnectionFactory ?: run {
                Log.e(TAG, "PeerConnectionFactory is null")
                scheduleReconnect()
                return
            }

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val pc = factory.createPeerConnection(rtcConfig, peerConnectionObserver)
            if (pc == null) {
                Log.e(TAG, "Failed to create PeerConnection")
                scheduleReconnect()
                return
            }
            peerConnection = pc

            // Create the data channel for events
            val dcInit = DataChannel.Init().apply {
                ordered = true
            }
            val dc = pc.createDataChannel("oai-events", dcInit)
            if (dc == null) {
                Log.e(TAG, "Failed to create DataChannel")
                tearDownPeerConnection()
                scheduleReconnect()
                return
            }
            dataChannel = dc
            dc.registerObserver(dataChannelObserver)

            // Add local audio track (microphone). Start in the most recently requested
            // state so PTT comes up muted across reconnects.
            val src = factory.createAudioSource(MediaConstraints())
            val track = factory.createAudioTrack("mic-audio", src)
            track.setEnabled(micEnabledState)
            pc.addTrack(track)
            localAudioSource = src
            localAudioTrack = track

            // 3. SDP exchange: create offer, then POST to OpenAI
            val offer = createOffer(pc)
            if (offer == null) {
                Log.e(TAG, "Failed to create SDP offer")
                tearDownPeerConnection()
                scheduleReconnect()
                return
            }

            pc.setLocalDescription(SdpObserverAdapter("setLocalDesc"), offer)

            val sdpResp = sendSdpOffer(ephemeralToken, offer.description)
            if (sdpResp == null) {
                Log.e(TAG, "Failed to get SDP answer from OpenAI")
                tearDownPeerConnection()
                scheduleReconnect()
                return
            }

            val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdpResp.answer)
            val answerLatch = CountDownLatch(1)
            var answerOk = false
            pc.setRemoteDescription(object : SdpObserverAdapter("setRemoteDesc") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    answerOk = true
                    answerLatch.countDown()
                }
                override fun onSetFailure(error: String?) {
                    super.onSetFailure(error)
                    answerLatch.countDown()
                }
            }, remoteDesc)

            answerLatch.await(10, TimeUnit.SECONDS)
            if (!answerOk) {
                Log.e(TAG, "Failed to set remote description")
                tearDownPeerConnection()
                scheduleReconnect()
                return
            }

            Log.i(TAG, "WebRTC connection established, waiting for data channel to open")

            // Open sideband WebSocket to the same session so we can ship large payloads
            // (images) that won't fit through the data channel. Auth MUST be the
            // ephemeral key — the standard API key returns 404 for sideband joins.
            sdpResp.callId?.let { openSideband(it, ephemeralToken) }

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            tearDownPeerConnection()
            scheduleReconnect()
        }
    }

    private fun fetchEphemeralToken(): String? {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("session", buildJsonObject {
                    put("type", "realtime")
                    put("model", SessionConfig.MODEL)
                    putJsonObject("audio") {
                        putJsonObject("output") {
                            put("voice", currentVoice)
                        }
                    }
                })
            }
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/realtime/client_secrets")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Ephemeral token request failed: HTTP ${response.code}")
                response.close()
                return null
            }
            val responseBody = response.body?.string() ?: return null
            response.close()
            val obj = json.parseToJsonElement(responseBody).jsonObject
            Log.d(TAG, "Ephemeral token response keys: ${obj.keys}")
            val token = obj["value"]?.jsonPrimitive?.content
            if (token == null) {
                Log.e(TAG, "No 'value' in response: $responseBody")
            }
            token
        } catch (e: Exception) {
            Log.e(TAG, "Ephemeral token fetch error: ${e.message}", e)
            null
        }
    }

    private fun createOffer(pc: PeerConnection): SessionDescription? {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        pc.createOffer(object : SdpObserverAdapter("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                super.onCreateSuccess(sdp)
                result = sdp
                latch.countDown()
            }
            override fun onCreateFailure(error: String?) {
                super.onCreateFailure(error)
                latch.countDown()
            }
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        })
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    private data class SdpResponse(val answer: String, val callId: String?)

    private fun sendSdpOffer(ephemeralToken: String, sdpOffer: String): SdpResponse? {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/realtime/calls")
            .header("Authorization", "Bearer $ephemeralToken")
            .header("Content-Type", "application/sdp")
            .post(sdpOffer.toRequestBody("application/sdp".toMediaType()))
            .build()

        return try {
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "SDP exchange failed: HTTP ${response.code}")
                response.close()
                return null
            }
            val answer = response.body?.string()
            // Location header looks like "/v1/realtime/calls/rtc_xxxxx" — the trailing
            // segment is the call_id we'll use to open a sideband WebSocket.
            val callId = response.header("Location")?.substringAfterLast('/')
            response.close()
            if (answer == null) return null
            Log.i(TAG, "SDP answer received, call_id=$callId")
            SdpResponse(answer, callId)
        } catch (e: Exception) {
            Log.e(TAG, "SDP exchange error: ${e.message}", e)
            null
        }
    }

    private fun openSideband(callId: String, ephemeralToken: String) {
        closeSideband()
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?call_id=$callId")
            .header("Authorization", "Bearer $ephemeralToken")
            .build()
        sideband = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "sideband WS open (HTTP ${response.code})")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "sideband WS failure: ${t.message}")
                sideband = null
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "sideband WS closed $code $reason")
                sideband = null
            }
            // Inbound events also arrive via the data channel; we ignore them here to
            // avoid double-processing.
        })
    }

    private fun closeSideband() {
        try { sideband?.close(1000, "client close") } catch (_: Throwable) {}
        sideband = null
    }

    private fun tearDownPeerConnection() {
        closeSideband()
        dataChannel?.close()
        dataChannel = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        peerConnection?.dispose()
        peerConnection = null
        Log.i(TAG, "peer connection torn down")
    }

    private fun scheduleReconnect() {
        if (!shouldBeConnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 1000L
            while (isActive && shouldBeConnected && !isOpen) {
                Log.i(TAG, "reconnecting in ${delayMs}ms")
                delay(delayMs)
                if (!shouldBeConnected) return@launch
                establishConnection()
                // Give the connection a moment to establish
                delay(3000)
                delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    // ---- PeerConnection observer -----------------------------------------

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "ICE connection state: $state")
            when (state) {
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    isOpen = false
                    if (shouldBeConnected) {
                        scope.launch {
                            tearDownPeerConnection()
                            scheduleReconnect()
                        }
                    }
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    isOpen = false
                }
                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering state: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            // Trickle ICE: OpenAI uses server-side gathering via the SDP answer,
            // local candidates are added automatically by the peer connection.
            Log.d(TAG, "ICE candidate: ${candidate?.sdp}")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {
            // Remote audio track — WebRTC plays it through the audio device automatically.
            Log.i(TAG, "Remote stream added: ${stream?.audioTracks?.size} audio tracks")
        }

        override fun onRemoveStream(stream: MediaStream?) {
            Log.i(TAG, "Remote stream removed")
        }

        override fun onDataChannel(dc: DataChannel?) {
            Log.i(TAG, "onDataChannel (server-created)")
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            Log.i(TAG, "Remote track added: ${receiver?.track()?.kind()}")
        }
    }

    // ---- DataChannel observer --------------------------------------------

    private val dataChannelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(amount: Long) {}

        override fun onStateChange() {
            val state = dataChannel?.state()
            Log.i(TAG, "DataChannel state: $state")
            when (state) {
                DataChannel.State.OPEN -> {
                    isOpen = true
                    reconnectJob?.cancel(); reconnectJob = null
                    // Resend the last session config so the new connection picks up identical state.
                    lastSessionUpdate.get()?.let { send(it) }
                }
                DataChannel.State.CLOSED -> {
                    isOpen = false
                    if (shouldBeConnected) {
                        scope.launch {
                            tearDownPeerConnection()
                            scheduleReconnect()
                        }
                    }
                }
                else -> {}
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null || buffer.binary) return
            val data = buffer.data
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            val text = String(bytes, Charset.forName("UTF-8"))
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                events.tryEmit(obj)
                val t = obj["type"]?.jsonPrimitive?.content
                if (t != null && t != "response.output_audio.delta"
                    && t != "response.output_audio_transcript.delta") {
                    Log.d(TAG, "<- $t")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "parse failure: ${e.message}")
            }
        }
    }

    // ---- SDP observer adapter -------------------------------------------

    private open class SdpObserverAdapter(private val label: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            Log.d(TAG, "$label onCreateSuccess")
        }
        override fun onSetSuccess() {
            Log.d(TAG, "$label onSetSuccess")
        }
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$label onCreateFailure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$label onSetFailure: $error")
        }
    }

    companion object {
        private const val TAG = "WebRtcRealtimeClient"
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
