package com.semantic.ekko.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.util.FileUtils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DocumentAdapter
    extends ListAdapter<DocumentEntity, DocumentAdapter.DocumentViewHolder>
{

    public interface OnDocumentClickListener {
        void onClick(DocumentEntity document);
    }

    private final OnDocumentClickListener listener;

    private static final DiffUtil.ItemCallback<DocumentEntity> DIFF_CALLBACK =
        new DiffUtil.ItemCallback<DocumentEntity>() {
            @Override
            public boolean areItemsTheSame(
                @NonNull DocumentEntity a,
                @NonNull DocumentEntity b
            ) {
                return a.id == b.id;
            }

            @Override
            public boolean areContentsTheSame(
                @NonNull DocumentEntity a,
                @NonNull DocumentEntity b
            ) {
                return (
                    a.name.equals(b.name) &&
                    equalOrNull(a.summary, b.summary) &&
                    equalOrNull(a.category, b.category) &&
                    equalOrNull(a.keywords, b.keywords) &&
                    equalOrNull(a.fileType, b.fileType) &&
                    a.wordCount == b.wordCount &&
                    a.indexedAt == b.indexedAt
                );
            }

            private boolean equalOrNull(String a, String b) {
                if (a == null && b == null) return true;
                if (a == null || b == null) return false;
                return a.equals(b);
            }
        };

    public DocumentAdapter(OnDocumentClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    @NonNull
    @Override
    public DocumentViewHolder onCreateViewHolder(
        @NonNull ViewGroup parent,
        int viewType
    ) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
            R.layout.item_document,
            parent,
            false
        );
        return new DocumentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
        @NonNull DocumentViewHolder holder,
        int position
    ) {
        holder.bind(getItem(position), listener);
    }

    static class DocumentViewHolder extends RecyclerView.ViewHolder {

        private final ImageView imgFileIcon;
        private final TextView txtDocName;
        private final TextView txtCategory;
        private final TextView chipFileType;
        private final TextView txtSummary;
        private final ChipGroup chipGroupKeywords;
        private final TextView txtIndexedAt;
        private final TextView txtWordCount;
        private final TextView txtReadTime;

        DocumentViewHolder(@NonNull View itemView) {
            super(itemView);
            imgFileIcon = itemView.findViewById(R.id.imgFileIcon);
            txtDocName = itemView.findViewById(R.id.txtDocName);
            txtCategory = itemView.findViewById(R.id.txtCategory);
            chipFileType = itemView.findViewById(R.id.chipFileType);
            txtSummary = itemView.findViewById(R.id.txtSummary);
            chipGroupKeywords = itemView.findViewById(R.id.chipGroupKeywords);
            txtIndexedAt = itemView.findViewById(R.id.txtIndexedAt);
            txtWordCount = itemView.findViewById(R.id.txtWordCount);
            txtReadTime = itemView.findViewById(R.id.txtReadTime);
        }

        void bind(DocumentEntity doc, OnDocumentClickListener listener) {
            Context ctx = itemView.getContext();

            txtDocName.setText(doc.name);
            txtCategory.setText(
                doc.category != null ? doc.category : "General"
            );

            String fileType =
                doc.fileType != null ? doc.fileType.toUpperCase() : "FILE";
            chipFileType.setText(fileType);
            imgFileIcon.setImageResource(resolveFileIcon(doc.fileType));

            if (doc.summary != null && !doc.summary.isEmpty()) {
                txtSummary.setVisibility(View.VISIBLE);
                txtSummary.setText(doc.summary);
            } else {
                txtSummary.setVisibility(View.GONE);
            }

            chipGroupKeywords.removeAllViews();
            if (doc.keywords != null && !doc.keywords.isEmpty()) {
                String[] keywords = doc.keywords.split(",");
                int max = Math.min(keywords.length, 3);
                for (int i = 0; i < max; i++) {
                    String kw = keywords[i].trim();
                    if (kw.isEmpty()) continue;
                    Chip chip = new Chip(ctx);
                    chip.setText(kw);
                    chip.setClickable(false);
                    chip.setFocusable(false);
                    chip.setTypeface(
                        ResourcesCompat.getFont(ctx, R.font.bricolage_grotesque)
                    );
                    int chipBg = MaterialColors.getColor(
                        ctx,
                        com.google.android.material.R.attr.colorSurfaceVariant,
                        0
                    );
                    int chipFg = MaterialColors.getColor(
                        ctx,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        0
                    );
                    chip.setChipBackgroundColor(ColorStateList.valueOf(chipBg));
                    chip.setTextColor(chipFg);
                    chipGroupKeywords.addView(chip);
                }
            }

            txtIndexedAt.setText(formatIndexedDate(doc.indexedAt));
            txtWordCount.setText(formatWordCount(doc.wordCount));
            txtReadTime.setText(FileUtils.readTimeLabel(doc.wordCount));

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(doc);
            });
        }

        private String formatWordCount(int count) {
            if (count >= 1000) {
                return String.format(Locale.US, "%.1fk words", count / 1000f);
            }
            return count + " words";
        }

        private int resolveFileIcon(String fileType) {
            if (fileType == null) return R.drawable.ic_description;
            String normalized = fileType.toLowerCase(Locale.US);
            if ("pdf".equals(normalized)) return R.drawable.ic_picture_as_pdf;
            if ("txt".equals(normalized)) return R.drawable.ic_text_snippet;
            return R.drawable.ic_description;
        }

        private String formatIndexedDate(long indexedAt) {
            if (indexedAt <= 0) return "Indexed recently";
            SimpleDateFormat fmt = new SimpleDateFormat(
                "MMM d, yyyy",
                Locale.US
            );
            return "Indexed " + fmt.format(new Date(indexedAt));
        }
    }
}
