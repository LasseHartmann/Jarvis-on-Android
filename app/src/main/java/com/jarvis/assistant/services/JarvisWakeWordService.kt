package com.jarvis.assistant.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.voice.OpenWakeWordEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 100% local, zero-cloud hands-free wake-word service powered by openWakeWord ONNX
 * ("hey_jarvis_v0.1.onnx") reading raw 16 kHz PCM from AudioRecord.
 *
 * - Sends zero audio over the network while listening in standby
 * - Never disconnects on idle timeouts or network blips
 * - Immediately releases AudioRecord when "Hey Jarvis" is detected, during active Jarvis
 *   sessions, during phone/VoIP calls, or when the screen is off (when configured)
 */
class JarvisWakeWordService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wakeEngine: OpenWakeWordEngine? = null
    private var stateObserveJob: Job? = null
    private var audioLoopJob: Job? = null
    private var screenReceiverRegistered = false

    private val screenInteractive = MutableStateFlow(true)
    @Volatile
    private var lastTriggerMs = 0L

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
        stopAudioLoop()
        wakeEngine?.close()
        wakeEngine = null
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
                    stopAudioLoop()
                    stopSelf()
                    return@collectLatest
                }

                val screenAllowed = isScreenOn || !settings.wakeWordPauseScreenOff
                val allowStandby = !jarvisBusy && screenAllowed

                if (allowStandby) {
                    startAudioLoop()
                } else {
                    stopAudioLoop()
                }
            }
        }
    }

    private fun startAudioLoop() {
        if (audioLoopJob?.isActive == true) return

        audioLoopJob = serviceScope.launch(Dispatchers.Default) {
            // Brief cooldown after an overlay/TTS session so mic hardware is settled
            delay(350L)

            val engine = wakeEngine ?: runCatching {
                OpenWakeWordEngine.fromAssets(applicationContext).also { wakeEngine = it }
            }.onFailure { err ->
                Log.e(TAG, "Failed to load local openWakeWord ONNX models: ${err.message}", err)
            }.getOrNull() ?: return@launch

            engine.reset()
            val chunk = ShortArray(OpenWakeWordEngine.CHUNK_SAMPLES)
            var consecutiveAbove = 0

            while (isActive) {
                if (isPhoneCallActive()) {
                    delay(1_500L)
                    continue
                }

                val recorder = createAudioRecord()
                if (recorder == null) {
                    Log.w(TAG, "AudioRecord unavailable — retrying in 2s")
                    delay(2_000L)
                    continue
                }

                try {
                    recorder.startRecording()
                    engine.reset()
                    consecutiveAbove = 0
                    Log.i(TAG, "Local OpenWakeWord READY (16kHz ONNX 'Hey Jarvis', threshold=$DETECTION_THRESHOLD)")

                    while (isActive) {
                        if (isPhoneCallActive()) break

                        val read = readFullChunk(recorder, chunk)
                        if (read < 0) {
                            Log.w(TAG, "AudioRecord read error=$read — restarting recorder")
                            break
                        }
                        if (read < chunk.size) continue

                        val score = engine.processPcm16Chunk(chunk)
                        if (score < 0f) continue

                        if (score >= 0.25f) {
                            Log.d(TAG, "Hey Jarvis score=${"%.3f".format(score)}")
                        }

                        if (score >= DETECTION_THRESHOLD) {
                            consecutiveAbove++
                        } else {
                            consecutiveAbove = 0
                        }

                        // Trigger immediately on strong hit (>=0.52) or 2 consecutive frames >= threshold
                        if (score >= STRONG_THRESHOLD || consecutiveAbove >= 2) {
                            val now = System.currentTimeMillis()
                            if (now - lastTriggerMs >= TRIGGER_COOLDOWN_MS) {
                                lastTriggerMs = now
                                Log.i(TAG, "LOCAL WAKE WORD DETECTED! 'Hey Jarvis' score=${"%.3f".format(score)}")
                                // Stop & release AudioRecord BEFORE popping up the assistant overlay!
                                releaseRecorderQuietly(recorder)
                                engine.reset()
                                delay(40L)
                                JarvisVoiceInteractionService.triggerAssistantPopup(applicationContext)
                                return@launch
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    Log.w(TAG, "Wake-word audio loop exception: ${err.message}")
                    delay(1_000L)
                } finally {
                    releaseRecorderQuietly(recorder)
                }
            }
        }
    }

    private fun stopAudioLoop() {
        audioLoopJob?.cancel()
        audioLoopJob = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        if (!hasMicrophonePermission(this)) return null
        val minBuf = AudioRecord.getMinBufferSize(
            OpenWakeWordEngine.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(OpenWakeWordEngine.CHUNK_SAMPLES * 2)
        val bufferSize = maxOf(minBuf * 2, OpenWakeWordEngine.CHUNK_SAMPLES * 4)

        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    OpenWakeWordEngine.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            rec?.release()
        }
        return null
    }

    private fun readFullChunk(recorder: AudioRecord, dest: ShortArray): Int {
        var offset = 0
        while (offset < dest.size) {
            val n = recorder.read(dest, offset, dest.size - offset, AudioRecord.READ_BLOCKING)
            if (n < 0) return n
            if (n == 0) return offset
            offset += n
        }
        return offset
    }

    private fun releaseRecorderQuietly(recorder: AudioRecord) {
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
        runCatching { recorder.release() }
    }

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
            description = "On-device 'Hey Jarvis' wake-word detection (zero cloud audio)"
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
            .setContentText("On-device wake word · Say \"Hey Jarvis\"")
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
        private const val DETECTION_THRESHOLD = 0.38f
        private const val STRONG_THRESHOLD = 0.50f
        private const val TRIGGER_COOLDOWN_MS = 2_000L
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
