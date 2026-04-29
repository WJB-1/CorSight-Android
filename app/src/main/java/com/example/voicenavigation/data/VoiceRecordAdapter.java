package com.example.voicenavigation.data;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    public VoiceRecordAdapter(List<VoiceRecord> records) {
        this.records = records;
    }

    public void updateData(List<VoiceRecord> newRecords) {
        this.records = newRecords;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VoiceRecord record = records.get(position);
        holder.text1.setText(record.getContent());

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        String dateStr = sdf.format(new Date(record.getTimestamp()));
        String dest = record.getDestination();
        if (dest != null && !dest.isEmpty()) {
            holder.text2.setText(dateStr + "  → " + dest);
        } else {
            holder.text2.setText(dateStr);
        }
    }

    @Override
    public int getItemCount() {
        return records == null ? 0 : records.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1;
        TextView text2;

        ViewHolder(View itemView) {
            super(itemView);
            text1 = itemView.findViewById(android.R.id.text1);
            text2 = itemView.findViewById(android.R.id.text2);
        }
    }
}
