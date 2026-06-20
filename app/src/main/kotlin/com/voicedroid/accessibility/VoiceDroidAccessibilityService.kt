package com.voicedroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class VoiceDroidAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AccessibilityService connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: AxOps reads the tree on demand; we don't need event-driven updates.
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "VoiceDroidAx"

        @Volatile
        var instance: VoiceDroidAccessibilityService? = null
            private set
    }
}
