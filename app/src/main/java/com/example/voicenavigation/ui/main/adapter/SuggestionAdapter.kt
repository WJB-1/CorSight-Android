package com.example.voicenavigation.ui.main.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.services.core.PoiItem
import com.example.voicenavigation.R

class SuggestionAdapter(
    private var items: List<PoiItem>
) : RecyclerView.Adapter<SuggestionAdapter.ViewHolder>() {

    interface OnItemClickListener {
        fun onItemClick(item: PoiItem, position: Int)
    }

    private var listener: OnItemClickListener? = null

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    fun updateData(newItems: List<PoiItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        var address = item.cityName + item.adName
        if (!item.snippet.isNullOrEmpty()) {
            address = item.snippet
        }
        holder.tvAddress.text = address
        holder.itemView.setOnClickListener { listener?.onItemClick(item, position) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tv_suggestion_title)
        val tvAddress: TextView = itemView.findViewById(R.id.tv_suggestion_address)
    }
}
