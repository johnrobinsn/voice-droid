package com.voicedroid.service

import android.util.Base64
import android.util.Log
import com.voicedroid.service.realtime.RealtimeClient
import com.voicedroid.service.realtime.TurnMode
import com.voicedroid.service.realtime.WebRtcRealtimeClient
import com.voicedroid.service.vad.SileroVad
import com.voicedroid.storage.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.util.concurrent.atomic.AtomicBoolean

data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val inputTextTokens: Int = 0,
    val inputAudioTokens: Int = 0,
    val inputCachedTokens: Int = 0,
    val outputTextTokens: Int = 0,
    val outputAudioTokens: Int = 0,
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        inputTextTokens = inputTextTokens + other.inputTextTokens,
        inputAudioTokens = inputAudioTokens + other.inputAudioTokens,
        inputCachedTokens = inputCachedTokens + other.inputCachedTokens,
        outputTextTokens = outputTextTokens + other.outputTextTokens,
        outputAudioTokens = outputAudioTokens + other.outputAudioTokens,
    )
}

/**
 * The voice loop: glues AudioIO and a realtime client (WebSocket or WebRTC)
 * together. One instance per service lifetime; modes drive different turn
 * configurations.
 *
 * When [useWebRtc] is true, audio flows through WebRTC automatically — the mic
 * pump and playback enqueue are skipped. Events still flow as JSON and are
 * handled identically.
 */
