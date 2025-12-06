package com.ai.growsight.ai

import android.content.Context
import android.util.Log
import java.io.File

object ModelManager {

    private var yoloDetector: YoloDetector? = null
    private var maturityClassifier: MaturityClassifier? = null
    private var loadingInProgress = false

    // Initialize models once for the entire app
    fun initializeModels(context: Context) {
        if (loadingInProgress) return
        loadingInProgress = true

        try {
            // Copy models from assets → /data/data/.../files/ml/
            ensureModelsExist(context)

            // Initialize YOLO
            if (yoloDetector == null) {
                yoloDetector = YoloDetector(context)
                Log.d("ModelManager", "✓ YOLO loaded")
            }

            // Initialize CNN-LSTM
            if (maturityClassifier == null) {
                maturityClassifier = MaturityClassifier(context)
                Log.d("ModelManager", "✓ CNN loaded")
            }

            Log.d("ModelManager", "✓ All models initialized")

        } catch (e: Exception) {
            Log.e("ModelManager", "✗ Failed to initialize models: ${e.message}", e)
        }

        loadingInProgress = false
    }

    // Copy asset models to local storage if missing
    private fun ensureModelsExist(context: Context) {
        val mlDir = File(context.filesDir, "ml")
        if (!mlDir.exists()) mlDir.mkdirs()

        val filesToCopy = listOf(
            "yolov8.tflite",
            "cnn_lstm_mobile.pt",
            "yolo_labels.json",
            "label_maps.json",
            "numeric_cols.json"
        )

        filesToCopy.forEach { filename ->
            val target = File(mlDir, filename)

            if (!target.exists()) {
                try {
                    context.assets.open("ml/$filename").use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d("ModelManager", "✓ Copied $filename from assets")
                } catch (e: Exception) {
                    Log.e("ModelManager", "✗ Failed copying $filename: ${e.message}")
                }
            } else {
                Log.d("ModelManager", "✓ Found: $filename")
            }
        }
    }

    fun getYoloDetector(): YoloDetector? = yoloDetector

    fun getMaturityClassifier(): MaturityClassifier? = maturityClassifier

    fun areModelsAvailable(): Boolean {
        return yoloDetector != null && maturityClassifier != null
    }

    fun cleanup() {
        try {
            yoloDetector?.close()
        } catch (e: Exception) {
            Log.e("ModelManager", "Error closing YOLO", e)
        }

        yoloDetector = null
        maturityClassifier = null
    }
}