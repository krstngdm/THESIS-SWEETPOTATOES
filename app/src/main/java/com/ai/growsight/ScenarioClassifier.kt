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
        private const val TAG            = "ScenarioClassifier"
        private const val MODEL_PATH     = "ml/scenario_classifier.tflite"
        private const val LABELS_PATH    = "ml/scenario_labels.json"
        private const val SCALER_PATH    = "ml/feature_scaler.json"
        private const val N_FEATURES     = 10
        private const val N_CLASSES      = 12
    }

    data class InterpretationResult(
        val scenarioId: Int,
        val scenarioLabel: String,
        val confidence: Float,
        val summary: String,
        val recommendations: List<String>
    )

    // ── Feature order must match training script exactly ──────────────────
    // ["stage","week","confidence","week_stage_gap",
    //  "temperature","humidity","precipitation",
    //  "has_weather","image_count","has_conflict"]

    private val interpreter: Interpreter? by lazy { loadInterpreter() }
    private val scalerMean:  FloatArray   by lazy { loadScalerParam("mean")  }
    private val scalerScale: FloatArray   by lazy { loadScalerParam("scale") }
    private val labels:      Map<Int, String> by lazy { loadLabels() }

    // ─────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────
    fun interpret(
        stage: String?,
        confidence: Float,
        cropWeek: Int?,
        weather: WeatherData?,
        imageCount: Int,
        validCount: Int,
        hasConflict: Boolean
    ): InterpretationResult {

        val stageEncoded = encodeStage(stage)
        val week         = cropWeek ?: 0
        val expectedStage = expectedStageEncoded(week)
        val gap          = if (stageEncoded >= 0) stageEncoded - expectedStage else 0

        val temp   = weather?.temperatureCelsius ?: -1f
        val humid  = weather?.humidity?.toFloat() ?: -1f
        val precip = weather?.precipitationMm    ?: -1f
        val hasWx  = if (weather != null) 1f else 0f

        val rawFeatures = floatArrayOf(
            stageEncoded.toFloat(),
            week.toFloat(),
            confidence,
            gap.toFloat(),
            temp, humid, precip,
            hasWx,
            imageCount.toFloat(),
            if (hasConflict) 1f else 0f
        )

        val scenarioId = runInference(rawFeatures)
        val label      = labels[scenarioId] ?: "unknown"

        Log.d(TAG, "Scenario: $scenarioId ($label) for stage=$stage week=$week")

        return buildResult(
            scenarioId, label, confidence,
            stage, week, cropWeek, weather,
            imageCount, validCount, hasConflict
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // TFLite inference
    // ─────────────────────────────────────────────────────────────────────
    private fun runInference(rawFeatures: FloatArray): Int {
        val interp = interpreter ?: return fallbackRules(rawFeatures)

        // Apply StandardScaler
        val scaled = FloatArray(N_FEATURES) { i ->
            (rawFeatures[i] - scalerMean[i]) / scalerScale[i]
        }

        val inputBuffer = ByteBuffer.allocateDirect(N_FEATURES * 4).apply {
            order(ByteOrder.nativeOrder())
            scaled.forEach { putFloat(it) }
            rewind()
        }

        val outputBuffer = Array(1) { FloatArray(N_CLASSES) }
        interp.run(inputBuffer, outputBuffer)

        val probs = outputBuffer[0]
        return probs.indices.maxByOrNull { probs[it] } ?: 11
    }

    // ─────────────────────────────────────────────────────────────────────
    // Result builder — scenario_id → summary + recommendations
    // ─────────────────────────────────────────────────────────────────────
    private fun buildResult(
        scenarioId: Int,
        label: String,
        modelConfidence: Float,
        stage: String?,
        week: Int,
        cropWeek: Int?,
        weather: WeatherData?,
        imageCount: Int,
        validCount: Int,
        hasConflict: Boolean
    ): InterpretationResult {

        val weekStr   = if (cropWeek != null && cropWeek > 0) "Week $cropWeek" else "an unspecified age"
        val tempStr   = weather?.let { "%.1f°C".format(it.temperatureCelsius) } ?: "unknown temperature"
        val precipStr = weather?.let { "${it.precipitationMm}mm precipitation" } ?: "no precipitation data"
        val humidStr  = weather?.let { "${it.humidity}% humidity" } ?: ""

        val summary: String
        val recommendations: MutableList<String> = mutableListOf()

        when (scenarioId) {

            0 -> { // on_track_early
                summary = "Your $weekStr crop is developing right on schedule. " +
                    "The AI detected an early 'Not Ready' stage, which is completely " +
                    "normal at this point in the growing cycle. No action is needed — " +
                    "continue your standard crop management."
                recommendations += "Maintain regular watering schedule appropriate for early growth stage"
                recommendations += "Apply balanced fertilizer if not done in the past 2 weeks"
                recommendations += "Monitor for early signs of pest or disease pressure"
                recommendations += "Re-submit images at Week 10–11 for a progress check"
            }

            1 -> { // slightly_late
                summary = "At $weekStr, your crop is slightly behind the typical maturity " +
                    "timeline — this is not alarming but worth monitoring. Sweet potatoes " +
                    "at this age often show early signs of bulking that may not yet be " +
                    "visible in leaf images. Consider a physical root check."
                recommendations += "Gently probe the soil near the base of one or two plants to check root development"
                recommendations += "Ensure consistent soil moisture — dry spells at this stage slow bulking"
                recommendations += "Check for vine overcrowding that may be limiting light and airflow"
                recommendations += "Re-scan in 1–2 weeks to track progression"
                if (weather != null && weather.temperatureCelsius > 30f)
                    recommendations += "⚠️ High temperature ($tempStr) may be slowing maturity — ensure adequate irrigation"
            }

            2 -> { // behind_schedule
                summary = "Your $weekStr crop is showing 'Not Ready' characteristics, which is " +
                    "a concern at this growth stage. Most sweet potato varieties reach " +
                    "'Near Harvest' by Week 10–12. This gap suggests the crop may be " +
                    "experiencing a limiting factor — soil nutrition, water stress, or " +
                    "pest/disease pressure are the most common causes."
                recommendations += "🔍 Conduct a thorough field inspection — check root development by digging one sample plant"
                recommendations += "Review your fertilization history — potassium deficiency commonly delays root bulking"
                recommendations += "Check soil moisture levels — inconsistent watering is a leading cause of delayed maturity"
                recommendations += "Inspect leaves and vines for signs of disease (yellowing, spots, wilting)"
                recommendations += "Consult with your local agricultural extension officer if no clear cause is found"
                if (weather != null)
                    recommendations += "Current conditions ($tempStr, $precipStr) — ${weatherRiskNote(weather)}"
            }

            3 -> { // critically_behind
                summary = "This is a significant concern. Your crop is $weekStr old but still " +
                    "shows 'Not Ready' maturity characteristics — a gap of approximately " +
                    "${week - 12} weeks behind typical harvest timeline. At this stage, " +
                    "delayed maturity often indicates a serious agronomic issue that requires " +
                    "immediate attention. Do not wait for the next scan — conduct a physical " +
                    "field assessment today."
                recommendations += "⚠️ URGENT: Dig up 3–5 sample plants from different areas of the field to physically assess root size"
                recommendations += "Document and photograph root samples for comparison — bring these to an agricultural expert"
                recommendations += "Test soil pH and nutrient levels if not done recently — deficiencies compound over time"
                recommendations += "Check for root-knot nematode damage, which can prevent normal root development"
                recommendations += "Evaluate whether a partial harvest of the most developed plants is possible to recover some yield"
                recommendations += "Record this outcome in your crop diary — this data will help diagnose the issue post-harvest"
                if (weather != null && weather.temperatureCelsius > 33f)
                    recommendations += "🌡️ Extreme heat ($tempStr) is actively stressing the crop — prioritize shade nets or irrigation if available"
            }

            4 -> { // approaching_harvest normal
                summary = "Good news — your $weekStr crop is approaching harvest maturity " +
                    "right on schedule. Current weather conditions look manageable. " +
                    "Begin preparing your harvest logistics over the next 1–2 weeks " +
                    "and watch for the classic harvest-ready signs: yellowing lower leaves, " +
                    "firm roots when probed, and skin that resists rubbing."
                recommendations += "Begin preparing harvest tools and storage facilities now"
                recommendations += "Reduce irrigation slightly (if possible) 1–2 weeks before harvest to improve skin set"
                recommendations += "Check for physical harvest-readiness: probe roots every 2–3 days"
                recommendations += "Plan your harvest for a dry day to minimize soil compaction and root damage"
                recommendations += "Avoid harvesting in extreme midday heat — early morning is ideal"
            }

            5 -> { // approaching_harvest heat stress
                summary = "Your $weekStr crop is nearing harvest, but current heat ($tempStr) " +
                    "is a concern. High temperatures accelerate vine senescence and can " +
                    "cause root cracking and quality loss if harvest is delayed. " +
                    "Consider moving your harvest timeline slightly earlier than planned."
                recommendations += "🌡️ Heat stress alert: consider harvesting 3–5 days earlier than your planned date"
                recommendations += "Increase irrigation slightly if possible to reduce heat stress on developing roots"
                recommendations += "Harvest during the coolest part of the day (early morning, 5–8am)"
                recommendations += "After harvest, move roots to a shaded, ventilated area immediately — avoid sun exposure"
                recommendations += "Check roots for cracking — cracked roots should be sold or consumed first as they store poorly"
            }

            6 -> { // approaching_harvest rain risk
                summary = "Your $weekStr crop is near harvest maturity, but current rainfall " +
                    "($precipStr) raises the risk of waterlogging and fungal disease. " +
                    "Wet soil also makes harvesting more difficult and damages roots. " +
                    "Monitor field drainage closely and plan to harvest as soon as " +
                    "conditions dry out."
                recommendations += "🌧️ Check field drainage — clear any blocked drainage channels immediately"
                recommendations += "Inspect leaves for early signs of fungal disease (blight, leaf spots) which spread rapidly in wet conditions"
                recommendations += "Delay harvest until soil is workable — harvesting in wet soil causes root bruising and rot"
                recommendations += "If flooding risk exists, consider emergency early harvest of most mature areas"
                recommendations += "After harvest, cure roots in a dry, well-ventilated space to reduce post-harvest losses"
            }

            7 -> { // overdue near harvest
                summary = "Your $weekStr crop is showing 'Near Harvest' stage, but at this " +
                    "age it should ideally already be at 'Harvest Ready'. The crop is " +
                    "running behind schedule. While the quality may still be acceptable, " +
                    "continued delay increases the risk of over-maturity, which reduces " +
                    "sweetness and storage life."
                recommendations += "⏰ Begin harvest assessment immediately — do not wait for AI confirmation"
                recommendations += "Physically probe roots across multiple field sections to evaluate size and maturity"
                recommendations += "Prioritize harvesting the most mature sections of your field first"
                recommendations += "Over-mature roots tend to be starchy and fibrous — sample-taste before full harvest"
                recommendations += "Investigate the cause of delayed maturity for your crop diary and future planning"
            }

            8 -> { // harvest on time
                summary = "Excellent — your $weekStr crop has reached 'Harvest Ready' stage " +
                    "right within the optimal harvest window. This is the best possible " +
                    "outcome. Your crop management has been effective. Plan your harvest " +
                    "within the next few days for peak quality and sweetness."
                recommendations += "✅ Harvest within the next 3–5 days for peak quality"
                recommendations += "Harvest during early morning hours to reduce field heat stress on roots"
                recommendations += "Handle roots gently during harvest — bruising accelerates rot in storage"
                recommendations += "Cure harvested roots at 29–32°C and 85–90% humidity for 4–7 days to harden skin"
                recommendations += "Sort roots by size immediately after harvest — larger roots cure and store differently"
                if (weather != null)
                    recommendations += weatherStorageNote(weather)
            }

            9 -> { // harvest early
                summary = "The AI detected 'Harvest Ready' characteristics at only $weekStr — " +
                    "earlier than the typical 12–16 week window. This could indicate an " +
                    "early-maturing variety, unusually favorable growing conditions, or " +
                    "a false positive. Do not harvest based solely on this result — " +
                    "conduct a physical root check first."
                recommendations += "🔍 Verify with a physical root check before harvesting — dig 3–5 sample plants"
                recommendations += "Check root size: harvest-ready roots are typically 150–300g depending on variety"
                recommendations += "If roots are confirmed mature, proceed with harvest — some varieties mature early"
                recommendations += "If roots are small and underdeveloped, continue monitoring and re-scan in 1–2 weeks"
                recommendations += "Document this result — early maturity patterns are valuable for future crop planning"
            }

            10 -> { // harvest overdue
                summary = "Your $weekStr crop is past the typical harvest window and quality " +
                    "may already be declining. Over-mature sweet potatoes become starchy, " +
                    "lose sweetness, and are more prone to cracking and rot. " +
                    "Harvest as soon as logistically possible."
                recommendations += "⚠️ Harvest immediately — further delay will reduce quality and marketability"
                recommendations += "Prioritize roots showing any signs of cracking or surface damage"
                recommendations += "Expect higher starch content and reduced sweetness — adjust market/use accordingly"
                recommendations += "Sort aggressively at harvest — remove any damaged or rotting roots before storage"
                recommendations += "Review your planting schedule to avoid this timing issue in the next cycle"
                if (weather != null && weather.precipitationMm > 5f)
                    recommendations += "🌧️ Current rainfall increases rot risk for over-mature roots — prioritize harvest today"
            }

            else -> { // no detection (11)
                summary = "The AI was unable to detect a sweet potato plant in the submitted " +
                    "image(s). This is typically caused by unclear photos, insufficient " +
                    "lighting, or images that do not show the plant clearly. No agronomic " +
                    "assessment can be made from the current images."
                recommendations += "Ensure photos are taken in good natural light (avoid harsh midday shadows)"
                recommendations += "Position the camera 30–60cm above the plant canopy, aimed directly downward"
                recommendations += "Include the full leaf canopy in the frame — avoid zooming in on single leaves"
                recommendations += "Avoid taking photos when vines are wet — moisture creates glare that confuses the model"
                recommendations += "Try submitting 3–5 images from different angles or plant positions"
            }
        }

        // Add conflict warning if images disagreed
        if (hasConflict && scenarioId != 11) {
            recommendations += "⚠️ Note: Your submitted images showed mixed maturity signals — " +
                "results are based on the majority assessment. Consider re-submitting with images from the same plant"
        }

        return InterpretationResult(
            scenarioId      = scenarioId,
            scenarioLabel   = label,
            confidence      = modelConfidence,
            summary         = summary,
            recommendations = recommendations
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Weather helper notes
    // ─────────────────────────────────────────────────────────────────────
    private fun weatherRiskNote(weather: WeatherData): String = when {
        weather.temperatureCelsius > 35 -> "🌡️ Extreme heat (${weather.temperatureCelsius}°C) is a major stress factor — irrigation is critical"
        weather.temperatureCelsius > 30 -> "🌡️ High temperature (${weather.temperatureCelsius}°C) may be slowing root development"
        weather.precipitationMm > 20    -> "🌧️ Heavy rainfall (${weather.precipitationMm}mm) — check for waterlogging and fungal risk"
        weather.precipitationMm > 10    -> "🌦️ Moderate rainfall — monitor drainage to prevent root rot"
        weather.humidity > 85           -> "💧 High humidity (${weather.humidity}%) increases disease risk — inspect vines regularly"
        else                             -> "☀️ Weather conditions are within acceptable range for sweet potato growth"
    }

    private fun weatherStorageNote(weather: WeatherData): String = when {
        weather.temperatureCelsius > 33 -> "🌡️ Store harvested roots in the coolest available space — avoid temperatures above 30°C"
        weather.precipitationMm > 10    -> "🌧️ Ensure storage area is dry and well-ventilated — rain increases post-harvest rot risk"
        else                             -> "☀️ Good weather for harvest and curing — proceed as planned"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fallback rule-based classifier (used when TFLite model fails to load)
    // ─────────────────────────────────────────────────────────────────────
    private fun fallbackRules(features: FloatArray): Int {
        val stage  = features[0].toInt()
        val week   = features[1].toInt()
        val temp   = features[4]
        val precip = features[6]

        return when {
            stage == -1              -> 11
            stage == 0 && week <= 8  -> 0
            stage == 0 && week <= 10 -> 1
            stage == 0 && week <= 13 -> 2
            stage == 0               -> 3
            stage == 1 && week >= 14 -> 7
            stage == 1 && temp > 33  -> 5
            stage == 1 && precip > 10-> 6
            stage == 1               -> 4
            stage == 2 && week < 12  -> 9
            stage == 2 && week > 16  -> 10
            stage == 2               -> 8
            else                     -> 11
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────
    private fun encodeStage(stage: String?): Int = when (stage) {
        "Not Ready"     -> 0
        "Near Harvest"  -> 1
        "Harvest Ready" -> 2
        else            -> -1
    }

    private fun expectedStageEncoded(week: Int): Int = when {
        week <= 8  -> 0
        week <= 13 -> 1
        else       -> 2
    }

    private fun loadInterpreter(): Interpreter? {
        return try {
            val afd    = context.assets.openFd(MODEL_PATH)
            val input  = FileInputStream(afd.fileDescriptor)
            val buffer = input.channel.map(
                FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
            )
            Interpreter(buffer)
        } catch (e: Exception) {
            Log.w(TAG, "TFLite model not found, using rule fallback: ${e.message}")
            null
        }
    }

    private fun loadScalerParam(key: String): FloatArray {
        return try {
            val json   = context.assets.open(SCALER_PATH).bufferedReader().readText()
            val array  = JSONObject(json).getJSONArray(key)
            FloatArray(array.length()) { array.getDouble(it).toFloat() }
        } catch (e: Exception) {
            Log.w(TAG, "Scaler not found, using zeros: ${e.message}")
            FloatArray(N_FEATURES) { 0f }
        }
    }

    private fun loadLabels(): Map<Int, String> {
        return try {
            val json = context.assets.open(LABELS_PATH).bufferedReader().readText()
            val obj  = JSONObject(json)
            obj.keys().asSequence().associate { it.toInt() to obj.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Labels not found: ${e.message}")
            emptyMap()
        }
    }
}
