package com.voicedroid.service.realtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Modes that change how server-side turn detection is configured. */
enum class TurnMode {
    /** Manual: client commits the buffer and asks for response. (PTT) */
    PUSH_TO_TALK,
    /** Server VAD detects turn boundaries AND auto-replies. (Listening) */
    SERVER_VAD,
    /** Server detects + transcribes, but client decides when to fire response. (Guard) */
    GATED_VAD,
}

object SessionConfig {

    const val MODEL = "gpt-realtime-2"
    const val SAMPLE_RATE = 24_000
    const val DEFAULT_VOICE = "marin"

    /**
     * Build the session.update JSON payload.
     *
     * @param webrtc When true, omit audio format blocks — WebRTC negotiates Opus
     *               via SDP. Keep transcription and turn_detection config.
     * @param instructions Custom system prompt. Falls back to the hardcoded default.
     */
    /** Default silence window (ms) after speech before server VAD closes a PTT turn. */
    const val DEFAULT_PTT_SILENCE_MS = 2500

    /** Default server VAD speech-detection threshold (0..1). Higher = more conservative. */
    const val DEFAULT_VAD_THRESHOLD = 0.9

    fun build(
        turnMode: TurnMode,
        tools: JsonArray,
        webrtc: Boolean = false,
        instructions: String = com.voicedroid.storage.Settings.DEFAULT_SYSTEM_PROMPT,
        pttSilenceMs: Int = DEFAULT_PTT_SILENCE_MS,
        vadThreshold: Double = DEFAULT_VAD_THRESHOLD,
        voice: String = DEFAULT_VOICE,
    ): JsonObject = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("model", MODEL)
            put("instructions", instructions)
            put("output_modalities", buildJsonArray { add("audio") })
            putJsonObject("audio") {
                putJsonObject("input") {
                    if (!webrtc) {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", SAMPLE_RATE)
                        }
                    }
                    putJsonObject("transcription") {
                        put("model", "whisper-1")
                    }
                    // Server-side noise filter applied before VAD + Whisper. Massively
                    // reduces phantom transcriptions of room tone/HVAC/distant talk.
                    // far_field = aggressive (phone sitting on a desk); near_field is
                    // milder (close-talking headset).
                    putJsonObject("noise_reduction") {
                        put("type", "far_field")
                    }
                    when (turnMode) {
                        // PTT: server VAD with a longish silence window. Client gates the mic
                        // so audio only flows while the user is "talking" (between tap and
                        // VAD-detected end). Model auto-responds when VAD closes the turn.
                        TurnMode.PUSH_TO_TALK -> putJsonObject("turn_detection") {
                            put("type", "server_vad")
                            put("threshold", vadThreshold.coerceIn(0.0, 1.0))
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", pttSilenceMs.coerceIn(200, 10_000))
                            // Auto-respond on turn end; auto-cancel response when user
                            // starts talking again (voice barge-in, no retap required).
                            put("create_response", true)
                            put("interrupt_response", true)
                        }
                        TurnMode.SERVER_VAD -> putJsonObject("turn_detection") {
                            put("type", "server_vad")
                            put("threshold", vadThreshold.coerceIn(0.0, 1.0))
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 1200)
                        }
                        TurnMode.GATED_VAD -> putJsonObject("turn_detection") {
                            put("type", "semantic_vad")
                            put("eagerness", "high")
                            put("create_response", false)
                            put("interrupt_response", false)
                        }
                    }
                }
                putJsonObject("output") {
                    if (!webrtc) {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", SAMPLE_RATE)
                        }
                    }
                    put("voice", voice)
                }
            }
            put("tools", tools)
            put("tool_choice", "auto")
        }
    }
}
