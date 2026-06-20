package com.voicedroid.service.vad

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Client-side Silero VAD v5 using ONNX Runtime.
 *
 * Accepts 24 kHz PCM16 byte arrays (100 ms frames = 4800 bytes = 2400 samples),
 * downsamples to 16 kHz, processes in 512-sample chunks, and returns the max
 * speech probability across all chunks.
 *
 * Thread-safe: all mutable state is guarded by [lock].
 */
class SileroVad(context: Context) {

    private val lock = Any()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // RNN hidden state — [2, 1, 64]
    private var h: FloatArray = FloatArray(2 * 1 * 64)
    private var c: FloatArray = FloatArray(2 * 1 * 64)

    init {
        val modelBytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
        Log.i(TAG, "Silero VAD loaded (ONNX Runtime)")
    }

    /**
     * Run VAD on a 24 kHz PCM16 frame. Returns speech probability in [0, 1].
     */
    fun process(pcm16: ByteArray): Float = synchronized(lock) {
        // Decode PCM16 LE to float samples normalised to [-1, 1]
        val samples24k = decodePcm16(pcm16)
        // Downsample 24 kHz -> 16 kHz (ratio 2:3)
        val samples16k = downsample(samples24k, 24000, 16000)
        // Process in 512-sample windows; track max probability
        var maxProb = 0f
        var offset = 0
        while (offset + CHUNK_SIZE <= samples16k.size) {
            val chunk = samples16k.copyOfRange(offset, offset + CHUNK_SIZE)
            val prob = runChunk(chunk)
            if (prob > maxProb) maxProb = prob
            offset += CHUNK_SIZE
        }
        // If there are leftover samples, zero-pad to CHUNK_SIZE and process
        if (offset < samples16k.size) {
            val remaining = FloatArray(CHUNK_SIZE)
            System.arraycopy(samples16k, offset, remaining, 0, samples16k.size - offset)
            val prob = runChunk(remaining)
            if (prob > maxProb) maxProb = prob
        }
        return maxProb
    }

    /** Reset RNN state. Call when mic starts/stops. */
    fun reset(): Unit = synchronized(lock) {
        h = FloatArray(2 * 1 * 64)
        c = FloatArray(2 * 1 * 64)
    }

    /** Release ONNX session resources. */
    fun release(): Unit = synchronized(lock) {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX session", e)
        }
    }

    // ------------------------------------------------------------------

    private fun runChunk(chunk: FloatArray): Float {
        val inputShape = longArrayOf(1, CHUNK_SIZE.toLong())
        val srShape = longArrayOf(1)
        val stateShape = longArrayOf(2, 1, 64)

        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), inputShape)
        val srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000L)), srShape)
        val hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h.copyOf()), stateShape)
        val cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(c.copyOf()), stateShape)

        val inputs = mapOf(
            "input" to inputTensor,
            "sr" to srTensor,
            "h" to hTensor,
            "c" to cTensor,
        )

        val result = session.run(inputs)

        // Read outputs
        val outputTensor = result.get("output").get() as OnnxTensor
        @Suppress("UNCHECKED_CAST")
        val prob = (outputTensor.value as Array<FloatArray>)[0][0]

        val hnTensor = result.get("hn").get() as OnnxTensor
        val cnTensor = result.get("cn").get() as OnnxTensor
        h = hnTensor.floatBuffer.let { buf -> FloatArray(buf.remaining()).also { buf.get(it) } }
        c = cnTensor.floatBuffer.let { buf -> FloatArray(buf.remaining()).also { buf.get(it) } }

        // Close tensors
        inputTensor.close()
        srTensor.close()
        hTensor.close()
        cTensor.close()
        result.close()

        return prob
    }

    companion object {
        private const val TAG = "SileroVad"

        /** Silero VAD v5 chunk size at 16 kHz. */
        private const val CHUNK_SIZE = 512

        /** Decode little-endian PCM16 bytes to float samples in [-1, 1]. */
        private fun decodePcm16(pcm: ByteArray): FloatArray {
            val n = pcm.size / 2
            val out = FloatArray(n)
            for (i in 0 until n) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo  // signed 16-bit
                out[i] = sample / 32768f
            }
            return out
        }

        /**
         * Downsample via linear interpolation.
         * For 24000->16000 (ratio 3:2), each output sample i maps to position
         * i * (srcRate / dstRate) in the source.
         */
        private fun downsample(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
            if (srcRate == dstRate) return src
            val ratio = srcRate.toDouble() / dstRate
            val outLen = (src.size / ratio).toInt()
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val idx = srcPos.toInt()
                val frac = (srcPos - idx).toFloat()
                out[i] = if (idx + 1 < src.size) {
                    src[idx] * (1f - frac) + src[idx + 1] * frac
                } else {
                    src[idx]
                }
            }
            return out
        }
    }
}
