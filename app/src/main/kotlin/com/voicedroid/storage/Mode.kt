package com.voicedroid.storage

enum class Mode {
    OFF,
    LISTENING,
    PTT;

    companion object {
        fun fromName(name: String?): Mode = entries.firstOrNull { it.name == name } ?: OFF
    }
}
