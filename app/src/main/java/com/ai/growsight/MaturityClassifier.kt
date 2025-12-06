package com.ai.growsight.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import kotlin.math.exp

data class PlantAnalysisResult(
    val maturity: String,
    val health: String,
    val variant: String,
    val week: Int,  // Added week prediction (1-16)
    val weekRange: String,  // Added week range (Early 1-3, etc.)
    val confidence: Float
)

class MaturityClassifier(context: Context) {

    private var model: Module
    private lateinit var labelMaps: Map<String, Map<String, Int>>
    private lateinit var numericCols: List<String>

    private lateinit var headsOrder: List<String>
    private lateinit var headDims: List<Int>

    private val IMAGE_SIZE = 224
    private val NORM_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val NORM_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    // Week ranges based on training
    private val weekRanges = mapOf(
        "Early (1-3)" to Pair(1, 3),
        "Middle (4-8)" to Pair(4, 8),
        "Ready (9-12)" to Pair(9, 12),
        "Mature (13-16)" to Pair(13, 16)
    )

    init {
        Log.d("MaturityClassifier", "=== CNN INIT ===")
        try {
            model = loadModel(context)
            loadMetadata(context)
            Log.d("MaturityClassifier", "✓ CNN initialized successfully")
            Log.d("MaturityClassifier", "✓ Heads: $headsOrder, Dims: $headDims")
        } catch (e: Exception) {
            Log.e("MaturityClassifier", "✗ CNN initialization failed: ${e.message}", e)
            throw e
        }
    }

    // ---------------------------------------------------------
    // LOAD MODEL (.pt)
    // ---------------------------------------------------------
    private fun loadModel(context: Context): Module {
        // Try downloaded model first
        val downloadedModel = File(context.filesDir, "ml/cnn_lstm_mobile.pt")
        if (downloadedModel.exists()) {
            Log.d("Classifier", "Using downloaded model")
            return Module.load(downloadedModel.absolutePath)
        }

        // Fall back to assets
        val paths = listOf("ml/cnn_lstm_mobile.pt", "cnn_lstm_mobile.pt")
        for (p in paths) {
            try {
                val abs = assetFilePath(context, p)
                Log.d("Classifier", "Trying asset model: $p")
                return Module.load(abs)
            } catch (e: Exception) {
                Log.w("Classifier", "Failed loading $p: ${e.message}")
            }
        }
        throw IllegalStateException("MODEL NOT FOUND in assets or downloads.")
    }

