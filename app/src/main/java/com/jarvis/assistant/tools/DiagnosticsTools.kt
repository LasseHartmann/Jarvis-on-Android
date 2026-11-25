package com.jarvis.assistant.tools

import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.device.DeviceTelemetryMonitor
import org.json.JSONObject

class GetDeviceDiagnosticsTool(private val telemetryMonitor: DeviceTelemetryMonitor) : JarvisTool {
    override val spec = ToolSpec(
        name = "get_device_diagnostics",
        description = "Run full Xiaomi 15T Pro hardware and service diagnostics: battery percentage, charging wattage, thermal temperature, RAM, storage, network, and active J.A.R.V.I.S. subsystems.",
        params = emptyList(),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val t = telemetryMonitor.refresh()
        val json = JSONObject().apply {
            put("device", t.deviceModel)
            put("os", t.androidVersion)
            put("hyperos_detected", t.isXiaomiHyperOs)
            put("battery_percent", t.batteryPercent)
            put("charging", t.isCharging)
            put("charging_source", t.chargingSource)
            put("charging_watts_est", "%.1f".format(t.chargingWattsEstimate))
            put("battery_temp_c", "%.1f".format(t.batteryTempC))
            put("ram_used_percent", t.ramUsedPercent)
            put("ram_available_mb", t.ramAvailableMb)
            put("ram_total_mb", t.ramTotalMb)
            put("storage_free_gb", "%.1f".format(t.storageFreeGb))
            put("storage_total_gb", "%.1f".format(t.storageTotalGb))
            put("network", t.networkLabel)
            put("readiness_percent", t.readinessScorePercent)
            put("accessibility_pilot_online", t.accessibilityEnabled)
            put("notifications_core_online", t.notificationAccessEnabled)
        }
        return ToolExecutionOutput(
            success = true,
            message = t.toCompactContextString() + " | Readiness: ${t.readinessScorePercent}%",
            data = json
        )
    }
}
