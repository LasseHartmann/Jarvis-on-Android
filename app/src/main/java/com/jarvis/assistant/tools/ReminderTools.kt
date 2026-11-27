package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.agent.JarvisReminder
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.data.JarvisStore
import com.jarvis.assistant.services.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScheduleReminderTool(
    private val context: Context,
    private val store: JarvisStore
) : JarvisTool {
    override val spec = ToolSpec(
        name = "schedule_reminder",
        description = "Schedule a spoken J.A.R.V.I.S. reminder or recurring daily Morning Briefing at a specific time (hour/minute) or after delay_minutes.",
        params = listOf(
            ToolParam("title", "string", "Short reminder title (e.g. 'Call Thomas', 'Morning Briefing').", required = true),
            ToolParam("message", "string", "What Jarvis should announce when the reminder fires.", required = false),
            ToolParam("delay_minutes", "integer", "Fire after N minutes from now (e.g. 30, 120).", required = false),
            ToolParam("hour", "integer", "24-hour clock hour (0-23) if scheduling for a clock time.", required = false),
            ToolParam("minute", "integer", "Minute (0-59) if scheduling for a clock time (default 0).", required = false),
            ToolParam("repeat_daily", "boolean", "If true, repeats every day at that time.", required = false),
            ToolParam("is_briefing", "boolean", "If true, runs a live Morning Briefing (weather + calendar + notifications + battery) when triggered.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val title = args.optString("title", "").trim().ifBlank { "Jarvis Reminder" }
        val message = args.optString("message", "").trim().ifBlank { title }
        val repeatDaily = args.optBoolean("repeat_daily", false)
        val isBriefing = args.optBoolean("is_briefing", false)

        val now = System.currentTimeMillis()
        val triggerAtMs = when {
            args.has("delay_minutes") && !args.isNull("delay_minutes") && args.optInt("delay_minutes", 0) > 0 -> {
                now + args.optInt("delay_minutes") * 60_000L
            }

            args.has("hour") && !args.isNull("hour") -> {
                val h = args.optInt("hour", 8).coerceIn(0, 23)
                val m = args.optInt("minute", 0).coerceIn(0, 59)
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now + 10_000L) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
                cal.timeInMillis
            }

            else -> return ToolExecutionOutput(false, "Provide either delay_minutes or hour (0-23) to schedule a reminder.")
        }

        val id = store.addReminder(
            title = title,
            message = message,
            triggerAtMs = triggerAtMs,
            repeatDaily = repeatDaily,
            isBriefing = isBriefing
        )
        if (id <= 0L) {
            return ToolExecutionOutput(false, "Failed to persist reminder in database.")
        }

        val reminder = JarvisReminder(id, title, message, triggerAtMs, repeatDaily, isBriefing, active = true)
        ReminderScheduler.scheduleAlarm(context, reminder)

        val fmt = SimpleDateFormat("EEE, HH:mm", Locale.getDefault()).format(Date(triggerAtMs))
        val kind = if (isBriefing) "briefing" else "reminder"
        val repeatNote = if (repeatDaily) " (repeating daily)" else ""
        return ToolExecutionOutput(
            true,
            "Scheduled $kind #$id '$title' for $fmt$repeatNote.",
            JSONObject().put("id", id).put("trigger_at_ms", triggerAtMs)
        )
    }
}

class ListRemindersTool(private val store: JarvisStore) : JarvisTool {
    override val spec = ToolSpec(
        name = "list_reminders",
        description = "List all active scheduled Jarvis reminders and daily briefings.",
        params = emptyList(),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val list = store.getRemindersSnapshot()
        if (list.isEmpty()) {
            return ToolExecutionOutput(true, "No active reminders scheduled.")
        }
        val fmt = SimpleDateFormat("EEE, MMM d HH:mm", Locale.getDefault())
        val arr = JSONArray()
        val text = list.joinToString("\n") { r ->
            arr.put(JSONObject().put("id", r.id).put("title", r.title).put("time", fmt.format(Date(r.triggerAtMs))))
            val tags = buildList {
                if (r.isBriefing) add("briefing")
                if (r.repeatDaily) add("daily")
            }.joinToString(", ")
            val suffix = if (tags.isNotBlank()) " [$tags]" else ""
            "- #${r.id} ${r.title} at ${fmt.format(Date(r.triggerAtMs))}$suffix"
        }
        return ToolExecutionOutput(true, text, JSONObject().put("reminders", arr))
    }
}

class CancelReminderTool(
    private val context: Context,
    private val store: JarvisStore
) : JarvisTool {
    override val spec = ToolSpec(
        name = "cancel_reminder",
        description = "Cancel and remove a scheduled Jarvis reminder by its numeric id or title keyword.",
        params = listOf(
            ToolParam("id", "integer", "Numeric reminder ID from list_reminders.", required = false),
            ToolParam("title", "string", "Reminder title keyword if id is omitted.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val idArg = if (args.has("id") && !args.isNull("id")) args.optLong("id", -1L) else -1L
        val titleQuery = args.optString("title", "").trim()

        val target = when {
            idArg > 0L -> store.getRemindersSnapshot().firstOrNull { it.id == idArg }
            titleQuery.isNotBlank() -> store.getRemindersSnapshot().firstOrNull {
                it.title.contains(titleQuery, ignoreCase = true) || it.message.contains(titleQuery, ignoreCase = true)
            }
            else -> null
        } ?: return ToolExecutionOutput(false, "No matching active reminder found.")

        ReminderScheduler.cancelAlarm(context, target.id)
        store.deleteReminder(target.id)
        return ToolExecutionOutput(true, "Cancelled reminder #${target.id} ('${target.title}').")
    }
}
