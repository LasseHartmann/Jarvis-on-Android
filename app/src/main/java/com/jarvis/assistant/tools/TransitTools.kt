package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.agent.GeminiLlmClient
import com.jarvis.assistant.agent.JarvisReminder
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.data.JarvisStore
import com.jarvis.assistant.services.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val transitHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

data class TransitRouteSummary(
    val text: String,
    val recommendedLeaveEpochMs: Long?,
    val firstLineLabel: String,
    val destinationName: String
)

object TransitParser {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val isoUtcFmt = DateTimeFormatter.ISO_INSTANT

    /**
     * Parses user-provided "HH:mm" or ISO-8601 time into epoch milliseconds (rolling to tomorrow
     * if HH:mm has already passed today).
     */
    fun parseClockOrIsoTimeMs(raw: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val clean = raw.trim()
        if (clean.isBlank()) return null

        // ISO-8601 offset datetime
        runCatching { return OffsetDateTime.parse(clean).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(clean).toEpochMilli() }

        val hhmm = Regex("^(\\d{1,2}):(\\d{2})$").find(clean) ?: return null
        val h = hhmm.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val m = hhmm.groupValues[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs + 30_000L) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    fun formatIsoUtc(epochMs: Long): String = isoUtcFmt.format(Instant.ofEpochMilli(epochMs))

    fun parseMvgIsoToEpochMs(iso: String): Long? = runCatching {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrNull()

    fun formatEpochHm(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFmt)

    fun formatMvgDepartures(stationName: String, jsonArrayStr: String, maxItems: Int = 6): String {
        val arr = JSONArray(jsonArrayStr)
        if (arr.length() == 0) return "No upcoming MVG departures found for $stationName."
        val lines = ArrayList<String>()
        val nowMs = System.currentTimeMillis()
        for (i in 0 until minOf(maxItems, arr.length())) {
            val d = arr.optJSONObject(i) ?: continue
            val label = d.optString("label", "?")
            val dest = d.optString("destination", "")
            val rtMs = d.optLong("realtimeDepartureTime", d.optLong("plannedDepartureTime", 0L))
            val delay = d.optInt("delayInMinutes", 0)
            val platform = d.optString("platform", "").takeIf { it.isNotBlank() && it != "null" }
            val cancelled = d.optBoolean("cancelled", false)
            val inMin = ((rtMs - nowMs) / 60_000L).coerceAtLeast(0L)
            val hm = if (rtMs > 0L) formatEpochHm(rtMs) else "--:--"
            val delayTag = when {
                cancelled -> " [CANCELLED]"
                delay > 0 -> " (+${delay}m delay)"
                else -> " (on time)"
            }
            val platTag = if (platform != null) ", Pl. $platform" else ""
            lines.add("- $label -> $dest at $hm (in ${inMin}m)$delayTag$platTag")
        }
        return "Live departures from $stationName:\n" + lines.joinToString("\n")
    }

    fun formatMvgRoutes(routesJsonStr: String, originLabel: String, destLabel: String): TransitRouteSummary? {
        val arr = JSONArray(routesJsonStr)
        if (arr.length() == 0) return null

        val sb = StringBuilder()
        sb.appendLine("Live MVG transit routes ($originLabel -> $destLabel):")

        var bestLeaveMs: Long? = null
        var bestLine = ""

        val count = minOf(3, arr.length())
        for (rIdx in 0 until count) {
            val route = arr.optJSONObject(rIdx) ?: continue
            val parts = route.optJSONArray("parts") ?: continue
            if (parts.length() == 0) continue

            val firstFrom = parts.getJSONObject(0).optJSONObject("from")
            val lastTo = parts.getJSONObject(parts.length() - 1).optJSONObject("to")

            val startIso = firstFrom?.optString("plannedDeparture").orEmpty()
            val startDelayMin = firstFrom?.optInt("departureDelayInMinutes", 0) ?: 0
            val startMs = parseMvgIsoToEpochMs(startIso)?.let { it + startDelayMin * 60_000L }

            val endIso = lastTo?.optString("plannedDeparture").orEmpty()
            val endDelayMin = lastTo?.optInt("arrivalDelayInMinutes", 0) ?: 0
            val endMs = parseMvgIsoToEpochMs(endIso)?.let { it + endDelayMin * 60_000L }

            val legSummaries = ArrayList<String>()
            var firstTransitLine = ""
            for (pIdx in 0 until parts.length()) {
                val part = parts.getJSONObject(pIdx)
                val lineObj = part.optJSONObject("line")
                val tType = lineObj?.optString("transportType").orEmpty()
                val label = lineObj?.optString("label").orEmpty()
                val fromName = part.optJSONObject("from")?.optString("name").orEmpty()
                val toName = part.optJSONObject("to")?.optString("name").orEmpty()
                val plat = part.optJSONObject("from")?.optString("platform", "")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                val legDelay = part.optJSONObject("from")?.optInt("departureDelayInMinutes", 0) ?: 0

                if (tType.equals("PEDESTRIAN", ignoreCase = true) || label.equals("Fussweg", ignoreCase = true)) {
                    val distM = part.optDouble("distance", 0.0).toInt()
                    if (distM >= 40) legSummaries.add("Walk ${distM}m")
                } else {
                    if (firstTransitLine.isBlank()) firstTransitLine = label
                    val pStr = if (plat != null) " Pl.$plat" else ""
                    val dStr = if (legDelay > 0) " +${legDelay}m" else ""
                    legSummaries.add("$label ($fromName$pStr$dStr -> $toName)")
                }
            }

            if (rIdx == 0) {
                bestLeaveMs = startMs
                bestLine = firstTransitLine.ifBlank { "Transit" }
            }

            val depStr = startMs?.let { formatEpochHm(it) } ?: "?"
            val arrStr = endMs?.let { formatEpochHm(it) } ?: "?"
            val durStr = if (startMs != null && endMs != null) {
                "${((endMs - startMs) / 60_000L).coerceAtLeast(1L)} min"
            } else ""
            sb.appendLine("Option ${rIdx + 1}: Leave $depStr, arrive $arrStr ($durStr) — ${legSummaries.joinToString(" -> ")}")
        }

        return TransitRouteSummary(
            text = sb.toString().trim(),
            recommendedLeaveEpochMs = bestLeaveMs,
            firstLineLabel = bestLine,
            destinationName = destLabel
        )
    }

    fun formatDbDepartures(stationName: String, jsonObjectStr: String, maxItems: Int = 6): String {
        val root = JSONObject(jsonObjectStr)
        val arr = root.optJSONArray("departures") ?: return "No Deutsche Bahn departures found for $stationName."
        if (arr.length() == 0) return "No upcoming Deutsche Bahn departures for $stationName."
        val lines = ArrayList<String>()
        for (i in 0 until minOf(maxItems, arr.length())) {
            val d = arr.optJSONObject(i) ?: continue
            val train = d.optString("train", "Train")
            val dest = d.optString("destination", "")
            val sched = d.optString("scheduledDeparture", d.optString("time", ""))
            val delay = d.optInt("delayDeparture", 0)
            val platform = d.optString("platform", "").takeIf { it.isNotBlank() && it != "null" }
            val cancelled = d.optInt("isCancelled", 0) != 0
            val delayTag = when {
                cancelled -> " [CANCELLED]"
                delay > 0 -> " (+${delay}m)"
                else -> " (on time)"
            }
            val platTag = if (platform != null) ", Pl. $platform" else ""
            lines.add("- $train -> $dest at $sched$delayTag$platTag")
        }
        return "Live Deutsche Bahn departures from $stationName:\n" + lines.joinToString("\n")
    }
}

class GetPublicTransitTool(
    private val context: Context,
    private val store: JarvisStore,
    private val settingsProvider: () -> JarvisSettings
) : JarvisTool {

    override val spec = ToolSpec(
        name = "get_public_transit",
        description = "Look up live real-time public transit routes and departure boards (MVG Munich U-Bahn/S-Bahn/Tram/Bus, Deutsche Bahn nationwide trains, Berlin VBB, or global Google Search fallback). Computes exact 'when do I need to leave?' times with live delays and platforms, and can automatically schedule a spoken departure reminder N minutes before you need to leave.",
        params = listOf(
            ToolParam(
                name = "mode",
                type = "string",
                description = "'route' for point-to-point trip planning (default when destination is given) or 'departures' for a station's live departure board.",
                enumValues = listOf("route", "departures")
            ),
            ToolParam(
                name = "origin",
                type = "string",
                description = "Starting station, stop, or address (e.g. 'Marienplatz', 'München Hbf'). Omit to automatically use the user's current GPS position."
            ),
            ToolParam(
                name = "destination",
                type = "string",
                description = "Destination station, stop, or place for route queries (e.g. 'Hauptbahnhof', 'Flughafen München')."
            ),
            ToolParam(
                name = "arrive_by",
                type = "string",
                description = "Optional target arrival time in 24h 'HH:mm' (e.g. '18:00') to calculate when to leave."
            ),
            ToolParam(
                name = "depart_at",
                type = "string",
                description = "Optional target departure time in 24h 'HH:mm' (omit for leaving now)."
            ),
            ToolParam(
                name = "remind_minutes_before",
                type = "integer",
                description = "Optional: automatically schedule a spoken Jarvis departure reminder this many minutes before the recommended leave time (e.g. 10)."
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput = withContext(Dispatchers.IO) {
        val originArg = args.optString("origin", "").trim()
        val destArg = args.optString("destination", "").trim()
        val mode = args.optString("mode", "").trim().lowercase().ifBlank {
            if (destArg.isNotBlank()) "route" else "departures"
        }
        val arriveByRaw = args.optString("arrive_by", "").trim()
        val departAtRaw = args.optString("depart_at", "").trim()
        val remindBefore = if (args.has("remind_minutes_before") && !args.isNull("remind_minutes_before")) {
            args.optInt("remind_minutes_before", 0).coerceIn(0, 180)
        } else 0

        if (mode == "departures") {
            val stationQuery = originArg.ifBlank { destArg }
            if (stationQuery.isBlank()) {
                return@withContext ToolExecutionOutput(false, "Please specify a station name for live departures.")
            }
            // 1. Try MVG Munich live departures first
            val mvgLoc = queryMvgLocation(stationQuery)
            if (mvgLoc != null) {
                val deps = fetchMvgDepartures(mvgLoc.first, mvgLoc.second)
                if (deps != null) return@withContext ToolExecutionOutput(true, deps)
            }
            // 2. Try Deutsche Bahn live departure board (nationwide ICE/IC/RE/S-Bahn)
            val dbDeps = fetchDbDepartures(stationQuery)
            if (dbDeps != null) return@withContext ToolExecutionOutput(true, dbDeps)

            // 3. Fallback to Gemini Google Search Grounding
            return@withContext fallbackGroundedTransit("Live public transit departures from $stationQuery right now")
        }

        // Route mode
        if (destArg.isBlank()) {
            return@withContext ToolExecutionOutput(false, "Destination is required for public transit routing.")
        }

        val targetTimeMs: Long?
        val isArrival: Boolean
        when {
            arriveByRaw.isNotBlank() -> {
                targetTimeMs = TransitParser.parseClockOrIsoTimeMs(arriveByRaw)
                isArrival = true
            }

            departAtRaw.isNotBlank() -> {
                targetTimeMs = TransitParser.parseClockOrIsoTimeMs(departAtRaw)
                isArrival = false
            }

            else -> {
                targetTimeMs = null
                isArrival = false
            }
        }

        // Try MVG Munich v3 live router
        val destMvg = queryMvgLocation(destArg)
        if (destMvg != null) {
            val routeSummary = if (originArg.isNotBlank()) {
                val origMvg = queryMvgLocation(originArg)
                if (origMvg != null) {
                    fetchMvgRoutesByIds(
                        originId = origMvg.first,
                        originName = origMvg.second,
                        destId = destMvg.first,
                        destName = destMvg.second,
                        targetTimeMs = targetTimeMs,
                        isArrival = isArrival
                    )
                } else null
            } else {
                val gps = GetCurrentLocationTool.resolveDeviceLocation(context)
                if (gps != null) {
                    fetchMvgRoutesFromGps(
                        lat = gps.latitude,
                        lon = gps.longitude,
                        destId = destMvg.first,
                        destName = destMvg.second,
                        targetTimeMs = targetTimeMs,
                        isArrival = isArrival
                    )
                } else {
                    // Fallback to Marienplatz if user didn't pass origin & GPS is off
                    null
                }
            }

            if (routeSummary != null) {
                val reminderNote = maybeScheduleDepartureReminder(routeSummary, remindBefore)
                val fullText = if (reminderNote != null) "${routeSummary.text}\n$reminderNote" else routeSummary.text
                return@withContext ToolExecutionOutput(
                    true,
                    fullText,
                    JSONObject()
                        .put("leave_epoch_ms", routeSummary.recommendedLeaveEpochMs ?: 0L)
                        .put("line", routeSummary.firstLineLabel)
                        .put("destination", routeSummary.destinationName)
                )
            }
        }

        // Global fallback via Gemini Google Search Grounding
        val fromDesc = originArg.ifBlank {
            val gps = GetCurrentLocationTool.resolveDeviceLocation(context)
            if (gps != null) GetCurrentLocationTool.reverseGeocode(context, gps.latitude, gps.longitude).ifBlank { "current location" }
            else "Munich"
        }
        val timingDesc = when {
            arriveByRaw.isNotBlank() -> "arriving by $arriveByRaw"
            departAtRaw.isNotBlank() -> "departing at $departAtRaw"
            else -> "leaving now"
        }
        fallbackGroundedTransit("Public transit route and departure times from $fromDesc to $destArg $timingDesc")
    }

    private fun maybeScheduleDepartureReminder(route: TransitRouteSummary, minutesBefore: Int): String? {
        if (minutesBefore <= 0) return null
        val leaveMs = route.recommendedLeaveEpochMs ?: return null
        val alertMs = leaveMs - minutesBefore * 60_000L
        val now = System.currentTimeMillis()
        if (alertMs <= now + 15_000L) {
            return "Departure reminder skipped because you need to leave in less than $minutesBefore minutes."
        }
        val leaveHm = TransitParser.formatEpochHm(leaveMs)
        val alertHm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(alertMs))
        val title = "Leave for ${route.destinationName}"
        val msg = "Time to leave in $minutesBefore minutes for ${route.destinationName} (${route.firstLineLabel} departs at $leaveHm)."
        val id = store.addReminder(
            title = title,
            message = msg,
            triggerAtMs = alertMs,
            repeatDaily = false,
            isBriefing = false
        )
        if (id > 0L) {
            ReminderScheduler.scheduleAlarm(
                context,
                JarvisReminder(id, title, msg, alertMs, repeatDaily = false, isBriefing = false, active = true)
            )
            return "Scheduled spoken departure reminder #$id for $alertHm ($minutesBefore min before $leaveHm)."
        }
        return null
    }

    private fun queryMvgLocation(query: String): Pair<String, String>? {
        val enc = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.mvg.de/api/bgw-pt/v3/locations?query=$enc&locationTypes=STATION"
        return runCatching {
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            transitHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val arr = JSONArray(resp.body?.string().orEmpty())
                if (arr.length() == 0) return@use null
                val first = arr.getJSONObject(0)
                val id = first.optString("globalId").trim()
                val name = first.optString("name", query).trim()
                if (id.isBlank()) null else (id to name)
            }
        }.getOrNull()
    }

    private fun fetchMvgDepartures(globalId: String, stationName: String): String? {
        val enc = URLEncoder.encode(globalId, "UTF-8")
        val url = "https://www.mvg.de/api/bgw-pt/v3/departures?globalId=$enc&limit=8"
        return runCatching {
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            transitHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                TransitParser.formatMvgDepartures(stationName, resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private fun fetchMvgRoutesByIds(
        originId: String,
        originName: String,
        destId: String,
        destName: String,
        targetTimeMs: Long?,
        isArrival: Boolean
    ): TransitRouteSummary? {
        val url = buildString {
            append("https://www.mvg.de/api/bgw-pt/v3/routes")
            append("?originStationGlobalId=").append(URLEncoder.encode(originId, "UTF-8"))
            append("&destinationStationGlobalId=").append(URLEncoder.encode(destId, "UTF-8"))
            if (targetTimeMs != null) {
                append("&routingDateTime=").append(URLEncoder.encode(TransitParser.formatIsoUtc(targetTimeMs), "UTF-8"))
                append("&routingDateTimeIsArrival=").append(isArrival)
            }
        }
        return runCatching {
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            transitHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                TransitParser.formatMvgRoutes(resp.body?.string().orEmpty(), originName, destName)
            }
        }.getOrNull()
    }

    private fun fetchMvgRoutesFromGps(
        lat: Double,
        lon: Double,
        destId: String,
        destName: String,
        targetTimeMs: Long?,
        isArrival: Boolean
    ): TransitRouteSummary? {
        val url = buildString {
            append(String.format(Locale.US, "https://www.mvg.de/api/bgw-pt/v3/routes?originLatitude=%.5f&originLongitude=%.5f", lat, lon))
            append("&destinationStationGlobalId=").append(URLEncoder.encode(destId, "UTF-8"))
            if (targetTimeMs != null) {
                append("&routingDateTime=").append(URLEncoder.encode(TransitParser.formatIsoUtc(targetTimeMs), "UTF-8"))
                append("&routingDateTimeIsArrival=").append(isArrival)
            }
        }
        return runCatching {
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            transitHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                TransitParser.formatMvgRoutes(resp.body?.string().orEmpty(), "Current location", destName)
            }
        }.getOrNull()
    }

    private fun fetchDbDepartures(stationName: String): String? {
        val enc = URLEncoder.encode(stationName, "UTF-8").replace("+", "%20")
        val url = "https://dbf.finalrewind.org/$enc.json?version=3&limit=8"
        return runCatching {
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            transitHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (!body.contains("departures")) return@use null
                TransitParser.formatDbDepartures(stationName, body)
            }
        }.getOrNull()
    }

    private suspend fun fallbackGroundedTransit(query: String): ToolExecutionOutput {
        val settings = settingsProvider()
        if (settings.geminiApiKey.isBlank()) {
            return ToolExecutionOutput(false, "No MVG/DB station match found and Gemini API key is not set for global transit fallback.")
        }
        return runCatching {
            val client = GeminiLlmClient(apiKey = settings.geminiApiKey, model = settings.geminiModel)
            ToolExecutionOutput(true, client.groundedWebSearch(query))
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Transit lookup failed: ${err.message}")
        }
    }
}
