package com.ai.growsight.ai

import android.content.Context
import android.util.Log
import java.io.File

object ModelManager {

    private var yoloDetector: YoloDetector? = null
    private var maturityClassifier: MaturityClassifier? = null
    private var scenarioClassifier: ScenarioClassifier? = null  // ← ADD
    private var loadingInProgress = false

    fun initializeModels(context: Context) {
        if (loadingInProgress) return
        loadingInProgress = true

        try {
            ensureModelsExist(context)

            if (yoloDetector == null) {
                yoloDetector = YoloDetector(context)
                Log.d("ModelManager", "✓ YOLO loaded")
            }

            if (maturityClassifier == null) {
                maturityClassifier = MaturityClassifier(context)
                Log.d("ModelManager", "✓ TFLite CNN loaded")
            }

            if (scenarioClassifier == null) {
                scenarioClassifier = ScenarioClassifier(context)  // ← ADD
                Log.d("ModelManager", "✓ ScenarioClassifier loaded")
            }

            Log.d("ModelManager", "✓ All models initialized")

        } catch (e: Exception) {
            Log.e("ModelManager", "✗ Failed to initialize models: ${e.message}", e)
        }

        loadingInProgress = false
    }

    private fun ensureModelsExist(context: Context) {
        val mlDir = File(context.filesDir, "ml")
        if (!mlDir.exists()) mlDir.mkdirs()

        val filesToCopy = listOf(
            "yolov8.tflite",
            "sweetpotato_final.tflite",
            "labels.txt",
            "yolo_labels.json",
            "scenario_classifier.tflite",  // ← ADD
            "scenario_labels.json",         // ← ADD
            "feature_scaler.json"           // ← ADD
        )

        filesToCopy.forEach { filename ->
            val target = File(mlDir, filename)
            if (!target.exists()) {
                try {
                    context.assets.open("ml/$filename").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.d("ModelManager", "✓ Copied $filename")
                } catch (e: Exception) {
                    Log.e("ModelManager", "✗ Failed copying $filename: ${e.message}")
                }
            }
        }
    }

    fun getYoloDetector(): YoloDetector? = yoloDetector
    fun getMaturityClassifier(): MaturityClassifier? = maturityClassifier
    fun getScenarioClassifier(): ScenarioClassifier? = scenarioClassifier  // ← ADD

    fun areModelsAvailable(): Boolean = yoloDetector != null && maturityClassifier != null
    // Note: ScenarioClassifier is optional — app still works without it

    fun cleanup() {
        try { yoloDetector?.close() } catch (e: Exception) { Log.e("ModelManager", "Error closing YOLO", e) }
        try { maturityClassifier?.close() } catch (e: Exception) { Log.e("ModelManager", "Error closing CNN", e) }
        yoloDetector = null
        maturityClassifier = null
        scenarioClassifier = null  // ← ADD
    }
}