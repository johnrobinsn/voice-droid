package com.voicedroid.service

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the live model reply transcript and the most recently HEARD user utterance,
 * so [DebugRecorder] can attach them to action records.
 *
 *  - [appendResponseDelta] is called for every `response.output_audio_transcript.delta`.
 *  - [endResponse] is called on `response.done` — moves the current builder into
 *    `lastResponse` and resets the live one.
 *  - [setHeard] records the last user utterance from
 *    `conversation.item.input_audio_transcription.completed`.
 *
 *  Action records snapshot via [snapshot]: the live builder (mid-response, before
 *  it's been moved) or `lastResponse` (after response.done) — whichever has content.
 */
object Transcripts {

    private val responseBuilder = StringBuilder()
    private val lastResponseRef = AtomicReference("")
    private val lastHeardRef = AtomicReference("")

    @Synchronized
    fun appendResponseDelta(delta: String) {
        responseBuilder.append(delta)
    }

    @Synchronized
    fun endResponse() {
        lastResponseRef.set(responseBuilder.toString().trim())
        responseBuilder.setLength(0)
    }

    fun setHeard(text: String) {
        lastHeardRef.set(text)
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val live = responseBuilder.toString().trim()
        val response = if (live.isNotEmpty()) live else lastResponseRef.get()
        return Snapshot(
            lastHeard = lastHeardRef.get(),
            modelResponse = response,
        )
    }

    fun clear() {
        synchronized(this) {
            responseBuilder.setLength(0)
            lastResponseRef.set("")
        }
        lastHeardRef.set("")
    }

    data class Snapshot(val lastHeard: String, val modelResponse: String)
}
