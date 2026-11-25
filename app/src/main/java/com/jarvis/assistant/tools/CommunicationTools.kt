package com.jarvis.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.services.JarvisNotificationListenerService
import org.json.JSONArray
import org.json.JSONObject

class FindContactTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "find_contact",
        description = "Search the phone's address book for contacts matching a name and return their phone numbers.",
        params = listOf(
            ToolParam("query", "string", "Contact name or partial name to search.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ToolExecutionOutput(false, "Contacts permission not granted. Grant it in the Xiaomi Cockpit tab.")
        }
        val query = args.optString("query", "").trim()
        if (query.isBlank()) return ToolExecutionOutput(false, "Contact search query is blank.")

        val results = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < 10) {
                    val name = cursor.getString(0).orEmpty()
                    val num = cursor.getString(1).orEmpty()
                    if (name.isNotBlank() && num.isNotBlank()) {
                        results.add(name to num)
                    }
                }
            }
        }.onFailure { err ->
            return ToolExecutionOutput(false, "Error querying contacts: ${err.message}")
        }

        if (results.isEmpty()) {
            return ToolExecutionOutput(true, "No contacts found matching '$query'.")
        }
        val arr = JSONArray()
        val lines = results.distinct().joinToString("\n") { (name, number) ->
            arr.put(JSONObject().put("name", name).put("phone", number))
            "- $name: $number"
        }
        return ToolExecutionOutput(true, "Matching contacts:\n$lines", JSONObject().put("contacts", arr))
    }
}

class MakeCallTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "make_call",
        description = "Place a phone call (or open the dialer pre-filled) to a given phone number.",
        params = listOf(
            ToolParam("phone_number", "string", "Phone number to call (use find_contact first if you only have a name).", required = true),
            ToolParam("direct_call", "boolean", "If true and CALL_PHONE permission is granted, starts call immediately; otherwise opens dialer.", required = false)
        ),
        defaultRisk = RiskLevel.SENSITIVE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val number = args.optString("phone_number", "").trim()
        if (number.isBlank()) return ToolExecutionOutput(false, "Missing phone_number.")
        val direct = args.optBoolean("direct_call", true)
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        val action = if (direct && hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolExecutionOutput(
                true,
                if (action == Intent.ACTION_CALL) "Dialing $number now." else "Opened dialer with $number ready."
            )
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Could not initiate call: ${err.message}")
        }
    }
}

class SendSmsTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "send_sms",
        description = "Send an SMS text message to a phone number.",
        params = listOf(
            ToolParam("phone_number", "string", "Recipient phone number.", required = true),
            ToolParam("message", "string", "Text message body.", required = true)
        ),
        defaultRisk = RiskLevel.SENSITIVE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val number = args.optString("phone_number", "").trim()
        val body = args.optString("message", "").trim()
        if (number.isBlank() || body.isBlank()) {
            return ToolExecutionOutput(false, "Both phone_number and message are required.")
        }
        val hasSmsPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (hasSmsPerm) {
            return runCatching {
                val smsManager = context.getSystemService(SmsManager::class.java)
                    ?: return ToolExecutionOutput(false, "SMS service unavailable.")
                val parts = smsManager.divideMessage(body)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(number, null, body, null, null)
                }
                ToolExecutionOutput(true, "SMS transmitted to $number.")
            }.getOrElse { err ->
                ToolExecutionOutput(false, "SMS transmission failed: ${err.message}")
            }
        }

        // Graceful fallback: open pre-filled SMS composer
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionOutput(true, "Opened SMS composer addressed to $number with your message ready to send.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Could not open SMS composer: ${err.message}")
        }
    }
}

class GetNotificationsTool : JarvisTool {
    override val spec = ToolSpec(
        name = "get_notifications",
        description = "Read currently active phone notifications (messages, alerts, emails) with reply capability flags.",
        params = listOf(
            ToolParam("include_ongoing", "boolean", "Include persistent/ongoing notifications like media players.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisNotificationListenerService.instance
            ?: return ToolExecutionOutput(false, "Notification Core is offline. Grant Notification Access in Xiaomi Cockpit.")
        val includeOngoing = args.optBoolean("include_ongoing", false)
        val items = service.getCurrentNotifications(includeOngoing).take(20)
        if (items.isEmpty()) {
            return ToolExecutionOutput(true, "No unread notifications at the moment.")
        }
        val arr = JSONArray()
        val text = items.joinToString("\n") { n ->
            arr.put(
                JSONObject()
                    .put("key", n.key)
                    .put("app", n.appName)
                    .put("title", n.title)
                    .put("text", n.text)
                    .put("can_reply", n.canReply)
            )
            "- [${n.appName}] ${n.title}: ${n.text}${if (n.canReply) " (can_reply, key=${n.key})" else " (key=${n.key})"}"
        }
        return ToolExecutionOutput(true, "${items.size} active notifications:\n$text", JSONObject().put("notifications", arr))
    }
}

class DismissNotificationTool : JarvisTool {
    override val spec = ToolSpec(
        name = "dismiss_notification",
        description = "Dismiss an active notification using its key from get_notifications.",
        params = listOf(
            ToolParam("notification_key", "string", "The notification key returned by get_notifications.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisNotificationListenerService.instance
            ?: return ToolExecutionOutput(false, "Notification Core is offline.")
        val key = args.optString("notification_key", "").trim()
        if (key.isBlank()) return ToolExecutionOutput(false, "Missing notification_key.")
        val ok = service.dismissByKey(key)
        return ToolExecutionOutput(ok, if (ok) "Notification dismissed." else "Failed to dismiss notification.")
    }
}

class ReplyNotificationTool : JarvisTool {
    override val spec = ToolSpec(
        name = "reply_notification",
        description = "Send an inline direct reply to a messaging notification that supports RemoteInput.",
        params = listOf(
            ToolParam("notification_key", "string", "Notification key from get_notifications.", required = true),
            ToolParam("reply_text", "string", "Reply text to send.", required = true)
        ),
        defaultRisk = RiskLevel.SENSITIVE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisNotificationListenerService.instance
            ?: return ToolExecutionOutput(false, "Notification Core is offline.")
        val key = args.optString("notification_key", "").trim()
        val reply = args.optString("reply_text", "").trim()
        if (key.isBlank() || reply.isBlank()) {
            return ToolExecutionOutput(false, "Both notification_key and reply_text are required.")
        }
        val (ok, msg) = service.replyToNotification(key, reply)
        return ToolExecutionOutput(ok, msg)
    }
}
