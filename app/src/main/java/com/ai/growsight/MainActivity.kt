package com.ai.growsight

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var navCamera: LinearLayout

    private lateinit var galleryPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>

    private var cameraImageUri: Uri? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) openCamera()
        else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)   // or R.layout.main — match your file name

        navCamera = findViewById(R.id.navCamera)

        // ── Camera launcher ──────────────────────────────────────────────────
        cameraLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            if (success && cameraImageUri != null) {
                launchQuickScan(arrayListOf(cameraImageUri!!))
            } else {
                Toast.makeText(this, "Camera cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        // ── Gallery launcher ─────────────────────────────────────────────────
        galleryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(5)
        ) { uris ->
            if (uris.isNotEmpty()) launchQuickScan(ArrayList(uris))
            else Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
        }

        // ── Camera tab → quick scan chooser ─────────────────────────────────
        navCamera.setOnClickListener {
            showQuickScanChooser()
        }
    }

    // ── Show camera / gallery picker dialog ──────────────────────────────────
    private fun showQuickScanChooser() {
        val options = arrayOf("📷  Take Photo", "🖼️  Choose from Gallery")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quick Scan")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> pickFromGallery()
                }
            }
            .show()
    }

    // ── Camera ────────────────────────────────────────────────────────────────
    private fun openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }

        val imageFile = File(
            getExternalFilesDir(null),
            "quick_scan_${System.currentTimeMillis()}.jpg"
        )
        cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.provider", imageFile)
        cameraLauncher.launch(cameraImageUri)
    }

    // ── Gallery ───────────────────────────────────────────────────────────────
    private fun pickFromGallery() {
        galleryPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // ── Launch ConversationsActivity with NO plantation (quick scan mode) ─────
    private fun launchQuickScan(imageUris: ArrayList<Uri>) {
        val intent = Intent(this, ConversationsActivity::class.java).apply {
            putParcelableArrayListExtra(ConversationsActivity.EXTRA_IMAGE_URIS, imageUris)
            putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)  // ← new flag
            // No plantation ID passed → ConversationsActivity treats this as quick scan
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }
}