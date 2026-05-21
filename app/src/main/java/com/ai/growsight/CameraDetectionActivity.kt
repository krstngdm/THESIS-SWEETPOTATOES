package com.ai.growsight

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraDetectionActivity : AppCompatActivity() {

    companion object {
        const val RESULT_IMAGE_URI = "result_image_uri"
        private const val FRAME_SKIP = 3
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: FrameLayout
    private lateinit var captureButtonInner: View
    private lateinit var statusText: TextView
    private lateinit var tipText: TextView

    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    // Holds the last captured frame for saving on button tap
    private var lastGoodBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private val frameCounter = AtomicInteger(0)
    private val isProcessing = AtomicBoolean(false)
    private val isCapturing = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(buildLayout())
        startCamera()

        captureButton.setOnClickListener {
            if (!isCapturing.get() && captureButton.isEnabled) {
                captureFromLastFrame()
            }
        }
    }

    // ─── Layout built in code ─────────────────────────────────────────────────

    private fun buildLayout(): FrameLayout {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0A0A0A.toInt())

        // ── TOP BAR ───────────────────────────────────────────────────────────
        val topBar = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(92)
            ).apply { gravity = Gravity.TOP }
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            gravity = Gravity.CENTER
        }

        val backAndTitle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(36) }
        }

        val backBtn = FrameLayout(this).apply {
            val size = dpToPx(40)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = dpToPx(16)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            addView(TextView(this@CameraDetectionActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                text = "‹"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 0, dpToPx(2), dpToPx(2))
            })
        }
        backAndTitle.addView(backBtn)

        backAndTitle.addView(TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            text = "Scan Plant"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = try {
                androidx.core.content.res.ResourcesCompat.getFont(
                    this@CameraDetectionActivity, R.font.nunito_bold
                )
            } catch (e: Exception) { null }
        })

        topBar.addView(backAndTitle)

        // Status pill
        statusText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            text = "📷  Camera ready"
            textSize = 13f
            setPadding(dpToPx(18), dpToPx(6), dpToPx(18), dpToPx(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(0xFF222222.toInt())
            }
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = try {
                androidx.core.content.res.ResourcesCompat.getFont(
                    this@CameraDetectionActivity, R.font.nunito_regular
                )
            } catch (e: Exception) { null }
        }
        topBar.addView(statusText)
        root.addView(topBar)

        // ── CAMERA PREVIEW ────────────────────────────────────────────────────
        val previewContainer = FrameLayout(this).apply {
            val screenWidth = resources.displayMetrics.widthPixels
            val previewSize = (screenWidth * 0.88f).toInt()
            layoutParams = FrameLayout.LayoutParams(previewSize, previewSize).apply {
                gravity = Gravity.CENTER
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: android.view.View,
                    outline: android.graphics.Outline
                ) {
                    outline.setRoundRect(
                        0, 0, view.width, view.height,
                        dpToPx(20).toFloat()
                    )
                }
            }
            clipToOutline = true
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewContainer.addView(previewView)

        // Corner bracket scan guide
        val scanFrame = object : android.view.View(this) {
            private val paint = android.graphics.Paint().apply {
                color = 0xFF4CAF50.toInt()
                strokeWidth = dpToPx(3).toFloat()
                style = android.graphics.Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val cl = dpToPx(28).toFloat()
                val pad = dpToPx(32).toFloat()
                val r = width.toFloat() - pad
                val b = height.toFloat() - pad
                val l = pad; val t = pad
                canvas.drawLine(l, t + cl, l, t, paint)
                canvas.drawLine(l, t, l + cl, t, paint)
                canvas.drawLine(r - cl, t, r, t, paint)
                canvas.drawLine(r, t, r, t + cl, paint)
                canvas.drawLine(l, b - cl, l, b, paint)
                canvas.drawLine(l, b, l + cl, b, paint)
                canvas.drawLine(r - cl, b, r, b, paint)
                canvas.drawLine(r, b, r, b - cl, paint)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setWillNotDraw(false)
        }
        previewContainer.addView(scanFrame)
        root.addView(previewContainer)

        // ── BOTTOM BAR ────────────────────────────────────────────────────────
        val bottomBar = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            setPadding(0, dpToPx(20), 0, dpToPx(40))
        }

        tipText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(24) }
            text = "Tap the button to capture"
            textSize = 13f
            setTextColor(0xAAFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = try {
                androidx.core.content.res.ResourcesCompat.getFont(
                    this@CameraDetectionActivity, R.font.nunito_regular
                )
            } catch (e: Exception) { null }
        }
        bottomBar.addView(tipText)

        // Capture button row
        val buttonRow = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(88)
            )
        }

        captureButton = FrameLayout(this).apply {
            val size = dpToPx(76)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
            isEnabled = false   // enabled once first frame is buffered
            alpha = 0.35f
            isClickable = true
            isFocusable = true
        }

        captureButton.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dpToPx(3), 0xFFFFFFFF.toInt())
                setColor(0x00000000)
            }
        })

        captureButtonInner = View(this).apply {
            val inset = dpToPx(8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(inset, inset, inset, inset) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
        }
        captureButton.addView(captureButtonInner)
        buttonRow.addView(captureButton)
        bottomBar.addView(buttonRow)
        root.addView(bottomBar)

        return root
    }

    // ─── Camera setup ─────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(inferenceExecutor) { imageProxy ->
                val count = frameCounter.incrementAndGet()
                if (count % FRAME_SKIP != 0 || isProcessing.get() || isCapturing.get()) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                isProcessing.set(true)
                try {
                    val plane = imageProxy.planes[0].buffer
                    plane.rewind()

                    val rawBitmap = Bitmap.createBitmap(
                        imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
                    )
                    rawBitmap.copyPixelsFromBuffer(plane)

                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val bitmap = if (rotation != 0) {
                        val m = Matrix().apply { postRotate(rotation.toFloat()) }
                        val rotated = Bitmap.createBitmap(
                            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, m, true
                        )
                        rawBitmap.recycle()
                        rotated
                    } else rawBitmap

                    // Store every frame unconditionally — no detection gating
                    synchronized(bitmapLock) {
                        lastGoodBitmap?.recycle()
                        lastGoodBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    }

                    bitmap.recycle()

                    // Enable capture button once the first frame is buffered
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && !captureButton.isEnabled) {
                            captureButton.isEnabled = true
                            captureButton.alpha = 1.0f
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CameraDetection", "Frame error: ${e.message}")
                } finally {
                    imageProxy.close()
                    isProcessing.set(false)
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )

        }, ContextCompat.getMainExecutor(this))
    }

    // ─── Capture from stored frame ────────────────────────────────────────────

    private fun captureFromLastFrame() {
        isCapturing.set(true)

        val bitmapToSave = synchronized(bitmapLock) {
            lastGoodBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }

        if (bitmapToSave == null) {
            Toast.makeText(this, "No frame captured yet, try again", Toast.LENGTH_SHORT).show()
            isCapturing.set(false)
            return
        }

        // Visual feedback
        captureButtonInner.alpha = 0.3f
        captureButton.postDelayed({ captureButtonInner.alpha = 1f }, 120)

        inferenceExecutor.execute {
            try {
                val photoFile = File(
                    cacheDir,
                    "detected_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(Date())}.jpg"
                )
                FileOutputStream(photoFile).use { out ->
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bitmapToSave.recycle()

                val uri = FileProvider.getUriForFile(
                    this@CameraDetectionActivity,
                    "${packageName}.provider",
                    photoFile
                )

                runOnUiThread {
                    val resultIntent = Intent().apply {
                        putExtra(RESULT_IMAGE_URI, uri.toString())
                        putExtra("was_detected", false)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            } catch (e: Exception) {
                android.util.Log.e("CameraDetection", "Frame save failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Capture failed, try again", Toast.LENGTH_SHORT).show()
                    isCapturing.set(false)
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        isProcessing.set(true)
    }

    override fun onResume() {
        super.onResume()
        isProcessing.set(false)
        frameCounter.set(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceExecutor.shutdownNow()
        synchronized(bitmapLock) { lastGoodBitmap?.recycle(); lastGoodBitmap = null }
    }
}