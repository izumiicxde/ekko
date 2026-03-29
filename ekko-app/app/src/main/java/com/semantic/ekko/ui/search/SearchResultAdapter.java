package com.semantic.ekko.ui.search;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.google.android.material.color.MaterialColors;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.SearchResult;
import java.util.Locale;

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
                    ? result.getDocument().fileType.toUpperCase(Locale.US)
                    : "FILE";
            txtFileType.setText(fileType);

            int wordCount = result.getDocument().wordCount;
            txtWordCount.setText(
                wordCount >= 1000
                    ? String.format(Locale.US, "%.1fk words", wordCount / 1000f)
                    : wordCount + " words"
            );

            String chipLabel =
                result.getScoreLabel() + " · " + result.getRelevanceTier();
            txtRelevanceChip.setText(chipLabel);
            applyChipColor(txtRelevanceChip, result.getRelevanceTier());

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(result);
            });
        }

        private void applyChipColor(TextView chip, String tier) {
            int bgColor;
            int textColor;

            switch (tier) {
                case "High":
                    bgColor = MaterialColors.getColor(
                        chip,
                        com.google.android.material.R.attr.colorTertiaryContainer
                    );
                    textColor = MaterialColors.getColor(
                        chip,
                        com.google.android.material.R.attr.colorOnTertiaryContainer
                    );
                    break;
                case "Medium":
                    bgColor = MaterialColors.getColor(
                        chip,
                        com.google.android.material.R.attr.colorSecondaryContainer
                    );
                    textColor = MaterialColors.getColor(
                        chip,
                        com.google.android.material.R.attr.colorOnSecondaryContainer
                    );
                    break;
                default:
                    bgColor = MaterialColors.getColor(
                        chip,
                        com.google.android.material.R.attr.colorSurfaceVariant
                    );
                    textColor = MaterialColors.getColor(
                        chip,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    );
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
