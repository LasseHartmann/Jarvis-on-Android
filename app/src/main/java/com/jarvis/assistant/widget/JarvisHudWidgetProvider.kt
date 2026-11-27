package com.jarvis.assistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.JarvisReminder
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.assist.JarvisAssistActivity
import com.jarvis.assistant.device.DeviceTelemetry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimalist pure-black AMOLED Home Screen Stark HUD Widget.
 * Shows live battery / PC Bridge status, active wake phrase + Voiceprint Lock status,
 * next scheduled reminder/briefing, and a 1-tap glowing Voice Orb button.
 */
class JarvisHudWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET ||
            intent.action == Intent.ACTION_POWER_CONNECTED ||
            intent.action == Intent.ACTION_POWER_DISCONNECTED
        ) {
            updateAllWidgets(context)
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.jarvis.assistant.ACTION_REFRESH_HUD_WIDGET"

        fun requestUpdate(context: Context) {
            updateAllWidgets(context.applicationContext)
        }

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, JarvisHudWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                updateSingleWidget(context, mgr, id)
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val app = context.applicationContext as? JarvisApplication
            val settings = app?.container?.settingsStore?.current() ?: JarvisSettings()
            val telemetry = app?.container?.telemetryMonitor?.refresh() ?: DeviceTelemetry()
            val nextReminder = app?.container?.repository?.getRemindersSnapshot()?.firstOrNull()

            val accentArgb = settings.hudColorTheme.primaryArgb
            val views = RemoteViews(context.packageName, R.layout.widget_jarvis_hud)

            views.setTextViewText(R.id.widget_status_badge, formatStatusBadge(settings, telemetry))
            views.setTextColor(R.id.widget_status_badge, accentArgb)

            views.setTextViewText(R.id.widget_wake_line, formatWakeLine(settings))
            views.setTextViewText(R.id.widget_reminder_line, formatReminderOrTelemetryLine(nextReminder, telemetry))

            // Tint orb icon to match chosen Amber / Frost / Mono finish
            views.setInt(R.id.widget_orb_icon, "setColorFilter", accentArgb)

            // Tap Orb -> launch Jarvis wake overlay straight into voice listening
            val wakeIntent = Intent(context, JarvisAssistActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            val wakePending = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 1,
                wakeIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_orb_button, wakePending)

            // Tap info area -> open Jarvis Settings app
            val settingsIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val settingsPending = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 2,
                settingsIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_info_area, settingsPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        internal fun formatStatusBadge(settings: JarvisSettings, telemetry: DeviceTelemetry): String {
            val bat = if (telemetry.isCharging) "${telemetry.batteryPercent}% ⚡" else "${telemetry.batteryPercent}%"
            val extras = buildList {
                if (settings.pcBridgeUrl.isNotBlank() && settings.pcBridgeToken.isNotBlank()) add("PC")
                if (settings.alexaVoiceMonkeyToken.isNotBlank() || settings.alexaWebhookUrl.isNotBlank()) add("Alexa")
                if (settings.homeAssistantUrl.isNotBlank() && settings.homeAssistantToken.isNotBlank()) add("HA")
            }
            return if (extras.isNotEmpty()) {
                "$bat · ${extras.joinToString("·")}"
            } else {
                "$bat · ${telemetry.networkLabel}"
            }
        }

        internal fun formatWakeLine(settings: JarvisSettings): String {
            val wakePart = if (settings.wakeWordEnabled) {
                "Say \"${settings.wakeWordModel.displayName}\""
            } else {
                "Tap orb or hold power"
            }
            val lockPart = if (settings.voiceprintLockEnabled && settings.hasEnrolledVoiceprint()) {
                " · Voiceprint ON"
            } else ""
            return "$wakePart$lockPart"
        }

        internal fun formatReminderOrTelemetryLine(
            nextReminder: JarvisReminder?,
            telemetry: DeviceTelemetry
        ): String {
            if (nextReminder != null) {
                val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(nextReminder.triggerAtMs))
                val prefix = if (nextReminder.isBriefing) "Briefing" else nextReminder.title
                return "$prefix · $fmt"
            }
            val pilot = if (telemetry.accessibilityEnabled) "Pilot online" else "Pilot standby"
            return "RAM ${telemetry.ramUsedPercent}% · $pilot"
        }
    }
}
