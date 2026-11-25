package com.jarvis.assistant.services

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

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
        @Volatile
        var instance: JarvisNotificationListenerService? = null
            private set

        @Volatile
        var isConnected: Boolean = false
            private set
    }
}
