package com.ai.growsight

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class SelectedImagesAdapter(
    private var images: List<Uri>,
    private val onItemSelected: (Uri, Boolean) -> Unit
) : RecyclerView.Adapter<SelectedImagesAdapter.ImageVH>() {

    private val selectedItems = mutableSetOf<Uri>()

    fun updateData(newImages: List<Uri>) {
        images = newImages
        selectedItems.clear()
        notifyDataSetChanged()
    }

    inner class ImageVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iv: ImageView = itemView.findViewById(R.id.previewImage)
        private val overlay: View = itemView.findViewById(R.id.selectionOverlay)
        private val check: ImageView = itemView.findViewById(R.id.selectionCheck)
        private val border: View = itemView.findViewById(R.id.selectionBorder)

        fun bind(uri: Uri, isSelected: Boolean) {
            iv.setImageURI(uri)
            overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            check.visibility = if (isSelected) View.VISIBLE else View.GONE
            border.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.isSelected = isSelected
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_image, parent, false)
        return ImageVH(v)
    }

    override fun onBindViewHolder(holder: ImageVH, position: Int) {
        val uri = images[position]
        val isSelected = selectedItems.contains(uri)
        holder.bind(uri, isSelected)

        holder.itemView.setOnClickListener {
            val wasSelected = selectedItems.contains(uri)
            if (wasSelected) {
                selectedItems.remove(uri)
            } else {
                selectedItems.add(uri)
            }
            notifyItemChanged(position)
            onItemSelected(uri, !wasSelected)
        }

        holder.itemView.setOnLongClickListener {
            // Optional: Handle long press for additional actions
            true
        }
    }

    override fun getItemCount(): Int = images.size
}