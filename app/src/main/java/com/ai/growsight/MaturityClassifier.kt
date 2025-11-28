package com.ai.growsight.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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

    init {
        Log.d("MaturityClassifier", "=== CNN INIT ===")
        try {
            model = loadModel(context)
            loadMetadata(context)
            Log.d("MaturityClassifier", "✓ CNN initialized successfully")

            // Test the model with a small input
            testModelLoad()
        } catch (e: Exception) {
            Log.e("MaturityClassifier", "✗ CNN initialization failed: ${e.message}", e)
            throw e
        }
    }

    private fun testModelLoad() {
        try {
            val testBitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            val result = classify(testBitmap)
            Log.d("MaturityClassifier", "✓ Model test successful: $result")
        } catch (e: Exception) {
            Log.e("MaturityClassifier", "✗ Model test failed: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------
    // LOAD MODEL (.pt)
    // ---------------------------------------------------------
    private fun loadModel(context: Context): Module {
        val paths = listOf("ml/cnn_lstm_mobile.pt", "cnn_lstm_mobile.pt")
        for (p in paths) {
            try {
                val abs = assetFilePath(context, p)
                Log.d("Classifier", "Trying model: $p")
                return Module.load(abs)
            } catch (e: Exception) {
                Log.w("Classifier", "Failed loading $p: ${e.message}")
            }
        }
        throw IllegalStateException("MODEL NOT FOUND in assets.")
    }

    // ---------------------------------------------------------
    // LOAD label_maps.json + numeric_cols.json
    // Also derive headsOrder + headDims from them
    // ---------------------------------------------------------
    private fun loadMetadata(context: Context) {
        val jsonPaths = listOf(
            "ml/label_maps.json",
            "label_maps.json"
        )

        var labelJson: JSONObject? = null

        for (p in jsonPaths) {
            try {
                val txt = readAssetFile(context, p)
                labelJson = JSONObject(txt)
                break
            } catch (_: Exception) {}
        }

        if (labelJson == null) throw Exception("label_maps.json not found")

        val tempLabelMaps = mutableMapOf<String, Map<String, Int>>()
        val heads = mutableListOf<String>()
        val dims = mutableListOf<Int>()

        val iterator = labelJson!!.keys()
        while (iterator.hasNext()) {
            val head = iterator.next()
            val obj = labelJson!!.getJSONObject(head)
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

        labelMaps = tempLabelMaps
        headsOrder = heads
        headDims = dims

        // numeric_cols.json
        numericCols = try {
            val txt = readAssetFile(context, "ml/numeric_cols.json")
            val arr = JSONObject("{\"a\":$txt}").getJSONArray("a")
            List(arr.length()) { i -> arr.getString(i) }
        } catch (e: Exception) {
            emptyList()
        }

        Log.d("Classifier", "Loaded metadata. Heads=$headsOrder dims=$headDims numeric=$numericCols")
    }

    // ---------------------------------------------------------
    // RUN PREDICTION
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

            // 2. NUMERIC TENSOR
            val numericTensor = if (numericCols.isNotEmpty()) {
                Tensor.fromBlob(FloatArray(numericCols.size) { 0f }, longArrayOf(1, numericCols.size.toLong()))
            } else {
                Tensor.fromBlob(FloatArray(0), longArrayOf(1, 0))
            }
            Log.d("MaturityClassifier", "Numeric tensor created, size: ${numericCols.size}")

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

            val result = PlantAnalysisResult(
                maturity = mapBack("maturity", predictions["maturity"] ?: 0),
                health = mapBack("health", predictions["health"] ?: 0),
                variant = mapBack("variant", predictions["variant"] ?: 0),
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
    // HELPERS
    // ---------------------------------------------------------
    private fun softmax(x: FloatArray): FloatArray {
        val expVals = x.map { exp(it) }
        val sum = expVals.sum()
        return expVals.map { (it / sum).toFloat() }.toFloatArray()
    }

    private fun mapBack(head: String, idx: Int): String {
        val map = labelMaps[head] ?: return "unknown"
        return map.entries.firstOrNull { it.value == idx }?.key ?: "unknown"
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