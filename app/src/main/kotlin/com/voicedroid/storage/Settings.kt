package com.voicedroid.storage

import android.content.Context
import android.content.SharedPreferences
import com.voicedroid.service.TokenUsage

/** Plain (unencrypted) preferences for non-secret settings like the active mode. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var mode: Mode
        get() = Mode.fromName(prefs.getString(KEY_MODE, Mode.OFF.name))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var debugExpanded: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_EXPANDED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_EXPANDED, value).apply()

    /** PTT end-of-turn silence window in milliseconds. Clamped on use. */
    var pttSilenceMs: Int
        get() = prefs.getInt(KEY_PTT_SILENCE_MS, DEFAULT_PTT_SILENCE_MS)
        set(value) = prefs.edit().putInt(KEY_PTT_SILENCE_MS, value.coerceIn(200, 10_000)).apply()

    /**
     * Server-side VAD speech-detection threshold (0..1). Higher = more conservative
     * (less likely to false-trigger on keyboard taps / fans / distant chatter).
     * Applied to both PTT and Listening modes.
     */
    var vadThreshold: Float
        get() = prefs.getFloat(KEY_VAD_THRESHOLD, DEFAULT_VAD_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_VAD_THRESHOLD, value.coerceIn(0f, 1f)).apply()

    /** OpenAI realtime voice id. */
    var voice: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value.ifBlank { DEFAULT_VOICE }).apply()

    /** Auto-turn-off after [AUTO_OFF_MS]. Default on. */
    var autoOffEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_OFF, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_OFF, value).apply()

    /**
     * Cumulative token usage across all sessions. Persists across mode toggles, service
     * restarts, and app restarts. Reset via [resetTokenUsage].
     */
    var tokenUsage: TokenUsage
        get() = TokenUsage(
            inputTokens = prefs.getInt(KEY_USAGE_IN, 0),
            outputTokens = prefs.getInt(KEY_USAGE_OUT, 0),
            inputTextTokens = prefs.getInt(KEY_USAGE_IN_TEXT, 0),
            inputAudioTokens = prefs.getInt(KEY_USAGE_IN_AUDIO, 0),
            inputCachedTokens = prefs.getInt(KEY_USAGE_IN_CACHED, 0),
            outputTextTokens = prefs.getInt(KEY_USAGE_OUT_TEXT, 0),
            outputAudioTokens = prefs.getInt(KEY_USAGE_OUT_AUDIO, 0),
        )
        set(value) {
            prefs.edit()
                .putInt(KEY_USAGE_IN, value.inputTokens)
                .putInt(KEY_USAGE_OUT, value.outputTokens)
                .putInt(KEY_USAGE_IN_TEXT, value.inputTextTokens)
                .putInt(KEY_USAGE_IN_AUDIO, value.inputAudioTokens)
                .putInt(KEY_USAGE_IN_CACHED, value.inputCachedTokens)
                .putInt(KEY_USAGE_OUT_TEXT, value.outputTextTokens)
                .putInt(KEY_USAGE_OUT_AUDIO, value.outputAudioTokens)
                .apply()
        }

    fun resetTokenUsage() {
        tokenUsage = TokenUsage()
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        private const val FILE_NAME = "voice_droid_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_DEBUG_EXPANDED = "debug_expanded"
        private const val KEY_PTT_SILENCE_MS = "ptt_silence_ms"
        private const val KEY_VAD_THRESHOLD = "vad_threshold"
        private const val KEY_VOICE = "voice"
        private const val KEY_AUTO_OFF = "auto_off"
        const val DEFAULT_PTT_SILENCE_MS = 2500
        const val DEFAULT_VAD_THRESHOLD = 0.9f
        const val DEFAULT_VOICE = "marin"
        /** How long to wait before auto-shutting-off a Listening/PTT session. */
        const val AUTO_OFF_MS = 60L * 60 * 1000  // 1 hour

        /** Voices accepted by OpenAI's realtime model. */
        val AVAILABLE_VOICES = listOf(
            "alloy", "ash", "ballad", "cedar", "coral", "echo",
            "marin", "sage", "shimmer", "verse",
        )
        private const val KEY_USAGE_IN = "usage_input_tokens"
        private const val KEY_USAGE_OUT = "usage_output_tokens"
        private const val KEY_USAGE_IN_TEXT = "usage_input_text_tokens"
        private const val KEY_USAGE_IN_AUDIO = "usage_input_audio_tokens"
        private const val KEY_USAGE_IN_CACHED = "usage_input_cached_tokens"
        private const val KEY_USAGE_OUT_TEXT = "usage_output_text_tokens"
        private const val KEY_USAGE_OUT_AUDIO = "usage_output_audio_tokens"
        const val DEFAULT_SYSTEM_PROMPT =
            "You are the voice operating system for an Android phone. The user speaks commands " +
            "to control the device. Call exactly one matching tool, then give a short, natural " +
            "spoken confirmation. Only call a tool when the command is clear; if it's a fragment " +
            "or noise, ignore it. Prefer the highest-level tool that matches (e.g. launch_app " +
            "before tap_text). Keep replies brief.\n" +
            "Tap selection rules (FOLLOW STRICTLY):\n" +
            "  1. If the target has its OWN visible text or content description, use tap_text.\n" +
            "  2. If the target is an icon-only button (heart, like, repost, bookmark, share, " +
            "profile picture, comment, etc.) sitting NEAR a visible text label, use " +
            "tap_near_text with that label as the anchor and a pixel offset. " +
            "Example: in X/Twitter, the heart icon sits roughly 60px LEFT of its like-count " +
            "text — tap_near_text(anchor_text='59.4K', dx=-60, dy=0). " +
            "The action row in X is heart | reply | repost | views | bookmark | share, each " +
            "with a count to its right; offset from the count to hit the icon.\n" +
            "  3. ONLY use tap_xy as a last resort when no nearby text anchor exists. " +
            "Absolute pixel coordinates from a screenshot are unreliable — prefer the two " +
            "options above.\n" +
            "You have vision capability via the screenshot tool — do not refuse to take " +
            "screenshots. Use them to find anchors and judge offsets."
    }
}
