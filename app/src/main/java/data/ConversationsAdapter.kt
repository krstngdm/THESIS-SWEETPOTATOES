package com.ai.growsight.data

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ai.growsight.data.ConversationEntity

class ConversationsAdapter(
    private val onClick: (ConversationEntity) -> Unit
) : RecyclerView.Adapter<ConversationsAdapter.ViewHolder>() {

    private var conversations: List<ConversationEntity> = emptyList()

    fun submitList(newList: List<ConversationEntity>) {
        conversations = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversations[position]
        holder.title.text = conversation.name
        holder.itemView.setOnClickListener { onClick(conversation) }
    }

    override fun getItemCount() = conversations.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
    }
}
