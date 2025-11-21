package com.ai.growsight

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var uploadButton: Button
    private lateinit var drawerLayout: DrawerLayout

    private lateinit var galleryPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>

    private var cameraImageUri: Uri? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()  // Permission granted → open camera
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        uploadButton = findViewById(R.id.uploadButton)
        drawerLayout = findViewById(R.id.drawerLayout)

        // ---------- Register Camera Launcher ----------
        cameraLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            if (success && cameraImageUri != null) {
                sendImagesToConversation(arrayListOf(cameraImageUri!!))
            } else {
                Toast.makeText(this, "Camera cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        // ---------- Register Gallery Picker Launcher ----------
        galleryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(5) // up to 5 images
        ) { uris ->
            if (uris.isNotEmpty()) {
                sendImagesToConversation(ArrayList(uris))
            } else {
                Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            }
        }

        // Upload button → show chooser
        uploadButton.setOnClickListener {
            pickFromSystemChooser()
        }
    }


    // ----------------------------------------------------------
    //  SHOW CAMERA or GALLERY CHOOSER
    // ----------------------------------------------------------
    private fun pickFromSystemChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        startActivityForResult(Intent.createChooser(intent, "Select Image"), 999)
    }



    // ----------------------------------------------------------
    //  CAMERA FUNCTION
    // ----------------------------------------------------------
    private fun openCamera() {
        // Check permission
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }

        // Permission already granted → proceed
        val imageFile = File(
            getExternalFilesDir(null),
            "camera_image_${System.currentTimeMillis()}.jpg"
        )

        cameraImageUri = FileProvider.getUriForFile(
            this,
            "${packageName}.provider",
            imageFile
        )

        cameraLauncher.launch(cameraImageUri)
    }



    // ----------------------------------------------------------
    //  GALLERY FUNCTION
    // ----------------------------------------------------------
    private fun pickFromGallery() {
        galleryPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }


    // ----------------------------------------------------------
    //  SEND IMAGES TO NEXT PAGE
    // ----------------------------------------------------------
    private fun sendImagesToConversation(imageUris: ArrayList<Uri>) {
        val intent = Intent(this, ConversationsActivity::class.java).apply {
            putParcelableArrayListExtra(
                ConversationsActivity.EXTRA_IMAGE_URIS,
                imageUris
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(intent)

        Toast.makeText(this, "Images sent to conversation", Toast.LENGTH_SHORT).show()
    }
}
