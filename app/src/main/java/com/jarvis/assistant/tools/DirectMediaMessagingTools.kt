package com.jarvis.assistant.tools

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.ScreenTreeSerializer
import kotlinx.coroutines.delay
import org.json.JSONObject

class SendWhatsAppTool(
    private val context: Context,
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "send_whatsapp",
        description = "Jump directly into a WhatsApp chat with a contact (by name from Contacts or phone number) with an optional pre-filled message, skipping slow app search and typing. Returns the opened WhatsApp CURRENT SCREEN so you can tap Send immediately if requested.",
        params = listOf(
            ToolParam("contact_name", "string", "Contact's name in address book (e.g. 'Thomas').", required = false),
            ToolParam("phone_number", "string", "Direct phone number if contact_name isn't used.", required = false),
            ToolParam("message", "string", "Optional message text to pre-fill in the chat.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val contactName = args.optString("contact_name", "").trim()
        val rawNumber = args.optString("phone_number", "").trim()
        val message = args.optString("message", "").trim()

        var resolvedName = contactName
        var resolvedPhone = rawNumber

        if (resolvedPhone.isBlank() && contactName.isNotBlank()) {
            val lookup = lookupFirstContactNumber(context, contactName)
            if (lookup != null) {
                resolvedName = lookup.first
                resolvedPhone = lookup.second
            } else {
                return ToolExecutionOutput(
                    false,
                    "No phone number found for '$contactName' in Contacts (ensure Contacts permission is granted, or use open_app('WhatsApp') to search inside WhatsApp)."
                )
            }
        }

        val digits = normalizeWhatsAppPhone(resolvedPhone)
        if (digits.length < 7) {
            return ToolExecutionOutput(false, "Invalid phone number '$resolvedPhone' for WhatsApp.")
        }

        prepareScreenForPilot()

        val uriStr = buildString {
            append("https://api.whatsapp.com/send?phone=").append(digits)
            if (message.isNotBlank()) {
                append("&text=").append(Uri.encode(message))
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(WHATSAPP_PACKAGE)
        }

        val opened = runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }

        if (!opened) {
            return ToolExecutionOutput(false, "WhatsApp is not installed or blocked from launching.")
        }

        val a11y = JarvisAccessibilityService.instance
        val screenAppendix = if (a11y != null) {
            delay(1_050L)
            val snap = a11y.captureScreenSnapshot()
            if (snap != null && snap.nodes.isNotEmpty()) {
                "\n\nCURRENT SCREEN:\n${ScreenTreeSerializer.formatForLlm(snap)}"
            } else ""
        } else ""

        val label = resolvedName.ifBlank { "+$digits" }
        val prefillNote = if (message.isNotBlank()) " with message pre-filled (tap Send button if user asked to send)" else ""
        return ToolExecutionOutput(
            true,
            "Opened WhatsApp chat with $label$prefillNote.$screenAppendix"
        )
    }

    private fun lookupFirstContactNumber(context: Context, query: String): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0).orEmpty()
                    val num = c.getString(1).orEmpty()
                    if (num.isNotBlank()) name to num else null
                } else null
            }
        }.getOrNull()
    }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"

        /**
         * Converts human phone numbers (e.g. "+49 176 123456", "0049 176...", "0176 123456")
         * into WhatsApp international digits without leading '+'.
         */
        fun normalizeWhatsAppPhone(raw: String, defaultCountryCode: String = "49"): String {
            val trimmed = raw.trim()
            val cleaned = trimmed.replace(Regex("[^0-9+]"), "")
            return when {
                cleaned.startsWith("+") -> cleaned.drop(1)
                cleaned.startsWith("00") -> cleaned.drop(2)
                cleaned.startsWith("0") && cleaned.length >= 8 -> defaultCountryCode + cleaned.drop(1)
                else -> cleaned
            }
        }
    }
}

class PlayMusicTool(
    private val context: Context,
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "play_music",
        description = "Directly search and start playing a song, artist, album, playlist, podcast, or video in Spotify, YouTube Music, or YouTube without manual UI clicking.",
        params = listOf(
            ToolParam("query", "string", "Song, artist, playlist, or video title (e.g. 'Daft Punk Get Lucky', 'lofi beats').", required = true),
            ToolParam(
                "app",
                "string",
                "Target media app: spotify (default for music), youtube_music, youtube, or auto.",
                required = false,
                enumValues = listOf("spotify", "youtube_music", "youtube", "auto")
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) return ToolExecutionOutput(false, "Media search query is empty.")
        val appChoice = args.optString("app", "auto").trim().lowercase()

        prepareScreenForPilot()

        val targetPkg = when (appChoice) {
            "spotify" -> PKG_SPOTIFY
            "youtube_music", "ytmusic" -> PKG_YT_MUSIC
            "youtube", "yt" -> PKG_YOUTUBE
            else -> when {
                hasPkg(PKG_SPOTIFY) -> PKG_SPOTIFY
                hasPkg(PKG_YT_MUSIC) -> PKG_YT_MUSIC
                hasPkg(PKG_YOUTUBE) -> PKG_YOUTUBE
                else -> null
            }
        }

        // Direct Android Media Play-From-Search intent (supported natively by Spotify, YT Music & YouTube)
        val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
            if (targetPkg != null && hasPkg(targetPkg)) {
                setPackage(targetPkg)
            }
        }

        val ok = runCatching {
            context.startActivity(mediaIntent)
            true
        }.getOrElse {
            // Fallback for YouTube video search or Spotify search URI
            runCatching {
                val fallbackUri = when (targetPkg) {
                    PKG_SPOTIFY -> Uri.parse("spotify:search:${Uri.encode(query)}")
                    else -> Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                }
                val fallback = Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (targetPkg != null && hasPkg(targetPkg)) setPackage(targetPkg)
                }
                context.startActivity(fallback)
                true
            }.getOrDefault(false)
        }

        val appLabel = when (targetPkg) {
            PKG_SPOTIFY -> "Spotify"
            PKG_YT_MUSIC -> "YouTube Music"
            PKG_YOUTUBE -> "YouTube"
            else -> "media player"
        }
        return if (ok) {
            ToolExecutionOutput(true, "Playing '$query' in $appLabel.")
        } else {
            ToolExecutionOutput(false, "Unable to start playback for '$query' in $appLabel.")
        }
    }

    private fun hasPkg(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    companion object {
        private const val PKG_SPOTIFY = "com.spotify.music"
        private const val PKG_YT_MUSIC = "com.google.android.apps.youtube.music"
        private const val PKG_YOUTUBE = "com.google.android.youtube"
    }
}
