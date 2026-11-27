package com.jarvis.assistant.services

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.agent.JarvisLanguageDetector
import com.jarvis.assistant.agent.SpokenLanguage

data class NotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestampMs: Long,
    val canReply: Boolean,
    val isOngoing: Boolean
)

class JarvisNotificationListenerService : NotificationListenerService() {

    private var lastWhisperSignature: String = ""
    private var lastWhisperAtMs: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        if (instance === this) {
            instance = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing || sbn.packageName == packageName) return
        if (sbn.packageName !in WHISPER_MESSAGING_PACKAGES) return

        val app = applicationContext as? JarvisApplication ?: return
        val container = app.container
        val settings = container.settingsStore.current()
        if (!settings.bluetoothWhisperEnabled || !settings.ttsEnabled) return

        // Only whisper softly when Bluetooth earbuds/headphones are actively connected
        if (!isBluetoothAudioConnected()) return
        if (isCallOrJarvisBusy(container)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() || text.isBlank()) return
        // Ignore generic WhatsApp/Android summary notifications ("3 messages from 2 chats")
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val sig = "${sbn.packageName}|$title|$text"
        val now = System.currentTimeMillis()
        if (sig == lastWhisperSignature && now - lastWhisperAtMs < 6_000L) return
        lastWhisperSignature = sig
        lastWhisperAtMs = now

        val lang = JarvisLanguageDetector.detect(text, mode = settings.languageMode)
        val clipped = text.take(140)
        val announcement = if (lang == SpokenLanguage.GERMAN) {
            "Nachricht von $title: $clipped"
        } else {
            "Message from $title: $clipped"
        }
        Log.i(TAG, "Bluetooth whisper (${lang.name}) for ${sbn.packageName}: $title")
        container.voiceEngine.speak(
            text = announcement,
            options = container.taskCoordinator.buildSpeechOptions(settings, language = lang)
        )
    }

    private fun isBluetoothAudioConnected(): Boolean = runCatching {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputs.any { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)

    private fun isCallOrJarvisBusy(container: com.jarvis.assistant.JarvisAppContainer): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val inCall = am != null && (
            am.mode == AudioManager.MODE_IN_CALL ||
                am.mode == AudioManager.MODE_IN_COMMUNICATION ||
                am.mode == AudioManager.MODE_RINGTONE
            )
        return inCall ||
            container.taskCoordinator.overlayVisible.value ||
            container.taskCoordinator.taskActive.value ||
            container.voiceEngine.isListening.value ||
            container.voiceEngine.isSpeaking.value
    }

    fun getCurrentNotifications(includeOngoing: Boolean = false): List<NotificationItem> {
        val raw = runCatching { activeNotifications }.getOrNull() ?: return emptyList()
        val pm = packageManager
        return raw.mapNotNull { sbn ->
            if (!includeOngoing && sbn.isOngoing) return@mapNotNull null
            if (sbn.packageName == packageName) return@mapNotNull null
            val extras = sbn.notification?.extras ?: Bundle.EMPTY
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (title.isBlank() && text.isBlank()) return@mapNotNull null

            val appName = runCatching {
                val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(sbn.packageName.substringAfterLast('.'))

            NotificationItem(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = text.take(300),
                timestampMs = sbn.postTime,
                canReply = findReplyAction(sbn) != null,
                isOngoing = sbn.isOngoing
            )
        }.sortedByDescending { it.timestampMs }
    }

    fun dismissByKey(key: String): Boolean = runCatching {
        cancelNotification(key)
        true
    }.getOrDefault(false)

    fun replyToNotification(key: String, replyText: String): Pair<Boolean, String> {
        val sbn = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key }
            ?: return false to "Notification not found or already dismissed."

        val action = findReplyAction(sbn)
            ?: return false to "This notification does not support inline direct replies."
        val remoteInput = action.remoteInputs?.firstOrNull()
            ?: return false to "Notification reply action has no RemoteInput field."

        return runCatching {
            val intent = Intent()
            val results = Bundle().apply {
                putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
            action.actionIntent.send(this, 0, intent)
            true to "Sent reply via notification action."
        }.getOrElse { err ->
            false to "Failed to dispatch notification reply: ${err.message}"
        }
    }

    private fun findReplyAction(sbn: StatusBarNotification): Notification.Action? {
        val actions = sbn.notification?.actions ?: return null
        return actions.firstOrNull { action ->
            !action.remoteInputs.isNullOrEmpty() && action.actionIntent != null
        }
    }

    companion object {
        private const val TAG = "JarvisNotifWhisper"

        private val WHISPER_MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.discord",
            "com.Slack",
            "com.instagram.android",
            "com.facebook.orca"
        )

        @Volatile
        var instance: JarvisNotificationListenerService? = null
            private set

        @Volatile
        var isConnected: Boolean = false
            private set
    }
}
