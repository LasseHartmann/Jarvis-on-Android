package com.jarvis.assistant.services

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required by Android's VoiceInteractionServiceInfo so VoiceInteractionManagerService
 * actively binds JarvisVoiceInteractionService when selected as the phone's Default Digital Assistant.
 * Explicitly delegates to an external system recognizer to guarantee zero self-recursion.
 */
class JarvisRecognitionService : RecognitionService() {

    private var delegateRecognizer: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        runCatching { delegateRecognizer?.destroy() }
        val recognizer = createExternalDelegate()
        if (recognizer == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        delegateRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runCatching { listener.readyForSpeech(params ?: Bundle.EMPTY) }
            }

            override fun onBeginningOfSpeech() {
                runCatching { listener.beginningOfSpeech() }
            }

            override fun onRmsChanged(rmsdB: Float) {
                runCatching { listener.rmsChanged(rmsdB) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (buffer != null) runCatching { listener.bufferReceived(buffer) }
            }

            override fun onEndOfSpeech() {
                runCatching { listener.endOfSpeech() }
            }

            override fun onError(error: Int) {
                runCatching { listener.error(error) }
            }

            override fun onResults(results: Bundle?) {
                runCatching { listener.results(results ?: Bundle.EMPTY) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                runCatching { listener.partialResults(partialResults ?: Bundle.EMPTY) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(recognizerIntent)
    }

    private fun createExternalDelegate(): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            return runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }.getOrNull()
        }
        val intent = Intent(SERVICE_INTERFACE)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, 0)
        }
        val external = services
            .mapNotNull { it.serviceInfo }
            .firstOrNull { it.packageName != packageName }
            ?.let { ComponentName(it.packageName, it.name) }
            ?: return null
        return runCatching { SpeechRecognizer.createSpeechRecognizer(this, external) }.getOrNull()
    }

    override fun onCancel(listener: Callback) {
        runCatching { delegateRecognizer?.cancel() }
    }

    override fun onStopListening(listener: Callback) {
        runCatching { delegateRecognizer?.stopListening() }
    }

    override fun onDestroy() {
        runCatching { delegateRecognizer?.destroy() }
        delegateRecognizer = null
        super.onDestroy()
    }
}
