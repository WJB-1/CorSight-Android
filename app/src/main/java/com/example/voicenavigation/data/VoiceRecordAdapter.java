package com.example.voicenavigation.data;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.voicenavigation.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class VoiceRecordAdapter extends RecyclerView.Adapter<VoiceRecordAdapter.ViewHolder> {

    private List<VoiceRecord> records;
    private OnItemActionListener listener;

    public interface OnItemActionListener {
        void onPlay(VoiceRecord record, int position);
        void onDelete(VoiceRecord record, int position);
    }

    public VoiceRecordAdapter(List<VoiceRecord> records) {
        this.records = records;
    }

    public void setOnItemActionListener(OnItemActionListener listener) {
        this.listener = listener;
    }

    public void updateData(List<VoiceRecord> newRecords) {
        this.records = newRecords;
        notifyDataSetChanged();
    }

    public void removeItem(int position) {
        records.remove(position);
        notifyItemRemoved(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_voice_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VoiceRecord record = records.get(position);
        holder.tvContent.setText(record.getContent());

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        String dateStr = sdf.format(new Date(record.getTimestamp()));
        String dest = record.getDestination();

        String meta;
        if (dest != null && !dest.isEmpty()) {
            meta = dateStr + "  → " + dest;
        } else {
            meta = dateStr;
        }
        holder.tvMeta.setText(meta);

        holder.btnPlay.setOnClickListener(v -> {
            if (listener != null) listener.onPlay(record, position);
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(record, position);
        });
    }

    @Override
    public int getItemCount() {
        return records == null ? 0 : records.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvContent;
        TextView tvMeta;
        ImageButton btnPlay;
        ImageButton btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            tvContent = itemView.findViewById(R.id.tv_record_content);
            tvMeta = itemView.findViewById(R.id.tv_record_meta);
            btnPlay = itemView.findViewById(R.id.btn_play);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
