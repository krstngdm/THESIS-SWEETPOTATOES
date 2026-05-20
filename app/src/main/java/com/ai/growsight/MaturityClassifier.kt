package com.ai.growsight.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class PlantAnalysisResult(
    val label: String,          // "not_ready", "near_harvest", "harvest_ready"
    val confidence: Float,      // 0.0 - 1.0
    val allScores: Map<String, Float>
)

class MaturityClassifier(private val context: Context) {

    private val interpreter: Interpreter
    private val labels = listOf("harvest_ready", "near_harvest", "not_ready") // matches labels.txt order
    private val IMG_SIZE = 224

    init {
        interpreter = Interpreter(loadModelFile())
        Log.d("MaturityClassifier", "✓ TFLite CNN loaded")
    }

    private fun loadModelFile(): ByteBuffer {
        // Check local storage first (for model update support)
        val localModel = File(context.filesDir, "ml/sweetpotato_final.tflite")
        return if (localModel.exists()) {
            val fis = FileInputStream(localModel)
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, localModel.length())
        } else {
            // Fall back to assets
            val afd = context.assets.openFd("ml/sweetpotato_final.tflite")
            FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }

    fun classify(bitmap: Bitmap): PlantAnalysisResult {
        // Resize and normalize
        val resized = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true)
        val inputBuffer = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)

        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)           // B
        }

        // Run inference
        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(inputBuffer, output)

        val scores = output[0]
        val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
        val confidence = scores[maxIndex]

        val allScores = labels.mapIndexed { i, label -> label to scores[i] }.toMap()

        Log.d("MaturityClassifier", "Prediction: ${labels[maxIndex]} (${confidence * 100}%)")
        Log.d("MaturityClassifier", "All scores: $allScores")

        return PlantAnalysisResult(
            label = labels[maxIndex],
            confidence = confidence,
            allScores = allScores
        )
    }

    fun close() {
        interpreter.close()
    }
}