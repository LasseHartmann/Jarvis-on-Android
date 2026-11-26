package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.device.InstalledAppsRepository
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.ScreenTreeSerializer
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class ListInstalledAppsTool(private val appsRepo: InstalledAppsRepository) : JarvisTool {
    override val spec = ToolSpec(
        name = "list_installed_apps",
        description = "Search or list installed launchable apps on the phone.",
        params = listOf(
            ToolParam("query", "string", "Optional keyword to filter apps (e.g. 'spotify', 'camera', 'mail').", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.optString("query", "")
        val matches = appsRepo.searchApps(query, limit = 20)
        if (matches.isEmpty()) {
            return ToolExecutionOutput(true, "No installed apps matched '$query'.")
        }
        val arr = JSONArray()
        val summary = matches.joinToString("\n") { app ->
            arr.put(JSONObject().put("label", app.label).put("package", app.packageName))
            "- ${app.label} (${app.packageName})"
        }
        return ToolExecutionOutput(
            true,
            "Found ${matches.size} apps:\n$summary",
            JSONObject().put("apps", arr)
        )
    }
}

class OpenAppTool(
    private val appsRepo: InstalledAppsRepository,
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "open_app",
        description = "Find and launch any installed app on the phone by name or package ID (e.g. Spotify, WhatsApp, YouTube, Camera, Settings). Automatically waits briefly and returns the opened app's CURRENT SCREEN element tree.",
        params = listOf(
            ToolParam("app_name", "string", "App name or package identifier to open.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.optString("app_name", "").trim()
        if (query.isBlank()) {
            return ToolExecutionOutput(false, "No app_name provided.")
        }
        val best = appsRepo.findBestMatch(query)
            ?: return ToolExecutionOutput(false, "No installed app matched '$query'. Try list_installed_apps to inspect available apps.")

        // Hide the Jarvis overlay so the launched app becomes 100% interactive and visible to Accessibility
        prepareScreenForPilot()

        val launched = appsRepo.launchApp(best.packageName)
        if (!launched) {
            return ToolExecutionOutput(
                false,
                "Matched ${best.label}, but HyperOS blocked launching from background. Enable 'Display pop-up windows while running in background' in Jarvis permissions."
            )
        }

        // Wait for the launched app window to render and attach its fresh UI tree so multi-step piloting needs no extra read_screen turn
        val a11y = JarvisAccessibilityService.instance
        val screenAppendix = if (a11y != null) {
            delay(650L)
            var snap = a11y.captureScreenSnapshot()
            var attempts = 0
            while (attempts < 3 && (snap == null || snap.packageName != best.packageName || snap.nodes.isEmpty())) {
                delay(450L)
                snap = a11y.captureScreenSnapshot()
                attempts++
            }
            if (snap != null && (snap.nodes.isNotEmpty() || snap.isSensitiveApp)) {
                "\n\nCURRENT SCREEN:\n${ScreenTreeSerializer.formatForLlm(snap)}"
            } else ""
        } else ""

        return ToolExecutionOutput(
            true,
            "Launched ${best.label} (${best.packageName}).$screenAppendix",
            JSONObject().put("label", best.label).put("package", best.packageName)
        )
    }
}

class OpenUrlTool(
    private val context: Context,
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "open_url",
        description = "Open a web URL or deep link in the default browser or handling app.",
        params = listOf(
            ToolParam("url", "string", "URL or URI to open (e.g. https://wikipedia.org or geo:0,0?q=coffee).", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val raw = args.optString("url", "").trim()
        if (raw.isBlank()) return ToolExecutionOutput(false, "URL is empty.")
        val normalized = normalizeUriString(raw)
        prepareScreenForPilot()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (shouldRouteToGoogleMaps(normalized) && isPackageInstalled(context, GOOGLE_MAPS_PACKAGE)) {
            intent.setPackage(GOOGLE_MAPS_PACKAGE)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionOutput(true, "Opened $normalized.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Unable to open URL '$normalized': ${err.message}")
        }
    }

    companion object {
        private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")

        fun normalizeUriString(raw: String): String {
            val trimmed = raw.trim()
            return if (SCHEME_REGEX.matches(trimmed)) trimmed else "https://$trimmed"
        }

        fun shouldRouteToGoogleMaps(uri: String): Boolean {
            val lower = uri.lowercase()
            return lower.startsWith("google.navigation:") ||
                lower.startsWith("geo:") ||
                lower.contains("maps.google.") ||
                lower.contains("google.com/maps") ||
                lower.contains("maps.app.goo.gl")
        }
    }
}

class NavigateToTool(
    private val context: Context,
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "navigate_to",
        description = "Start turn-by-turn navigation directly inside Google Maps to a specific place name, landmark, address, or coordinates. Always resolve vague references (e.g. 'tallest building in Munich' -> 'Olympiaturm, Munich') into a concrete destination name/address.",
        params = listOf(
            ToolParam("destination", "string", "Concrete destination name, address, or lat,lng (e.g. 'Olympiaturm, Munich').", required = true),
            ToolParam(
                "mode",
                "string",
                "Travel mode: driving (default), walking, bicycling, or transit.",
                required = false,
                enumValues = listOf("driving", "walking", "bicycling", "transit")
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val destination = args.optString("destination", "").trim()
        if (destination.isBlank()) {
            return ToolExecutionOutput(false, "No destination provided.")
        }
        val mode = args.optString("mode", "driving").trim().lowercase().ifBlank { "driving" }
        val uriString = buildNavigationUri(destination, mode)

        prepareScreenForPilot()

        val mapsInstalled = isPackageInstalled(context, GOOGLE_MAPS_PACKAGE)
        val primaryIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (mapsInstalled) {
                setPackage(GOOGLE_MAPS_PACKAGE)
            }
        }

        val launched = runCatching {
            context.startActivity(primaryIntent)
            true
        }.getOrElse {
            // Fallback without package pin if another maps app is preferred
            runCatching {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
                true
            }.getOrDefault(false)
        }

        return if (launched) {
            ToolExecutionOutput(
                true,
                "Started $mode navigation in Google Maps to $destination.",
                JSONObject().put("destination", destination).put("mode", mode).put("uri", uriString)
            )
        } else {
            ToolExecutionOutput(false, "Unable to launch Google Maps navigation to '$destination'.")
        }
    }

    companion object {
        fun buildNavigationUri(destination: String, mode: String = "driving"): String {
            val encoded = Uri.encode(destination.trim())
            return when (mode.trim().lowercase()) {
                "walking", "walk", "foot" -> "google.navigation:q=$encoded&mode=w"
                "bicycling", "bike", "cycling" -> "google.navigation:q=$encoded&mode=b"
                "transit", "public_transit", "train", "bus" ->
                    "https://www.google.com/maps/dir/?api=1&destination=$encoded&travelmode=transit"
                else -> "google.navigation:q=$encoded&mode=d"
            }
        }
    }
}

private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
    context.packageManager.getPackageInfo(packageName, 0)
    true
}.getOrDefault(false)

class WebSearchTool(
    private val context: Context,
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "web_search",
        description = "Launch a Google web search in the default browser.",
        params = listOf(
            ToolParam("query", "string", "Search query.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) return ToolExecutionOutput(false, "Search query cannot be empty.")
        prepareScreenForPilot()
        val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolExecutionOutput(true, "Opened web search for '$query'.")
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Unable to launch web search: ${err.message}")
        }
    }
}
