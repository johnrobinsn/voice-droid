package com.voicedroid.service.realtime

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper over OkHttp WebSocket to OpenAI's Realtime API. Maintains one logical
 * connection, exposes inbound events as a SharedFlow, and offers helpers for outbound
 * messages. Reconnects automatically on unexpected closes (the API caps sessions at
 * 60 minutes; we also tolerate transient network drops).
 */
class RealtimeClient(
    private val apiKey: String,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout on the WS
        .build()

    private var ws: WebSocket? = null
    private var senderJob: Job? = null
    private var reconnectJob: Job? = null

    /** The most recent session.update payload; resent on reconnect. */
    private val lastSessionUpdate = AtomicReference<JsonObject?>(null)

    /** True once the caller has asked us to be connected. */
    @Volatile private var shouldBeConnected = false

    /** Inbound parsed events. */
    val events: MutableSharedFlow<JsonObject> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 64)

    private val sendQueue: Channel<String> = Channel(Channel.UNLIMITED)

    @Volatile var isOpen: Boolean = false
        private set

    fun connect() {
        shouldBeConnected = true
        openSocket()
        if (senderJob == null) {
            senderJob = scope.launch {
                for (msg in sendQueue) {
                    val socket = ws ?: continue
                    val ok = socket.send(msg)
                    if (!ok) Log.w(TAG, "WS send failed (buffer or closed)")
                }
            }
        }
    }

    fun close(reason: String = "client close") {
        shouldBeConnected = false
        isOpen = false
        reconnectJob?.cancel(); reconnectJob = null
        try { ws?.close(1000, reason) } catch (_: Throwable) {}
        ws = null
    }

    fun release() {
        close()
        senderJob?.cancel(); senderJob = null
        scope.cancel()
        sendQueue.close()
    }

    // ---- outbound helpers --------------------------------------------------

    fun send(obj: JsonObject) {
        sendQueue.trySend(json.encodeToString(JsonObject.serializer(), obj))
    }

    fun sendSessionUpdate(
        turnMode: TurnMode,
        toolsJson: JsonArray,
        instructions: String = com.voicedroid.storage.Settings.DEFAULT_SYSTEM_PROMPT,
        pttSilenceMs: Int = SessionConfig.DEFAULT_PTT_SILENCE_MS,
        vadThreshold: Double = SessionConfig.DEFAULT_VAD_THRESHOLD,
        voice: String = SessionConfig.DEFAULT_VOICE,
    ) {
        val update = SessionConfig.build(
            turnMode, toolsJson,
            instructions = instructions, pttSilenceMs = pttSilenceMs,
            vadThreshold = vadThreshold, voice = voice,
        )
        lastSessionUpdate.set(update)
        send(update)
    }

    fun appendAudio(pcm: ByteArray) {
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        send(buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        })
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
        send(buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", "data:image/jpeg;base64,$b64")
                        put("detail", "high")
                    })
                })
            })
        })
    }

    // ---- connection / reconnect -------------------------------------------

    private fun openSocket() {
        val req = Request.Builder()
            .url("$URL?model=${SessionConfig.MODEL}")
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = http.newWebSocket(req, listener)
    }

    private fun scheduleReconnect() {
        if (!shouldBeConnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 500L
            while (isActive && shouldBeConnected && !isOpen) {
                Log.i(TAG, "↻ reconnecting in ${delayMs}ms")
                delay(delayMs)
                if (!shouldBeConnected) return@launch
                openSocket()
                // Give the WS a moment to open; if it does, the listener will resend the
                // session.update. If not, we loop with backoff.
                delay(2000)
                delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS open (HTTP ${response.code})")
            isOpen = true
            // Resend the last session config so the new connection picks up identical state.
            lastSessionUpdate.get()?.let { send(it) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                events.tryEmit(obj)
                val t = obj["type"]?.jsonPrimitive?.content
                if (t != null && t != "response.output_audio.delta"
                    && t != "response.output_audio_transcript.delta") {
                    Log.d(TAG, "← $t")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "parse failure: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closing $code $reason")
            isOpen = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed $code $reason")
            isOpen = false
            if (shouldBeConnected) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            isOpen = false
            if (shouldBeConnected) scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "RealtimeClient"
        private const val URL = "wss://api.openai.com/v1/realtime"
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
