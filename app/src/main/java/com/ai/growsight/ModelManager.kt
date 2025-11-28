package com.ai.growsight.ai

import android.content.Context
import android.util.Log

object ModelManager {
    private var yoloDetector: YoloDetector? = null
    private var maturityClassifier: MaturityClassifier? = null
    private var modelsLoaded = false
    private var loadingInProgress = false

    fun getYoloDetector(context: Context): YoloDetector? {
        if (yoloDetector == null && !loadingInProgress) {
            loadingInProgress = true
            try {
                yoloDetector = YoloDetector(context)
                Log.d("ModelManager", "✓ YOLO loaded")
            } catch (e: Exception) {
                Log.e("ModelManager", "✗ YOLO failed: ${e.message}", e)
            }
            loadingInProgress = false
        }
        return yoloDetector
    }

    fun getMaturityClassifier(context: Context): MaturityClassifier? {
        if (maturityClassifier == null && !loadingInProgress) {
            loadingInProgress = true
            try {
                maturityClassifier = MaturityClassifier(context)
                Log.d("ModelManager", "✓ CNN loaded")
            } catch (e: Exception) {
                Log.e("ModelManager", "✗ CNN failed: ${e.message}", e)
            }
            loadingInProgress = false
        }
        return maturityClassifier
    }

    fun areModelsAvailable(): Boolean {
        return yoloDetector != null || maturityClassifier != null
    }

    fun cleanup() {
        try {
            yoloDetector?.close()
        } catch (e: Exception) {
            Log.e("ModelManager", "Error closing YOLO", e)
        }
        yoloDetector = null
        maturityClassifier = null
        modelsLoaded = false
    }
}