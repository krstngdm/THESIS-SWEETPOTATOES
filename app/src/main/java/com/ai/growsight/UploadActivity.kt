    package com.ai.growsight
    
    import android.content.Intent
    import android.net.Uri
    import android.os.Bundle
    import android.view.animation.AnimationUtils
    import android.widget.Button
    import android.widget.Toast
    import android.widget.ViewFlipper
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.appcompat.app.AppCompatActivity


    class UploadActivity : AppCompatActivity() {
    
        private lateinit var uploadButton: Button
        private lateinit var flipper: ViewFlipper
    
        // Launcher for picking multiple images
        private val pickImagesLauncher = registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris: List<Uri> ->
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
            if (uris.isNullOrEmpty()) {
                Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val unsentUris = uris.filterNot { selectedUri ->
                ConversationsActivity.sentUris.contains(selectedUri.toString())
            }
    
            if (unsentUris.isEmpty()) {
                Toast.makeText(this, "All selected images were already sent", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
    
            val intent = Intent(this, ConversationsActivity::class.java).apply {
                putParcelableArrayListExtra(
                    ConversationsActivity.EXTRA_IMAGE_URIS,
                    ArrayList(unsentUris)
                )
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.main) // ensure this layout includes flipperContainer and uploadButton
    
            uploadButton = findViewById(R.id.uploadButton)
            flipper = findViewById(R.id.flipperContainer)
    
            // Configure flipper: 3-second interval with fade in/out
            flipper.inAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            flipper.outAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)
            flipper.flipInterval = 5000
            flipper.startFlipping()
    
            uploadButton.setOnClickListener {
                pickImagesLauncher.launch("image/*")
            }
        }
    
        override fun onResume() {
            super.onResume()
            flipper.startFlipping()
        }
    
        override fun onPause() {
            super.onPause()
            flipper.stopFlipping()
        }
    }
