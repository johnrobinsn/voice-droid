package com.voicedroid.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Picks an output device for the live voice session. Priority:
 *
 *   1. BT LE / SCO / A2DP headset
 *   2. Wired or USB headset
 *   3. **Built-in speakerphone** (deliberately, not the earpiece — Voice Droid is hands-free)
 *
 * Re-routes when devices are added or removed. Released cleanly on [stop] so other
 * apps get the system back to normal audio mode.
 */
class AudioRouter(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var deviceCallback: AudioDeviceCallback? = null
    private var active = false

    fun start() {
        if (active) return
        active = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applyPreferredRoute()

        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                Log.i(TAG, "devices added (${addedDevices.size})")
                applyPreferredRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                Log.i(TAG, "devices removed (${removedDevices.size})")
                applyPreferredRoute()
            }
        }
        am.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun stop() {
        if (!active) return
        active = false
        deviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { am.clearCommunicationDevice() } catch (_: Throwable) {}
        } else {
            try {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            } catch (_: Throwable) {}
        }
        am.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "stopped")
    }

    private fun applyPreferredRoute() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val candidates = am.availableCommunicationDevices
            val pick = pickByType(candidates) ?: return
            val ok = am.setCommunicationDevice(pick)
            Log.i(TAG, "route -> ${describe(pick)} ok=$ok")
        } else {
            routeLegacy()
        }
    }

    private fun pickByType(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val priority = intArrayOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        for (type in priority) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return devices.firstOrNull()
    }

    private fun routeLegacy() {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBt = outs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        val hasWired = outs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        @Suppress("DEPRECATION")
        when {
            hasBt -> {
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                Log.i(TAG, "legacy route -> BT SCO")
            }
            hasWired -> {
                am.stopBluetoothSco()
                am.isSpeakerphoneOn = false
                Log.i(TAG, "legacy route -> wired")
            }
            else -> {
                am.stopBluetoothSco()
                am.isSpeakerphoneOn = true
                Log.i(TAG, "legacy route -> speakerphone")
            }
        }
    }

    private fun describe(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speakerphone"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        else -> "type=${d.type}"
    }

    companion object {
        private const val TAG = "AudioRouter"
    }
}
