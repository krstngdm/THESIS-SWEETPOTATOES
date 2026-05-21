package com.ai.growsight.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloDetector(private val context: Context) {

    private val inputSize = 640
    private val confidenceThreshold = 0.25f
    private val iouThreshold = 0.45f

    private val interpreter: Interpreter
    private val labels: Map<Int, String>

    // Detected at init time — true = [1, 5, 8400], false = [1, 8400, 5]
    private var isChannelsFirst: Boolean = false

    init {
        Log.d("YoloDetector", "=== YOLO INIT ===")
        try {
            val modelFile = loadLatestModel()
            Log.d("YoloDetector", "✓ Model path: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

            interpreter = Interpreter(modelFile, Interpreter.Options().apply {
                setNumThreads(4)
            })

            // ── Detect output layout from the actual tensor shape ──────────────
            val outputTensor = interpreter.getOutputTensor(0)
            val shape = outputTensor.shape()  // e.g. [1, 5, 8400] or [1, 8400, 5]
            Log.d("YoloDetector", "✓ Output tensor shape: ${shape.toList()}")

            // shape[1] == 5 means channels-first [1, 5, 8400]  (int8 / some float16 exports)
            // shape[1] == 8400 means rows-first  [1, 8400, 5]  (float32 exports — your case)
            isChannelsFirst = (shape.size >= 3 && shape[1] == 5)
            Log.d("YoloDetector", "✓ Layout: ${if (isChannelsFirst) "channels-first [1,5,8400]" else "rows-first [1,8400,5]"}")

            labels = loadLabels()
            Log.d("YoloDetector", "✓ Labels: $labels")
        } catch (e: Exception) {
            Log.e("YoloDetector", "✗ Init failed: ${e.message}", e)
            throw e
        }
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private fun loadLatestModel(): File {
        val downloaded = File(context.filesDir, "ml/yolov11_3_5.tflite")
        if (downloaded.exists() && downloaded.length() > 0L) {
            Log.d("YoloDetector", "Using downloaded model")
            return downloaded
        }
        Log.d("YoloDetector", "Using built-in model")
        return copyAsset("ml/yolov11_3_5.tflite")
    }

    private fun copyAsset(assetPath: String): File {
        val outFile = File(context.filesDir, assetPath)
        if (outFile.exists() && outFile.length() > 0L) return outFile
        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun loadLabels(): Map<Int, String> {
        return try {
            val file = File(context.filesDir, "ml/yolo_labels.json")
            val jsonText = if (file.exists()) file.readText()
            else context.assets.open("ml/yolo_labels.json").bufferedReader().readText()
            val json = JSONObject(jsonText)
            json.keys().asSequence().associate { k -> k.toInt() to json.getString(k) }
        } catch (e: Exception) {
            Log.e("YoloDetector", "Failed to load labels — using default", e)
            mapOf(0 to "sweet_potato_leaf")
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────
    private data class LetterboxInfo(val scale: Float, val padX: Float, val padY: Float)

    data class Detection(
        val box: RectF,
        val score: Float,
        val classId: Int,
        val label: String
    )

    fun detect(bitmap: Bitmap): List<Detection> {
        return try {
            val (input, lbInfo) = preprocess(bitmap)

            val detections = if (isChannelsFirst) {
                val output = Array(1) { Array(5) { FloatArray(8400) } }
                interpreter.run(input, output)
                parseChannelsFirst(output[0], bitmap.width, bitmap.height, lbInfo)
            } else {
                val output = Array(1) { Array(8400) { FloatArray(5) } }
                interpreter.run(input, output)
                parseRowsFirst(output[0], bitmap.width, bitmap.height, lbInfo)
            }

            Log.d("YoloDetector", "Raw detections before NMS: ${detections.size}")
            val result = applyNMS(detections)
            Log.d("YoloDetector", "Detections after NMS: ${result.size}")
            result
        } catch (e: Exception) {
            Log.e("YoloDetector", "Detection error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * For float32 exports: output shape [8400, 5]
     * Each row = [cx, cy, w, h, conf]  — coordinates are NORMALIZED 0-1
     * (matches exactly what your working Colab Cell 5 does)
     */
    private fun parseRowsFirst(
        out: Array<FloatArray>,   // [8400, 5]
        origW: Int,
        origH: Int,
        lb: LetterboxInfo
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        for (i in out.indices) {
            val conf = out[i][4]
            if (conf < confidenceThreshold) continue

            val cx640 = out[i][0] * inputSize
            val cy640 = out[i][1] * inputSize
            val w640  = out[i][2] * inputSize
            val h640  = out[i][3] * inputSize

            val cxImg = (cx640 - lb.padX) / lb.scale
            val cyImg = (cy640 - lb.padY) / lb.scale
            val wImg  = w640 / lb.scale
            val hImg  = h640 / lb.scale

            val x1 = cxImg - wImg / 2f
            val y1 = cyImg - hImg / 2f
            val x2 = cxImg + wImg / 2f
            val y2 = cyImg + hImg / 2f

            detections.add(Detection(
                box = RectF(
                    x1.coerceIn(0f, origW.toFloat()),
                    y1.coerceIn(0f, origH.toFloat()),
                    x2.coerceIn(0f, origW.toFloat()),
                    y2.coerceIn(0f, origH.toFloat())
                ),
                score = conf,
                classId = 0,
                label = labels[0] ?: "sweet_potato_leaf"
            ))
        }
        return detections
    }

    /**
     * For channels-first exports: output shape [5, 8400]
     * Each column = [cx, cy, w, h, conf] — coordinates relative to inputSize
     */
    private fun parseChannelsFirst(
        out: Array<FloatArray>,   // [5, 8400]
        origW: Int,
        origH: Int,
        lb: LetterboxInfo
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Runtime check: YOLO11 float32 may output pixel-space (0-640), not normalized (0-1)
        val sampleMax = (0 until minOf(200, 8400)).maxOfOrNull { out[0][it] } ?: 0f
        val coordsNormalized = sampleMax <= 1.0f
        Log.d("YoloDetector", "coordsNormalized=$coordsNormalized (sampleMax=$sampleMax)")

        for (i in 0 until 8400) {
            val conf = out[4][i]
            if (conf < confidenceThreshold) continue

            val cx640 = if (coordsNormalized) out[0][i] * inputSize else out[0][i]
            val cy640 = if (coordsNormalized) out[1][i] * inputSize else out[1][i]
            val w640  = if (coordsNormalized) out[2][i] * inputSize else out[2][i]
            val h640  = if (coordsNormalized) out[3][i] * inputSize else out[3][i]

            val cxImg = (cx640 - lb.padX) / lb.scale
            val cyImg = (cy640 - lb.padY) / lb.scale
            val wImg  = w640 / lb.scale
            val hImg  = h640 / lb.scale

            val x1 = cxImg - wImg / 2f
            val y1 = cyImg - hImg / 2f
            val x2 = cxImg + wImg / 2f
            val y2 = cyImg + hImg / 2f

            detections.add(Detection(
                box = RectF(
                    x1.coerceIn(0f, origW.toFloat()),
                    y1.coerceIn(0f, origH.toFloat()),
                    x2.coerceIn(0f, origW.toFloat()),
                    y2.coerceIn(0f, origH.toFloat())
                ),
                score = conf,
                classId = 0,
                label = labels[0] ?: "sweet_potato_leaf"
            ))
        }
        return detections
    }

    // ── NMS ───────────────────────────────────────────────────────────────────

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best.box, it.box) >= iouThreshold }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left   = maxOf(a.left, b.left)
        val top    = maxOf(a.top, b.top)
        val right  = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter)
    }

    // ── Preprocessing ─────────────────────────────────────────────────────────

    /**
     * Simple resize to 640x640 — matches what your Colab Cell 5 does with
     * cv2.resize(img, (IMG_SIZE, IMG_SIZE)) without letterboxing.
     * The float32 export uses normalized coords relative to the resized square,
     * so we just scale back by origW/origH — no padding math needed.
     */
    private fun preprocess(bitmap: Bitmap): Pair<ByteBuffer, LetterboxInfo> {
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()
        val scale = inputSize / maxOf(origW, origH)
        val newW = (origW * scale).toInt()
        val newH = (origH * scale).toInt()
        val padX = (inputSize - newW) / 2f
        val padY = (inputSize - newH) / 2f

        val canvas = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val g = android.graphics.Canvas(canvas)
        g.drawColor(android.graphics.Color.rgb(114, 114, 114))
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        g.drawBitmap(scaled, padX, padY, null)
        if (scaled != bitmap) scaled.recycle()

        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        canvas.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat((pixel shr 16 and 0xFF) / 255f)
            buffer.putFloat((pixel shr 8  and 0xFF) / 255f)
            buffer.putFloat((pixel        and 0xFF) / 255f)
        }
        buffer.rewind()
        canvas.recycle()
        return Pair(buffer, LetterboxInfo(scale, padX, padY))
    }

    fun close() { interpreter.close() }
}