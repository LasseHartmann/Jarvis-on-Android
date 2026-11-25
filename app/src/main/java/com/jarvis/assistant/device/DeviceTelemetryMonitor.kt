package com.jarvis.assistant.device

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.JarvisNotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

data class DeviceTelemetry(
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val isXiaomiHyperOs: Boolean = false,
    val batteryPercent: Int = 0,
    val isCharging: Boolean = false,
    val chargingSource: String = "Battery",
    val batteryTempC: Float = 0f,
    val batteryVoltageMv: Int = 0,
    val chargingWattsEstimate: Float = 0f,
    val ramUsedPercent: Int = 0,
    val ramAvailableMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val storageFreeGb: Float = 0f,
    val storageTotalGb: Float = 0f,
    val networkLabel: String = "Offline",
    val foregroundPackage: String = "",
    val isDefaultAssistant: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationAccessEnabled: Boolean = false,
    val writeSettingsEnabled: Boolean = false,
    val dndAccessEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val micGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val callGranted: Boolean = false,
    val smsGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false
) {
    val readinessScorePercent: Int
        get() {
            val checks = listOf(
                isDefaultAssistant,
                micGranted,
                accessibilityEnabled,
                notificationAccessEnabled,
                writeSettingsEnabled,
                dndAccessEnabled,
                batteryUnrestricted
            )
            return ((checks.count { it }.toFloat() / checks.size) * 100f).roundToInt()
        }

    fun toCompactContextString(): String = buildString {
        append("Device: $deviceModel ($androidVersion")
        if (isXiaomiHyperOs) append(", HyperOS")
        append(") | Battery: $batteryPercent%")
        if (isCharging) {
            append(" ($chargingSource")
            if (chargingWattsEstimate >= 1f) append(", ~${"%.1f".format(chargingWattsEstimate)}W")
            append(")")
        }
        append(", ${"%.1f".format(batteryTempC)}°C")
        append(" | RAM: $ramUsedPercent% (${ramAvailableMb}MB free/${ramTotalMb}MB)")
        append(" | Storage: ${"%.1f".format(storageFreeGb)}GB free/${"%.1f".format(storageTotalGb)}GB")
        append(" | Net: $networkLabel")
        if (foregroundPackage.isNotBlank()) append(" | Foreground: $foregroundPackage")
        append(" | Pilot(A11y): ${if (accessibilityEnabled) "ONLINE" else "OFFLINE"}")
        append(" | Notifs: ${if (notificationAccessEnabled) "ONLINE" else "OFFLINE"}")
    }
}

class DeviceTelemetryMonitor(private val context: Context) {

    private val appContext = context.applicationContext
    private val _telemetry = MutableStateFlow(captureSnapshot())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    fun refresh(): DeviceTelemetry {
        val snap = captureSnapshot()
        _telemetry.value = snap
        return snap
    }

    private fun captureSnapshot(): DeviceTelemetry {
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeSrc = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC HyperCharge"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (isCharging) "Charging" else "Battery"
        }
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentMicroAmps = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val watts = if (isCharging && voltageMv > 0 && currentMicroAmps != Int.MIN_VALUE && currentMicroAmps != 0) {
            val amps = abs(currentMicroAmps).let { if (it > 10000) it / 1_000_000f else it / 1000f }
            (voltageMv / 1000f) * amps
        } else 0f

        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val ramPct = if (totalMb > 0) (((totalMb - availMb) * 100L) / totalMb).toInt() else 0

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalStorageGb = (stat.totalBytes / (1024.0 * 1024.0 * 1024.0)).toFloat()
        val freeStorageGb = (stat.availableBytes / (1024.0 * 1024.0 * 1024.0)).toFloat()

        val netLabel = resolveNetworkLabel()
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val notifManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val currentAssistant = runCatching {
            Settings.Secure.getString(appContext.contentResolver, "assistant").orEmpty()
        }.getOrDefault("")
        val currentVoiceInteractor = runCatching {
            Settings.Secure.getString(appContext.contentResolver, "voice_interaction_service").orEmpty()
        }.getOrDefault("")
        val isDefaultAssist = currentAssistant.contains(appContext.packageName) ||
            currentVoiceInteractor.contains(appContext.packageName)

        return DeviceTelemetry(
            deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            isXiaomiHyperOs = HyperOsSetupHelper.isXiaomiDevice(),
            batteryPercent = batteryPct,
            isCharging = isCharging,
            chargingSource = chargeSrc,
            batteryTempC = tempTenths / 10f,
            batteryVoltageMv = voltageMv,
            chargingWattsEstimate = watts,
            ramUsedPercent = ramPct,
            ramAvailableMb = availMb,
            ramTotalMb = totalMb,
            storageFreeGb = freeStorageGb,
            storageTotalGb = totalStorageGb,
            networkLabel = netLabel,
            foregroundPackage = JarvisAccessibilityService.currentForegroundPackage,
            isDefaultAssistant = isDefaultAssist,
            accessibilityEnabled = HyperOsSetupHelper.isAccessibilityServiceEnabled(appContext),
            notificationAccessEnabled = HyperOsSetupHelper.isNotificationListenerEnabled(appContext) ||
                JarvisNotificationListenerService.isConnected,
            writeSettingsEnabled = Settings.System.canWrite(appContext),
            dndAccessEnabled = notifManager?.isNotificationPolicyAccessGranted == true,
            batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
            micGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
            contactsGranted = hasPermission(Manifest.permission.READ_CONTACTS),
            callGranted = hasPermission(Manifest.permission.CALL_PHONE),
            smsGranted = hasPermission(Manifest.permission.SEND_SMS),
            calendarGranted = hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR),
            postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            } else true
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun resolveNetworkLabel(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "Unknown"
        val active = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(active) ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "5G / Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Connected"
        }
    }
}
