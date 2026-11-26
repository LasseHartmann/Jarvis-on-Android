package com.jarvis.assistant.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.assist.JarvisAssistActivity

class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        activeInstance = this
        Log.i(TAG, "JarvisVoiceInteractionService ready")
        // Sync hands-free "Jarvis" wake-word listener when the system assistant service binds
        runCatching {
            val app = applicationContext as? JarvisApplication ?: return
            JarvisWakeWordService.syncWithSettings(applicationContext, app.container.settingsStore.current())
        }
    }

    override fun onShutdown() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onShutdown()
    }

    companion object {
        private const val TAG = "JarvisVoiceSvc"
        const val EXTRA_INITIAL_COMMAND = "jarvis_initial_command"
        const val EXTRA_LANGUAGE_HINT = "jarvis_language_hint"

        @Volatile
        var activeInstance: JarvisVoiceInteractionService? = null
            private set

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Pops up the full-screen frosted-glass 3D J.A.R.V.I.S. overlay from anywhere
         * (even in the background on Android 14/15 + Xiaomi HyperOS) using the privileged
         * Default Digital Assistant showSession() path, with clean fallback to JarvisAssistActivity.
         */
        fun triggerAssistantPopup(
            context: Context,
            initialCommand: String? = null,
            languageHint: String? = null
        ) {
            mainHandler.post {
                val svc = activeInstance
                if (svc != null) {
                    val args = Bundle().apply {
                        if (!initialCommand.isNullOrBlank()) {
                            putString(EXTRA_INITIAL_COMMAND, initialCommand)
                        }
                        if (!languageHint.isNullOrBlank()) {
                            putString(EXTRA_LANGUAGE_HINT, languageHint)
                        }
                    }
                    val shown = runCatching {
                        svc.showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                        true
                    }.onFailure { err ->
                        Log.w(TAG, "showSession failed, falling back to JarvisAssistActivity: ${err.message}")
                    }.getOrDefault(false)
                    if (shown) {
                        Log.i(TAG, "Triggered VoiceInteractionSession popup (oneShot=${!initialCommand.isNullOrBlank()})")
                        return@post
                    }
                }

                // Fallback when Jarvis is not yet assigned as the system Default Digital Assistant
                runCatching {
                    val intent = Intent(context, JarvisAssistActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        if (!initialCommand.isNullOrBlank()) {
                            putExtra(EXTRA_INITIAL_COMMAND, initialCommand)
                        }
                        if (!languageHint.isNullOrBlank()) {
                            putExtra(EXTRA_LANGUAGE_HINT, languageHint)
                        }
                    }
                    context.startActivity(intent)
                }.onFailure { err ->
                    Log.w(TAG, "Fallback JarvisAssistActivity launch failed: ${err.message}")
                }
            }
        }
    }
}
