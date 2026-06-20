package com.voicedroid.storage

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class SystemPrompt(
    val id: String,
    val name: String,
    val body: String,
    val isBuiltIn: Boolean = false,
)

/**
 * Manages a list of named system prompts. A single built-in "Default" prompt always
 * exists at the head of the list and cannot be edited or deleted. User prompts are
 * persisted as a JSON array in the shared preferences file.
 */
class PromptStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        migrateLegacyPromptIfPresent()
    }

    /** Built-in default + persisted user prompts, in order. */
    fun all(): List<SystemPrompt> = listOf(builtIn()) + userPrompts()

    fun byId(id: String): SystemPrompt? = all().firstOrNull { it.id == id }

    var activeId: String
        get() = prefs.getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID
        set(value) {
            prefs.edit().putString(KEY_ACTIVE, value).apply()
        }

    /** Active prompt; falls back to default if the saved id is gone. */
    fun active(): SystemPrompt = byId(activeId) ?: builtIn().also { activeId = DEFAULT_ID }

    fun activeBody(): String = active().body

    /** Add a new user prompt initialized from the built-in default. Returns it. */
    fun addNew(baseName: String = "Custom"): SystemPrompt {
        val p = SystemPrompt(
            id = UUID.randomUUID().toString(),
            name = uniqueName(baseName),
            body = Settings.DEFAULT_SYSTEM_PROMPT,
        )
        saveUserPrompts(userPrompts() + p)
        return p
    }

    /** Clone an existing prompt (built-in or user) into a new user prompt. */
    fun clone(id: String): SystemPrompt? {
        val src = byId(id) ?: return null
        val copy = SystemPrompt(
            id = UUID.randomUUID().toString(),
            name = uniqueName("${src.name} copy"),
            body = src.body,
        )
        saveUserPrompts(userPrompts() + copy)
        return copy
    }

    /** Update a user prompt's name/body. No-op on built-in. */
    fun update(id: String, name: String, body: String): Boolean {
        val list = userPrompts()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        saveUserPrompts(list.toMutableList().apply {
            this[i] = this[i].copy(name = name.ifBlank { this[i].name }, body = body)
        })
        return true
    }

    /** Delete a user prompt. If it was active, fall back to default. */
    fun delete(id: String): Boolean {
        val list = userPrompts()
        if (list.none { it.id == id }) return false
        saveUserPrompts(list.filterNot { it.id == id })
        if (activeId == id) activeId = DEFAULT_ID
        return true
    }

    // ----------------------------------------------------------------- internal

    private val listSerializer = ListSerializer(SystemPrompt.serializer())

    private fun userPrompts(): List<SystemPrompt> {
        val raw = prefs.getString(KEY_PROMPTS, null) ?: return emptyList()
        return try {
            json.decodeFromString(listSerializer, raw)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveUserPrompts(list: List<SystemPrompt>) {
        prefs.edit().putString(KEY_PROMPTS, json.encodeToString(listSerializer, list)).apply()
    }

    private fun uniqueName(base: String): String {
        val existing = all().map { it.name }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base $i" in existing) i++
        return "$base $i"
    }

    private fun builtIn() = SystemPrompt(
        id = DEFAULT_ID,
        name = "Default",
        body = Settings.DEFAULT_SYSTEM_PROMPT,
        isBuiltIn = true,
    )

    /**
     * One-shot migration of the old single `system_prompt` value into a user prompt.
     * Runs once: if a legacy value exists and we haven't yet persisted a user-prompt
     * list, we copy it in as a prompt called "Custom" and mark it active.
     */
    private fun migrateLegacyPromptIfPresent() {
        if (!prefs.contains(LEGACY_KEY) || prefs.contains(KEY_PROMPTS)) return
        val legacy = prefs.getString(LEGACY_KEY, null)
        if (!legacy.isNullOrBlank() && legacy != Settings.DEFAULT_SYSTEM_PROMPT) {
            val migrated = SystemPrompt(
                id = UUID.randomUUID().toString(),
                name = "Custom",
                body = legacy,
            )
            saveUserPrompts(listOf(migrated))
            prefs.edit().putString(KEY_ACTIVE, migrated.id).remove(LEGACY_KEY).apply()
        } else {
            prefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    companion object {
        private const val FILE_NAME = "voice_droid_settings"
        private const val KEY_PROMPTS = "prompt_list"
        private const val KEY_ACTIVE = "active_prompt_id"
        private const val LEGACY_KEY = "system_prompt"
        const val DEFAULT_ID = "default"
    }
}
