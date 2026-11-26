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
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.agent.JarvisLanguageMode
import com.jarvis.assistant.agent.SpokenLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
    val preferInstantLocal: Boolean = false,
    val language: SpokenLanguage = SpokenLanguage.ENGLISH
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
    private var activeStreamSession: SpeechStreamSession? = null
    private val synthesisWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    @Volatile
    var lastDetectedLanguageTag: String? = null
        private set

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
            configureVoiceForLanguage(SpokenLanguage.ENGLISH)
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
     * Selects the highest-quality male voice for English (en-GB: gbd/rjs/gbb) or
     * German (de-DE: deb/ded/deg male voices) so J.A.R.V.I.S. keeps a refined male timbre in both languages.
     */
    private fun configureVoiceForLanguage(language: SpokenLanguage) {
        val engine = tts ?: return
        val targetLocale = language.locale
        val res = engine.setLanguage(targetLocale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (language == SpokenLanguage.ENGLISH) {
                engine.setLanguage(Locale.US)
            }
        }
        runCatching {
            val voices = engine.voices.orEmpty()
            val langCode = targetLocale.language
            val countryCode = targetLocale.country
            val best = voices
                .filter { it.locale.language.equals(langCode, ignoreCase = true) }
                .maxByOrNull { v ->
                    val name = v.name.lowercase(Locale.ROOT)
                    var score = 0
                    if (v.locale.country.equals(countryCode, ignoreCase = true)) score += 200

                    if (language == SpokenLanguage.ENGLISH) {
                        when {
                            name.contains("-gbd") -> score += 160
                            name.contains("-rjs") -> score += 150
                            name.contains("-gbb") -> score += 140
                            name.contains("male") && !name.contains("female") -> score += 110
                        }
                        if (name.contains("-gba") || name.contains("-gbc") || name.contains("-gbg") || name.contains("female")) {
                            score -= 120
                        }
                    } else {
                        // Google TTS German male voice IDs: deb, ded, deg
                        when {
                            name.contains("-deb") -> score += 160
                            name.contains("-ded") -> score += 150
                            name.contains("-deg") -> score += 140
                            name.contains("male") && !name.contains("female") -> score += 110
                        }
                        if (name.contains("-dea") || name.contains("-dec") || name.contains("-dee") || name.contains("-def") || name.contains("female")) {
                            score -= 120
                        }
                    }

                    if (!v.isNetworkConnectionRequired) score += 25
                    if (name.contains("local") || name.contains("network")) score += 10
                    score
                }
            if (best != null) {
                engine.voice = best
            }
        }
    }

    /**
     * Pipelined sentence-by-sentence streaming speech session.
     * Pre-fetches sentence N+1 over HTTP SSE while sentence N plays through a single persistent
     * AudioTrack + stateful StarkVoiceDsp.StreamingProcessor (zero clicks, minimal inter-sentence gap).
     */
    inner class SpeechStreamSession(
        private val options: JarvisSpeechOptions,
        private val onAudioStarted: (() -> Unit)?,
        private val onAllFinished: (() -> Unit)?
    ) {
        private val sentenceQueue = Channel<String>(Channel.UNLIMITED)
        private val job: Job
        @Volatile
        private var finishedCalled = false
        @Volatile
        private var audioStartedNotified = false

        init {
            job = engineScope.launch {
                _isSpeaking.value = true
                val prefetchPipe = Channel<Pair<String, Channel<DecodedPcm>>>(capacity = 2)

                // Producer: starts streaming each queued sentence up to 2 sentences ahead
                val producer = launch {
                    try {
                        for (rawSentence in sentenceQueue) {
                            val clean = sanitizeForSpeech(rawSentence)
                            if (clean.isBlank()) continue
                            val chunkChan = Channel<DecodedPcm>(capacity = 32)
                            prefetchPipe.send(clean to chunkChan)
                            launch {
                                try {
                                    streamSentenceToChannel(clean, options, chunkChan)
                                } finally {
                                    chunkChan.close()
                                }
                            }
                        }
                    } finally {
                        prefetchPipe.close()
                    }
                }

                // Consumer: plays PCM chunks seamlessly through ONE persistent AudioTrack & stateful DSP
                var track: AudioTrack? = null
                var trackRate = 0
                var dsp: StarkVoiceDsp.StreamingProcessor? = null
                var playedAnyAudio = false

                try {
                    for ((sentenceText, chunkChan) in prefetchPipe) {
                        var sentenceHadPcm = false
                        for (pcm in chunkChan) {
                            if (pcm.samples.isEmpty()) continue
                            sentenceHadPcm = true
                            playedAnyAudio = true

                            if (track == null || trackRate != pcm.sampleRateHz) {
                                drainAndReleaseTrack(track, waitDrain = track != null)
                                track = openStreamingAudioTrack(pcm.sampleRateHz)
                                trackRate = pcm.sampleRateHz
                                dsp = if (options.intercomDspEnabled) {
                                    StarkVoiceDsp.StreamingProcessor(pcm.sampleRateHz)
                                } else null
                            }

                            val processed = dsp?.processChunk(pcm.samples) ?: pcm.samples
                            if (!audioStartedNotified) {
                                audioStartedNotified = true
                                mainHandler.post { onAudioStarted?.invoke() }
                            }
                            writeSamplesToTrack(track, processed)
                        }

                        // If both Gemini TTS & offline WAV synthesis returned nothing, speak sentence directly via Android TTS
                        if (!sentenceHadPcm && ttsReady) {
                            drainAndReleaseTrack(track, waitDrain = true)
                            track = null
                            dsp = null
                            if (!audioStartedNotified) {
                                audioStartedNotified = true
                                mainHandler.post { onAudioStarted?.invoke() }
                            }
                            speakDirectTtsBlocking(sentenceText, options)
                        }
                    }
                    drainAndReleaseTrack(track, waitDrain = playedAnyAudio)
                } catch (_: Throwable) {
                    drainAndReleaseTrack(track, waitDrain = false)
                } finally {
                    producer.cancel()
                    _isSpeaking.value = false
                    if (activeStreamSession === this@SpeechStreamSession) {
                        activeStreamSession = null
                    }
                    if (!finishedCalled) {
                        finishedCalled = true
                        mainHandler.post { onAllFinished?.invoke() }
                    }
                }
            }
        }

        fun enqueueSentence(sentence: String) {
            if (sentence.isNotBlank()) {
                sentenceQueue.trySend(sentence)
            }
        }

        fun finish() {
            sentenceQueue.close()
        }

        fun cancel() {
            finishedCalled = true
            sentenceQueue.close()
            job.cancel()
        }
    }

    fun openSpeechStream(
        options: JarvisSpeechOptions,
        onAudioStarted: (() -> Unit)? = null,
        onAllFinished: (() -> Unit)? = null
    ): SpeechStreamSession {
        stopSpeaking()
        val session = SpeechStreamSession(options, onAudioStarted, onAllFinished)
        activeStreamSession = session
        return session
    }

    private suspend fun streamSentenceToChannel(
        cleanText: String,
        options: JarvisSpeechOptions,
        out: Channel<DecodedPcm>
    ) {
        if (!options.preferInstantLocal && options.neuralVoiceEnabled && options.geminiApiKey.isNotBlank()) {
            val streamed = geminiTtsClient.streamJarvisSpeech(
                apiKey = options.geminiApiKey,
                text = cleanText,
                voiceName = options.neuralVoiceName,
                language = options.language
            ) { chunk ->
                out.send(chunk)
            }
            if (streamed) return
        }

        if (!options.preferInstantLocal && ttsReady && options.intercomDspEnabled) {
            val offlinePcm = synthesizeOfflineTtsToPcm(cleanText, options.speechRate, options.pitch, options.language)
            if (offlinePcm != null && offlinePcm.samples.isNotEmpty()) {
                out.send(offlinePcm)
            }
        }
    }

    private suspend fun speakDirectTtsBlocking(cleanText: String, options: JarvisSpeechOptions) {
        val waiter = CompletableDeferred<Boolean>()
        onDirectTtsFinishedCallback = { waiter.complete(true) }
        withContext(Dispatchers.Main) {
            configureVoiceForLanguage(options.language)
            tts?.setSpeechRate(options.speechRate.coerceIn(0.6f, 1.6f))
            tts?.setPitch(options.pitch.coerceIn(0.6f, 1.4f))
            val id = "jarvis_${UUID.randomUUID().toString().take(8)}"
            val res = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, id)
            if (res != TextToSpeech.SUCCESS) {
                waiter.complete(false)
            }
        }
        withTimeoutOrNull(18_000L) { waiter.await() }
    }

    fun speak(
        text: String,
        options: JarvisSpeechOptions,
        onFinished: (() -> Unit)? = null,
        onAudioStarted: (() -> Unit)? = null
    ) {
        val clean = sanitizeForSpeech(text)
        if (clean.isBlank()) {
            mainHandler.post { onFinished?.invoke() }
            return
        }

        if (options.preferInstantLocal) {
            stopSpeaking()
            activeSpeakJob = engineScope.launch {
                _isSpeaking.value = true
                try {
                    if (ttsReady) {
                        onDirectTtsFinishedCallback = onFinished
                        withContext(Dispatchers.Main) {
                            configureVoiceForLanguage(options.language)
                            tts?.setSpeechRate(options.speechRate.coerceIn(0.6f, 1.6f))
                            tts?.setPitch(options.pitch.coerceIn(0.6f, 1.4f))
                            val id = "jarvis_${UUID.randomUUID().toString().take(8)}"
                            onAudioStarted?.invoke()
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
            return
        }

        // Streaming Tier 1 (Gemini SSE) -> Tier 2 (Offline WAV + DSP) -> Tier 3 (Direct Android Male TTS)
        val session = openSpeechStream(
            options = options,
            onAudioStarted = onAudioStarted,
            onAllFinished = onFinished
        )
        session.enqueueSentence(clean)
        session.finish()
    }

    private suspend fun synthesizeOfflineTtsToPcm(
        cleanText: String,
        speechRate: Float,
        pitch: Float,
        language: SpokenLanguage
    ): DecodedPcm? {
        val engine = tts ?: return null
        val outFile = File(appContext.cacheDir, "jarvis_tts_${UUID.randomUUID().toString().take(8)}.wav")
        val uttId = "synth_${UUID.randomUUID().toString().take(8)}"
        val waiter = CompletableDeferred<Boolean>()
        synthesisWaiters[uttId] = waiter

        val queued = withContext(Dispatchers.Main) {
            configureVoiceForLanguage(language)
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

    private fun openStreamingAudioTrack(sampleRateHz: Int): AudioTrack {
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
        track.play()
        return track
    }

    private fun writeSamplesToTrack(track: AudioTrack, samples: ShortArray) {
        var offset = 0
        val chunkSize = 2048
        while (offset < samples.size && activeAudioTrack === track) {
            val count = minOf(chunkSize, samples.size - offset)
            val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    private fun drainAndReleaseTrack(track: AudioTrack?, waitDrain: Boolean) {
        if (track == null) return
        try {
            if (waitDrain && activeAudioTrack === track) {
                Thread.sleep(130L)
            }
        } catch (_: InterruptedException) {
            // Cancelled cleanly
        } finally {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.release()
            }
            if (activeAudioTrack === track) activeAudioTrack = null
        }
    }

    fun stopSpeaking() {
        activeStreamSession?.cancel()
        activeStreamSession = null
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

    /**
     * Proactively triggers offline speech model downloads for English (en-US) and German (de-DE)
     * on Android 13+ so on-device bilingual recognition and wake-word switching work seamlessly.
     */
    fun ensureBilingualOfflineModels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) return
        mainHandler.post {
            runCatching {
                val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                val probeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                }
                probe.checkRecognitionSupport(
                    probeIntent,
                    appContext.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            val installed = recognitionSupport.installedOnDeviceLanguages.map { it.lowercase(Locale.ROOT) }.toSet()
                            val supported = recognitionSupport.supportedOnDeviceLanguages.map { it.lowercase(Locale.ROOT) }.toSet()
                            val needed = listOf("en-US", "de-DE").filter { tag ->
                                val lower = tag.lowercase(Locale.ROOT)
                                lower !in installed && lower in supported
                            }
                            for (tag in needed) {
                                Log.i(TAG, "Requesting offline speech pack download for $tag")
                                val dlIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    runCatching {
                                        probe.triggerModelDownload(
                                            dlIntent,
                                            appContext.mainExecutor,
                                            object : ModelDownloadListener {
                                                override fun onProgress(completedPercent: Int) = Unit
                                                override fun onSuccess() {
                                                    Log.i(TAG, "Offline speech model ready: $tag")
                                                }
                                                override fun onScheduled() {
                                                    Log.i(TAG, "Offline speech model download scheduled: $tag")
                                                }
                                                override fun onError(error: Int) {
                                                    Log.w(TAG, "Offline speech model download error ($error) for $tag")
                                                }
                                            }
                                        )
                                    }
                                } else {
                                    runCatching { probe.triggerModelDownload(dlIntent) }
                                }
                            }
                            runCatching { probe.destroy() }
                        }

                        override fun onError(error: Int) {
                            runCatching { probe.destroy() }
                        }
                    }
                )
            }
        }
    }

    fun startListening(
        languageMode: JarvisLanguageMode = JarvisLanguageMode.BILINGUAL_EN_DE,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopSpeaking()
        onFinalResultCallback = onFinalResult
        onErrorCallback = onError
        lastDetectedLanguageTag = null

        mainHandler.post {
            pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
            val hadExisting = speechRecognizer != null
            destroyRecognizerQuietly()

            val token = ++activeListenToken
            // On-device AiAi only handles installed offline packs; for bilingual EN+DE use GoogleTTS first
            // unless on-device packs are verified, with automatic fallback between both engines.
            val preferOnDevice = languageMode != JarvisLanguageMode.BILINGUAL_EN_DE &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

            val launchTask = Runnable {
                if (token != activeListenToken) return@Runnable
                startRecognizerAttempt(
                    token = token,
                    useOnDevice = preferOnDevice,
                    languageMode = languageMode,
                    enableLanguageSwitch = languageMode == JarvisLanguageMode.BILINGUAL_EN_DE,
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
        languageMode: JarvisLanguageMode,
        enableLanguageSwitch: Boolean,
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
        Log.i(TAG, "Starting SpeechRecognizer (onDevice=$useOnDevice, mode=$languageMode, langSwitch=$enableLanguageSwitch, attempt=$attempt)")

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

            override fun onLanguageDetection(results: Bundle) {
                if (token != activeListenToken) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val detected = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                    if (!detected.isNullOrBlank()) {
                        lastDetectedLanguageTag = detected
                        Log.i(TAG, "Detected spoken language: $detected")
                    }
                }
            }

            override fun onError(error: Int) {
                if (token != activeListenToken) return
                Log.w(TAG, "SpeechRecognizer error=$error (onDevice=$useOnDevice, langSwitch=$enableLanguageSwitch, attempt=$attempt)")

                // Automatically recover from transient Android 13-15 binder/server disconnects & missing language packs
                val recoverableErrors = setOf(
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, // 11
                    SpeechRecognizer.ERROR_CLIENT,              // 5
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,     // 8
                    12, // ERROR_LANGUAGE_NOT_SUPPORTED
                    13  // ERROR_LANGUAGE_UNAVAILABLE
                )
                if (error in recoverableErrors && attempt < 2) {
                    destroyRecognizerQuietly()
                    val isLangPackError = error == 12 || error == 13
                    val nextOnDevice = if (attempt == 0 && !isLangPackError) !useOnDevice else false
                    val nextLangSwitch = if (isLangPackError) false else enableLanguageSwitch
                    val retryTask = Runnable {
                        startRecognizerAttempt(
                            token = token,
                            useOnDevice = nextOnDevice,
                            languageMode = languageMode,
                            enableLanguageSwitch = nextLangSwitch,
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

        val intent = buildSpeechIntent(
            callingPackage = appContext.packageName,
            languageMode = languageMode,
            enableLanguageSwitch = enableLanguageSwitch,
            completeSilenceMs = 1_300L
        )
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
        // Prefer GoogleTTSRecognitionService (cloud + offline multilingual EN/DE) as external recognizer,
        // avoiding AiAi here since createOnDeviceSpeechRecognizer already targets AiAi.
        val preferred = candidates.firstOrNull { it.packageName == "com.google.android.tts" }
            ?: candidates.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: candidates.firstOrNull { it.packageName == "com.google.android.as" }
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

        fun buildSpeechIntent(
            callingPackage: String,
            languageMode: JarvisLanguageMode,
            enableLanguageSwitch: Boolean,
            completeSilenceMs: Long = 1_300L,
            segmentedSession: Boolean = false
        ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)

            when (languageMode) {
                JarvisLanguageMode.ENGLISH_ONLY -> {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                }

                JarvisLanguageMode.GERMAN_ONLY -> {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                }

                JarvisLanguageMode.BILINGUAL_EN_DE -> {
                    val primary = if (Locale.getDefault().language.equals("de", ignoreCase = true)) {
                        "de-DE"
                    } else {
                        "en-US"
                    }
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && enableLanguageSwitch) {
                        val allowed = arrayListOf("en-US", "de-DE")
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                        putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
                        putExtra(
                            RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                            RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE
                        )
                        putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
                    }
                }
            }

            if (segmentedSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
                )
            }
        }
    }
}
