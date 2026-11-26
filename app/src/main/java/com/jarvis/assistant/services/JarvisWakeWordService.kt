package com.jarvis.assistant.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.JarvisLanguageMode
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.voice.JarvisVoiceEngine
import com.jarvis.assistant.voice.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Low-footprint standby microphone service that listens locally (on-device SpeechRecognizer)
 * for "Jarvis" / "Hey Jarvis" in both English and German and immediately pops up the
 * J.A.R.V.I.S. 3D neural overlay.
 *
 * Automatically yields the microphone whenever Jarvis's overlay is active, a task or TTS
 * is running, a phone/VoIP call is active, or the screen is off (when configured).
 */
class JarvisWakeWordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var standbyRecognizer: SpeechRecognizer? = null
    private var stateObserveJob: Job? = null
    private var screenReceiverRegistered = false

    private val screenInteractive = MutableStateFlow(true)
    private var shouldBeListening = false
    private var activeLanguageMode = JarvisLanguageMode.BILINGUAL_EN_DE
    private var activeSessionToken = 0L
    private var consecutiveErrors = 0
    private var lastTriggerMs = 0L
    private var preferOnDeviceEngine = false
    private var allowLangSwitch = true

    private var pendingRestartRunnable: Runnable? = null
    private var pendingWakeRunnable: Runnable? = null
    private var pendingOneShotCommand: String = ""
    private var detectedLangHint: String? = null
    private var wakeTriggeredInCurrentSession = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenInteractive.value = false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> screenInteractive.value = true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenInteractive.value = pm?.isInteractive ?: true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            val app = applicationContext as? JarvisApplication
            app?.container?.settingsStore?.update { it.copy(wakeWordEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasMicrophonePermission(this)) {
            Log.w(TAG, "RECORD_AUDIO not granted — stopping JarvisWakeWordService")
            stopSelf()
            return START_NOT_STICKY
        }

        val startedFg = promoteToForeground()
        if (!startedFg) {
            stopSelf()
            return START_NOT_STICKY
        }

        startObservingJarvisState()
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        stateObserveJob?.cancel()
        stateObserveJob = null
        stopStandbyRecognizer()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean {
        ensureNotificationChannel()
        val notification = buildStandbyNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.onFailure { err ->
            Log.e(TAG, "Unable to enter microphone foreground service: ${err.message}")
        }.getOrDefault(false)
    }

    private fun startObservingJarvisState() {
        if (stateObserveJob != null) return
        val app = applicationContext as? JarvisApplication ?: return
        val container = app.container

        val busyFlow = combine(
            container.taskCoordinator.overlayVisible,
            container.taskCoordinator.taskActive,
            container.voiceEngine.isListening,
            container.voiceEngine.isSpeaking
        ) { overlayVis, taskActive, activeListen, speaking ->
            overlayVis || taskActive || activeListen || speaking
        }

        stateObserveJob = serviceScope.launch {
            combine(
                container.settingsStore.settings,
                busyFlow,
                screenInteractive
            ) { settings, jarvisBusy, isScreenOn ->
                Triple(settings, jarvisBusy, isScreenOn)
            }.collectLatest { (settings, jarvisBusy, isScreenOn) ->
                if (!settings.wakeWordEnabled) {
                    stopStandbyRecognizer()
                    stopSelf()
                    return@collectLatest
                }

                activeLanguageMode = settings.languageMode
                val screenAllowed = isScreenOn || !settings.wakeWordPauseScreenOff
                val allowStandby = !jarvisBusy && screenAllowed && !isPhoneCallActive()

                if (allowStandby) {
                    if (!shouldBeListening) {
                        shouldBeListening = true
                        consecutiveErrors = 0
                        scheduleStandbyStart(delayMs = 550L)
                    }
                } else {
                    shouldBeListening = false
                    stopStandbyRecognizer()
                }
            }
        }
    }

    private fun scheduleStandbyStart(delayMs: Long) {
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            pendingRestartRunnable = null
            if (!shouldBeListening) return@Runnable
            if (isPhoneCallActive()) {
                scheduleStandbyStart(delayMs = 3_000L)
                return@Runnable
            }
            startStandbyRecognizer()
        }
        pendingRestartRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun startStandbyRecognizer() {
        destroyStandbyRecognizerQuietly()
        cancelPendingWake()
        wakeTriggeredInCurrentSession = false
        pendingOneShotCommand = ""
        detectedLangHint = null

        val token = ++activeSessionToken
        val useOnDevice = preferOnDeviceEngine &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

        val recognizer = createStandbyRecognizer(useOnDevice = useOnDevice)
        if (recognizer == null) {
            Log.w(TAG, "No standby SpeechRecognizer available")
            return
        }
        standbyRecognizer = recognizer

        val enableLangSwitch = activeLanguageMode == JarvisLanguageMode.BILINGUAL_EN_DE && allowLangSwitch

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (token != activeSessionToken) return
                consecutiveErrors = 0
                Log.i(TAG, "Standby wake-word recognizer READY (onDevice=$useOnDevice, langSwitch=$enableLangSwitch)")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onLanguageDetection(results: Bundle) {
                if (token != activeSessionToken) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val tag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                    if (!tag.isNullOrBlank()) {
                        detectedLangHint = tag
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (token != activeSessionToken || wakeTriggeredInCurrentSession) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                handlePartialTranscript(token, text)
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                if (token != activeSessionToken || wakeTriggeredInCurrentSession) return
                val text = segmentResults
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                handleFinalTranscript(token, text, keepAlive = true)
            }

            override fun onEndOfSegmentedSession() {
                if (token != activeSessionToken) return
                if (!wakeTriggeredInCurrentSession && shouldBeListening) {
                    scheduleStandbyStart(delayMs = 350L)
                }
            }

            override fun onResults(results: Bundle?) {
                if (token != activeSessionToken) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                handleFinalTranscript(token, text, keepAlive = false)
            }

            override fun onError(error: Int) {
                if (token != activeSessionToken) return
                if (wakeTriggeredInCurrentSession) return

                // If a trailing one-shot command was already captured before silence timeout, fire it cleanly!
                if (pendingWakeRunnable != null) {
                    val cmd = pendingOneShotCommand
                    cancelPendingWake()
                    triggerWakePopup(cmd)
                    return
                }

                if (!shouldBeListening) return

                val isSilentTimeout = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val restartDelay = if (isSilentTimeout) {
                    consecutiveErrors = 0
                    320L
                } else {
                    consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(5)
                    // Dynamically adapt engine & language-switch flags when a recognizer rejects a pack/mode
                    if (error == 12 || error == 13) {
                        if (useOnDevice) {
                            preferOnDeviceEngine = false
                        } else {
                            allowLangSwitch = false
                        }
                    } else if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_CLIENT) {
                        preferOnDeviceEngine = !useOnDevice
                    }
                    val backoff = when (consecutiveErrors) {
                        1 -> 500L
                        2 -> 1_200L
                        3 -> 3_000L
                        else -> 7_000L
                    }
                    Log.w(TAG, "Standby recognizer error=$error (onDevice=$useOnDevice, langSwitch=$enableLangSwitch), backing off ${backoff}ms")
                    backoff
                }
                scheduleStandbyStart(delayMs = restartDelay)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = JarvisVoiceEngine.buildSpeechIntent(
            callingPackage = packageName,
            languageMode = activeLanguageMode,
            enableLanguageSwitch = enableLangSwitch,
            completeSilenceMs = 1_650L,
            segmentedSession = false
        )

        runCatching {
            recognizer.startListening(intent)
        }.onFailure { err ->
            Log.w(TAG, "Standby startListening failed: ${err.message}")
            if (shouldBeListening) {
                scheduleStandbyStart(delayMs = 2_000L)
            }
        }
    }

    private fun handlePartialTranscript(token: Long, text: String) {
        val parsed = WakeWordDetector.parse(text)
        if (!parsed.matched) return

        pendingOneShotCommand = parsed.trailingCommand
        cancelPendingWake()

        // Standalone "Jarvis" pops up rapidly (~320ms); if user keeps talking ("Jarvis öffne WhatsApp"),
        // wait briefly for the trailing phrase to complete so we capture the full one-shot command.
        val waitMs = if (parsed.trailingCommand.isBlank()) 320L else 1_150L
        val wakeTask = Runnable {
            pendingWakeRunnable = null
            if (token != activeSessionToken || wakeTriggeredInCurrentSession) return@Runnable
            triggerWakePopup(pendingOneShotCommand)
        }
        pendingWakeRunnable = wakeTask
        mainHandler.postDelayed(wakeTask, waitMs)
    }

    private fun handleFinalTranscript(token: Long, text: String, keepAlive: Boolean) {
        if (wakeTriggeredInCurrentSession) return
        val parsed = WakeWordDetector.parse(text)
        if (parsed.matched) {
            cancelPendingWake()
            triggerWakePopup(parsed.trailingCommand)
            return
        }
        if (pendingWakeRunnable != null) {
            val cmd = pendingOneShotCommand
            cancelPendingWake()
            triggerWakePopup(cmd)
            return
        }
        if (!keepAlive && shouldBeListening) {
            scheduleStandbyStart(delayMs = 320L)
        }
    }

    private fun triggerWakePopup(trailingCommand: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < 1_800L) return
        lastTriggerMs = now
        wakeTriggeredInCurrentSession = true
        shouldBeListening = false

        val hint = detectedLangHint
        val cleanCmd = trailingCommand.trim()
        Log.i(TAG, "Wake word 'Jarvis' detected! trailing='${cleanCmd.take(40)}' langHint=$hint")

        // Release standby microphone BEFORE popping up the assistant overlay to prevent mic contention
        stopStandbyRecognizer()

        mainHandler.postDelayed({
            JarvisVoiceInteractionService.triggerAssistantPopup(
                context = applicationContext,
                initialCommand = cleanCmd.takeIf { it.isNotBlank() },
                languageHint = hint
            )
        }, 110L)
    }

    private fun stopStandbyRecognizer() {
        activeSessionToken++
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRestartRunnable = null
        cancelPendingWake()
        destroyStandbyRecognizerQuietly()
    }

    private fun cancelPendingWake() {
        pendingWakeRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingWakeRunnable = null
    }

    private fun destroyStandbyRecognizerQuietly() {
        val current = standbyRecognizer
        standbyRecognizer = null
        if (current != null) {
            runCatching { current.cancel() }
            runCatching { current.destroy() }
        }
    }

    private fun createStandbyRecognizer(useOnDevice: Boolean): SpeechRecognizer? {
        if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            return runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }.getOrNull()
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            val external = findExternalRecognitionComponent()
            if (external != null) {
                return runCatching { SpeechRecognizer.createSpeechRecognizer(this, external) }.getOrNull()
            }
            return runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
        }
        return null
    }

    private fun findExternalRecognitionComponent(): ComponentName? = runCatching {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, 0)
        }
        val candidates = services.mapNotNull { it.serviceInfo }.filter { it.packageName != packageName }
        // Prefer GoogleTTSRecognitionService (supports both EN + DE cleanly), fall back to Google QuickSearchBox or AiAi
        val preferred = candidates.firstOrNull { it.packageName == "com.google.android.tts" }
            ?: candidates.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: candidates.firstOrNull { it.packageName == "com.google.android.as" }
            ?: candidates.firstOrNull()
        preferred?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    private fun isPhoneCallActive(): Boolean = runCatching {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE
    }.getOrDefault(false)

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis Wake Word",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps hands-free 'Jarvis' wake-word detection active"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildStandbyNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisWakeWordService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_tile)
            .setContentTitle("Jarvis ready")
            .setContentText("Say \"Jarvis\" to wake (EN / DE)")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .addAction(0, "Pause", pauseIntent)
            .build()
    }

    companion object {
        private const val TAG = "JarvisWakeWord"
        private const val CHANNEL_ID = "jarvis_wake_word_standby"
        private const val NOTIFICATION_ID = 4207
        const val ACTION_PAUSE = "com.jarvis.assistant.ACTION_PAUSE_WAKE_WORD"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun hasMicrophonePermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        fun syncWithSettings(context: Context, settings: JarvisSettings) {
            val appCtx = context.applicationContext
            if (settings.wakeWordEnabled && hasMicrophonePermission(appCtx)) {
                runCatching {
                    val intent = Intent(appCtx, JarvisWakeWordService::class.java)
                    ContextCompat.startForegroundService(appCtx, intent)
                }.onFailure { err ->
                    Log.w(TAG, "Unable to start JarvisWakeWordService right now: ${err.message}")
                }
            } else {
                runCatching {
                    appCtx.stopService(Intent(appCtx, JarvisWakeWordService::class.java))
                }
            }
        }
    }
}
