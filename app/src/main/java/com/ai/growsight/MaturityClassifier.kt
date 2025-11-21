package com.ai.growsight.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class MaturityClassifier(private val context: Context) {

    private lateinit var interpreter: Interpreter
    private val labels: JSONObject
    private val inputSize = 224

    init {
        Log.d("MaturityClassifier", "=== INIT START ===")

        // Load labels
        labels = try {
            val labelText = context.assets.open("ml/label_maps.json")
                .bufferedReader().use { it.readText() }
            JSONObject(labelText).also { Log.d("MaturityClassifier", "✓ Labels loaded") }
        } catch (e: Exception) {
            Log.e("MaturityClassifier", "✗ Failed to load labels: ${e.message}")
            JSONObject()
        }

        // Load interpreter
        loadInterpreter()
        Log.d("MaturityClassifier", "=== INIT COMPLETE ===")
    }

    private fun loadInterpreter() {
        try {
            val modelBuffer = loadModelFile("ml/maturity_model.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
                setUseNNAPI(false)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d("MaturityClassifier", "✓ Interpreter loaded successfully")
        } catch (e: Exception) {
            Log.e("MaturityClassifier", "✗ Interpreter load failed: ${e.message}")
            throw RuntimeException("Failed to load CNN model", e)
        }
    }

    private fun loadModelFile(assetPath: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(assetPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            .order(ByteOrder.nativeOrder())
    }

    data class Result(
        val maturity: String,
        val health: String,
        val variant: String
    )

    fun predict(bitmap: Bitmap): Result {
        return try {
            Log.d("MaturityClassifier", "Starting prediction on bitmap: ${bitmap.width}x${bitmap.height}")

            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = convertBitmapToByteBuffer(resized)

            val maturityOut = Array(1) { FloatArray(3) }
            val healthOut = Array(1) { FloatArray(4) }
            val variantOut = Array(1) { FloatArray(3) }

            val outputs = mutableMapOf<Int, Any>()
            outputs[0] = maturityOut
            outputs[1] = healthOut
            outputs[2] = variantOut

            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

            Log.d("MaturityClassifier", "✓ Inference completed")
            Log.d("MaturityClassifier", "Maturity scores: ${maturityOut[0].contentToString()}")
            Log.d("MaturityClassifier", "Health scores: ${healthOut[0].contentToString()}")
            Log.d("MaturityClassifier", "Variant scores: ${variantOut[0].contentToString()}")

            Result(
                argmaxToLabel("maturity", maturityOut[0]),
                argmaxToLabel("health", healthOut[0]),
                argmaxToLabel("variant", variantOut[0])
            )
        } catch (e: Exception) {
            Log.e("MaturityClassifier", "✗ Prediction failed: ${e.message}", e)
            e.printStackTrace()
            Result("error", "error", "error")
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val v = intValues[pixel++]
                buffer.putFloat(((v shr 16) and 0xFF) / 255f)
                buffer.putFloat(((v shr 8) and 0xFF) / 255f)
                buffer.putFloat((v and 0xFF) / 255f)
            }
        }
        return buffer
    }

    private fun argmaxToLabel(key: String, logits: FloatArray): String {
        val maxIndex = logits.indices.maxByOrNull { logits[it] } ?: 0
        return try {
            val obj = labels.getJSONObject(key)
            for (k in obj.keys()) {
                if (obj.getInt(k) == maxIndex) return k
            }
            "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun close() {
        if (::interpreter.isInitialized) {
            interpreter.close()
            Log.d("MaturityClassifier", "Interpreter closed")
        }
    }
}
