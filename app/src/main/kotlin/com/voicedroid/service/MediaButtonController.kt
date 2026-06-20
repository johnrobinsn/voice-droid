package com.voicedroid.service

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Registers a MediaSession that captures the headset/Bluetooth play-pause key and
 * forwards it as a PTT toggle. Owned by [VoiceDroidService].
 *
 * MediaSession requires an "active" PlaybackState to receive media buttons. We publish
 * STATE_PLAYING so the routing keeps working in the background.
 */
class MediaButtonController(
    context: Context,
    private val onToggle: () -> Unit,
) {

    private val session = MediaSession(context, "VoiceDroid")

    init {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY)
                .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f, SystemClock.elapsedRealtime())
                .build(),
        )

        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val ev: KeyEvent? = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                if (ev == null || ev.action != KeyEvent.ACTION_DOWN) return false
                return when (ev.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    -> { Log.i(TAG, "media button → toggleTalk"); onToggle(); true }
                    else -> false
                }
            }

            override fun onPlay() { Log.i(TAG, "onPlay → toggleTalk"); onToggle() }
            override fun onPause() { Log.i(TAG, "onPause → toggleTalk"); onToggle() }
        })

        session.isActive = true
    }

    fun release() {
        try {
            session.isActive = false
            session.release()
        } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "MediaButton"
    }
}
