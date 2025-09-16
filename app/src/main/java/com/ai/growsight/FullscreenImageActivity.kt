package com.ai.growsight

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import android.graphics.BitmapFactory

class FullscreenImageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create an ImageView that fills the screen
        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        setContentView(imageView)

        // Get the URI from the Intent and decode it safely
        intent.getStringExtra("imageUri")?.let { uriString ->
            val uri = Uri.parse(uriString)

            val bmp = try {
                when (uri.scheme) {
                    "content" -> contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    "file" -> BitmapFactory.decodeFile(uri.path)
                    else -> null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }

            if (bmp != null) {
                imageView.setImageBitmap(bmp)
            } else {
                // fallback icon if the image can’t be loaded
                imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        }
    }
}
