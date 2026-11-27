package com.jarvis.assistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.assistant.agent.GeminiLlmClient
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.RiskLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

private val sharedInfoHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

class LiveWebAnswerTool(
    private val settingsProvider: () -> JarvisSettings
) : JarvisTool {
    override val spec = ToolSpec(
        name = "live_web_answer",
        description = "Search the live internet using Google Search Grounding and return a direct, up-to-date factual answer (news, sports scores, stock/crypto prices, opening hours, schedules, current events) WITHOUT opening Chrome.",
        params = listOf(
            ToolParam("query", "string", "The live question or search query to look up.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) return ToolExecutionOutput(false, "Search query is empty.")
        val settings = settingsProvider()
        if (settings.geminiApiKey.isBlank()) {
            return ToolExecutionOutput(false, "Gemini API key is required for live Google Search grounding.")
        }
        val client = GeminiLlmClient(apiKey = settings.geminiApiKey, model = settings.geminiModel)
        val answer = client.groundedWebSearch(query)
        return ToolExecutionOutput(true, answer)
    }
}

class FetchWebpageTool : JarvisTool {
    override val spec = ToolSpec(
        name = "fetch_webpage",
        description = "Fetch and read the clean plain-text content of any public web URL without opening a browser.",
        params = listOf(
            ToolParam("url", "string", "Full https:// URL to fetch and read.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput = withContext(Dispatchers.IO) {
        val raw = args.optString("url", "").trim()
        if (raw.isBlank()) return@withContext ToolExecutionOutput(false, "URL is empty.")
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) JarvisAssistant/2.0")
            .get()
            .build()

        runCatching {
            sharedInfoHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use ToolExecutionOutput(false, "HTTP ${resp.code} fetching $url.")
                }
                val html = resp.body?.string().orEmpty().take(120_000)
                val cleanText = stripHtmlToText(html).take(4_200)
                if (cleanText.isBlank()) {
                    ToolExecutionOutput(false, "No readable text found at $url.")
                } else {
                    ToolExecutionOutput(true, cleanText)
                }
            }
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Failed to fetch $url: ${err.message}")
        }
    }

    companion object {
        private val scriptStyleRegex = Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>")
        private val tagRegex = Regex("<[^>]+>")
        private val whitespaceRegex = Regex("\\s+")

        fun stripHtmlToText(html: String): String =
            html.replace(scriptStyleRegex, " ")
                .replace(tagRegex, " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(whitespaceRegex, " ")
                .trim()
    }
}

class GetCurrentLocationTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "get_current_location",
        description = "Get the user's current GPS/network location (city, neighborhood, street address, and latitude/longitude coordinates).",
        params = emptyList(),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val loc = resolveDeviceLocation(context)
            ?: return ToolExecutionOutput(
                false,
                "Location unavailable. Grant Location permission in Jarvis Phone Permissions and ensure GPS is enabled."
            )
        val place = reverseGeocode(context, loc.latitude, loc.longitude)
        val summary = buildString {
            if (place.isNotBlank()) append(place).append(" ")
            append("(lat=${"%.5f".format(Locale.US, loc.latitude)}, lon=${"%.5f".format(Locale.US, loc.longitude)})")
        }
        return ToolExecutionOutput(
            true,
            summary,
            JSONObject()
                .put("latitude", loc.latitude)
                .put("longitude", loc.longitude)
                .put("address", place)
        )
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        @SuppressLint("MissingPermission")
        suspend fun resolveDeviceLocation(context: Context): Location? {
            if (!hasLocationPermission(context)) return null
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

            val providers = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.PASSIVE_PROVIDER)
            }

            // Pick freshest last-known fix if under 10 minutes old
            val now = System.currentTimeMillis()
            val bestCached = providers.mapNotNull { p ->
                runCatching { if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }.getOrNull()
            }.maxByOrNull { it.time }

            if (bestCached != null && (now - bestCached.time) < 10 * 60 * 1000L) {
                return bestCached
            }

            // Request fresh single fix on Android 11+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val activeProvider = providers.firstOrNull { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
                if (activeProvider != null) {
                    val deferred = CompletableDeferred<Location?>()
                    runCatching {
                        lm.getCurrentLocation(activeProvider, null, context.mainExecutor) { loc ->
                            if (!deferred.isCompleted) deferred.complete(loc)
                        }
                    }.onFailure {
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                    val fresh = withTimeoutOrNull(4_500L) { deferred.await() }
                    if (fresh != null) return fresh
                }
            }
            return bestCached
        }

        @Suppress("DEPRECATION")
        suspend fun reverseGeocode(context: Context, lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
            runCatching {
                val geocoder = Geocoder(context, Locale.getDefault())
                val list = geocoder.getFromLocation(lat, lon, 1)
                val addr = list?.firstOrNull() ?: return@runCatching ""
                listOfNotNull(
                    addr.thoroughfare?.let { st ->
                        if (!addr.subThoroughfare.isNullOrBlank()) "$st ${addr.subThoroughfare}" else st
                    },
                    addr.postalCode,
                    addr.locality ?: addr.subAdminArea,
                    addr.countryName
                ).filter { it.isNotBlank() }.distinct().joinToString(", ")
            }.getOrDefault("")
        }
    }
}

class GetWeatherTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "get_weather",
        description = "Fetch real-time weather conditions and a 3-day forecast via Open-Meteo (temperature, feels-like, rain probability, wind). Omit 'location' to automatically use the user's current GPS position.",
        params = listOf(
            ToolParam("location", "string", "Optional city or place name (e.g. 'Munich', 'Berlin'). Omit for current GPS location.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput = withContext(Dispatchers.IO) {
        val requestedCity = args.optString("location", "").trim()
        val coords: Triple<Double, Double, String> = if (requestedCity.isNotBlank()) {
            geocodeCity(requestedCity)
                ?: return@withContext ToolExecutionOutput(false, "Could not find coordinates for '$requestedCity'.")
        } else {
            val loc = GetCurrentLocationTool.resolveDeviceLocation(context)
                ?: return@withContext ToolExecutionOutput(
                    false,
                    "No city provided and GPS location is unavailable. Specify a city name or grant Location permission."
                )
            val label = GetCurrentLocationTool.reverseGeocode(context, loc.latitude, loc.longitude)
                .ifBlank { "Current location" }
            Triple(loc.latitude, loc.longitude, label)
        }

        val (lat, lon, displayPlace) = coords
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3",
            lat,
            lon
        )
        val req = Request.Builder().url(url).get().build()

        runCatching {
            sharedInfoHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use ToolExecutionOutput(false, "Weather service returned HTTP ${resp.code}.")
                }
                val root = JSONObject(resp.body?.string().orEmpty())
                val cur = root.getJSONObject("current")
                val temp = cur.optDouble("temperature_2m")
                val feels = cur.optDouble("apparent_temperature")
                val wind = cur.optDouble("wind_speed_10m")
                val code = cur.optInt("weather_code", 0)
                val cond = wmoCondition(code)

                val daily = root.optJSONObject("daily")
                val dates = daily?.optJSONArray("time")
                val highs = daily?.optJSONArray("temperature_2m_max")
                val lows = daily?.optJSONArray("temperature_2m_min")
                val rainProbs = daily?.optJSONArray("precipitation_probability_max")
                val dCodes = daily?.optJSONArray("weather_code")

                val sb = StringBuilder()
                sb.appendLine("Weather for $displayPlace:")
                sb.appendLine(
                    "Now: %.1f°C (feels like %.1f°C), %s, wind %.0f km/h.".format(Locale.US, temp, feels, cond, wind)
                )
                if (dates != null && highs != null && lows != null) {
                    for (i in 0 until minOf(3, dates.length())) {
                        val dayLabel = when (i) {
                            0 -> "Today"
                            1 -> "Tomorrow"
                            else -> dates.optString(i)
                        }
                        val hi = highs.optDouble(i)
                        val lo = lows.optDouble(i)
                        val rain = rainProbs?.optInt(i, 0) ?: 0
                        val dCond = wmoCondition(dCodes?.optInt(i, 0) ?: 0)
                        sb.appendLine("- $dayLabel: %.0f°C / %.0f°C, %s, rain chance %d%%".format(Locale.US, lo, hi, dCond, rain))
                    }
                }
                ToolExecutionOutput(true, sb.toString().trim())
            }
        }.getOrElse { err ->
            ToolExecutionOutput(false, "Weather fetch failed: ${err.message}")
        }
    }

    private fun geocodeCity(city: String): Triple<Double, Double, String>? {
        val enc = URLEncoder.encode(city, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$enc&count=1&language=en&format=json"
        val req = Request.Builder().url(url).get().build()
        return runCatching {
            sharedInfoHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: return@use null
                if (arr.length() == 0) return@use null
                val first = arr.getJSONObject(0)
                val name = listOfNotNull(
                    first.optString("name").takeIf { it.isNotBlank() },
                    first.optString("country").takeIf { it.isNotBlank() }
                ).joinToString(", ")
                Triple(first.getDouble("latitude"), first.getDouble("longitude"), name.ifBlank { city })
            }
        }.getOrNull()
    }

    companion object {
        fun wmoCondition(code: Int): String = when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Variable conditions"
        }
    }
}
