package com.jarvis.assistant.tools

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.jarvis.assistant.agent.RiskLevel
import org.json.JSONObject
import kotlin.math.roundToInt

class SetFlashlightTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "set_flashlight",
        description = "Turn the phone rear camera LED flashlight/torch on or off.",
        params = listOf(
            ToolParam("enabled", "boolean", "True to turn flashlight on, false to turn off.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val enabled = args.optBoolean("enabled", true)
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolExecutionOutput(false, "Camera service unavailable.")

        return runCatching {
            val torchCameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBack = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && isBack
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolExecutionOutput(false, "No flashlight hardware detected.")

            cm.setTorchMode(torchCameraId, enabled)
            ToolExecutionOutput(true, if (enabled) "Flashlight engaged." else "Flashlight extinguished.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Unable to toggle flashlight: ${err.message}")
        }
    }
}

class SetVolumeTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "set_volume",
        description = "Set or adjust phone volume for media, ring, alarm, notification, or call streams.",
        params = listOf(
            ToolParam("stream", "string", "Audio stream to control.", required = false, enumValues = listOf("media", "ring", "alarm", "notification", "call")),
            ToolParam("percent", "integer", "Target volume level from 0 to 100.", required = false),
            ToolParam("delta", "integer", "Relative percent adjustment (e.g. +15 or -20) if percent is not specified.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolExecutionOutput(false, "Audio service unavailable.")
        val streamName = args.optString("stream", "media").lowercase()
        val streamType = when (streamName) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }

        val maxVol = am.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val curVol = am.getStreamVolume(streamType)
        val curPct = ((curVol.toFloat() / maxVol) * 100f).roundToInt()

        val targetPct = when {
            args.has("percent") && !args.isNull("percent") -> args.optInt("percent", curPct)
            args.has("delta") && !args.isNull("delta") -> curPct + args.optInt("delta", 0)
            else -> return ToolExecutionOutput(true, "$streamName volume is currently $curPct%.")
        }.coerceIn(0, 100)

        val targetIndex = ((targetPct / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
        return runCatching {
            am.setStreamVolume(streamType, targetIndex, AudioManager.FLAG_SHOW_UI)
            ToolExecutionOutput(true, "Set $streamName volume to $targetPct%.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Could not adjust $streamName volume (check DND access): ${err.message}")
        }
    }
}

class SetRingerModeTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "set_ringer_mode",
        description = "Switch phone ringer profile between normal, vibrate, or silent.",
        params = listOf(
            ToolParam("mode", "string", "Desired ringer mode.", required = true, enumValues = listOf("normal", "vibrate", "silent"))
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolExecutionOutput(false, "Audio service unavailable.")
        val mode = args.optString("mode", "normal").lowercase()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (mode == "silent" && nm?.isNotificationPolicyAccessGranted == false) {
            return ToolExecutionOutput(false, "Silent mode requires Do Not Disturb Policy Access. Enable it in the Xiaomi Cockpit.")
        }
        val ringerVal = when (mode) {
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return runCatching {
            am.ringerMode = ringerVal
            ToolExecutionOutput(true, "Ringer profile set to $mode.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Failed to change ringer mode: ${err.message}")
        }
    }
}

class SetBrightnessTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "set_brightness",
        description = "Set screen brightness percentage (0-100) or enable adaptive/auto brightness.",
        params = listOf(
            ToolParam("percent", "integer", "Target screen brightness from 5 to 100.", required = false),
            ToolParam("auto", "boolean", "Set true to turn on adaptive auto-brightness.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        if (!Settings.System.canWrite(context)) {
            return ToolExecutionOutput(
                false,
                "Modify System Settings permission is not granted. Enable it in the Xiaomi Cockpit tab."
            )
        }
        return runCatching {
            val cr = context.contentResolver
            if (args.has("auto") && args.optBoolean("auto", false)) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return ToolExecutionOutput(true, "Adaptive screen brightness enabled.")
            }
            val pct = args.optInt("percent", 50).coerceIn(5, 100)
            val rawValue = ((pct / 100f) * 255f).roundToInt().coerceIn(10, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, rawValue)
            ToolExecutionOutput(true, "Display brightness calibrated to $pct%.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Failed to set brightness: ${err.message}")
        }
    }
}

class SetDndTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "set_dnd",
        description = "Enable or disable Do Not Disturb mode.",
        params = listOf(
            ToolParam("enabled", "boolean", "True to enable Do Not Disturb, false to disable.", required = true)
        ),
        defaultRisk = RiskLevel.SENSITIVE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ToolExecutionOutput(false, "NotificationManager unavailable.")
        if (!nm.isNotificationPolicyAccessGranted) {
            return ToolExecutionOutput(false, "Do Not Disturb Policy Access not granted. Enable it in Xiaomi Cockpit.")
        }
        val enabled = args.optBoolean("enabled", true)
        return runCatching {
            nm.setInterruptionFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            ToolExecutionOutput(true, if (enabled) "Do Not Disturb activated." else "Do Not Disturb deactivated.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Failed to update Do Not Disturb: ${err.message}")
        }
    }
}

class MediaControlTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "media_control",
        description = "Control active music/video playback (Spotify, YouTube, podcasts, etc.).",
        params = listOf(
            ToolParam("action", "string", "Media playback command.", required = true, enumValues = listOf("play", "pause", "toggle", "next", "previous", "stop"))
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolExecutionOutput(false, "Audio service unavailable.")
        val action = args.optString("action", "toggle").lowercase()
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return ToolExecutionOutput(true, "Dispatched media command: $action.")
    }
}

class OpenSystemPanelTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "open_system_panel",
        description = "Open an Android/HyperOS quick settings panel or system settings page (wifi, internet, bluetooth, volume, nfc, battery, display, location, airplane, settings).",
        params = listOf(
            ToolParam(
                "panel",
                "string",
                "Which settings panel to open.",
                required = true,
                enumValues = listOf("wifi", "internet", "bluetooth", "volume", "nfc", "battery", "display", "sound", "location", "airplane", "settings")
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val panel = args.optString("panel", "settings").lowercase()
        val action = when (panel) {
            "wifi" -> Settings.Panel.ACTION_WIFI
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume" -> Settings.Panel.ACTION_VOLUME
            "nfc" -> Settings.Panel.ACTION_NFC
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolExecutionOutput(true, "Opened $panel panel.")
        }.getOrElse {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolExecutionOutput(true, "Opened Android Settings (direct $panel panel unavailable).")
            }.getOrElse { err ->
                ToolExecutionOutput(false, "Unable to open settings panel: ${err.message}")
            }
        }
    }
}
