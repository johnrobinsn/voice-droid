package com.voicedroid.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wraps EncryptedSharedPreferences for the OpenAI API key. The key is encrypted
 * at rest with a hardware-backed master key (AES-256 GCM).
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var openAiKey: String?
        get() = prefs.getString(KEY_OPENAI, null)
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_OPENAI) else putString(KEY_OPENAI, value)
        }.apply()

    fun hasOpenAiKey(): Boolean = !openAiKey.isNullOrBlank()

    companion object {
        private const val FILE_NAME = "voice_droid_secure"
        private const val KEY_OPENAI = "openai_api_key"
    }
}
