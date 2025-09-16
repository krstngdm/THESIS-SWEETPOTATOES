package com.ai.growsight

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

class MainActivity : AppCompatActivity() {

    private lateinit var uploadButton: Button
    private lateinit var photoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var hamburgerButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        uploadButton = findViewById(R.id.uploadButton)
        drawerLayout = findViewById(R.id.drawerLayout)
        hamburgerButton = findViewById(R.id.hamburgerButton)

        // Setup hamburger button to toggle the drawer
        hamburgerButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END)
            } else {
                drawerLayout.openDrawer(GravityCompat.END)
            }
        }

        // Register system photo picker for single or multiple images
        photoPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(5) // allows up to 5 images
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                // Persist URI permissions for all images
                uris.forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                    }
                }

                // Immediately send images to ConversationsActivity
                val intent = Intent(this, ConversationsActivity::class.java).apply {
                    putParcelableArrayListExtra(
                        ConversationsActivity.EXTRA_IMAGE_URIS,
                        ArrayList(uris)
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)

                Toast.makeText(this, "Images sent to conversation", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            }
        }

        // Open photo picker on button click
        uploadButton.setOnClickListener {
            pickImages()
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }

    private fun pickImages() {
        // Launch the system photo picker with Image-only filter
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
}