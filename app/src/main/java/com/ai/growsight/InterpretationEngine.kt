    package com.ai.growsight.ai

    data class AnomalyFlag(
        val badgeLabel: String,
        val severity: String,       // "critical" | "high" | "medium" | "low"
        val detail: String,
        val suggestion: String
    )

    data class CropInterpretation(
        val stage: String,
        val stageEmoji: String,
        val stageColor: String,
        val confidencePercent: Int,
        val harvestTime: String,          // now always from scenarioHarvestTime()
        val recommendations: List<String>,
        val lowConfidenceWarning: Boolean,
        val weatherSummary: String? = null,
        val scenarioLabel: String? = null,
        val interpretationSummary: String? = null,
        val scenarioId: Int = -1,
        val anomalyFlags: List<AnomalyFlag> = emptyList()
    )

    object InterpretationEngine {

        private const val LOW_CONFIDENCE_THRESHOLD = 0.65f

        // ── Called when ScenarioClassifier is available ───────────────────────
        fun interpretWithScenario(
            result: PlantAnalysisResult,
            scenarioResult: ScenarioClassifier.InterpretationResult,
            weather: WeatherData? = null
        ): CropInterpretation {
            val confidencePercent = (result.confidence * 100).toInt()
            val lowConfidence     = result.confidence <= LOW_CONFIDENCE_THRESHOLD

            val stageEmoji = stageEmoji(result.label)
            val stageColor = stageColor(result.label)
            val harvestTime = scenarioHarvestTime(scenarioResult.scenarioId)

            val weatherSummary = weather?.let {
                val conditionText = buildWeatherConditionText(it)
                "Weather: $conditionText | ${"%.1f".format(it.temperatureCelsius)}°C, ${it.humidity}% humidity"
            }

            return CropInterpretation(
                stage                 = stageFromLabel(result.label),
                stageEmoji            = stageEmoji,
                stageColor            = stageColor,
                confidencePercent     = confidencePercent,
                harvestTime           = harvestTime,
                recommendations       = scenarioResult.recommendations,
                lowConfidenceWarning  = lowConfidence,
                weatherSummary        = weatherSummary,
                scenarioLabel         = scenarioResult.scenarioLabel,
                interpretationSummary = scenarioResult.summary,
                scenarioId            = scenarioResult.scenarioId,
                anomalyFlags          = scenarioResult.anomalyFlags
            )
        }

        // ── Fallback — no ScenarioClassifier, basic output ───────────────────
        fun interpret(result: PlantAnalysisResult): CropInterpretation {
            val confidencePercent = (result.confidence * 100).toInt()
            val lowConfidence     = result.confidence <= LOW_CONFIDENCE_THRESHOLD

            return CropInterpretation(
                stage                = stageFromLabel(result.label),
                stageEmoji           = stageEmoji(result.label),
                stageColor           = stageColor(result.label),
                confidencePercent    = confidencePercent,
                harvestTime          = fallbackHarvestTime(result.label),
                recommendations      = fallbackRecommendations(result.label),
                lowConfidenceWarning = lowConfidence
            )
        }

        // ── Kept for compatibility ────────────────────────────────────────────
        fun interpretWithWeather(result: PlantAnalysisResult, weather: WeatherData): CropInterpretation {
            val base = interpret(result)
            val conditionText = buildWeatherConditionText(weather)
            val summary = "Weather: $conditionText | ${"%.1f".format(weather.temperatureCelsius)}°C, ${weather.humidity}% humidity"
            return base.copy(weatherSummary = summary)
        }

        // ─────────────────────────────────────────────────────────────────────
        // Harvest time — driven entirely by scenario model output
        // ─────────────────────────────────────────────────────────────────────

        /**
         * Maps scenario ID → harvest time string.
         * This is the ONLY source of harvestTime — no hardcoded stage-level strings.
         */
        fun scenarioHarvestTime(scenarioId: Int): String = when (scenarioId) {
            0  -> "~8–15 weeks remaining"
            1  -> "~6–7 weeks remaining"
            2  -> "~3–5 weeks remaining"
            3  -> "Significantly delayed — assess urgently"
            4  -> "~7–14 days to harvest"
            5  -> "Harvest within 7 days — heat risk"
            6  -> "Harvest when dry — rain risk"
            7  -> "~1–2 weeks remaining — watch for vine slowdown and soil mounding near plant bases"
            8  -> "Harvest within 3–5 days"
            9  -> "Verify readiness before harvesting"
            10 -> "Harvest immediately — overdue"
            11 -> "Unable to determine — no plant detected"
            else -> "Unknown"
        }

        /**
         * Returns true when the harvest-time row is worth showing.
         * Hides for "Cannot estimate / Cannot determine / Unable / Unknown" values —
         * those situations are already communicated through interpretationText.
         */
        fun isHarvestTimeDisplayable(harvestTime: String): Boolean =
            harvestTime.isNotBlank() &&
                    !harvestTime.startsWith("Cannot estimate",      ignoreCase = true) &&
                    !harvestTime.startsWith("Cannot determine",     ignoreCase = true) &&
                    !harvestTime.startsWith("Unable to determine",  ignoreCase = true) &&
                    !harvestTime.equals("Unknown",                  ignoreCase = true)

        // Fallback only used when ScenarioClassifier is unavailable
        private fun fallbackHarvestTime(label: String): String = when (label) {
            "not_ready"     -> "Harvest not yet ready"
            "near_harvest"  -> "Approaching harvest window"
            "harvest_ready" -> "Ready to harvest now"
            else            -> "Unable to determine"
        }

        // ─────────────────────────────────────────────────────────────────────
        // Helpers
        // ─────────────────────────────────────────────────────────────────────

        private fun stageFromLabel(label: String): String = when (label) {
            "not_ready"     -> "Not Ready"
            "near_harvest"  -> "Near Harvest"
            "harvest_ready" -> "Harvest Ready"
            else            -> "Unknown"
        }

        private fun stageEmoji(label: String): String = when (label) {
            "not_ready"     -> "🌱"
            "near_harvest"  -> "🥔"
            "harvest_ready" -> "🍠"
            else            -> "⚪"
        }

        private fun stageColor(label: String): String = when (label) {
            "not_ready"     -> "red"
            "near_harvest"  -> "yellow"
            "harvest_ready" -> "green"
            else            -> "gray"
        }

        private fun fallbackRecommendations(label: String): List<String> = when (label) {
            "not_ready"     -> listOf(
                "Continue regular irrigation",
                "Monitor leaf color — should remain green",
                "Avoid early harvesting",
                "Check for pest or disease signs"
            )
            "near_harvest"  -> listOf(
                "Reduce watering slightly",
                "Check tuber size manually",
                "Prepare harvesting tools",
                "Monitor for over-maturity signs"
            )
            "harvest_ready" -> listOf(
                "Harvest now — do not delay",
                "Avoid over-maturity — quality loss risk",
                "Store in cool, dry conditions",
                "Handle tubers carefully to avoid bruising"
            )
            else            -> listOf("Please upload a clearer image for analysis")
        }

        private fun buildWeatherConditionText(weather: WeatherData): String {
            val parts = mutableListOf<String>()
            when {
                weather.temperatureCelsius >= 38 -> parts.add("extreme heat ${weather.temperatureCelsius.toInt()}°C")
                weather.temperatureCelsius >= 32 -> parts.add("hot ${weather.temperatureCelsius.toInt()}°C")
                weather.temperatureCelsius in 24f..31f -> parts.add("warm ${weather.temperatureCelsius.toInt()}°C")
                weather.temperatureCelsius < 18  -> parts.add("cold ${weather.temperatureCelsius.toInt()}°C")
            }
            when {
                weather.humidity >= 90 -> parts.add("very high humidity ${weather.humidity}%")
                weather.humidity < 40  -> parts.add("low humidity ${weather.humidity}%")
            }
            when {
                weather.precipitationMm >= 20             -> parts.add("heavy rain ${weather.precipitationMm}mm")
                weather.precipitationMm in 5f..19f        -> parts.add("moderate rain ${weather.precipitationMm}mm")
                weather.precipitationMm in 1f..4f         -> parts.add("light rain ${weather.precipitationMm}mm")
                weather.precipitationMm < 1f && weather.temperatureCelsius > 28f -> parts.add("dry conditions")
            }
            return if (parts.isEmpty()) "Normal conditions" else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        }
    }