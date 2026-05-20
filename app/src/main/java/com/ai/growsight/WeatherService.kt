// com/ai/growsight/ai/WeatherService.kt
package com.ai.growsight.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class WeatherData(
    val temperatureCelsius: Float,
    val humidity: Int,
    val precipitationMm: Float,
    val weatherCode: Int,
    val locationLabel: String
)

data class WeatherInsight(
    val summary: String,
    val additionalRecommendations: List<String>
)

object WeatherService {

    private val client = OkHttpClient()

    suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherData? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$latitude" +
                        "&longitude=$longitude" +
                        "&current=temperature_2m,relative_humidity_2m,precipitation,rain,showers,weather_code" +
                        "&daily=precipitation_sum" +
                        "&timezone=auto" +
                        "&forecast_days=1"

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e("WeatherService", "HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val current = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")

                val precipSum = daily.getJSONArray("precipitation_sum")
                    .optDouble(0, 0.0).toFloat()

                WeatherData(
                    temperatureCelsius = current.getDouble("temperature_2m").toFloat(),
                    humidity = current.getInt("relative_humidity_2m"),
                    precipitationMm    = precipSum,
                    weatherCode = current.getInt("weather_code"),
                    locationLabel = "Lat: ${"%.2f".format(latitude)}, Lon: ${"%.2f".format(longitude)}"
                )
            } catch (e: Exception) {
                Log.e("WeatherService", "Failed to fetch weather: ${e.message}", e)
                null
            }
        }
    }

    fun generateWeatherInsight(weather: WeatherData, stage: String): WeatherInsight {
        val recommendations = mutableListOf<String>()
        val conditions = mutableListOf<String>()

        // ── Temperature ───────────────────────────────────────────────────────
        when {
            weather.temperatureCelsius >= 38f -> {
                conditions.add("extreme heat ${weather.temperatureCelsius.toInt()}°C")
                recommendations.add("🌡️ Extreme heat — water crops in early morning and late evening")
                recommendations.add("Apply mulch around base to retain soil moisture")
                if (stage == "harvest_ready") recommendations.add("Harvest immediately — heat accelerates over-maturity")
            }
            weather.temperatureCelsius >= 32f -> {
                conditions.add("hot ${weather.temperatureCelsius.toInt()}°C")
                recommendations.add("🌡️ Hot weather — increase irrigation frequency")
                if (stage == "not_ready") recommendations.add("Monitor for heat stress — leaves may wilt midday")
            }
            weather.temperatureCelsius in 24f..31f -> {
                conditions.add("warm ${weather.temperatureCelsius.toInt()}°C")
                recommendations.add("🌡️ Good growing temperature — maintain regular care")
            }
            weather.temperatureCelsius in 18f..23f -> {
                conditions.add("cool ${weather.temperatureCelsius.toInt()}°C")
                if (stage == "not_ready") recommendations.add("🌡️ Cool temp may slow growth slightly")
            }
            weather.temperatureCelsius < 18f -> {
                conditions.add("cold ${weather.temperatureCelsius.toInt()}°C")
                recommendations.add("🌡️ Cold weather — growth may stall, protect from frost if possible")
                if (stage == "harvest_ready") recommendations.add("Harvest soon — cold may damage tuber quality")
            }
        }

        // ── Humidity ──────────────────────────────────────────────────────────
        when {
            weather.humidity >= 90 -> {
                conditions.add("very high humidity ${weather.humidity}%")
                recommendations.add("💧 Very high humidity — inspect for fungal disease daily")
                recommendations.add("Improve air circulation, avoid overhead watering")
            }
            weather.humidity in 75..89 -> {
                conditions.add("high humidity ${weather.humidity}%")
                recommendations.add("💧 High humidity — watch for early signs of fungal spots")
            }
            weather.humidity in 40..74 -> {
                // Ideal range — no warning needed
            }
            weather.humidity < 40 -> {
                conditions.add("low humidity ${weather.humidity}%")
                recommendations.add("💧 Low humidity — increase irrigation and consider mulching")
            }
        }

        // ── Precipitation ─────────────────────────────────────────────────────
        when {
            weather.precipitationMm >= 20f -> {
                conditions.add("heavy rain ${weather.precipitationMm}mm")
                recommendations.add("🌧️ Heavy rain — skip irrigation completely today")
                recommendations.add("Check for waterlogging around roots")
                if (stage == "harvest_ready" || stage == "near_harvest") {
                    recommendations.add("⚠️ Delay harvest if soil is waterlogged — wait for it to dry")
                }
            }
            weather.precipitationMm in 5f..19f -> {
                conditions.add("moderate rain ${weather.precipitationMm}mm")
                recommendations.add("🌦️ Moderate rain — reduce irrigation by half today")
            }
            weather.precipitationMm in 1f..4f -> {
                conditions.add("light rain ${weather.precipitationMm}mm")
                recommendations.add("🌦️ Light rain — monitor soil moisture before irrigating")
            }
            weather.precipitationMm == 0f -> {
                when {
                    weather.temperatureCelsius >= 30f -> {
                        recommendations.add("☀️ Dry and hot — irrigate today, preferably morning or evening")
                    }
                    weather.temperatureCelsius in 22f..29f -> {
                        recommendations.add("☀️ Dry conditions — check soil moisture every 2 days")
                    }
                }
            }
        }

        // ── Stage-specific weather advice ─────────────────────────────────────
        when (stage) {
            "not_ready" -> {
                if (weather.temperatureCelsius >= 24f && weather.precipitationMm == 0f)
                    recommendations.add("🌱 Good growing weather — maintain consistent irrigation schedule")
            }
            "near_harvest" -> {
                if (weather.precipitationMm >= 10f)
                    recommendations.add("⚠️ Rain before harvest — stop all watering to help soil dry")
                if (weather.temperatureCelsius in 20f..30f && weather.precipitationMm < 5f)
                    recommendations.add("✅ Good conditions for final pre-harvest preparation")
            }
            "harvest_ready" -> {
                if (weather.precipitationMm < 5f && weather.temperatureCelsius < 35f)
                    recommendations.add("✅ Good harvesting conditions today")
                if (weather.precipitationMm >= 10f)
                    recommendations.add("🌧️ Rain today — harvest tomorrow morning if soil drains well")
            }
        }

        val conditionText = if (conditions.isEmpty()) "Normal conditions"
        else conditions.joinToString(", ").replaceFirstChar { it.uppercase() }

        val summary = "🌤️ Weather: $conditionText | ${weather.temperatureCelsius.toInt()}°C, ${weather.humidity}% humidity"

        return WeatherInsight(
            summary = summary,
            additionalRecommendations = recommendations
        )
    }
}