package com.voicedroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.voicedroid.MainActivity
import com.voicedroid.service.realtime.WebRtcRealtimeClient
import com.voicedroid.service.vad.SileroVad
import com.voicedroid.storage.Mode
import com.voicedroid.storage.PromptStore
import com.voicedroid.storage.SecureStore
import com.voicedroid.storage.Settings as AppSettings
import com.voicedroid.tools.ToolDispatcher
import com.voicedroid.tools.ToolResult
import com.voicedroid.tools.ToolSchemas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VoiceDroidService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = AudioIO()
    private var session: SessionLoop? = null
    private var mediaButtons: MediaButtonController? = null
    private var router: AudioRouter? = null
    private var bubble: PttBubble? = null
    private var autoOffJob: Job? = null
    private var vad: SileroVad? = null
    private var webRtcClient: WebRtcRealtimeClient? = null
    private var statusJob: Job? = null

    /** Set to true to use WebRTC transport instead of WebSocket. */
    private val useWebRtc: Boolean = true

    private lateinit var settings: AppSettings
    private lateinit var secure: SecureStore
    private lateinit var dispatcher: ToolDispatcher

    private val modeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mode") onModeChanged(settings.mode)
        }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        secure = SecureStore(this)
        dispatcher = ToolDispatcher(applicationContext)
        vad = SileroVad(applicationContext)
        settings.registerListener(modeListener)
        ensureChannel()
        mediaButtons = MediaButtonController(this) {
            session?.toggleTalk()
        }
        router = AudioRouter(applicationContext)
        bubble = PttBubble(applicationContext)
        // Surface the persisted cumulative usage so the UI shows it even before the
        // first onUsage callback of this session.
        _sessionUsage.value = settings.tokenUsage
        instance = this
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_TALK -> {
                session?.toggleTalk()
                return START_STICKY
            }
        }
        val mode = settings.mode
        startForegroundForMode(mode)
        onModeChanged(mode)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed — stopping service")
        settings.mode = Mode.OFF
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        if (instance === this) instance = null
        autoOffJob?.cancel(); autoOffJob = null
        bubble?.hide()
        bubble = null
        router?.stop()
        router = null
        statusJob?.cancel(); statusJob = null
        _connectionActive.value = false
        settings.unregisterListener(modeListener)
        session?.release()
        session = null
        webRtcClient = null  // released by SessionLoop.release()
        mediaButtons?.release()
        mediaButtons = null
        vad?.release()
        vad = null
        audio.release()
        scope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------

    private fun onModeChanged(mode: Mode) {
        Log.i(TAG, "mode → $mode")
        updateNotification(mode)
        if (mode == Mode.OFF) {
            statusJob?.cancel(); statusJob = null
            _connectionActive.value = false
            session?.release(); session = null
            webRtcClient = null  // released by SessionLoop.release()
            router?.stop()
            bubble?.hide()
            audio.stop()
            autoOffJob?.cancel(); autoOffJob = null
            stopSelf()
            return
        }

        // Route output to BT > wired > speakerphone for any non-OFF mode.
        router?.start()
        scheduleAutoOff()
        // Bubble belongs to PTT only — it's the trigger. In Listening the mic is
        // already open continuously, so the bubble has no job.
        if (mode == Mode.PTT) {
            bubble?.show { session?.toggleTalk() }
        } else {
            bubble?.hide()
        }

        // Start AudioIO in both modes: WebSocket needs it for mic capture + playback;
        // WebRTC doesn't use it for transport but the channel must exist for the loop.
        audio.start()

        // (Re)build the session if needed
        if (session == null) {
            val key = secure.openAiKey
            if (key.isNullOrBlank()) {
                Log.w(TAG, "No API key set; running mic loop only")
                return
            }

            // Create the WebRTC client if needed
            val rtcClient = if (useWebRtc) {
                WebRtcRealtimeClient(key, applicationContext, settings.voice)
                    .also { webRtcClient = it }
            } else null

            val loop = SessionLoop(
                audio = audio,
                apiKey = key,
                toolsJson = ToolSchemas.tools,
                vad = vad!!,
                instructions = PromptStore(applicationContext).activeBody(),
                pttSilenceMs = settings.pttSilenceMs,
                vadThreshold = settings.vadThreshold.toDouble(),
                useWebRtc = useWebRtc,
                webRtcClient = rtcClient,
                onToolCall = { name, callId, args ->
                    scope.launch {
                        when (val result = dispatcher.dispatch(name, args)) {
                            is ToolResult.Text ->
                                session?.sendToolResult(callId, result.output)
                            is ToolResult.TextWithImage ->
                                session?.sendToolResultWithImage(callId, result.output, result.jpeg)
                        }
                    }
                },
                onUsage = { usage ->
                    val updated = _sessionUsage.value + usage
                    _sessionUsage.value = updated
                    settings.tokenUsage = updated
                },
                onStateChange = { listening, speaking ->
                    bubble?.setState(listening, speaking)
                },
            )
            session = loop
            loop.start(mode)
            startStatusPolling()
        } else {
            session?.setMode(mode)
        }
    }



    /** (Re)arm the auto-off timer when entering a non-OFF mode, if the user has it on. */
    private fun scheduleAutoOff() {
        autoOffJob?.cancel()
        if (!settings.autoOffEnabled) return
        autoOffJob = scope.launch {
            kotlinx.coroutines.delay(AppSettings.AUTO_OFF_MS)
            if (settings.mode != Mode.OFF) {
                Log.i(TAG, "auto-off timer fired after ${AppSettings.AUTO_OFF_MS}ms")
                settings.mode = Mode.OFF
                // The mode-change listener will fire onModeChanged(OFF) which tears down.
            }
        }
    }

    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                _connectionActive.value = webRtcClient?.isOpen == true ||
                    session?.let { !useWebRtc && it.mode != Mode.OFF } == true
                delay(1000)
            }
        }
    }

    // ---------------------------------------------------- notification

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Voice Droid",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active microphone / voice control"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(mode: Mode): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val talkIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceDroidService::class.java).setAction(ACTION_TOGGLE_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Droid")
            .setContentText("Mode: ${mode.name.lowercase()}")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    /* icon = */ android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_btn_speak_now,
                    ),
                    /* title = */ "Talk",
                    /* intent = */ talkIntent,
                ).build(),
            )
            .build()
    }

    private fun startForegroundForMode(mode: Mode) {
        val notif = buildNotification(mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(mode: Mode) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(mode))
    }

    companion object {
        private const val TAG = "VoiceDroidService"
        private const val CHANNEL_ID = "voice_droid_fg"
        private const val NOTIF_ID = 1
        const val ACTION_TOGGLE_TALK = "com.voicedroid.PTT_TOGGLE"

        @Volatile private var instance: VoiceDroidService? = null

        private val _connectionActive = MutableStateFlow(false)
        val connectionActive: StateFlow<Boolean> = _connectionActive

        private val _sessionUsage = MutableStateFlow(TokenUsage())
        val sessionUsage: StateFlow<TokenUsage> = _sessionUsage

        fun start(ctx: Context) {
            val intent = Intent(ctx, VoiceDroidService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VoiceDroidService::class.java))
        }

        /** Zero the cumulative token counter and persist. Safe to call from anywhere. */
        fun resetUsage(ctx: Context) {
            AppSettings(ctx).resetTokenUsage()
            _sessionUsage.value = TokenUsage()
        }

        /**
         * Push the currently active prompt's body to a running session.
         * No-op if the service or session isn't running — the next session will
         * pick the active prompt up at start time anyway.
         */
        fun updateActivePrompt(ctx: Context) {
            val body = PromptStore(ctx).activeBody()
            instance?.session?.updateInstructions(body)
        }

        /** Push the persisted PTT silence-duration to any running session. */
        fun updatePttSilence(ctx: Context) {
            val ms = AppSettings(ctx).pttSilenceMs
            instance?.session?.updatePttSilenceMs(ms)
        }

        /** Push the persisted server-VAD threshold to any running session. */
        fun updateVadThreshold(ctx: Context) {
            val t = AppSettings(ctx).vadThreshold.toDouble()
            instance?.session?.updateVadThreshold(t)
        }
    }
}
