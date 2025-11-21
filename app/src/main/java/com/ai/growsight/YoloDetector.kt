package com.ai.growsight.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream

class YoloDetector(context: Context) {

    private val inputSize = 640
    private val interpreter: Interpreter
    private val labels: Map<Int, String>
    private val confidenceThreshold = 0.25f

    init {
        Log.d("YoloDetector", "=== INIT START ===")

        // Copy model from assets to filesDir if missing
        val modelFile = File(context.filesDir, "yolov8.tflite")
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.d("YoloDetector", "Copying YOLO model from assets...")
            context.assets.open("ml/yolov8.tflite").use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            Log.d("YoloDetector", "Model copied successfully. Size=${modelFile.length()}")
        } else {
            Log.d("YoloDetector", "Model already exists. Size=${modelFile.length()}")
        }

        // Load interpreter
        interpreter = Interpreter(modelFile, Interpreter.Options().apply {
            setNumThreads(4)
        })

        // Load YOLO labels
        val labelJson = try {
            context.assets.open("ml/yolo_labels.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "{\"0\": \"plant\"}" // Default label if file missing
        }
        labels = JSONObject(labelJson).keys().asSequence().map { key ->
            key.toInt() to JSONObject(labelJson).getString(key)
        }.toMap()

        Log.d("YoloDetector", "✓ Interpreter loaded successfully")
        Log.d("YoloDetector", "Loaded labels: $labels")
        Log.d("YoloDetector", "=== INIT COMPLETE ===")
    }

    data class Detection(
        val box: RectF,
        val score: Float,
        val classId: Int,
        val label: String
    )

    fun detect(bitmap: Bitmap): Detection? {
        return try {
            Log.d("YoloDetector", "Starting detection on bitmap: ${bitmap.width}x${bitmap.height}")

            val input = preprocessBitmap(bitmap)

            // Based on your Python output: [1, 5, 8400]
            val output = Array(1) { Array(5) { FloatArray(8400) } }

            interpreter.run(input, output)

            // Parse the [1, 5, 8400] format
            parseYoloV8Output(output[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e("YoloDetector", "Detection failed: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    private fun parseYoloV8Output(output: Array<FloatArray>, originalWidth: Int, originalHeight: Int): Detection? {
        var bestDetection: Detection? = null
        var bestScore = confidenceThreshold

        // output structure: [5, 8400] where:
        // output[0] = x_center (normalized 0-1)
        // output[1] = y_center (normalized 0-1)
        // output[2] = width (normalized 0-1)
        // output[3] = height (normalized 0-1)
        // output[4] = confidence scores
        // 8400 detection candidates

        for (i in 0 until 8400) {
            val confidence = output[4][i]

            if (confidence > bestScore) {
                bestScore = confidence

                // Get normalized coordinates (0-1 relative to 640x640)
                val xCenter = output[0][i]
                val yCenter = output[1][i]
                val width = output[2][i]
                val height = output[3][i]

                // Convert to pixel coordinates in original image
                val scaleX = originalWidth.toFloat() / inputSize
                val scaleY = originalHeight.toFloat() / inputSize

                // Convert center coordinates to corner coordinates
                val left = (xCenter - width / 2) * inputSize * scaleX
                val top = (yCenter - height / 2) * inputSize * scaleY
                val right = (xCenter + width / 2) * inputSize * scaleX
                val bottom = (yCenter + height / 2) * inputSize * scaleY

                // Clamp to image boundaries
                val clampedLeft = left.coerceIn(0f, originalWidth.toFloat())
                val clampedTop = top.coerceIn(0f, originalHeight.toFloat())
                val clampedRight = right.coerceIn(0f, originalWidth.toFloat())
                val clampedBottom = bottom.coerceIn(0f, originalHeight.toFloat())

                // For plant detection, use class 0
                val classId = 0

                bestDetection = Detection(
                    RectF(clampedLeft, clampedTop, clampedRight, clampedBottom),
                    confidence,
                    classId,
                    labels[classId] ?: "plant"
                )

                Log.d("YoloDetector", "Found: ${bestDetection.label} (${"%.1f".format(confidence * 100)}%) " +
                        "at [${clampedLeft.toInt()}, ${clampedTop.toInt()}, ${clampedRight.toInt()}, ${clampedBottom.toInt()}] " +
                        "size: ${(clampedRight - clampedLeft).toInt()}x${(clampedBottom - clampedTop).toInt()}")
            }
        }

        Log.d("YoloDetector", "Best detection: ${bestDetection?.score ?: "None"}")
        return bestDetection
    }

    private fun preprocessBitmap(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = scaled.getPixel(x, y)
                input[0][y][x][0] = ((pixel shr 16 and 0xFF) / 255.0f) // R
                input[0][y][x][1] = ((pixel shr 8 and 0xFF) / 255.0f)  // G
                input[0][y][x][2] = ((pixel and 0xFF) / 255.0f)        // B
            }
        }
        return input
    }

    fun close() {
        interpreter.close()
        Log.d("YoloDetector", "Interpreter closed")
    }
}