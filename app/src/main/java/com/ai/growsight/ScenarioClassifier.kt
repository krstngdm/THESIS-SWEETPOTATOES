package com.ai.growsight.ai

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ScenarioClassifier(private val context: Context) {

    companion object {
        private const val TAG         = "ScenarioClassifier"
        private const val MODEL_PATH  = "ml/scenario_classifier.tflite"
        private const val LABELS_PATH = "ml/scenario_labels.json"
        private const val SCALER_PATH = "ml/feature_scaler.json"
        private const val N_FEATURES  = 11
        private const val N_CLASSES   = 12
    }

    data class InterpretationResult(
        val scenarioId: Int,
        val scenarioLabel: String,
        val confidence: Float,
        val summary: String,
        val recommendations: List<String>,
        val anomalyFlags: List<AnomalyFlag> = emptyList()
    )

    data class ScanHistoryEntry(
        val weekNumber: Int,
        val stage: String,
        val scenarioLabel: String,
        val timestamp: String
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Anomaly detection types
    // ─────────────────────────────────────────────────────────────────────────

    private sealed class AnomalyType {
        object None : AnomalyType()
        data class AbruptStageSkip(val fromStage: String, val toStage: String, val weekGap: Int) : AnomalyType()
        data class SevereRegression(val fromStage: String, val toStage: String) : AnomalyType()
        data class MildRegression(val fromStage: String, val toStage: String) : AnomalyType()
        data class PrematureStage(val stage: String, val week: Int, val minExpected: Int) : AnomalyType()
        data class StageStuck(val stage: String, val consecutiveScans: Int, val weeksOverdue: Int) : AnomalyType()
        data class PostHarvestReset(val weeksSinceHarvest: Int) : AnomalyType()
        data class OscillatingStage(val fromStage: String, val toStage: String) : AnomalyType()
        data class LargeGapWithJump(val weekGap: Int, val fromStage: String, val toStage: String) : AnomalyType()
        data class ConflictAmplified(val base: AnomalyType) : AnomalyType()
    }

    private enum class AnomalySeverity { LOW, MEDIUM, HIGH, CRITICAL }

    private val stageOrder = mapOf("Not Ready" to 0, "Near Harvest" to 1, "Harvest Ready" to 2)

    private val interpreter: Interpreter?     by lazy { loadInterpreter() }
    private val scalerMean: FloatArray        by lazy { loadScalerParam("mean") }
    private val scalerScale: FloatArray       by lazy { loadScalerParam("scale") }
    private val labels: Map<Int, String>      by lazy { loadLabels() }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun interpretWithHistory(
        stage: String?,
        confidence: Float,
        cropWeek: Int?,
        weather: WeatherData?,
        imageCount: Int,
        validCount: Int,
        hasConflict: Boolean,
        scanHistory: List<ScanHistoryEntry> = emptyList(),
        conversationName: String = "Your plantation",
        isQuickScan: Boolean = false,
        stageBreakdown: Map<String, Int> = emptyMap()   // per-stage image count e.g. {"Near Harvest"->2, "Not Ready"->1}
    ): InterpretationResult {

        val stageEncoded  = encodeStage(stage)
        val week          = cropWeek ?: 0
        val expectedStage = expectedStageEncoded(week)
        val gap           = if (stageEncoded >= 0) stageEncoded - expectedStage else 0

        val rawFeatures = floatArrayOf(
            stageEncoded.toFloat(), week.toFloat(), confidence, gap.toFloat(),
            weather?.temperatureCelsius ?: -1f,
            weather?.humidity?.toFloat() ?: -1f,
            weather?.precipitationMm ?: -1f,
            if (weather != null) 1f else 0f,
            imageCount.toFloat(),
            if (hasConflict) 1f else 0f,
            if (isQuickScan) 1f else 0f
        )

        val scenarioId = runInference(rawFeatures)
        val label      = labels[scenarioId] ?: "unknown"

        // Detect anomalies — this runs independently of the scenario model
        // cropWeek must be non-null: week=0 (the null default) causes false CRITICAL
        // PrematureStage alarms on any "Harvest Ready" scan with no planting date.
        val (anomaly, severity) = if (stage != null && !isQuickScan && cropWeek != null)
            detectAnomaly(stage, week, scanHistory, hasConflict, confidence)
        else
            AnomalyType.None to AnomalySeverity.LOW

        Log.d(TAG, "Scenario=$scenarioId ($label) anomaly=${anomaly::class.simpleName} severity=$severity")

        return buildDynamicResult(
            scenarioId, label, confidence, stage, week, cropWeek, weather,
            imageCount, validCount, hasConflict, scanHistory,
            conversationName, isQuickScan, anomaly, severity,
            stageBreakdown
        )
    }

    fun interpret(
        stage: String?,
        confidence: Float,
        cropWeek: Int?,
        weather: WeatherData?,
        imageCount: Int,
        validCount: Int,
        hasConflict: Boolean
    ): InterpretationResult = interpretWithHistory(
        stage = stage, confidence = confidence, cropWeek = cropWeek,
        weather = weather, imageCount = imageCount, validCount = validCount,
        hasConflict = hasConflict
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Anomaly detection — runs on plantation mode only
    // ─────────────────────────────────────────────────────────────────────────

    private fun detectAnomaly(
        currentStage: String,
        cropWeek: Int,
        scanHistory: List<ScanHistoryEntry>,
        hasConflict: Boolean,
        confidence: Float
    ): Pair<AnomalyType, AnomalySeverity> {

        if (scanHistory.isEmpty()) {
            // First scan — only check for premature detection
            return checkPremature(currentStage, cropWeek, hasConflict)
        }

        val currOrd = stageOrder[currentStage] ?: return AnomalyType.None to AnomalySeverity.LOW
        val prevEntry = scanHistory.last()
        val prevOrd   = stageOrder[prevEntry.stage] ?: return AnomalyType.None to AnomalySeverity.LOW
        val weekGap   = (cropWeek - prevEntry.weekNumber).coerceAtLeast(1)

        // ── CASE 1: Abrupt skip (Not Ready → Harvest Ready, ≤ 3 weeks) ─────────
        if (prevOrd == 0 && currOrd == 2 && weekGap <= 3) {
            val base = AnomalyType.AbruptStageSkip(prevEntry.stage, currentStage, weekGap)
            return if (hasConflict) AnomalyType.ConflictAmplified(base) to AnomalySeverity.CRITICAL
            else base to AnomalySeverity.CRITICAL
        }

        // ── CASE 2: Severe regression (2 steps backward) ─────────────────────────
        if (prevOrd - currOrd >= 2) {
            val base = AnomalyType.SevereRegression(prevEntry.stage, currentStage)
            return if (hasConflict) AnomalyType.ConflictAmplified(base) to AnomalySeverity.CRITICAL
            else base to AnomalySeverity.HIGH
        }

        // ── CASE 3: Premature detection (no history needed but check week) ────────
        val (prematureType, prematureSeverity) = checkPremature(currentStage, cropWeek, hasConflict)
        if (prematureType !is AnomalyType.None) return prematureType to prematureSeverity

        // ── CASE 4: Stage stuck AND overdue ──────────────────────────────────────
        val consecutiveSame = scanHistory.reversed().takeWhile { it.stage == currentStage }.size + 1
        if (consecutiveSame >= 4) {
            val weeksOverdue = when {
                currentStage == "Not Ready"    && cropWeek > 13 -> cropWeek - 13
                currentStage == "Near Harvest" && cropWeek > 13 -> cropWeek - 13
                else                                            -> 0
            }
            if (weeksOverdue > 0) {
                val base = AnomalyType.StageStuck(currentStage, consecutiveSame, weeksOverdue)
                return if (hasConflict) AnomalyType.ConflictAmplified(base) to AnomalySeverity.CRITICAL
                else base to AnomalySeverity.HIGH
            }
            // Stuck but not yet overdue — medium concern
            if (consecutiveSame >= 5) {
                val isApproachingTransition = when (currentStage) {
                    "Not Ready"     -> cropWeek >= 7   // meaningful near the wk8–9 transition window
                    "Near Harvest"  -> cropWeek >= 11  // meaningful near the wk13–14 transition window
                    "Harvest Ready" -> true
                    else            -> false
                }
                if (isApproachingTransition) {
                    return AnomalyType.StageStuck(currentStage, consecutiveSame, 0) to AnomalySeverity.MEDIUM
                }
            }
        }

        // ── CASE 5: Mild regression (1 step backward) ─────────────────────────────
        if (prevOrd - currOrd == 1 && cropWeek >= 4) {
            val base = AnomalyType.MildRegression(prevEntry.stage, currentStage)
            return if (hasConflict) AnomalyType.ConflictAmplified(base) to AnomalySeverity.HIGH
            else base to AnomalySeverity.MEDIUM
        }

        // ── CASE 6: Post-harvest reset ─────────────────────────────────────────────
        if (currentStage == "Not Ready") {
            val lastHarvestEntry = scanHistory.lastOrNull { it.stage == "Harvest Ready" }
            if (lastHarvestEntry != null) {
                val weeksSince = (cropWeek - lastHarvestEntry.weekNumber).coerceAtLeast(1)
                    val base6 = AnomalyType.PostHarvestReset(weeksSince)
                    return if (hasConflict) AnomalyType.ConflictAmplified(base6) to AnomalySeverity.HIGH
                    else base6 to AnomalySeverity.MEDIUM
            }
        }

        // ── CASE 7: Oscillating — current stage appeared earlier, with a different stage between ──
        if (scanHistory.size >= 2 && cropWeek >= 6) {
            val prevMatchIndex = (0 until scanHistory.size)
                .reversed()
                .firstOrNull { scanHistory[it].stage == currentStage }
            if (prevMatchIndex != null && prevMatchIndex < scanHistory.size - 1) {
                val between = scanHistory.subList(prevMatchIndex + 1, scanHistory.size)
                val hasDifferentBetween = between.any { it.stage != currentStage }
                if (hasDifferentBetween) {
                    val interveningStage = between.first { it.stage != currentStage }.stage
                    val base7 = AnomalyType.OscillatingStage(interveningStage, currentStage)
                    return if (hasConflict) AnomalyType.ConflictAmplified(base7) to AnomalySeverity.HIGH
                    else base7 to AnomalySeverity.MEDIUM
                }
            }
        }

        // ── CASE 8: Large gap with forward jump ────────────────────────────────────
        if (weekGap >= 5 && currOrd > prevOrd) {
            return AnomalyType.LargeGapWithJump(weekGap, prevEntry.stage, currentStage) to AnomalySeverity.LOW
        }

        return AnomalyType.None to AnomalySeverity.LOW
    }

    private fun checkPremature(
        stage: String, cropWeek: Int, hasConflict: Boolean
    ): Pair<AnomalyType, AnomalySeverity> {
        if (stage == "Harvest Ready" && cropWeek < 10) {
            val base = AnomalyType.PrematureStage(stage, cropWeek, 12)
            return if (hasConflict) AnomalyType.ConflictAmplified(base) to AnomalySeverity.CRITICAL
            else base to AnomalySeverity.HIGH
        }
        if (stage == "Near Harvest" && cropWeek < 8) {
            val base = AnomalyType.PrematureStage(stage, cropWeek, 9)
            return if (hasConflict) AnomalyType.ConflictAmplified(base) to AnomalySeverity.HIGH
            else base to AnomalySeverity.MEDIUM
        }
        return AnomalyType.None to AnomalySeverity.LOW
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dynamic result builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildDynamicResult(
        scenarioId: Int, label: String, modelConfidence: Float,
        stage: String?, week: Int, cropWeek: Int?,
        weather: WeatherData?,
        imageCount: Int, validCount: Int, hasConflict: Boolean,
        scanHistory: List<ScanHistoryEntry>,
        conversationName: String, isQuickScan: Boolean,
        anomaly: AnomalyType, severity: AnomalySeverity,
        stageBreakdown: Map<String, Int> = emptyMap()   // ← NEW
    ): InterpretationResult {

        // For CRITICAL and HIGH anomalies in plantation mode, the anomaly
        // overrides the normal scenario narrative entirely.
        // For MEDIUM/LOW, it prepends a warning but keeps the normal narrative.
        val overrideForAnomaly = !isQuickScan && severity in listOf(AnomalySeverity.CRITICAL, AnomalySeverity.HIGH)

        if (overrideForAnomaly && anomaly !is AnomalyType.None) {
            return buildAnomalyResult(
                anomaly, severity, scenarioId, label, modelConfidence,
                stage, week, cropWeek, weather, conversationName,
                scanHistory, hasConflict, imageCount, validCount,
                stageBreakdown
            )
        }

        // Normal narrative path (with optional medium/low warning prepended)
        val normalResult = buildNormalResult(
            scenarioId, label, modelConfidence, stage, week, cropWeek,
            weather, imageCount, validCount, hasConflict,
            scanHistory, conversationName, isQuickScan,
            stageBreakdown, anomaly, severity
        )

        if (anomaly is AnomalyType.None || isQuickScan) return normalResult

        // Prepend medium/low anomaly warning to normal result
        val warningPrefix = anomalyWarningSentence(anomaly, week, scanHistory)
        val updatedSummary = if (warningPrefix.isNotBlank())
            "⚠️ $warningPrefix ${normalResult.summary}"
        else normalResult.summary

        val warningRecs = anomalyRecommendations(anomaly, severity, stage, week, scanHistory)

        return normalResult.copy(
            summary         = updatedSummary,
            recommendations = warningRecs + normalResult.recommendations
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Anomaly-override narrative builder
    // Used when anomaly severity is CRITICAL or HIGH
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildAnomalyResult(
        anomaly: AnomalyType, severity: AnomalySeverity,
        scenarioId: Int, label: String, confidence: Float,
        stage: String?, week: Int, cropWeek: Int?,
        weather: WeatherData?,
        conversationName: String,
        scanHistory: List<ScanHistoryEntry>,
        hasConflict: Boolean,
        imageCount: Int, validCount: Int,
        stageBreakdown: Map<String, Int> = emptyMap()   // ← NEW
    ): InterpretationResult {

        val nameStr    = conversationName.trim().ifBlank { "Your plantation" }
        val weekStr    = if (cropWeek != null && cropWeek > 0) "Week $cropWeek" else "this week"
        val prevEntry  = scanHistory.lastOrNull()

        val summary: String
        val recommendations: MutableList<String> = mutableListOf()

        val baseAnomaly = if (anomaly is AnomalyType.ConflictAmplified) anomaly.base else anomaly
        val conflictNote = if (anomaly is AnomalyType.ConflictAmplified)
            " The submitted images also showed conflicting maturity signals, which further reduces confidence in this result." else ""

        when (baseAnomaly) {

            // ── CASE 1: Abrupt stage skip ──────────────────────────────────────────
            is AnomalyType.AbruptStageSkip -> {
                val gap = baseAnomaly.weekGap
                val gapStr = if (gap == 1) "just 1 week" else "$gap weeks"
                summary = "⚠️ Abnormal result for $nameStr at $weekStr. The AI detected '${baseAnomaly.toStage}' — but the previous scan (${prevEntry?.timestamp ?: "last scan"}) showed '${baseAnomaly.fromStage}'. " +
                        "This is a $gapStr jump that skips 'Near Harvest' entirely, which is not biologically possible for sweet potato. " +
                        "Do not make any harvest decision based on this result.$conflictNote"
                recommendations += "🚨 Do NOT harvest — this result is flagged as abnormal and unreliable"
                recommendations += "Photograph the same plant again from directly above the canopy — one clear, unobstructed image"
                recommendations += "Confirm you are photographing the same plants as previous scans, not a different area of the field"
                recommendations += "If the re-scan also shows Harvest Ready, look for supporting signs: vine yellowing, canopy thinning, and cracked soil around mounds"
                if (hasConflict) recommendations += "Multiple images showed different maturity levels this week — inconsistent photography is likely the cause"
                recommendations += "Note: The crop was '${baseAnomaly.fromStage}' last scan — physically, it cannot reach full harvest readiness in $gapStr"
            }

            // ── CASE 2: Severe regression (2 stages back) ──────────────────────────
            is AnomalyType.SevereRegression -> {
                summary = "⚠️ Significant inconsistency detected for $nameStr at $weekStr. The previous scan showed '${baseAnomaly.fromStage}' — now '${baseAnomaly.toStage}' is detected. " +
                        "Sweet potato maturity does not reverse by two stages. This almost certainly means the wrong plant or field area was photographed this week.$conflictNote"
                recommendations += "🚨 This result should not influence any crop management decision"
                recommendations += "Re-photograph the exact same plant locations used in previous scans"
                recommendations += "Mark your monitoring plants with a stake or GPS pin to ensure consistency between scans"
                recommendations += "If the re-scan shows the same regression, consult an agricultural extension officer — severe crop health decline is possible but rare"
                recommendations += "Check whether the field was replanted in any section — a new crop in the same conversation would explain this"
            }

            // ── CASE 3: Premature detection ────────────────────────────────────────
            is AnomalyType.PrematureStage -> {
                val minExp = baseAnomaly.minExpected
                val earlyBy = (minExp - week).coerceAtLeast(1)
                summary = "⚠️ The AI detected '${baseAnomaly.stage}' for $nameStr at only $weekStr — approximately $earlyBy ${if (earlyBy == 1) "week" else "weeks"} earlier than the minimum expected window. " +
                        "Sweet potato varieties in this region typically need $minExp+ weeks before reaching this stage. " +
                        "This is most likely a false positive.$conflictNote"
                recommendations += "Do not act on this result without above-ground confirmation"
                recommendations += "Observe vine condition: genuine early maturity typically shows canopy thinning and lower leaf yellowing alongside the stage signal"
                recommendations += "Re-submit photos from multiple different plants in different field sections"
                recommendations += "If canopy and vine signs match the early maturity signal, some varieties do mature ahead of schedule — proceed carefully"
            }

            // ── CASE 4: Stage stuck overdue ────────────────────────────────────────
            is AnomalyType.StageStuck -> {
                val over = baseAnomaly.weeksOverdue
                val overStr = if (over > 0) "$over ${if (over == 1) "week" else "weeks"} past the expected transition point" else "for ${baseAnomaly.consecutiveScans} consecutive scans"
                summary = "⚠️ $nameStr has shown '${baseAnomaly.stage}' stage for ${baseAnomaly.consecutiveScans} consecutive scans now — $overStr. " +
                        "Prolonged stage stagnation at $weekStr usually signals an agronomic problem: soil deficiency, water stress, disease, or root pest damage. " +
                        "The crop is not progressing as expected and requires a physical investigation this week."
                recommendations += if (over > 0) "🚨 Observe for sudden wilting, yellowing patches, or collapsed vines — the crop should have progressed by now" else "Investigate why the crop is not advancing — check irrigation consistency and observe for yellowing or stress patterns"
                recommendations += "Test or estimate soil pH and nutrient levels — potassium and phosphorus deficiency commonly stall progression"
                recommendations += "Check the past ${baseAnomaly.consecutiveScans} weeks of irrigation records — water stress is the leading cause of stagnation"
                recommendations += "Inspect vine stems at soil level for dark spots or tunneling marks — above-ground indicators of root pest activity"
                if (over >= 3) recommendations += "🚨 Partial harvest may be necessary to recover some yield — assess with your agricultural extension officer"
                if (weather != null) recommendations += weatherRiskNote(weather)
            }

            // ── CASE 5: MildRegression via ConflictAmplified — only reaches here at HIGH severity ──
            is AnomalyType.MildRegression -> {
                summary = "⚠️ Inconsistent scan result for $nameStr at $weekStr. " +
                        "The detected stage ('${baseAnomaly.toStage}') is one step behind the previous result ('${baseAnomaly.fromStage}'), " +
                        "and the submitted images showed conflicting maturity signals. " +
                        "The combined inconsistency makes this result unreliable for any crop management decision.$conflictNote"
                recommendations += "Re-photograph the same plant locations used in previous scans before taking any action"
                recommendations += "Submit a single clear image from directly above the plot canopy to eliminate conflicting signals between images"
                recommendations += "If the regression persists in the re-scan, inspect for uneven watering or fertiliser distribution across the field"
                if (weather != null) recommendations += weatherRiskNote(weather)
            }

            else -> {
                // Should not reach here for CRITICAL/HIGH — fall through to normal
                return buildNormalResult(
                    scenarioId, label, confidence, stage, week, cropWeek,
                    weather, imageCount, validCount, hasConflict,
                    scanHistory, conversationName, false,
                    stageBreakdown
                )
            }
        }

        // ── Image tally ────────────────────────────────────────────────────────
        if (imageCount > 1 && stageBreakdown.isNotEmpty()) {
            val tally = buildImageTallyText(imageCount, validCount, stageBreakdown)
            if (tally.isNotBlank()) recommendations += "📷 $tally"
        }

        // Append image quality notes
        if (imageCount > 1 && validCount < imageCount) {
            recommendations += "📊 Only $validCount of $imageCount submitted images were analysed — resubmit with all clear images for a reliable re-scan"
        }

        return InterpretationResult(
            scenarioId      = scenarioId,
            scenarioLabel   = label,
            confidence      = confidence,
            summary         = summary,
            recommendations = recommendations,
            anomalyFlags    = buildAnomalyFlags(anomaly, severity, confidence, scenarioId, weather, hasConflict, week)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Anomaly helpers — for MEDIUM/LOW (prepended to normal narrative)
    // ─────────────────────────────────────────────────────────────────────────

    private fun anomalyWarningSentence(
        anomaly: AnomalyType,
        week: Int,
        history: List<ScanHistoryEntry>
    ): String {
        val prevEntry = history.lastOrNull()
        return when (val base = if (anomaly is AnomalyType.ConflictAmplified) anomaly.base else anomaly) {
            is AnomalyType.MildRegression ->
                "Note: the detected stage ('${base.toStage}') is one step behind the previous scan ('${base.fromStage}') — possible photography inconsistency."
            is AnomalyType.PostHarvestReset ->
                "Note: a previous scan in this plantation recorded 'Harvest Ready' — if this is the same crop, this result is inconsistent."
            is AnomalyType.OscillatingStage -> {
                val prevMatchIndex = (0 until history.size)
                    .reversed()
                    .firstOrNull { history[it].stage == base.toStage }
                val sinceDesc = if (prevMatchIndex != null && prevMatchIndex < history.size)
                    "since Week ${history[prevMatchIndex].weekNumber}"
                else "over recent scans"
                "Note: the scan result has alternated between '${base.fromStage}' and '${base.toStage}' $sinceDesc — photography consistency may be affecting results."
            }
            is AnomalyType.StageStuck ->
                "Note: the crop has shown '${base.stage}' for ${base.consecutiveScans} consecutive scans."
            is AnomalyType.LargeGapWithJump ->
                "Note: ${base.weekGap} weeks passed since the last scan — the stage change from '${base.fromStage}' to '${base.toStage}' is plausible given the gap."
            else -> ""
        }
    }

    private fun anomalyRecommendations(
        anomaly: AnomalyType, severity: AnomalySeverity,
        stage: String?, week: Int,
        history: List<ScanHistoryEntry>
    ): List<String> {
        val recs = mutableListOf<String>()
        val base = if (anomaly is AnomalyType.ConflictAmplified) anomaly.base else anomaly
        val conflictExtra = if (anomaly is AnomalyType.ConflictAmplified)
            "Resubmit photos from a single consistent plant area — conflicting images reduced accuracy this week" else ""

        when (base) {
            is AnomalyType.MildRegression -> {
                recs += "Verify you photographed the same plants as previous scans — consistent camera position matters"
                if (conflictExtra.isNotBlank()) recs += conflictExtra
            }
            is AnomalyType.PostHarvestReset -> {
                recs += "Confirm whether this is the same crop cycle or a new planting — if new, create a separate plantation entry"
                if (conflictExtra.isNotBlank()) recs += conflictExtra
            }
            is AnomalyType.OscillatingStage -> {
                recs += "Use a consistent photographing method: same time of day, same distance, same plant group each week"
                recs += "Mark 3–5 specific plants with stakes and photograph only those plants each week"
                if (conflictExtra.isNotBlank()) recs += conflictExtra
            }
            is AnomalyType.LargeGapWithJump -> {
                recs += "Large gaps between scans reduce accuracy — try to maintain the weekly monitoring schedule"
                recs += "Verify by observing vine condition and checking for soil cracking near mounds given the ${base.weekGap}-week gap"
            }
            else -> {}
        }
        return recs
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Normal narrative builder (unchanged logic from previous version)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNormalResult(
        scenarioId: Int, label: String, modelConfidence: Float,
        stage: String?, week: Int, cropWeek: Int?,
        weather: WeatherData?,
        imageCount: Int, validCount: Int, hasConflict: Boolean,
        scanHistory: List<ScanHistoryEntry>,
        conversationName: String, isQuickScan: Boolean,
        stageBreakdown: Map<String, Int> = emptyMap(),
        anomaly: AnomalyType = AnomalyType.None,
        severity: AnomalySeverity = AnomalySeverity.LOW
    ): InterpretationResult {

        val nameStr   = conversationName.trim().ifBlank { "Your plantation" }
        val weekStr   = if (cropWeek != null && cropWeek > 0) "Week $cropWeek" else "your crop"
        val tempStr   = weather?.let { "${"%.1f".format(it.temperatureCelsius)}°C" }
        val precipStr = weather?.let { "${it.precipitationMm}mm" }
        val humidStr  = weather?.let { "${it.humidity}%" }

        val prevEntry = scanHistory.lastOrNull()
        val progressionNote = if (!isQuickScan && prevEntry != null && stage != null) {
            val prev = stageOrder[prevEntry.stage] ?: -1
            val curr = stageOrder[stage] ?: -1
            when {
                curr > prev  -> "Since Week ${prevEntry.weekNumber}, the crop has advanced from '${prevEntry.stage}' to '$stage'. "
                curr == prev -> {
                    val firstInStreak = scanHistory.reversed()
                        .takeWhile { it.stage == stage }
                        .lastOrNull()
                    val sinceWeek = firstInStreak?.weekNumber ?: prevEntry.weekNumber
                    "The stage has remained at '$stage' since Week $sinceWeek. "
                }
                else         -> ""
            }
        } else null

        val historyNote = if (!isQuickScan && scanHistory.isNotEmpty())
            "This is scan ${scanHistory.size + 1} for $nameStr. " else null

        val weatherPhrase: String? = weather?.let { w ->
            when {
                w.temperatureCelsius >= 38f   -> "with extreme heat at ${tempStr},"
                w.temperatureCelsius >= 33f   -> "under high heat (${tempStr}),"
                w.precipitationMm >= 20f      -> "with heavy rain today (${precipStr}),"
                w.precipitationMm in 10f..19.9f -> "with moderate rainfall (${precipStr}),"
                w.humidity >= 90              -> "with very high humidity (${humidStr}),"
                else                          -> null
            }
        }

        var summary: String
        val recommendations: MutableList<String> = mutableListOf()

        when (scenarioId) {
            0 -> {
                summary = if (isQuickScan) {
                    "Scan result: 'Not Ready' — this is normal for an early-stage crop. No immediate action required."
                } else {
                    val wPhrase = weatherPhrase?.let { " $it" } ?: ""
                    "${historyNote ?: ""}${nameStr} is right on track.$wPhrase $weekStr shows 'Not Ready' maturity — exactly what's expected at this point. ${progressionNote ?: ""}Continue your standard management."
                }
                if (!isQuickScan) {
                    recommendations += "Maintain your current watering schedule — the crop is developing normally"
                    if (week in 1..4) recommendations += "Apply starter fertilizer if not yet done this cycle"
                    else recommendations += "Monitor soil nutrition — apply balanced fertilizer if growth looks sluggish"
                    if (weather != null && weather.humidity >= 85)
                        recommendations += "💧 High humidity (${humidStr}) — inspect for early fungal symptoms"
                    recommendations += "Check for pests: sweet potato weevil, aphids, and leaf miners"
                    recommendations += "Next scan recommended at Week ${(week + 2).coerceAtMost(16)}"
                } else {
                    recommendations += "Continue regular irrigation and fertilization"
                    recommendations += "Monitor for pest or disease signs"
                    recommendations += "Re-scan in 2–3 weeks if tracking progress"
                }
            }
            1 -> {
                summary = if (isQuickScan) {
                    "The crop shows 'Not Ready' at $weekStr — slightly behind schedule. Check soil moisture and nutrition."
                } else {
                    val wPhrase = weatherPhrase?.let { " Current conditions $it" } ?: ""
                    "${nameStr} at $weekStr is showing 'Not Ready' — slightly behind the expected timeline.$wPhrase ${progressionNote ?: ""}Usually self-corrects with targeted care."
                }
                recommendations += "Check soil moisture at 10cm — water stress is the most common cause of delayed maturity"
                recommendations += "Consider a potassium-rich fertilizer application to boost root bulking"
                if (weather != null && weather.temperatureCelsius > 30f)
                    recommendations += "🌡️ High temperature (${tempStr}) may be slowing development — irrigate in the evening"
                recommendations += "Watch for soil swelling or cracking near vine bases — a natural sign roots are starting to bulk"
                if (!isQuickScan) recommendations += "Re-scan at Week ${(week + 2).coerceAtMost(20)}"
            }
            2 -> {
                val weeksLate = (week - 10).coerceAtLeast(1)
                summary = if (isQuickScan) {
                    "The crop is showing 'Not Ready' at $weekStr — noticeably behind schedule. A physical check is recommended."
                } else {
                    val wPhrase = weatherPhrase?.let { " $it" } ?: ""
                    "${nameStr} is${wPhrase} behind schedule at $weekStr — approximately $weeksLate ${if (weeksLate == 1) "week" else "weeks"} late. ${progressionNote ?: ""}A physical field check is needed this week."
                }
                recommendations += "🔍 Observe lower leaf yellowing and soil surface around mounds — above-ground signs of delayed development"
                recommendations += "Check soil pH — sweet potato grows best at 5.8–6.2"
                recommendations += "Review the past 2 weeks of irrigation — inconsistency delays root bulking"
                recommendations += "Inspect leaves for disease signs: yellowing, spots, or stunted growth"
                if (weather != null) recommendations += weatherRiskNote(weather)
            }
            3 -> {
                val weeksLate = (week - 12).coerceAtLeast(2)
                summary = if (isQuickScan) {
                    "Critically behind schedule at $weekStr — immediate physical inspection needed."
                } else {
                    val wPhrase = weatherPhrase?.let { " $it" } ?: ""
                    "${nameStr} is${wPhrase} significantly behind at $weekStr — $weeksLate ${if (weeksLate == 1) "week" else "weeks"} past the expected harvest window with 'Not Ready' still detected. ${progressionNote ?: ""}Urgent field assessment required today."
                }
                recommendations += "🚨 Observe vines for sudden wilting, rapid yellowing, or collapse — critical above-ground stress indicators"
                recommendations += "Check for root-knot nematode damage — a common cause of this pattern"
                recommendations += "Evaluate whether a partial harvest of the most developed plants can recover some yield"
                if (weather != null && weather.temperatureCelsius > 33f)
                    recommendations += "🌡️ Extreme heat at ${tempStr} is actively stressing the crop"
                recommendations += "Record this outcome — the data helps diagnose the cause after harvest"
            }
            4 -> {
                summary = if (isQuickScan) {
                    "The crop is at 'Near Harvest' — getting close. Prepare harvesting tools and check readiness within 1–2 weeks."
                } else {
                    val wPhrase = weatherPhrase?.let { " $it" } ?: ""
                    "${nameStr} is approaching harvest maturity at $weekStr.$wPhrase ${progressionNote ?: ""}Begin preparing harvest logistics. Watch for yellowing lower leaves and firm roots."
                }
                recommendations += "Watch for yellowing lower leaves and cracked soil near mounds — natural harvest readiness signals"
                recommendations += "Reduce irrigation slightly to begin skin-set hardening"
                recommendations += "Prepare and clean harvesting tools and storage space"
                if (!isQuickScan) recommendations += "Plan harvest for a dry day — wet conditions damage roots"
                recommendations += "Harvest in the early morning (5–8am)"
            }
            5 -> {
                val tempVal = tempStr ?: "high temperature"
                summary = if (isQuickScan) {
                    "The crop is nearing harvest but current heat ($tempVal) is a risk. Consider harvesting earlier."
                } else {
                    "${nameStr} is approaching harvest at $weekStr, but heat at ${tempVal} risks quality loss. ${progressionNote ?: ""}Bring harvest forward by 3–5 days."
                }
                recommendations += "🌡️ Heat risk: move harvest forward 3–5 days"
                recommendations += "Irrigate in the early morning to cool the root zone"
                recommendations += "Apply mulch around vines to insulate soil from surface heat"
                recommendations += "Harvest before 9am — roots degrade faster in midday heat"
                recommendations += "Move roots to a shaded, ventilated space immediately after harvest"
            }
            6 -> {
                val pStr = precipStr ?: "significant rainfall"
                summary = if (isQuickScan) {
                    "The crop is near harvest but current rainfall ($pStr) creates waterlogging risk. Wait for soil to dry."
                } else {
                    "${nameStr} is approaching harvest at $weekStr, but rainfall ($pStr) raises waterlogging risk. ${progressionNote ?: ""}Hold off harvesting until soil drains."
                }
                recommendations += "🌧️ Delay harvest until soil is workable — wet conditions bruise roots"
                recommendations += "Check field drainage — clear any blocked channels"
                recommendations += "Stop all irrigation"
                if (weather != null && weather.humidity >= 85)
                    recommendations += "💧 High humidity (${humidStr}) combined with rain — inspect for fungal blight"
                recommendations += "Plan harvest for the first dry morning"
            }
            7 -> {
                val weeksLate = (week - 13).coerceAtLeast(1)
                summary = if (isQuickScan) {
                    "The crop is at 'Near Harvest' but appears overdue at $weekStr. A physical root check is needed."
                } else {
                    val wPhrase = weatherPhrase?.let { " $it" } ?: ""
                    "${nameStr} is${wPhrase} at 'Near Harvest' at $weekStr — $weeksLate ${if (weeksLate == 1) "week" else "weeks"} past the expected transition. ${progressionNote ?: ""}Quality may be declining."
                }
                recommendations += "⏰ Look for vine yellowing, leaf die-back, and cracked soil near mounds — above-ground harvest readiness indicators"
                recommendations += "Over-mature roots become starchy — sample-taste before full harvest"
                recommendations += "Re-scan next week to detect the transition to Harvest Ready"
            }
            8 -> {
                val extremeHeat   = weather != null && weather.temperatureCelsius >= 38f
                val heavyRain     = weather != null && weather.precipitationMm >= 20f
                summary = if (isQuickScan) {
                    when {
                        extremeHeat -> "Harvest Ready at $weekStr — extreme heat detected. Harvest today or tomorrow to prevent quality loss."
                        heavyRain   -> "Harvest Ready at $weekStr — heavy rain detected. Harvest as soon as the field dries."
                        else        -> "Harvest Ready at $weekStr — the crop is within the optimal harvest window. Plan harvest within 3–5 days."
                    }
                } else {
                    val wPhrase = weatherPhrase?.let { " $it" } ?: ""
                    when {
                        extremeHeat -> "Excellent result for ${nameStr}.$wPhrase Harvest Ready at $weekStr — but extreme heat at ${tempStr} accelerates quality loss. ${progressionNote ?: ""}Harvest today or tomorrow."
                        heavyRain   -> "Excellent result for ${nameStr}.$wPhrase Harvest Ready at $weekStr — but heavy rain (${precipStr}) creates rot risk once harvested. ${progressionNote ?: ""}Harvest as soon as the field dries."
                        else        -> "Excellent result for ${nameStr}.$wPhrase Harvest Ready at $weekStr is optimal timing. ${progressionNote ?: ""}Harvest within 3–5 days for peak quality."
                    }
                }
                when {
                    extremeHeat -> {
                        recommendations += "🌡️ Extreme heat: harvest today or tomorrow — do not wait 3–5 days"
                        recommendations += "Harvest before 9am — roots degrade rapidly in midday heat"
                        recommendations += "Move harvested roots to a shaded, ventilated space immediately"
                        recommendations += "Handle roots gently — bruising accelerates rot"
                        recommendations += "Cure at 29–32°C and 85–90% humidity for 4–7 days"
                    }
                    heavyRain -> {
                        recommendations += "🌧️ Heavy rain: hold harvest until soil drains — wet conditions bruise roots"
                        recommendations += "Check field drainage — clear any blocked channels now"
                        recommendations += "Plan harvest for the first dry morning"
                        recommendations += "Handle roots gently — bruising accelerates rot"
                        recommendations += "Cure at 29–32°C and 85–90% humidity for 4–7 days"
                    }
                    else -> {
                        recommendations += "✅ Harvest within the next 3–5 days"
                        recommendations += "Harvest in early morning (5–8am)"
                        recommendations += "Handle roots gently — bruising accelerates rot"
                        recommendations += "Cure at 29–32°C and 85–90% humidity for 4–7 days"
                        if (weather != null) recommendations += weatherStorageNote(weather)
                    }
                }
            }
            9 -> {
                summary = if (isQuickScan) {
                    "Harvest Ready signals at $weekStr — earlier than the 12–16 week window. Verify before harvesting."
                } else {
                    val wPhrase = weatherPhrase?.let { " $it" } ?: ""
                    "${nameStr} is showing 'Harvest Ready' at $weekStr — earlier than expected.$wPhrase ${progressionNote ?: ""}Do not harvest without confirming above-ground readiness signs."
                }
                recommendations += "🔍 Verify by observing canopy thinning, lower leaf yellowing, and soil cracking near mounds — non-destructive readiness signs"
                recommendations += "If vine yellowing and canopy thinning both match the scan result, some varieties do mature ahead of schedule"
                recommendations += "Re-scan in 1–2 weeks — if Harvest Ready persists alongside visible vine die-back, the signal is genuine"
            }
            10 -> {
                val weeksOver = (week - 16).coerceAtLeast(1)
                summary = if (isQuickScan) {
                    "The crop is past the optimal harvest window at $weekStr. Harvest as soon as possible."
                } else {
                    val wPhrase = weatherPhrase?.let { " $it" } ?: ""
                    "${nameStr} is${wPhrase} past the harvest window at $weekStr — $weeksOver ${if (weeksOver == 1) "week" else "weeks"} overdue. ${progressionNote ?: ""}Every additional day reduces quality."
                }
                recommendations += "⚠️ Harvest immediately"
                recommendations += "Prioritise roots showing cracking or surface damage"
                recommendations += "Expect higher starch and lower sweetness — adjust market plan"
                if (weather != null && weather.temperatureCelsius >= 38f)
                    recommendations += "🌡️ Extreme heat (${tempStr}) is compounding quality loss — harvest today, no delay"
                if (weather != null && weather.precipitationMm > 5f)
                    recommendations += "🌧️ Current rain (${precipStr}) increases rot risk — prioritise harvest today"
            }
            else -> {
                summary = if (isQuickScan) {
                    "No sweet potato plant was detected. Try again with clearer photos closer to the canopy."
                } else {
                    "The AI was unable to detect a sweet potato plant in the images submitted for ${nameStr} this week. No assessment can be made — please resubmit."
                }
                recommendations += "Take photos in good natural light — avoid harsh shadows"
                recommendations += "Position the camera 30–60cm directly above the canopy"
                recommendations += "Ensure the full leaf canopy is visible"
                recommendations += "Avoid shooting when vines are wet"
            }
        }

        // ── Image Tally ───────────────────────────────────────────────────────
        if (!isQuickScan && imageCount > 1 && stageBreakdown.isNotEmpty() && scenarioId != 11) {
            val tally = buildImageTallyText(imageCount, validCount, stageBreakdown)
            if (tally.isNotBlank()) summary = "$summary\n\n📷 $tally"
        }

        // ── Growth Progress Highlight ─────────────────────────────────────────
        if (!isQuickScan && scanHistory.size >= 1 && stage != null && scenarioId != 11) {
            val progress = buildGrowthProgressText(scanHistory, stage, week)
            if (progress.isNotBlank()) summary = "$summary\n\n$progress"
        }

        if (hasConflict && scenarioId != 11) {
            recommendations += "⚠️ Images showed mixed maturity signals — result is based on majority. Resubmit from a consistent plant area."
        }
        if (!isQuickScan && imageCount > 1) {
            val skipped = imageCount - validCount
            if (skipped > 0) recommendations += "📊 $validCount of $imageCount images were analysed ($skipped unclear)"
        }

        return InterpretationResult(
            scenarioId      = scenarioId,
            scenarioLabel   = label,
            confidence      = modelConfidence,
            summary         = summary,
            recommendations = recommendations,
            anomalyFlags    = buildAnomalyFlags(anomaly, severity, modelConfidence, scenarioId, weather, hasConflict, week)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TFLite inference
    // ─────────────────────────────────────────────────────────────────────────
    // ── Image tally helper ──────────────────────────────────────────────────
    // Produces: "3 images scanned — 2 images are Near Harvest and 1 image is Not Ready."
    // Returns blank string if imageCount <= 1 or all images agreed (no noise added for clean scans).
    private fun buildImageTallyText(
        imageCount: Int,
        validCount: Int,
        stageBreakdown: Map<String, Int>
    ): String {
        if (imageCount <= 1 || stageBreakdown.isEmpty()) return ""
        // Unanimous agreement — not worth showing
        if (stageBreakdown.size == 1) return ""

        val parts = stageBreakdown.entries
            .sortedByDescending { it.value }
            .map { (stage, count) ->
                "$count ${if (count == 1) "image is" else "images are"} $stage"
            }

        val header = "$imageCount images scanned"
        return when (parts.size) {
            2    -> "$header — ${parts[0]} and ${parts[1]}."
            3    -> "$header — ${parts[0]}, ${parts[1]}, and ${parts[2]}."
            else -> {
                val last = parts.last()
                val rest = parts.dropLast(1).joinToString(", ")
                "$header — $rest, and $last."
            }
        }
    }

    // ── Growth progress helper ──────────────────────────────────────────────
    // Builds a compact arc from full scan history: groups consecutive same-stage
    // entries into week ranges.
    // e.g. "🌱 Growth progress: Not Ready (Wks 1–4) → Near Harvest (Wks 5–7) → Harvest Ready (now, Wk 9)."
    private fun buildGrowthProgressText(
        scanHistory: List<ScanHistoryEntry>,
        currentStage: String,
        currentWeek: Int
    ): String {
        if (scanHistory.isEmpty()) return ""

        data class ScanPoint(val week: Int, val stage: String)

        val allPoints = (scanHistory.map { ScanPoint(it.weekNumber, it.stage) } +
                ScanPoint(currentWeek, currentStage))
            .sortedBy { it.week }
            .filter { it.stage.isNotBlank() && it.stage != "no_detection" }

        if (allPoints.size < 2) return ""

        // Group consecutive entries with the same stage
        data class StageGroup(val stage: String, val startWeek: Int, var endWeek: Int)

        val groups = mutableListOf<StageGroup>()
        for (point in allPoints) {
            val last = groups.lastOrNull()
            if (last != null && last.stage == point.stage) {
                last.endWeek = point.week
            } else {
                groups += StageGroup(point.stage, point.week, point.week)
            }
        }

        if (groups.size < 2) return ""

        val lastGroup = groups.last()
        val arc = groups.joinToString(" → ") { g ->
            when {
                g === lastGroup && g.startWeek == g.endWeek ->
                    "${g.stage} (now, Wk ${g.startWeek})"
                g === lastGroup ->
                    "${g.stage} (Wks ${g.startWeek}–${g.endWeek}, current)"
                g.startWeek == g.endWeek ->
                    "${g.stage} (Wk ${g.startWeek})"
                else ->
                    "${g.stage} (Wks ${g.startWeek}–${g.endWeek})"
            }
        }
        return "🌱 Growth progress: $arc."
    }

    private fun buildAnomalyFlags(
        anomaly: AnomalyType,
        severity: AnomalySeverity,
        confidence: Float,
        scenarioId: Int,
        weather: WeatherData?,
        hasConflict: Boolean,
        week: Int = 0
    ): List<AnomalyFlag> {
        val flags = mutableListOf<AnomalyFlag>()
        val sev = severity.name.lowercase()

        // ── 1. Structural anomaly from scan history ───────────────────────────
        val base = if (anomaly is AnomalyType.ConflictAmplified) anomaly.base else anomaly
        when (base) {
            is AnomalyType.AbruptStageSkip -> flags += AnomalyFlag(
                badgeLabel = "Abrupt Stage Jump",
                severity   = "critical",
                detail     = "Crop jumped from '${base.fromStage}' to '${base.toStage}' in only ${base.weekGap} week(s) — biologically unlikely.",
                suggestion = "Verify planting date in your profile. Manually check tubers in the field. Camera angle or lighting change may be causing misclassification."
            )
            is AnomalyType.SevereRegression -> flags += AnomalyFlag(
                badgeLabel = "Stage Regression",
                severity   = sev,
                detail     = "Stage dropped 2 steps backward from '${base.fromStage}' to '${base.toStage}'.",
                suggestion = "Inspect for root rot, pest damage, or waterlogging. Check that photos are from the same field area as previous scans."
            )
            is AnomalyType.MildRegression -> flags += AnomalyFlag(
                badgeLabel = "Mild Regression",
                severity   = sev,
                detail     = "Stage appears to have moved backward from '${base.fromStage}' to '${base.toStage}'.",
                suggestion = "May be a lighting issue. Resubmit in consistent daylight. If confirmed, inspect for plant stress or disease."
            )
            is AnomalyType.PrematureStage -> flags += AnomalyFlag(
                badgeLabel = "Premature Stage",
                severity   = sev,
                detail     = "'${base.stage}' detected at week ${base.week}, but not expected before week ${base.minExpected}.",
                suggestion = "Confirm your planting date is accurate. Check if fertilizer use or variety differences are accelerating growth."
            )
            is AnomalyType.StageStuck -> flags += AnomalyFlag(
                badgeLabel = "Growth Stalled",
                severity   = sev,
                detail     = "'${base.stage}' has persisted for ${base.consecutiveScans} consecutive scans, now ${base.weeksOverdue} week(s) overdue.",
                suggestion = "Check irrigation consistency and observe vine health — stress patterns show in leaf color before underground. Consider soil testing for nutrients. Consult an agronomist if stagnation persists."
            )
            is AnomalyType.PostHarvestReset -> flags += AnomalyFlag(
                badgeLabel = "Post-Harvest Reset",
                severity   = "medium",
                detail     = "Stage reset detected ${base.weeksSinceHarvest} week(s) after a previous harvest-ready signal.",
                suggestion = "If this is a new planting cycle, update your planting date in the profile. Otherwise verify this is not a data error."
            )
            is AnomalyType.OscillatingStage -> flags += AnomalyFlag(
                badgeLabel = "Unstable Stage",
                severity   = sev,
                detail     = "Stage is oscillating between '${base.fromStage}' and '${base.toStage}' across recent scans.",
                suggestion = "Ensure photos come from the same plot area each scan. Oscillation may indicate uneven crop development across your field."
            )
            is AnomalyType.LargeGapWithJump -> flags += AnomalyFlag(
                badgeLabel = "Rapid Progress After Gap",
                severity   = sev,
                detail     = "Stage jumped from '${base.fromStage}' to '${base.toStage}' after a ${base.weekGap}-week gap with no scan.",
                suggestion = "Resume regular weekly scanning. The jump may be real if weeks were genuinely missed."
            )
            else -> {} // AnomalyType.None — no flag added
        }

        // ── 2. No plant detected ──────────────────────────────────────────────
        if (scenarioId == 11) flags += AnomalyFlag(
            badgeLabel = "No Plant Detected",
            severity   = "high",
            detail     = "No sweet potato plant was found in one or more images.",
            suggestion = "Retake photos with the leaf canopy filling the frame. Avoid bare soil or sky shots. Ensure camera focus is sharp."
        )

        // ── 3. Low model confidence ───────────────────────────────────────────
        if (confidence < 0.65f && scenarioId != 11) flags += AnomalyFlag(
            badgeLabel = "Low Confidence",
            severity   = "medium",
            detail     = "Model confidence is ${(confidence * 100).toInt()}% — below the reliable 65% threshold.",
            suggestion = "Retake in natural daylight with leaves fully visible. Avoid heavy shadows, blur, or overexposure."
        )

        // ── 4. Overdue harvest ────────────────────────────────────────────────
        if (scenarioId == 7 && flags.none { it.badgeLabel == "Growth Stalled" }) flags += AnomalyFlag(
            badgeLabel = "Overdue At Stage",
            severity   = "high",
            detail     = "Crop is at Near Harvest at Week $week — past the expected transition window.",
            suggestion = "Watch for vine slowdown, reduced new leaf growth, and soil mounding or bulging near the base of plants — reliable above-ground indicators in warm climates."
        )
        if (scenarioId == 10) flags += AnomalyFlag(
            badgeLabel = "Overdue Harvest",
            severity   = "critical",
            detail     = "Crop is past the optimal harvest window — quality and marketability are declining.",
            suggestion = "Harvest immediately. Check for cracked or over-starched tubers. Prioritize early morning or late afternoon for handling."
        )

        // ── 5. Conflicting image results ──────────────────────────────────────
        if (hasConflict && scenarioId != 11) flags += AnomalyFlag(
            badgeLabel = "Mixed Results",
            severity   = "medium",
            detail     = "Uploaded images showed different maturity stages — result is based on the majority vote.",
            suggestion = "Resubmit photos from a single consistent plot area. Mixed signals may indicate uneven field development."
        )

        // ── 6. Extreme weather ────────────────────────────────────────────────
        weather?.let { w ->
            if (w.temperatureCelsius >= 38) flags += AnomalyFlag(
                badgeLabel = "Extreme Heat",
                severity   = "high",
                detail     = "Temperature is ${w.temperatureCelsius.toInt()}°C — well above safe range for sweet potato.",
                suggestion = "Irrigate during early morning or late afternoon. Consider shade netting if heat persists for multiple days."
            )
            if (w.precipitationMm >= 20) flags += AnomalyFlag(
                badgeLabel = "Heavy Rain",
                severity   = "medium",
                detail     = "Heavy rainfall (${w.precipitationMm.toInt()}mm) increases waterlogging and fungal disease risk.",
                suggestion = "Clear drainage channels immediately. Delay harvest if soil is saturated to avoid tuber bruising."
            )
            if (w.humidity >= 90) flags += AnomalyFlag(
                badgeLabel = "High Humidity",
                severity   = "low",
                detail     = "Humidity at ${w.humidity}% — elevated risk of fungal or bacterial infection on leaves.",
                suggestion = "Inspect leaves for powdery mildew or blight. Improve air circulation around the canopy if possible."
            )
            // ── Gap 1: Drought / dry stress ───────────────────────────────
            if (w.precipitationMm < 1f && w.temperatureCelsius > 28f) flags += AnomalyFlag(
                badgeLabel = "Dry Stress Risk",
                severity   = if (w.temperatureCelsius >= 34f) "high" else "medium",
                detail     = "No rainfall recorded (${w.precipitationMm}mm) with temperature at ${w.temperatureCelsius.toInt()}°C — conditions can cause moisture stress and slow root development.",
                suggestion = "Irrigate during early morning (5–7am) to cool the root zone and maintain consistent soil moisture. Avoid midday irrigation."
            )
            // ── Gap 2: Cold stress ────────────────────────────────────────
            if (w.temperatureCelsius <= 15f) flags += AnomalyFlag(
                badgeLabel = "Cold Stress",
                severity   = if (w.temperatureCelsius <= 10f) "high" else "medium",
                detail     = "Temperature is ${w.temperatureCelsius.toInt()}°C — below the safe range for sweet potato. Cold slows root development and promotes fungal rot.",
                suggestion = "Apply dry mulch around vine bases to insulate the root zone. Monitor leaves for yellowing or wilting — early signs of cold damage."
            )
        }

        return flags
    }

    private fun runInference(rawFeatures: FloatArray): Int {
        val interp = interpreter ?: return fallbackRules(rawFeatures)
        val scaled = FloatArray(N_FEATURES) { i ->
            if (scalerScale[i] != 0f) (rawFeatures[i] - scalerMean[i]) / scalerScale[i] else 0f
        }
        val inputBuffer = ByteBuffer.allocateDirect(N_FEATURES * 4).apply {
            order(ByteOrder.nativeOrder()); scaled.forEach { putFloat(it) }; rewind()
        }
        val outputBuffer = Array(1) { FloatArray(N_CLASSES) }
        interp.run(inputBuffer, outputBuffer)
        return outputBuffer[0].indices.maxByOrNull { outputBuffer[0][it] } ?: 11
    }

    private fun weatherRiskNote(w: WeatherData): String = when {
        w.temperatureCelsius > 35  -> "🌡️ Extreme heat (${"%.1f".format(w.temperatureCelsius)}°C) — irrigate early morning, avoid midday heat stress"
        w.temperatureCelsius <= 15 -> "🥶 Cold temperatures (${w.temperatureCelsius.toInt()}°C) — mulch vine bases to protect the root zone"
        w.temperatureCelsius > 30  -> "🌡️ High temperature (${w.temperatureCelsius.toInt()}°C) may be slowing root development"
        w.precipitationMm < 1f && w.temperatureCelsius > 28f -> "🏜️ Dry conditions — increase irrigation frequency to maintain root zone moisture"
        w.precipitationMm > 20    -> "🌧️ Heavy rainfall — check for waterlogging and fungal risk"
        w.precipitationMm > 10    -> "🌦️ Moderate rain — monitor drainage"
        w.humidity > 85           -> "💧 High humidity (${w.humidity}%) — inspect for fungal spots"
        else                      -> "☀️ Weather within acceptable range"
    }

    private fun weatherStorageNote(w: WeatherData): String = when {
        w.temperatureCelsius > 33 -> "🌡️ Store harvested roots in the coolest available space"
        w.precipitationMm > 10    -> "🌧️ Ensure curing area is dry and ventilated"
        else                      -> "☀️ Good conditions for curing — proceed as planned"
    }

    private fun fallbackRules(f: FloatArray): Int {
        val stage = f[0].toInt(); val week = f[1].toInt()
        val temp = f[4]; val precip = f[6]
        return when {
            stage == -1             -> 11; stage == 0 && week <= 8  -> 0
            stage == 0 && week <= 10 -> 1; stage == 0 && week <= 13 -> 2
            stage == 0               -> 3; stage == 1 && week >= 14 -> 7
            stage == 1 && temp > 33  -> 5; stage == 1 && precip > 10 -> 6
            stage == 1               -> 4; stage == 2 && week < 12  -> 9
            stage == 2 && week > 16  -> 10; stage == 2             -> 8
            else                     -> 11
        }
    }

    private fun encodeStage(s: String?): Int = when (s) {
        "Not Ready" -> 0; "Near Harvest" -> 1; "Harvest Ready" -> 2; else -> -1
    }
    private fun expectedStageEncoded(week: Int): Int = when {
        week <= 8 -> 0; week <= 13 -> 1; else -> 2
    }

    private fun loadInterpreter(): Interpreter? = try {
        val afd = context.assets.openFd(MODEL_PATH)
        Interpreter(FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength))
    } catch (e: Exception) { Log.w(TAG, "TFLite not found, using fallback: ${e.message}"); null }

    private fun loadScalerParam(key: String): FloatArray = try {
        val arr = JSONObject(context.assets.open(SCALER_PATH).bufferedReader().readText()).getJSONArray(key)
        FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    } catch (e: Exception) { FloatArray(N_FEATURES) { 0f } }

    private fun loadLabels(): Map<Int, String> = try {
        val obj = JSONObject(context.assets.open(LABELS_PATH).bufferedReader().readText())
        obj.keys().asSequence().associate { it.toInt() to obj.getString(it) }
    } catch (e: Exception) { emptyMap() }
}