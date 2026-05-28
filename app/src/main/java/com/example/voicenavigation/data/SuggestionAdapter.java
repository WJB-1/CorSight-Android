package com.example.voicenavigation.data;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.amap.api.services.core.PoiItem;
import com.example.voicenavigation.R;

import java.util.List;

public class SuggestionAdapter extends RecyclerView.Adapter<SuggestionAdapter.ViewHolder> {

    private List<PoiItem> items;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(PoiItem item, int position);
    }

    public SuggestionAdapter(List<PoiItem> items) {
        this.items = items;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<PoiItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_suggestion, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PoiItem item = items.get(position);
        holder.tvTitle.setText(item.getTitle());
        String address = item.getCityName() + item.getAdName();
        if (item.getSnippet() != null && !item.getSnippet().isEmpty()) {
            address = item.getSnippet();
        }
        holder.tvAddress.setText(address);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item, position);
        });
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvAddress;

        ViewHolder(View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_suggestion_title);
            tvAddress = itemView.findViewById(R.id.tv_suggestion_address);
        }
    }
}