class SessionLoop(
    private val audio: AudioIO,
    private val apiKey: String,
    private val toolsJson: JsonArray,
    private val vad: SileroVad,
    private var instructions: String = com.voicedroid.storage.Settings.DEFAULT_SYSTEM_PROMPT,
    private var pttSilenceMs: Int = com.voicedroid.service.realtime.SessionConfig.DEFAULT_PTT_SILENCE_MS,
    private var vadThreshold: Double = com.voicedroid.service.realtime.SessionConfig.DEFAULT_VAD_THRESHOLD,
    private val useWebRtc: Boolean = false,
    private val webRtcClient: WebRtcRealtimeClient? = null,
    private val onHeard: (String) -> Unit = {},
    private val onTranscript: (String) -> Unit = {},
    private val onToolCall: (name: String, callId: String, args: String) -> Unit =
        { _, _, _ -> },
    private val onUsage: (TokenUsage) -> Unit = {},
    private val onStateChange: (listening: Boolean, speaking: Boolean) -> Unit = { _, _ -> },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** WebSocket client — only created when NOT using WebRTC. */
    private val wsClient: RealtimeClient? = if (!useWebRtc) RealtimeClient(apiKey) else null

    private val listening = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private var pumpJob: Job? = null
    private var receiveJob: Job? = null

    /** Client-side Silero VAD threshold for the WebSocket mic gate (NOT the server VAD). */
    private val localVadThreshold = 0.3f
    /** Trailing pad frames to send after speech ends (~300 ms = 3 frames at 100 ms). */
    private val vadPadFrames = 3
    private var vadPadRemaining = 0

    /** Currently configured mode. Determines TurnMode + whether mic streams continuously. */
    @Volatile
    var mode: Mode = Mode.OFF
        private set

    /** Event flow — adapts whichever client is active. */
    private val eventFlow: MutableSharedFlow<JsonObject>
        get() = if (useWebRtc) webRtcClient!!.events else wsClient!!.events

    private val clientIsOpen: Boolean
        get() = if (useWebRtc) webRtcClient!!.isOpen else wsClient!!.isOpen

    fun start(initialMode: Mode) {
        this.mode = initialMode
        if (useWebRtc) {
            val rtc = webRtcClient!!
            rtc.connect()
            rtc.sendSessionUpdate(turnModeFor(initialMode), toolsJson, instructions, pttSilenceMs, vadThreshold)
        } else {
            val ws = wsClient!!
            ws.connect()
            ws.sendSessionUpdate(turnModeFor(initialMode), toolsJson, instructions, pttSilenceMs, vadThreshold)
        }
        // Only pump mic frames for WebSocket mode (WebRTC sends audio automatically)
        if (!useWebRtc) {
            pumpJob = scope.launch { micPump() }
        }
        receiveJob = scope.launch {
            eventFlow.collect { handleEvent(it) }
        }
        // LISTENING streams the mic continuously. PTT keeps the session alive but gates
        // the mic until the user triggers a turn (volume-down / notification / BT button).
        val shouldListen = initialMode == Mode.LISTENING
        if (shouldListen && !useWebRtc) { vad.reset(); vadPadRemaining = 0 }
        setListening(shouldListen)
    }

    fun setMode(newMode: Mode) {
        if (newMode == mode) return
        Log.i(TAG, "session mode -> $newMode")
        mode = newMode
        if (useWebRtc) {
            webRtcClient!!.sendSessionUpdate(turnModeFor(newMode), toolsJson, instructions, pttSilenceMs, vadThreshold)
        } else {
            wsClient!!.sendSessionUpdate(turnModeFor(newMode), toolsJson, instructions, pttSilenceMs, vadThreshold)
        }
        val shouldListen = newMode == Mode.LISTENING
        if (shouldListen && !listening.get() && !useWebRtc) { vad.reset(); vadPadRemaining = 0 }
        setListening(shouldListen)
    }

    /** Push a new PTT silence-duration to the server without tearing down the session. */
    fun updatePttSilenceMs(newMs: Int) {
        if (newMs == pttSilenceMs) return
        pttSilenceMs = newMs
        if (useWebRtc) {
            webRtcClient!!.sendSessionUpdate(turnModeFor(mode), toolsJson, instructions, pttSilenceMs, vadThreshold)
        } else {
            wsClient!!.sendSessionUpdate(turnModeFor(mode), toolsJson, instructions, pttSilenceMs, vadThreshold)
        }
        Log.i(TAG, "pttSilenceMs = $newMs")
    }

    /** Push a new server-VAD threshold without tearing down the session. */
    fun updateVadThreshold(newThreshold: Double) {
        if (newThreshold == vadThreshold) return
        vadThreshold = newThreshold
        if (useWebRtc) {
            webRtcClient!!.sendSessionUpdate(turnModeFor(mode), toolsJson, instructions, pttSilenceMs, vadThreshold)
        } else {
            wsClient!!.sendSessionUpdate(turnModeFor(mode), toolsJson, instructions, pttSilenceMs, vadThreshold)
        }
        Log.i(TAG, "vadThreshold = $newThreshold")
    }

    fun release() {
        pumpJob?.cancel(); pumpJob = null
        receiveJob?.cancel(); receiveJob = null
        if (useWebRtc) {
            webRtcClient?.release()
        } else {
            wsClient?.release()
        }
        vad.release()
        scope.cancel()
    }

    /**
     * Set the listening flag and mirror it onto the WebRTC mic track. When [listening]
     * is false in WebRTC mode, the local audio track is disabled so no audio leaves the
     * device. The WebSocket mic pump reads [listening] directly.
     */
    private fun setListening(active: Boolean) {
        listening.set(active)
        if (useWebRtc) {
            webRtcClient?.setMicEnabled(active)
        }
        emitState()
    }

    private fun setSpeaking(active: Boolean) {
        speaking.set(active)
        emitState()
    }

    private fun emitState() {
        onStateChange(listening.get(), speaking.get())
    }

    /**
     * Single PTT toggle invoked from the notification button.
     *
     *  - speaking -> barge-in: cancel response; start listening
     *  - listening -> force end: commit + response.create (don't wait for VAD)
     *  - idle -> start listening (server VAD will close the turn and respond)
     */
    fun toggleTalk() {
        when {
            speaking.get() -> {
                if (useWebRtc) {
                    webRtcClient!!.cancelResponse()
                    webRtcClient.clearAudio()
                } else {
                    wsClient!!.cancelResponse()
                    audio.clearPlayback()
                    wsClient.clearAudio()
                    vad.reset(); vadPadRemaining = 0
                }
                setSpeaking(false)
                setListening(true)
                Log.i(TAG, "listening (barge-in)")
            }
            listening.get() -> {
                setListening(false)
                if (useWebRtc) {
                    webRtcClient!!.commitAudio()
                    webRtcClient.createResponse()
                } else {
                    wsClient!!.commitAudio()
                    wsClient.createResponse()
                }
                Log.i(TAG, "commit + response.create (forced end)")
            }
            else -> {
                if (useWebRtc) {
                    webRtcClient!!.clearAudio()
                } else {
                    wsClient!!.clearAudio()
                    vad.reset(); vadPadRemaining = 0
                }
                setListening(true)
                Log.i(TAG, "listening (PTT)")
            }
        }
    }

    /**
     * Replace the model instructions in-flight. Re-sends session.update so the change
     * takes effect on the next turn without tearing down the session.
     */
    fun updateInstructions(newInstructions: String) {
        if (newInstructions == instructions) return
        instructions = newInstructions
        if (useWebRtc) {
            webRtcClient!!.sendSessionUpdate(turnModeFor(mode), toolsJson, newInstructions, pttSilenceMs, vadThreshold)
        } else {
            wsClient!!.sendSessionUpdate(turnModeFor(mode), toolsJson, newInstructions, pttSilenceMs, vadThreshold)
        }
        Log.i(TAG, "instructions updated (${newInstructions.length} chars)")
    }

    /** Wire a tool result back to the model and ask for a follow-up response. */
    fun sendToolResult(callId: String, output: String) {
        if (useWebRtc) {
            webRtcClient!!.sendToolResult(callId, output)
            webRtcClient.createResponse()
        } else {
            wsClient!!.sendToolResult(callId, output)
            wsClient.createResponse()
        }
    }

    /** Send a screenshot image + tool result, then ask for a response. */
    fun sendToolResultWithImage(callId: String, output: String, jpeg: ByteArray) {
        if (useWebRtc) {
            webRtcClient!!.sendImage(jpeg)
            webRtcClient.sendToolResult(callId, output)
            webRtcClient.createResponse()
        } else {
            wsClient!!.sendImage(jpeg)
            wsClient.sendToolResult(callId, output)
            wsClient.createResponse()
        }
    }

    // -----------------------------------------------------------------

    /** Mic pump — only used for WebSocket mode (sends PCM frames as Base64). */
    private suspend fun micPump() {
        while (scope.isActive) {
            val frame = audio.micFrames.receive()
            if (!listening.get() || speaking.get()) continue
            val ws = wsClient ?: continue
            if (!ws.isOpen) continue

            // Run client-side VAD to gate silence
            val prob = vad.process(frame)
            if (prob >= localVadThreshold) {
                vadPadRemaining = vadPadFrames
                ws.appendAudio(frame)
            } else if (vadPadRemaining > 0) {
                // Trailing pad — keep sending a few frames after speech ends
                vadPadRemaining--
                ws.appendAudio(frame)
            }
            // else: silence frame, drop it
        }
    }

    private fun handleEvent(ev: JsonObject) {
        val t = ev["type"]?.jsonPrimitive?.content ?: return
        when (t) {
            "response.created" -> setSpeaking(true)
            "response.output_audio.delta" -> {
                // With WebRTC, audio arrives via the RTP track — no Base64 decoding needed.
                if (!useWebRtc) {
                    val b64 = ev["delta"]?.jsonPrimitive?.content ?: return
                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                    audio.enqueuePlayback(pcm)
                }
            }
            "response.output_audio_transcript.delta" -> {
                val d = ev["delta"]?.jsonPrimitive?.content ?: return
                Transcripts.appendResponseDelta(d)
                onTranscript(d)
            }
            "response.output_audio_transcript.done",
            "response.output_audio.done" -> {
                setSpeaking(false)
            }
            "response.done" -> {
                setSpeaking(false)
                // PTT: after a turn ends, fall back to muted/gated until the next press.
                // LISTENING: stay open.
                if (mode == Mode.PTT) setListening(false)
                Transcripts.endResponse()
                parseUsage(ev)?.let { usage ->
                    Log.i(TAG, "tokens: in=${usage.inputTokens} (text=${usage.inputTextTokens} audio=${usage.inputAudioTokens} cached=${usage.inputCachedTokens}) out=${usage.outputTokens} (text=${usage.outputTextTokens} audio=${usage.outputAudioTokens})")
                    onUsage(usage)
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val heard = ev["transcript"]?.jsonPrimitive?.content?.trim().orEmpty()
                Log.i(TAG, "HEARD: $heard")
                Transcripts.setHeard(heard)
                onHeard(heard)
            }
            "input_audio_buffer.speech_started" -> {
                // user is talking — barge-in: flush playback or cancel response
                if (useWebRtc) {
                    // With WebRTC, cancelling the response stops audio from the server.
                    if (speaking.get()) {
                        webRtcClient!!.cancelResponse()
                        setSpeaking(false)
                    }
                } else {
                    audio.clearPlayback()
                }
            }
            "response.function_call_arguments.done" -> {
                val name = ev["name"]?.jsonPrimitive?.content ?: return
                val callId = ev["call_id"]?.jsonPrimitive?.content ?: return
                val args = ev["arguments"]?.jsonPrimitive?.content ?: "{}"
                Log.i(TAG, "tool: $name($args)")
                onToolCall(name, callId, args)
            }
            "error" -> {
                val err = ev["error"]?.jsonObject?.toString() ?: ev.toString()
                Log.w(TAG, "realtime error: $err")
            }
        }
    }

    private fun parseUsage(ev: JsonObject): TokenUsage? {
        val usage = ev["response"]?.jsonObject?.get("usage")?.jsonObject ?: return null
        val inputDetails = usage["input_token_details"]?.jsonObject
        val outputDetails = usage["output_token_details"]?.jsonObject
        fun JsonObject.intField(key: String): Int =
            get(key)?.jsonPrimitive?.int ?: 0

        return TokenUsage(
            inputTokens = usage.intField("input_tokens"),
            outputTokens = usage.intField("output_tokens"),
            inputTextTokens = inputDetails?.intField("text_tokens") ?: 0,
            inputAudioTokens = inputDetails?.intField("audio_tokens") ?: 0,
            inputCachedTokens = inputDetails?.intField("cached_tokens") ?: 0,
            outputTextTokens = outputDetails?.intField("text_tokens") ?: 0,
            outputAudioTokens = outputDetails?.intField("audio_tokens") ?: 0,
        )
    }

    private fun turnModeFor(mode: Mode): TurnMode = when (mode) {
        Mode.OFF, Mode.PTT -> TurnMode.PUSH_TO_TALK
        Mode.LISTENING -> TurnMode.SERVER_VAD
    }

    companion object {
        private const val TAG = "SessionLoop"
    }
}
