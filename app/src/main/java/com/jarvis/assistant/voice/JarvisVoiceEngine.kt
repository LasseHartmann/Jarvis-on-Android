package com.jarvis.assistant.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class JarvisSpeechOptions(
    val geminiApiKey: String = "",
    val neuralVoiceEnabled: Boolean = true,
    val neuralVoiceName: String = "Charon",
    val intercomDspEnabled: Boolean = true,
    val speechRate: Float = 1.03f,
    val pitch: Float = 0.88f,
    val preferInstantLocal: Boolean = false
)

class JarvisVoiceEngine(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val geminiTtsClient = GeminiTtsClient()

    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null
    private var activeSpeakJob: Job? = null
    private val synthesisWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private var onFinalResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onDirectTtsFinishedCallback: (() -> Unit)? = null
    private var pendingStartRunnable: Runnable? = null
    private var activeListenToken: Long = 0L

    init {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            configureBritishMaleVoice()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId?.startsWith("synth_") != true) {
                        _isSpeaking.value = true
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null && utteranceId.startsWith("synth_")) {
                        synthesisWaiters.remove(utteranceId)?.complete(true)
                    } else {
                        _isSpeaking.value = false
                        val cb = onDirectTtsFinishedCallback
                        onDirectTtsFinishedCallback = null
                        mainHandler.post { cb?.invoke() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null && utteranceId.startsWith("synth_")) {
                        synthesisWaiters.remove(utteranceId)?.complete(false)
                    } else {
                        _isSpeaking.value = false
                        val cb = onDirectTtsFinishedCallback
                        onDirectTtsFinishedCallback = null
                        mainHandler.post { cb?.invoke() }
                    }
                }
            })
        }
    }

    /**
     * Strictly prioritizes authentic British English (en-GB) MALE voices
     * (Google TTS: gbd = deep British male, rjs = crisp BBC British male, gbb = warm British male)
     * rather than falling back to female defaults.
     */
    private fun configureBritishMaleVoice() {
        val engine = tts ?: return
        val gbRes = engine.setLanguage(Locale.UK)
        if (gbRes == TextToSpeech.LANG_MISSING_DATA || gbRes == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        runCatching {
            val voices = engine.voices.orEmpty()
            val best = voices
                .filter { it.locale.language.equals("en", ignoreCase = true) }
                .maxByOrNull { v ->
                    val name = v.name.lowercase()
                    var score = 0
                    val isGb = v.locale.country.equals("GB", ignoreCase = true)
                    if (isGb) score += 200
                    // Google TTS British Male voice IDs: gbd (deep refined), rjs (classic RP), gbb (warm)
                    when {
                        name.contains("-gbd") -> score += 160
                        name.contains("-rjs") -> score += 150
                        name.contains("-gbb") -> score += 140
                        name.contains("male") && !name.contains("female") -> score += 110
                    }
                    // Explicitly penalize known Google female IDs so J.A.R.V.I.S. stays male!
                    if (name.contains("-gba") || name.contains("-gbc") || name.contains("-gbg") || name.contains("female")) {
                        score -= 120
                    }
                    // Slight bonus for offline local voice for zero latency
                    if (!v.isNetworkConnectionRequired) score += 25
                    if (name.contains("local") || name.contains("network")) score += 10
                    score
                }
            if (best != null) {
                engine.voice = best
            }
        }
    }

    fun speak(
        text: String,
        options: JarvisSpeechOptions,
        onFinished: (() -> Unit)? = null
    ) {
        val clean = sanitizeForSpeech(text)
        if (clean.isBlank()) {
            mainHandler.post { onFinished?.invoke() }
            return
        }

        stopSpeaking()
        activeSpeakJob = engineScope.launch {
            _isSpeaking.value = true
            try {
                // Tier 1: Authentic Neural J.A.R.V.I.S. Voice via Gemini TTS + Stark Helmet Intercom DSP
                // Skipped when preferInstantLocal=true (e.g. time-sensitive voice confirmation prompts)
                var pcm: DecodedPcm? = null
                if (!options.preferInstantLocal && options.neuralVoiceEnabled && options.geminiApiKey.isNotBlank()) {
                    pcm = geminiTtsClient.synthesizeJarvisSpeech(
                        apiKey = options.geminiApiKey,
                        text = clean,
                        voiceName = options.neuralVoiceName
                    )
                }

                // Tier 2: Offline British Male Android TTS synthesized into WAV + Stark Helmet Intercom DSP
                if (!options.preferInstantLocal && pcm == null && ttsReady && options.intercomDspEnabled) {
                    pcm = synthesizeOfflineTtsToPcm(clean, options.speechRate, options.pitch)
                }

                if (pcm != null && pcm.samples.isNotEmpty()) {
                    val processed = if (options.intercomDspEnabled) {
                        StarkVoiceDsp.processIntercom(pcm.samples, pcm.sampleRateHz)
                    } else {
                        pcm.samples
                    }
                    playPcmBlocking(processed, pcm.sampleRateHz)
                    _isSpeaking.value = false
                    mainHandler.post { onFinished?.invoke() }
                    return@launch
                }

                // Tier 3: Direct Android British Male TTS fallback
                if (ttsReady) {
                    onDirectTtsFinishedCallback = onFinished
                    withContext(Dispatchers.Main) {
                        configureBritishMaleVoice()
                        tts?.setSpeechRate(options.speechRate.coerceIn(0.6f, 1.6f))
                        tts?.setPitch(options.pitch.coerceIn(0.6f, 1.4f))
                        val id = "jarvis_${UUID.randomUUID().toString().take(8)}"
                        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
                    }
                } else {
                    _isSpeaking.value = false
                    mainHandler.post { onFinished?.invoke() }
                }
            } catch (_: Throwable) {
                _isSpeaking.value = false
                mainHandler.post { onFinished?.invoke() }
            }
        }
    }

    private suspend fun synthesizeOfflineTtsToPcm(
        cleanText: String,
        speechRate: Float,
        pitch: Float
    ): DecodedPcm? {
        val engine = tts ?: return null
        val outFile = File(appContext.cacheDir, "jarvis_tts_${UUID.randomUUID().toString().take(8)}.wav")
        val uttId = "synth_${UUID.randomUUID().toString().take(8)}"
        val waiter = CompletableDeferred<Boolean>()
        synthesisWaiters[uttId] = waiter

        val queued = withContext(Dispatchers.Main) {
            configureBritishMaleVoice()
            engine.setSpeechRate(speechRate.coerceIn(0.6f, 1.6f))
            engine.setPitch(pitch.coerceIn(0.6f, 1.4f))
            engine.synthesizeToFile(cleanText, Bundle(), outFile, uttId) == TextToSpeech.SUCCESS
        }
        if (!queued) {
            synthesisWaiters.remove(uttId)
            outFile.delete()
            return null
        }

        val ok = withTimeoutOrNull(7_000L) { waiter.await() } ?: false
        synthesisWaiters.remove(uttId)
        val decoded = if (ok && outFile.exists() && outFile.length() > 44L) {
            runCatching { StarkVoiceDsp.decodeWavFile(outFile.readBytes()) }.getOrNull()
        } else null
        runCatching { outFile.delete() }
        return decoded
    }

    private fun playPcmBlocking(samples: ShortArray, sampleRateHz: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeAudioTrack = track
        try {
            track.play()
            var offset = 0
            val chunkSize = 2048
            while (offset < samples.size && activeAudioTrack === track) {
                val count = minOf(chunkSize, samples.size - offset)
                val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            // Allow final audio buffer to drain cleanly (~120ms)
            if (activeAudioTrack === track) {
                Thread.sleep(140L)
            }
        } finally {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.release()
            }
            if (activeAudioTrack === track) activeAudioTrack = null
        }
    }

    fun stopSpeaking() {
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        onDirectTtsFinishedCallback = null
        val track = activeAudioTrack
        activeAudioTrack = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
        }
        mainHandler.post {
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    fun startListening(
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopSpeaking()
        onFinalResultCallback = onFinalResult
        onErrorCallback = onError

        mainHandler.post {
            pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
            val hadExisting = speechRecognizer != null
            destroyRecognizerQuietly()

            val token = ++activeListenToken
            val preferOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

            val launchTask = Runnable {
                if (token != activeListenToken) return@Runnable
                startRecognizerAttempt(
                    token = token,
                    useOnDevice = preferOnDevice,
                    attempt = 0
                )
            }
            pendingStartRunnable = launchTask
            // Brief binder cooldown if we just tore down a previous recognizer instance
            if (hadExisting) {
                mainHandler.postDelayed(launchTask, 140L)
            } else {
                launchTask.run()
            }
        }
    }

    private fun startRecognizerAttempt(
        token: Long,
        useOnDevice: Boolean,
        attempt: Int
    ) {
        if (token != activeListenToken) return

        val recognizer = createRecognizerInstance(useOnDevice)
        if (recognizer == null) {
            _isListening.value = false
            onErrorCallback?.invoke("No speech recognition engine available on this device.")
            return
        }

        speechRecognizer = recognizer
        Log.i(TAG, "Starting SpeechRecognizer (onDevice=$useOnDevice, attempt=$attempt)")

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (token != activeListenToken) return
                _isListening.value = true
                _partialTranscript.value = ""
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (token != activeListenToken) return
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _rmsLevel.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _rmsLevel.value = 0f
            }

            override fun onError(error: Int) {
                if (token != activeListenToken) return
                Log.w(TAG, "SpeechRecognizer error=$error (onDevice=$useOnDevice, attempt=$attempt)")

                // Automatically recover from transient Android 13-15 binder/server disconnects & language pack gaps
                val recoverableErrors = setOf(
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, // 11
                    SpeechRecognizer.ERROR_CLIENT,              // 5
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,     // 8
                    12, // ERROR_LANGUAGE_NOT_SUPPORTED
                    13  // ERROR_LANGUAGE_UNAVAILABLE
                )
                if (error in recoverableErrors && attempt < 2) {
                    destroyRecognizerQuietly()
                    val nextOnDevice = if (attempt == 0) !useOnDevice else false
                    val retryTask = Runnable {
                        startRecognizerAttempt(
                            token = token,
                            useOnDevice = nextOnDevice,
                            attempt = attempt + 1
                        )
                    }
                    pendingStartRunnable = retryTask
                    mainHandler.postDelayed(retryTask, 220L)
                    return
                }

                _isListening.value = false
                _rmsLevel.value = 0f
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network unavailable."
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected — tap orb to retry."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy — tap orb to retry."
                    else -> "Speech recognizer error ($error)."
                }
                onErrorCallback?.invoke(msg)
            }

            override fun onResults(results: Bundle?) {
                if (token != activeListenToken) return
                _isListening.value = false
                _rmsLevel.value = 0f
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim().orEmpty()
                _partialTranscript.value = ""
                if (best.isNotBlank()) {
                    onFinalResultCallback?.invoke(best)
                } else {
                    onErrorCallback?.invoke("Didn't catch that.")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (token != activeListenToken) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    _partialTranscript.value = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        }
        _isListening.value = true
        runCatching {
            recognizer.startListening(intent)
        }.onFailure { err ->
            Log.e(TAG, "startListening threw: ${err.message}")
            _isListening.value = false
            onErrorCallback?.invoke("Unable to start speech recognizer.")
        }
    }

    private fun createRecognizerInstance(useOnDevice: Boolean): SpeechRecognizer? {
        if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            return runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }.getOrNull()
        }
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            // Explicitly prefer an external Google/system RecognitionService so we never bind ourselves
            val external = findExternalRecognitionComponent()
            if (external != null) {
                return runCatching { SpeechRecognizer.createSpeechRecognizer(appContext, external) }.getOrNull()
            }
            return runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
        }
        return null
    }

    private fun findExternalRecognitionComponent(): ComponentName? = runCatching {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentServices(intent, 0)
        }
        val candidates = services
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != appContext.packageName }
        val preferred = candidates.firstOrNull { it.packageName == "com.google.android.as" }
            ?: candidates.firstOrNull { it.packageName == "com.google.android.tts" }
            ?: candidates.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: candidates.firstOrNull()
        preferred?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    private fun destroyRecognizerQuietly() {
        val current = speechRecognizer
        speechRecognizer = null
        if (current != null) {
            runCatching { current.cancel() }
            runCatching { current.destroy() }
        }
    }

    fun stopListening() {
        mainHandler.post {
            activeListenToken++
            pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingStartRunnable = null
            runCatching { speechRecognizer?.stopListening() }
            destroyRecognizerQuietly()
            _isListening.value = false
            _rmsLevel.value = 0f
        }
    }

    fun shutdown() {
        stopSpeaking()
        engineScope.cancel()
        mainHandler.post {
            activeListenToken++
            pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingStartRunnable = null
            destroyRecognizerQuietly()
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }

    private fun sanitizeForSpeech(raw: String): String =
        raw.replace(Regex("[*`#_~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)

    companion object {
        private const val TAG = "JarvisVoice"
    }
}
