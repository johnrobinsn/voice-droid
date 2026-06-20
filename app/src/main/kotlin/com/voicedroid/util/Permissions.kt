package com.voicedroid.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.voicedroid.accessibility.VoiceDroidAccessibilityService

object Permissions {

    fun canDrawOverlays(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun hasMic(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotifications(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns true if our AccessibilityService is enabled in system settings. We check the
     * raw enabled-services string rather than relying on `isAccessibilityEnabled`, which is
     * true for any AX service.
     */
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = "${ctx.packageName}/${VoiceDroidAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
