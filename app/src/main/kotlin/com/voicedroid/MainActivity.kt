package com.voicedroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.voicedroid.service.VoiceDroidService
import com.voicedroid.storage.Mode
import com.voicedroid.storage.Settings as AppSettings
import com.voicedroid.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The persisted mode shouldn't survive a process death or reinstall — if no
        // service is currently alive, snap mode back to OFF so the UI matches reality.
        val settings = AppSettings(this)
        if (!VoiceDroidService.connectionActive.value && settings.mode != Mode.OFF) {
            settings.mode = Mode.OFF
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }
}
