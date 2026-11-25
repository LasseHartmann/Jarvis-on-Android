package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class LaunchableApp(
    val label: String,
    val packageName: String
)

class InstalledAppsRepository(context: Context) {

    private val appContext = context.applicationContext
    @Volatile
    private var cachedApps: List<LaunchableApp> = emptyList()
    @Volatile
    private var lastLoadedMs: Long = 0L

    fun getLaunchableApps(forceRefresh: Boolean = false): List<LaunchableApp> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedApps.isNotEmpty() && now - lastLoadedMs < CACHE_TTL_MS) {
            return cachedApps
        }
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val apps = resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                LaunchableApp(label = label, packageName = pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        cachedApps = apps
        lastLoadedMs = now
        return apps
    }

    fun searchApps(query: String, limit: Int = 15): List<LaunchableApp> {
        val q = query.trim().lowercase()
        val apps = getLaunchableApps()
        if (q.isBlank()) return apps.take(limit)

        return apps
            .map { app -> app to scoreAppMatch(app, q) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun findBestMatch(query: String): LaunchableApp? = searchApps(query, limit = 1).firstOrNull()

    fun launchApp(packageName: String): Boolean {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            appContext.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun scoreAppMatch(app: LaunchableApp, query: String): Int {
        val label = app.label.lowercase()
        val pkg = app.packageName.lowercase()
        val compactLabel = label.replace(Regex("[^a-z0-9]"), "")
        val compactQuery = query.replace(Regex("[^a-z0-9]"), "")

        return when {
            label == query || pkg == query -> 1000
            compactLabel == compactQuery && compactQuery.isNotEmpty() -> 950
            label.startsWith(query) -> 800
            compactLabel.startsWith(compactQuery) && compactQuery.isNotEmpty() -> 750
            label.contains(query) -> 600
            pkg.contains(query) -> 450
            query.split(" ").all { token -> token.isNotBlank() && (label.contains(token) || pkg.contains(token)) } -> 350
            else -> 0
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 60_000L
    }
}
