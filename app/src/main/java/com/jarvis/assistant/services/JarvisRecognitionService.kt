package com.jarvis.assistant.services

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required by Android's VoiceInteractionServiceInfo so VoiceInteractionManagerService
 * actively binds JarvisVoiceInteractionService when selected as the phone's Default Digital Assistant.
 */
class JarvisRecognitionService : RecognitionService() {

    private var delegateRecognizer: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        runCatching { delegateRecognizer?.destroy() }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
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
