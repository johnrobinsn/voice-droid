package com.voicedroid.service

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Owns mic capture and speaker playback at 24 kHz mono PCM16.
 *
 * - Mic frames are pushed to [micFrames] as ByteArrays of [FRAME_BYTES].
 * - Playback bytes can be appended via [enqueuePlayback].
 *
 * Use [start]/[stop]. AudioRecord requires RECORD_AUDIO at runtime.
 */
class AudioIO {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private val recordRef = AtomicReference<AudioRecord?>(null)
    private val trackRef = AtomicReference<AudioTrack?>(null)
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val playQueue = Channel<ByteArray>(Channel.UNLIMITED)

    /** Mic frames from the device. Each frame is ~100 ms (FRAME_BYTES at 24 kHz mono). */
    val micFrames: Channel<ByteArray> = Channel(Channel.BUFFERED)

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start() {
        if (captureJob != null) return
        val minIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_BYTES * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minIn,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            record.release()
            return
        }
        recordRef.set(record)

        val sessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
            Log.i(TAG, "AEC enabled=${aec != null}")
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
            Log.i(TAG, "NoiseSuppressor enabled=${ns != null}")
        }

        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_BYTES * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minOut)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        trackRef.set(track)

        record.startRecording()
        track.play()

        captureJob = scope.launch { captureLoop(record) }
        playbackJob = scope.launch { playbackLoop(track) }
        Log.i(TAG, "AudioIO started (mic buf=$minIn out buf=$minOut)")
    }

    fun stop() {
        captureJob?.cancel(); captureJob = null
        playbackJob?.cancel(); playbackJob = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        recordRef.getAndSet(null)?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        trackRef.getAndSet(null)?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        // Drain pending playback bytes; new frames after restart should not see stale audio.
        while (true) {
            val r = playQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
        }
        Log.i(TAG, "AudioIO stopped")
    }

    fun release() {
        stop()
        scope.cancel()
        micFrames.close()
        playQueue.close()
    }

    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        playQueue.trySend(pcm)
    }

    fun clearPlayback() {
        while (true) {
            val r = playQueue.tryReceive()
            if (r.isFailure || r.isClosed) break
        }
        trackRef.get()?.let {
            try { it.pause(); it.flush(); it.play() } catch (_: Throwable) {}
        }
    }

    private suspend fun captureLoop(record: AudioRecord) {
        val buf = ByteArray(FRAME_BYTES)
        while (scope.isActive) {
            val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue
            val frame = buf.copyOf(n)
            micFrames.trySend(frame)
        }
    }

    private suspend fun playbackLoop(track: AudioTrack) {
        for (chunk in playQueue) {
            var offset = 0
            while (offset < chunk.size && scope.isActive) {
                val written = track.write(chunk, offset, chunk.size - offset)
                if (written <= 0) break
                offset += written
            }
        }
    }

    companion object {
        private const val TAG = "AudioIO"
        const val SAMPLE_RATE = 24_000

        /** 100 ms at 24 kHz mono PCM16 = 4800 bytes. Matches voice-os BLOCK. */
        const val FRAME_BYTES = 4800

        /** Convenience: RMS amplitude in [0,1] of a PCM16 frame. */
        fun rmsLevel(pcm: ByteArray): Float {
            if (pcm.size < 2) return 0f
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val s = (hi shl 8) or lo
                sum += (s * s).toDouble()
                count++
                i += 2
            }
            val rms = sqrt(sum / count)
            return (rms / 8000.0).coerceAtMost(1.0).toFloat()
        }
    }

    @Suppress("unused") // kept for future routing (Bluetooth SCO etc.)
    fun setSpeakerphone(am: AudioManager, on: Boolean) {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = on
    }
}
