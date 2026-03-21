package com.semantic.ekko.ui.search;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.SearchResult;

public class SearchResultAdapter
    extends ListAdapter<SearchResult, SearchResultAdapter.ViewHolder>
{

    public interface OnResultClickListener {
        void onClick(SearchResult result);
    }

    private final OnResultClickListener listener;

    private static final DiffUtil.ItemCallback<SearchResult> DIFF_CALLBACK =
        new DiffUtil.ItemCallback<SearchResult>() {
            @Override
            public boolean areItemsTheSame(
                @NonNull SearchResult a,
                @NonNull SearchResult b
            ) {
                return a.getDocument().id == b.getDocument().id;
            }

            @Override
            public boolean areContentsTheSame(
                @NonNull SearchResult a,
                @NonNull SearchResult b
            ) {
                return a.getScore() == b.getScore();
            }
        };

    public SearchResultAdapter(OnResultClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(
        @NonNull ViewGroup parent,
        int viewType
    ) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
            R.layout.item_search_result,
            parent,
            false
        );
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final TextView txtDocName;
        private final TextView txtCategory;
        private final TextView txtSummary;
        private final TextView txtFileType;
        private final TextView txtWordCount;
        private final TextView txtRelevanceChip;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtDocName = itemView.findViewById(R.id.txtDocName);
            txtCategory = itemView.findViewById(R.id.txtCategory);
            txtSummary = itemView.findViewById(R.id.txtSummary);
            txtFileType = itemView.findViewById(R.id.txtFileType);
            txtWordCount = itemView.findViewById(R.id.txtWordCount);
            txtRelevanceChip = itemView.findViewById(R.id.txtRelevanceChip);
        }

        void bind(SearchResult result, OnResultClickListener listener) {
            txtDocName.setText(result.getDocument().name);
            txtCategory.setText(
                result.getDocument().category != null
                    ? result.getDocument().category
                    : "General"
            );

            if (
                result.getDocument().summary != null &&
                !result.getDocument().summary.isEmpty()
            ) {
                txtSummary.setVisibility(View.VISIBLE);
                txtSummary.setText(result.getDocument().summary);
            } else {
                txtSummary.setVisibility(View.GONE);
            }

            String fileType =
                result.getDocument().fileType != null
                    ? result.getDocument().fileType.toUpperCase()
                    : "FILE";
            txtFileType.setText(fileType);

            int wordCount = result.getDocument().wordCount;
            txtWordCount.setText(
                wordCount >= 1000
                    ? String.format("%.1fk words", wordCount / 1000f)
                    : wordCount + " words"
            );

            // Relevance chip: "87% · High"
            String chipLabel =
                result.getScoreLabel() + " · " + result.getRelevanceTier();
            txtRelevanceChip.setText(chipLabel);
            applyChipColor(txtRelevanceChip, result.getRelevanceTier());

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(result);
            });
        }

        /**
         * Sets chip background color and text color based on relevance tier.
         * Uses a GradientDrawable so the corner radius is preserved.
         * Colors are semi-transparent to stay readable on both light and dark surfaces.
         */
        private void applyChipColor(TextView chip, String tier) {
            int bgColor;
            int textColor;

            switch (tier) {
                case "High":
                    bgColor = Color.parseColor("#2256A96A"); // green, 13% alpha
                    textColor = Color.parseColor("#56A96A");
                    break;
                case "Medium":
                    bgColor = Color.parseColor("#22E09A3E"); // amber, 13% alpha
                    textColor = Color.parseColor("#E09A3E");
                    break;
                default: // Low
                    bgColor = Color.parseColor("#22888888"); // grey, 13% alpha
                    textColor = Color.parseColor("#AAAAAA");
                    break;
            }

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(
                chip.getResources().getDisplayMetrics().density * 20
            );
            bg.setColor(bgColor);

            chip.setBackground(bg);
            chip.setTextColor(textColor);
        }
    }
}
