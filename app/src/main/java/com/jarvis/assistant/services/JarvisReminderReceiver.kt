package com.jarvis.assistant.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.JarvisLanguageDetector
import com.jarvis.assistant.agent.JarvisReminder
import com.jarvis.assistant.agent.SpokenLanguage
import com.jarvis.assistant.data.JarvisStore

object ReminderScheduler {
    private const val TAG = "JarvisReminder"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_TITLE = "reminder_title"
    const val EXTRA_MESSAGE = "reminder_message"
    const val EXTRA_REPEAT_DAILY = "reminder_repeat_daily"
    const val EXTRA_IS_BRIEFING = "reminder_is_briefing"

    fun scheduleAlarm(context: Context, reminder: JarvisReminder): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val pi = buildPendingIntent(context, reminder)
        val triggerAt = reminder.triggerAtMs.coerceAtLeast(System.currentTimeMillis() + 5_000L)
        return runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.i(TAG, "Scheduled reminder id=${reminder.id} '${reminder.title}' at=$triggerAt exact=$canExact daily=${reminder.repeatDaily}")
            true
        }.onFailure { err ->
            Log.w(TAG, "Failed to schedule alarm: ${err.message}")
        }.getOrDefault(false)
    }

    fun cancelAlarm(context: Context, reminderId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, JarvisReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        ) ?: return
        runCatching { am.cancel(pi) }
        runCatching { pi.cancel() }
    }

    fun rescheduleAllActive(context: Context, store: JarvisStore) {
        val now = System.currentTimeMillis()
        for (r in store.getRemindersSnapshot()) {
            if (!r.active) continue
            var next = r.triggerAtMs
            if (r.repeatDaily) {
                while (next <= now + 5_000L) {
                    next += 24L * 60L * 60L * 1_000L
                }
                if (next != r.triggerAtMs) {
                    store.updateReminderTrigger(r.id, next)
                }
                scheduleAlarm(context, r.copy(triggerAtMs = next))
            } else if (next > now) {
                scheduleAlarm(context, r)
            } else {
                store.deactivateReminder(r.id)
            }
        }
    }

    private fun buildPendingIntent(context: Context, reminder: JarvisReminder): PendingIntent {
        val intent = Intent(context, JarvisReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_TITLE, reminder.title)
            putExtra(EXTRA_MESSAGE, reminder.message)
            putExtra(EXTRA_REPEAT_DAILY, reminder.repeatDaily)
            putExtra(EXTRA_IS_BRIEFING, reminder.isBriefing)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

class JarvisReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty().ifBlank { "Jarvis Reminder" }
        val message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE).orEmpty().ifBlank { title }
        val repeatDaily = intent.getBooleanExtra(ReminderScheduler.EXTRA_REPEAT_DAILY, false)
        val isBriefing = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_BRIEFING, false)

        postReminderNotification(context, id, title, message)

        val app = context.applicationContext as? JarvisApplication ?: return
        val container = app.container

        if (id > 0) {
            if (repeatDaily) {
                val nextTrigger = System.currentTimeMillis() + 24L * 60L * 60L * 1_000L
                container.repository.updateReminderTrigger(id, nextTrigger)
                ReminderScheduler.scheduleAlarm(
                    context,
                    JarvisReminder(id, title, message, nextTrigger, repeatDaily = true, isBriefing = isBriefing)
                )
            } else {
                container.repository.deactivateReminder(id)
            }
        }

        val settings = container.settingsStore.current()
        if (isBriefing) {
            // Execute a real live Morning Briefing (weather + calendar + notifications + battery)
            container.taskCoordinator.submitSpokenDirective(
                "Give me my concise scheduled briefing: check live weather with get_weather, today's calendar events, unread notifications, and battery level."
            )
        } else if (settings.ttsEnabled) {
            val lang = JarvisLanguageDetector.detect(message, mode = settings.languageMode)
            val spoken = if (lang == SpokenLanguage.GERMAN) {
                "Erinnerung, ${settings.userTitle}: $message"
            } else {
                "Reminder, ${settings.userTitle}: $message"
            }
            container.voiceEngine.speak(
                text = spoken,
                options = container.taskCoordinator.buildSpeechOptions(settings, language = lang)
            )
        }
    }

    private fun postReminderNotification(context: Context, id: Long, title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis Reminders & Briefings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Scheduled voice reminders and daily Stark briefings"
        }
        nm.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            context,
            id.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_tile)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        runCatching { nm.notify((5000 + (id.toInt() and 0x0FFF)), notif) }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_reminders"
    }
}
