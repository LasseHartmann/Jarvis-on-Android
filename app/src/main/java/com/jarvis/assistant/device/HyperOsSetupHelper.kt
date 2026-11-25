package com.jarvis.assistant.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.JarvisNotificationListenerService

object HyperOsSetupHelper {

    fun isXiaomiDevice(): Boolean {
        val mfr = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return mfr.contains("xiaomi") || brand.contains("xiaomi") ||
            brand.contains("redmi") || brand.contains("poco")
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (JarvisAccessibilityService.instance != null) return true
        val expected = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val expected = ComponentName(context, JarvisNotificationListenerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        launchSafe(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openAppDetailsSettings(context: Context) {
        launchSafe(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
    }

    fun openXiaomiAutostart(context: Context) {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        )
        for (intent in candidates) {
            if (launchSafe(context, intent, fallbackToAppInfo = false)) return
        }
        openAppDetailsSettings(context)
    }

    fun openXiaomiOtherPermissions(context: Context) {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            }
        )
        for (intent in candidates) {
            if (launchSafe(context, intent, fallbackToAppInfo = false)) return
        }
        openAppDetailsSettings(context)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (!launchSafe(context, direct, fallbackToAppInfo = false)) {
            launchSafe(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openDefaultAssistantSettings(context: Context) {
        if (!launchSafe(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), fallbackToAppInfo = false)) {
            launchSafe(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        launchSafe(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openOverlaySettings(context: Context) {
        launchSafe(
            context,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }

    fun openWriteSettings(context: Context) {
        launchSafe(
            context,
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }

    fun openDndAccessSettings(context: Context) {
        launchSafe(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun launchSafe(
        context: Context,
        intent: Intent,
        fallbackToAppInfo: Boolean = true
    ): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            if (fallbackToAppInfo) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
            false
        }
    }
}
