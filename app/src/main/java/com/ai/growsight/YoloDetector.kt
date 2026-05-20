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

        init {
            Log.d("YoloDetector", "=== YOLO INIT (auto: downloaded → fallback) ===")

            try {
                val modelFile = loadLatestModel()

                Log.d("YoloDetector", "✓ Model path: ${modelFile.absolutePath}")
                Log.d("YoloDetector", "✓ Model size: ${modelFile.length()} bytes")

                interpreter = Interpreter(modelFile, Interpreter.Options().apply {
                    setNumThreads(4)
                })

                labels = loadDownloadedOrFallbackLabels()
                Log.d("YoloDetector", "✓ YOLO initialized with labels: $labels")

            } catch (e: Exception) {
                Log.e("YoloDetector", "✗ YOLO initialization failed: ${e.message}", e)
                throw e
            }
        }

        // ---------------------------------------------------------
        // MODEL LOADING (NO useBuiltIn)
        // ---------------------------------------------------------

        private fun loadLatestModel(): File {
            val downloadedModel = File(context.filesDir, "ml/yolov8.tflite")

            // Use downloaded model if available
            if (downloadedModel.exists() && downloadedModel.length() > 0L) {
                Log.d("YoloDetector", "Using DOWNLOADED model.")
                return downloadedModel
            }

            // If not available → fallback to your built-in model
            Log.d("YoloDetector", "Downloaded model not found → Using built-in model.")
            return copyAsset("ml/best_int8.tflite")
        }

        private fun copyAsset(assetPath: String): File {
            val outFile = File(context.filesDir, assetPath)

            // Only copy if not existing
            if (outFile.exists() && outFile.length() > 0L)
                return outFile

            outFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }

            return outFile
        }

        // ---------------------------------------------------------
        // LABEL LOADING (AUTO)
        // ---------------------------------------------------------

        private fun loadDownloadedOrFallbackLabels(): Map<Int, String> {
            return try {
                val file = File(context.filesDir, "ml/yolo_labels.json")

                val jsonText = if (file.exists()) file.readText()
                else context.assets.open("ml/yolo_labels.json")
                    .bufferedReader().use { it.readText() }

                val json = JSONObject(jsonText)
                json.keys().asSequence().associate { k ->
                    k.toInt() to json.getString(k)
                }

            } catch (e: Exception) {
                Log.e("YoloDetector", "Failed to load labels", e)
                mapOf(0 to "plant")
            }
        }

        // ---------------------------------------------------------
        // DETECTION
        // ---------------------------------------------------------

        data class Detection(
            val box: RectF,
            val score: Float,
            val classId: Int,
            val label: String
        )

        fun detect(bitmap: Bitmap): List<Detection> {
            return try {
                val input = preprocess(bitmap)

                val output = Array(1) { Array(5) { FloatArray(8400) } }

                val t0 = System.currentTimeMillis()
                interpreter.run(input, output)
                val t1 = System.currentTimeMillis()

                Log.d("YoloDetector", "Inference time: ${t1 - t0}ms")

                parseOutput(output[0], bitmap.width, bitmap.height)

            } catch (e: Exception) {
                Log.e("YoloDetector", "Detection error: ${e.message}", e)
                emptyList()
            }
        }

        private fun parseOutput(
            out: Array<FloatArray>,
            origW: Int,
            origH: Int
        ): List<Detection> {
            val detections = mutableListOf<Detection>()

            val scale = minOf(
                inputSize.toFloat() / origW,
                inputSize.toFloat() / origH
            )
            val padX = (inputSize - origW * scale) / 2f
            val padY = (inputSize - origH * scale) / 2f

            for (i in 0 until 8400) {
                val conf = out[4][i]
                if (conf < confidenceThreshold) continue

                val cx = out[0][i]
                val cy = out[1][i]
                val w  = out[2][i]
                val h  = out[3][i]

                val cxModel = cx * inputSize
                val cyModel = cy * inputSize
                val wModel  = w  * inputSize
                val hModel  = h  * inputSize

                val cxImg = (cxModel - padX) / scale
                val cyImg = (cyModel - padY) / scale
                val wImg  = wModel / scale
                val hImg  = hModel / scale

                val left   = cxImg - wImg / 2f
                val top    = cyImg - hImg / 2f
                val right  = cxImg + wImg / 2f
                val bottom = cyImg + hImg / 2f

                detections.add(
                    Detection(
                        box = RectF(
                            left.coerceIn(0f, origW.toFloat()),
                            top.coerceIn(0f, origH.toFloat()),
                            right.coerceIn(0f, origW.toFloat()),
                            bottom.coerceIn(0f, origH.toFloat())
                        ),
                        score = conf,
                        classId = 0,
                        label = labels[0] ?: "plant"
                    )
                )
            }

            return applyNMS(detections)
        }

        // ---------------------------------------------------------
        // NMS
        // ---------------------------------------------------------

        private fun applyNMS(detections: List<Detection>): List<Detection> {
            if (detections.isEmpty()) return emptyList()

            val sorted = detections.sortedByDescending { it.score }.toMutableList()
            val finalList = mutableListOf<Detection>()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                finalList.add(best)

                val iterator = sorted.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    val iou = iou(best.box, other.box)
                    if (iou >= iouThreshold) iterator.remove()
                }
            }

            return finalList
        }

        private fun iou(a: RectF, b: RectF): Float {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val right = minOf(a.right, b.right)
            val bottom = minOf(a.bottom, b.bottom)

            if (right <= left || bottom <= top) return 0f

            val inter = (right - left) * (bottom - top)
            val areaA = (a.right - a.left) * (a.bottom - a.top)
            val areaB = (b.right - b.left) * (b.bottom - b.top)

            return inter / (areaA + areaB - inter)
        }

        // ---------------------------------------------------------
        // PREPROCESS
        // ---------------------------------------------------------

        private fun preprocess(bitmap: Bitmap): ByteBuffer {
            val input = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(input)

            val scale = minOf(
                inputSize.toFloat() / bitmap.width,
                inputSize.toFloat() / bitmap.height
            )
            val dx = (inputSize - bitmap.width * scale) / 2f
            val dy = (inputSize - bitmap.height * scale) / 2f

            val matrix = android.graphics.Matrix().apply {
                postScale(scale, scale)
                postTranslate(dx, dy)
            }
            canvas.drawBitmap(bitmap, matrix, null)

            val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
            buffer.order(ByteOrder.nativeOrder())

            val pixels = IntArray(inputSize * inputSize)
            input.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

            for (pixel in pixels) {
                buffer.putFloat((pixel shr 16 and 0xFF) / 255f)
                buffer.putFloat((pixel shr 8 and 0xFF) / 255f)
                buffer.putFloat((pixel and 0xFF) / 255f)
            }

            buffer.rewind()
            return buffer
        }


        fun close() {
            interpreter.close()
        }
    }