    // ---------------------------------------------------------
    // LOAD METADATA
    // ---------------------------------------------------------
    private fun loadMetadata(context: Context) {
        // Try downloaded files first
        val downloadedLabelMaps = File(context.filesDir, "ml/label_maps.json")
        val labelJson = if (downloadedLabelMaps.exists()) {
            Log.d("Classifier", "Using downloaded label_maps.json")
            JSONObject(downloadedLabelMaps.readText())
        } else {
            // Fall back to assets
            val jsonPaths = listOf("ml/label_maps.json", "label_maps.json")
            var foundJson: JSONObject? = null
            for (p in jsonPaths) {
                try {
                    val txt = readAssetFile(context, p)
                    foundJson = JSONObject(txt)
                    break
                } catch (_: Exception) {}
            }
            foundJson ?: throw Exception("label_maps.json not found")
        }

        val tempLabelMaps = mutableMapOf<String, Map<String, Int>>()
        val heads = mutableListOf<String>()
        val dims = mutableListOf<Int>()

        val iterator = labelJson.keys()
        while (iterator.hasNext()) {
            val head = iterator.next()
            val obj = labelJson.getJSONObject(head)
            val map = mutableMapOf<String, Int>()
            val subKeys = obj.keys()
            while (subKeys.hasNext()) {
                val name = subKeys.next()
                map[name] = obj.getInt(name)
            }
            tempLabelMaps[head] = map
            heads.add(head)
            dims.add(map.size)
        }

        // Add week head if not already in label maps (since week is 1-16 numeric)
        if (!tempLabelMaps.containsKey("week")) {
            tempLabelMaps["week"] = (0 until 16).associate { it.toString() to it }
            heads.add("week")
            dims.add(16)
        }

        labelMaps = tempLabelMaps
        headsOrder = heads
        headDims = dims

        // numeric_cols.json
        val downloadedNumericCols = File(context.filesDir, "ml/numeric_cols.json")
        numericCols = if (downloadedNumericCols.exists()) {
            Log.d("Classifier", "Using downloaded numeric_cols.json")
            try {
                val txt = downloadedNumericCols.readText()
                if (txt.trim().startsWith('[')) {
                    val arr = JSONArray(txt)
                    List(arr.length()) { i -> arr.getString(i) }
                } else {
                    val obj = JSONObject(txt)
                    when {
                        obj.has("numeric_cols") -> {
                            val arr = obj.getJSONArray("numeric_cols")
                            List(arr.length()) { i -> arr.getString(i) }
                        }
                        obj.has("columns") -> {
                            val arr = obj.getJSONArray("columns")
                            List(arr.length()) { i -> arr.getString(i) }
                        }
                        obj.length() == 1 -> {
                            val firstKey = obj.keys().next()
                            val arr = obj.getJSONArray(firstKey)
                            List(arr.length()) { i -> arr.getString(i) }
                        }
                        else -> emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.e("Classifier", "Failed to parse downloaded numeric_cols.json", e)
                emptyList()
            }
        } else {
            try {
                val txt = readAssetFile(context, "ml/numeric_cols.json")
                if (txt.trim().startsWith('[')) {
                    val arr = JSONArray(txt)
                    List(arr.length()) { i -> arr.getString(i) }
                } else {
                    val obj = JSONObject(txt)
                    when {
                        obj.has("numeric_cols") -> {
                            val arr = obj.getJSONArray("numeric_cols")
                            List(arr.length()) { i -> arr.getString(i) }
                        }
                        obj.has("columns") -> {
                            val arr = obj.getJSONArray("columns")
                            List(arr.length()) { i -> arr.getString(i) }
                        }
                        obj.length() == 1 -> {
                            val firstKey = obj.keys().next()
                            val arr = obj.getJSONArray(firstKey)
                            List(arr.length()) { i -> arr.getString(i) }
                        }
                        else -> emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.e("Classifier", "Failed to parse asset numeric_cols.json", e)
                emptyList()
            }
        }

        Log.d("Classifier", "Loaded metadata. Heads=$headsOrder dims=$headDims numeric=$numericCols")
    }

    // ---------------------------------------------------------
    // RUN PREDICTION - CORRECTED VERSION
    // ---------------------------------------------------------
    fun classify(bitmap: Bitmap): PlantAnalysisResult {
        Log.d("MaturityClassifier", "Starting classification on bitmap: ${bitmap.width}x${bitmap.height}")

        return try {
            // 1. IMAGE TENSOR
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true),
                NORM_MEAN,
                NORM_STD
            )
            Log.d("MaturityClassifier", "Input tensor created")

            // 2. NUMERIC TENSOR - Compute actual features from the bitmap
            val numericFeatures = computeNumericFeatures(bitmap)

            // DEBUG: Log the features to verify
            Log.d("MaturityClassifier", "Numeric features [${numericFeatures.size}]: " +
                    "num_points=${numericFeatures[0]}, " +
                    "width=${numericFeatures[1]}, " +
                    "height=${numericFeatures[2]}, " +
                    "aspect_ratio=${numericFeatures[3]}, " +
                    "area=${numericFeatures[4]}, " +
                    "perimeter=${numericFeatures[5]}")

            val numericTensor = if (numericCols.isNotEmpty()) {
                // CRITICAL FIX: Shape must be [1, 1, 6] not [1, 6]
                Tensor.fromBlob(
                    numericFeatures,  // floatArray of size 6
                    longArrayOf(1, 1, 6)  // Shape: [batch_size=1, seq_len=1, num_features=6]
                )
            } else {
                Tensor.fromBlob(FloatArray(0), longArrayOf(1, 1, 0))
            }
            Log.d("MaturityClassifier", "Numeric tensor created, shape: ${numericTensor.shape().contentToString()}")

            // 3. Model forward
            Log.d("MaturityClassifier", "Running model inference...")
            val startTime = System.currentTimeMillis()
            val output = model.forward(
                IValue.from(inputTensor),
                IValue.from(numericTensor)
            ).toTensor()
            val endTime = System.currentTimeMillis()
            Log.d("MaturityClassifier", "Inference completed in ${endTime - startTime}ms")

            val logits = output.dataAsFloatArray
            Log.d("MaturityClassifier", "Output logits size: ${logits.size}")

            // 4. SPLIT LOGITS BACK PER HEAD
            var index = 0
            val predictions = mutableMapOf<String, Int>()
            val confidences = mutableMapOf<String, Float>()

            for (i in headsOrder.indices) {
                val head = headsOrder[i]
                val dim = headDims[i]
                Log.d("MaturityClassifier", "Processing head: $head, dim: $dim, index: $index")

                val slice = logits.slice(index until index + dim).toFloatArray()
                index += dim

                val probs = softmax(slice)
                val bestIdx = probs.indices.maxByOrNull { probs[it] } ?: 0

                predictions[head] = bestIdx
                confidences[head] = probs[bestIdx]

                Log.d("MaturityClassifier", "Head $head: bestIdx=$bestIdx, confidence=${probs[bestIdx]}")
            }

            // Get week prediction and convert to week range
            val weekPrediction = predictions["week"] ?: 0
            val weekNumber = weekPrediction + 1  // Convert from 0-based to 1-based weeks
            val weekRange = convertWeekToRange(weekNumber)

            val result = PlantAnalysisResult(
                maturity = mapBack("maturity", predictions["maturity"] ?: 0),
                health = mapBack("health", predictions["health"] ?: 0),
                variant = mapBack("variant", predictions["variant"] ?: 0),
                week = weekNumber,
                weekRange = weekRange,
                confidence = confidences["maturity"] ?: 0f
            )

            Log.d("MaturityClassifier", "Classification result: $result")
            result

        } catch (e: Exception) {
            Log.e("MaturityClassifier", "Classification error: ${e.message}", e)
            throw e
        }
    }

    // ---------------------------------------------------------
    // Convert week number (1-16) to week range
    // ---------------------------------------------------------
    private fun convertWeekToRange(week: Int): String {
        return when (week) {
            in 1..3 -> "Early (1-3)"
            in 4..8 -> "Middle (4-8)"
            in 9..12 -> "Ready (9-12)"
            in 13..16 -> "Mature (13-16)"
            else -> "Unknown"
        }
    }

    // ---------------------------------------------------------
    // Get stage number (1-4) from week number for UI arrow
    // ---------------------------------------------------------
    fun getStageFromWeek(week: Int): Int {
        return when (week) {
            in 1..3 -> 1  // Early
            in 4..8 -> 2  // Middle
            in 9..12 -> 3  // Ready
            in 13..16 -> 4  // Mature
            else -> 1  // Default to Early
        }
    }

    // ---------------------------------------------------------
    // COMPUTE NUMERIC FEATURES FROM BITMAP - CORRECTED
    // ---------------------------------------------------------
    private fun computeNumericFeatures(bitmap: Bitmap): FloatArray {
        // Get bitmap dimensions
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        // Calculate features in the EXACT order from training:
        // ['num_points', 'width', 'height', 'aspect_ratio', 'area', 'perimeter']

        // 1. num_points - Using your heuristic function
        val numPoints = estimateNumPoints(bitmap)

        // 2. width - Raw pixels (224, 320, etc.)
        val rawWidth = width

        // 3. height - Raw pixels (224, 320, etc.)
        val rawHeight = height

        // 4. aspect_ratio - width/height (e.g., 1.0 for square)
        val aspectRatio = if (height > 0) width / height else 1.0f

        // 5. area - width * height in pixels²
        val area = width * height

        // 6. perimeter - 2*(width + height) in pixels
        val perimeter = 2 * (width + height)

        // IMPORTANT: Order MUST match training: ['num_points', 'width', 'height', 'aspect_ratio', 'area', 'perimeter']
        return floatArrayOf(
            numPoints,      // num_points (0-1000 range from estimateNumPoints)
            rawWidth,       // width in pixels
            rawHeight,      // height in pixels
            aspectRatio,    // aspect_ratio (dimensionless)
            area,           // area in pixels²
            perimeter       // perimeter in pixels
        )
    }

    private fun estimateNumPoints(bitmap: Bitmap): Float {
        // Simple heuristic: estimate number of "interesting points" in the image
        // For plant images, this could be related to leaf edges, etc.
        // Use image variance as a proxy for complexity
        val smallBitmap = Bitmap.createScaledBitmap(bitmap, 50, 50, true)
        var sum = 0f
        var sumSq = 0f
        val pixels = IntArray(smallBitmap.width * smallBitmap.height)
        smallBitmap.getPixels(pixels, 0, smallBitmap.width, 0, 0, smallBitmap.width, smallBitmap.height)

        for (pixel in pixels) {
            val gray = (0.299 * (pixel shr 16 and 0xFF) +
                    0.587 * (pixel shr 8 and 0xFF) +
                    0.114 * (pixel and 0xFF)).toFloat()
            sum += gray
            sumSq += gray * gray
        }

        val n = pixels.size.toFloat()
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)

        // Normalize variance to a reasonable range for "num_points"
        return (variance * 1000f).coerceIn(0f, 1000f)
    }

    // ---------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------
    private fun softmax(x: FloatArray): FloatArray {
        val expVals = x.map { exp(it) }
        val sum = expVals.sum()
        return expVals.map { (it / sum).toFloat() }.toFloatArray()
    }

    private fun mapBack(head: String, index: Int): String {

        // Special handling for week head
        if (head == "week") {
            return (index + 1).toString()   // week1 → "1", week16 → "16"
        }

        val map = labelMaps[head] ?: return "Unknown"
        return map.entries.firstOrNull { it.value == index }?.key ?: "Unknown"
    }


    private fun readAssetFile(context: Context, assetName: String): String {
        return context.assets.open(assetName).bufferedReader().use { it.readText() }
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists()) return file.absolutePath

        context.assets.open(assetName).use { inp ->
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (inp.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
        return file.absolutePath
    }
}