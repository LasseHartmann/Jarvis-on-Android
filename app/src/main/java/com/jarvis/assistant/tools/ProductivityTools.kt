package com.jarvis.assistant.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.jarvis.assistant.agent.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SetAlarmTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "set_alarm",
        description = "Set a clock alarm at a specific 24-hour time (hour 0-23, minute 0-59).",
        params = listOf(
            ToolParam("hour", "integer", "Hour in 24-hour format (0-23).", required = true),
            ToolParam("minute", "integer", "Minute (0-59).", required = true),
            ToolParam("label", "string", "Optional alarm label.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", 0).coerceIn(0, 59)
        if (hour !in 0..23) return ToolExecutionOutput(false, "Invalid hour '$hour'. Use 0-23.")
        val label = args.optString("label", "J.A.R.V.I.S. Alarm").ifBlank { "J.A.R.V.I.S. Alarm" }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionOutput(true, "Alarm scheduled for %02d:%02d (%s).".format(hour, minute, label))
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Could not schedule alarm: ${err.message}")
        }
    }
}

class SetTimerTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "set_timer",
        description = "Start a countdown timer for a specified number of seconds.",
        params = listOf(
            ToolParam("seconds", "integer", "Timer duration in seconds.", required = true),
            ToolParam("label", "string", "Optional timer label.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val seconds = args.optInt("seconds", 0)
        if (seconds <= 0) return ToolExecutionOutput(false, "Timer duration must be greater than 0 seconds.")
        val label = args.optString("label", "J.A.R.V.I.S. Timer").ifBlank { "J.A.R.V.I.S. Timer" }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            val mins = seconds / 60
            val rem = seconds % 60
            val readable = if (mins > 0 && rem > 0) "${mins}m ${rem}s" else if (mins > 0) "${mins}m" else "${seconds}s"
            ToolExecutionOutput(true, "Countdown timer set for $readable ($label).")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Could not start timer: ${err.message}")
        }
    }
}

class GetCalendarEventsTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "get_calendar_events",
        description = "Read upcoming events from the phone's calendar.",
        params = listOf(
            ToolParam("days_ahead", "integer", "How many days ahead to query (default 2, max 14).", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return ToolExecutionOutput(false, "Calendar permission not granted. Grant it in the Xiaomi Cockpit tab.")
        }
        val days = args.optInt("days_ahead", 2).coerceIn(1, 14)
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 60L * 60L * 1000L
        val fmt = SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault())

        val events = mutableListOf<String>()
        val arr = JSONArray()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION
        )

        runCatching {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(now.toString())
                .appendPath(end.toString())
                .build()
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < 15) {
                    val title = cursor.getString(0).orEmpty().ifBlank { "(Untitled)" }
                    val begin = cursor.getLong(1)
                    val location = cursor.getString(2).orEmpty()
                    val dateStr = fmt.format(Date(begin))
                    arr.put(JSONObject().put("title", title).put("start", dateStr).put("location", location))
                    events.add("- $dateStr: $title${if (location.isNotBlank()) " @ $location" else ""}")
                }
            }
        }.onFailure { err ->
            return ToolExecutionOutput(false, "Failed to query calendar: ${err.message}")
        }

        return if (events.isEmpty()) {
            ToolExecutionOutput(true, "No calendar events scheduled in the next $days day(s).")
        } else {
            ToolExecutionOutput(true, "Upcoming events:\n${events.joinToString("\n")}", JSONObject().put("events", arr))
        }
    }
}

class CreateCalendarEventTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "create_calendar_event",
        description = "Schedule a new calendar event starting after a given offset in minutes from now.",
        params = listOf(
            ToolParam("title", "string", "Event title.", required = true),
            ToolParam("start_offset_minutes", "integer", "Minutes from now when the event starts (e.g. 60 for 1 hour from now).", required = true),
            ToolParam("duration_minutes", "integer", "Duration in minutes (default 60).", required = false),
            ToolParam("description", "string", "Optional notes or location.", required = false)
        ),
        defaultRisk = RiskLevel.SENSITIVE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val title = args.optString("title", "").trim()
        if (title.isBlank()) return ToolExecutionOutput(false, "Event title is required.")
        val startOffsetMin = args.optInt("start_offset_minutes", 60)
        val durationMin = args.optInt("duration_minutes", 60).coerceAtLeast(15)
        val description = args.optString("description", "")

        val startMs = System.currentTimeMillis() + startOffsetMin * 60_000L
        val endMs = startMs + durationMin * 60_000L

        val canWrite = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        if (canWrite) {
            val primaryCalId = findPrimaryCalendarId()
            if (primaryCalId != null) {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, primaryCalId)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.DTSTART, startMs)
                    put(CalendarContract.Events.DTEND, endMs)
                    put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                }
                val inserted = runCatching {
                    context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                }.getOrNull()
                if (inserted != null) {
                    return ToolExecutionOutput(true, "Added calendar event '$title' (${durationMin}m).")
                }
            }
        }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionOutput(true, "Opened Calendar composer for '$title'.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Could not create calendar event: ${err.message}")
        }
    }

    private fun findPrimaryCalendarId(): Long? = runCatching {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()
}

class ClipboardTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "clipboard_tool",
        description = "Read current text from the clipboard or copy new text to the clipboard.",
        params = listOf(
            ToolParam("action", "string", "Either 'read' or 'write'.", required = true, enumValues = listOf("read", "write")),
            ToolParam("text", "string", "Text to copy when action is 'write'.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@withContext ToolExecutionOutput(false, "Clipboard service unavailable.")
        when (args.optString("action", "read").lowercase()) {
            "write" -> {
                val text = args.optString("text", "")
                cm.setPrimaryClip(ClipData.newPlainText("J.A.R.V.I.S.", text))
                ToolExecutionOutput(true, "Copied text to clipboard (${text.length} chars).")
            }

            else -> {
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                } else ""
                if (text.isBlank()) ToolExecutionOutput(true, "Clipboard is currently empty (or hidden while app is backgrounded).")
                else ToolExecutionOutput(true, "Clipboard content: $text")
            }
        }
    }
}
