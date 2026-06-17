package com.example.voicenavigation.ui.main.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.voicenavigation.R
import com.example.voicenavigation.data.VoiceRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceRecordAdapter(
    private var records: MutableList<VoiceRecord>
) : RecyclerView.Adapter<VoiceRecordAdapter.ViewHolder>() {

    interface OnItemActionListener {
        fun onPlay(record: VoiceRecord, position: Int)
        fun onDelete(record: VoiceRecord, position: Int)
    }

    private var listener: OnItemActionListener? = null

    fun setOnItemActionListener(listener: OnItemActionListener?) {
        this.listener = listener
    }

    fun updateData(newRecords: List<VoiceRecord>) {
        this.records = newRecords.toMutableList()
        notifyDataSetChanged()
    }

    fun removeItem(position: Int) {
        records.removeAt(position)
        notifyItemRemoved(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        holder.tvContent.text = record.content

        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date(record.timestamp))
        val dest = record.destination

        val meta = if (!dest.isNullOrEmpty()) {
            "$dateStr  → $dest"
        } else {
            dateStr
        }
        holder.tvMeta.text = meta

        holder.btnPlay.setOnClickListener { listener?.onPlay(record, position) }
        holder.btnDelete.setOnClickListener { listener?.onDelete(record, position) }
    }

    override fun getItemCount(): Int = records.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvContent: TextView = itemView.findViewById(R.id.tv_record_content)
        val tvMeta: TextView = itemView.findViewById(R.id.tv_record_meta)
        val btnPlay: ImageButton = itemView.findViewById(R.id.btn_play)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
    }
}
