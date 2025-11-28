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
        Log.d("YoloDetector", "=== YOLO INIT ===")
        try {
            val modelFile = copyAsset(context, "ml/yolov8.tflite")
            Log.d("YoloDetector", "Model file size: ${modelFile.length()} bytes")

            interpreter = Interpreter(modelFile, Interpreter.Options().apply {
                setNumThreads(4)
                Log.d("YoloDetector", "TFLite interpreter created")
            })

            labels = loadLabels(context)
            Log.d("YoloDetector", "✓ YOLO initialized successfully, labels: $labels")

        } catch (e: Exception) {
            Log.e("YoloDetector", "✗ YOLO initialization failed: ${e.message}", e)
            throw e
        }
    }

    // ----------------------------------------------------------
    // Load model from assets → filesDir
    // ----------------------------------------------------------
    private fun copyAsset(context: Context, path: String): File {
        val outFile = File(context.filesDir, path)

        if (!outFile.exists() || outFile.length() == 0L) {
            outFile.parentFile?.mkdirs()
            context.assets.open(path).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }

    // ----------------------------------------------------------
    // Load labels JSON
    // ----------------------------------------------------------
    private fun loadLabels(context: Context): Map<Int, String> {
        return try {
            val text = context.assets.open("ml/yolo_labels.json")
                .bufferedReader().use { it.readText() }

            val json = JSONObject(text)
            json.keys().asSequence().associate { key ->
                key.toInt() to json.getString(key)
            }
        } catch (e: Exception) {
            mapOf(0 to "plant")
        }
    }

    // ----------------------------------------------------------
    // Detection result
    // ----------------------------------------------------------
    data class Detection(
        val box: RectF,
        val score: Float,
        val classId: Int,
        val label: String
    )

    // ----------------------------------------------------------
    // Run YOLO detection
    // ----------------------------------------------------------
    fun detect(bitmap: Bitmap): Detection? {
        return try {
            Log.d("YoloDetector", "Starting detection on bitmap: ${bitmap.width}x${bitmap.height}")
            val input = preprocessBitmap(bitmap)
            Log.d("YoloDetector", "Input tensor prepared")

            val output = Array(1) { Array(5) { FloatArray(8400) } }

            val startTime = System.currentTimeMillis()
            interpreter.run(input, output)
            val endTime = System.currentTimeMillis()
            Log.d("YoloDetector", "Inference completed in ${endTime - startTime}ms")

            val detection = parseYoloOutput(output[0], bitmap.width, bitmap.height)
            Log.d("YoloDetector", "Detection result: $detection")
            detection

        } catch (e: Exception) {
            Log.e("YoloDetector", "Detection error: ${e.message}", e)
            null
        }
    }

    // ----------------------------------------------------------
    // Parse YOLO Output
    // ----------------------------------------------------------
    private fun parseYoloOutput(
        out: Array<FloatArray>,
        origW: Int,
        origH: Int
    ): Detection? {
        Log.d("YoloDetector", "Parsing YOLO output, checking 8400 detections...")

        var bestScore = confidenceThreshold
        var best: Detection? = null
        var totalDetections = 0

        val scaleX = origW / 640f
        val scaleY = origH / 640f

        for (i in 0 until 8400) {
            val score = out[4][i]
            if (score < confidenceThreshold) continue

            totalDetections++
            if (score > bestScore) {
                val xc = out[0][i]
                val yc = out[1][i]
                val w = out[2][i]
                val h = out[3][i]

                val left = (xc - w / 2) * scaleX
                val top = (yc - h / 2) * scaleY
                val right = (xc + w / 2) * scaleX
                val bottom = (yc + h / 2) * scaleY

                val clamped = RectF(
                    left.coerceIn(0f, origW.toFloat()),
                    top.coerceIn(0f, origH.toFloat()),
                    right.coerceIn(0f, origW.toFloat()),
                    bottom.coerceIn(0f, origH.toFloat())
                )

                bestScore = score
                best = Detection(
                    box = clamped,
                    score = score,
                    classId = 0,
                    label = labels[0] ?: "plant"
                )

                Log.d("YoloDetector", "Found detection: score=$score, box=$clamped")
            }
        }

        Log.d("YoloDetector", "Total detections above threshold: $totalDetections, best: ${best?.score}")
        return best
    }

    // ----------------------------------------------------------
    // Preprocess bitmap → (1,640,640,3)
    // ----------------------------------------------------------
    private fun preprocessBitmap(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = Array(1) { Array(640) { Array(640) { FloatArray(3) } } }

        for (y in 0 until 640) {
            for (x in 0 until 640) {
                val p = resized.getPixel(x, y)

                input[0][y][x][0] = ((p shr 16 and 0xFF) / 255f)
                input[0][y][x][1] = ((p shr 8 and 0xFF) / 255f)
                input[0][y][x][2] = ((p and 0xFF) / 255f)
            }
        }
        return input
    }

    fun close() {
        interpreter.close()
    }
}