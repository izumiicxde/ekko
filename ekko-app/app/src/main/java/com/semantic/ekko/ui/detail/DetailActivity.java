package com.semantic.ekko.ui.detail;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.ml.EntityExtractorHelper;
import java.util.List;

public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_DOCUMENT_ID = "document_id";

    private DetailViewModel viewModel;
    private DocumentEntity currentDoc;

    private TextView txtDocName;
    private TextView txtFileType;
    private TextView txtSummary;
    private TextView txtWordCount;
    private TextView txtReadTime;
    private TextView txtEntityCount;
    private Chip chipCategory;
    private ChipGroup chipGroupKeywords;
    private ChipGroup chipGroupEntities;
    private ChipGroup chipGroupCorrection;
    private MaterialButton btnOpenFile;
    private MaterialButton btnEnhanceSummary;
    private View labelEntities;
    private View progressEnhanceSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        bindViews();
        setupViewModel();

        long docId = getIntent().getLongExtra(EXTRA_DOCUMENT_ID, -1);
        if (docId == -1) {
            finish();
            return;
        }

        viewModel.loadDocument(docId);
    }

    // =========================
    // BIND
    // =========================

    private void bindViews() {
        txtDocName = findViewById(R.id.txtDocName);
        txtFileType = findViewById(R.id.txtFileType);
        txtSummary = findViewById(R.id.txtSummary);
        txtWordCount = findViewById(R.id.txtWordCount);
        txtReadTime = findViewById(R.id.txtReadTime);
        txtEntityCount = findViewById(R.id.txtEntityCount);
        chipCategory = findViewById(R.id.chipCategory);
        chipGroupKeywords = findViewById(R.id.chipGroupKeywords);
        chipGroupEntities = findViewById(R.id.chipGroupEntities);
        chipGroupCorrection = findViewById(R.id.chipGroupCorrection);
        btnOpenFile = findViewById(R.id.btnOpenFile);
        btnEnhanceSummary = findViewById(R.id.btnEnhanceSummary);
        labelEntities = findViewById(R.id.labelEntities);
        progressEnhanceSummary = findViewById(R.id.progressEnhanceSummary);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    // =========================
    // VIEWMODEL
    // =========================

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(DetailViewModel.class);

        viewModel
            .getDocument()
            .observe(this, doc -> {
                if (doc != null) {
                    currentDoc = doc;
                    bindDocument(doc);
                }
            });

        viewModel
            .getErrorMessage()
            .observe(this, msg -> {
                if (msg != null) {
                    Snackbar.make(
                        btnOpenFile,
                        msg,
                        Snackbar.LENGTH_LONG
                    ).show();
                }
            });

        viewModel
            .getAiSummary()
            .observe(this, summary -> {
                if (summary != null && !summary.isEmpty()) {
                    txtSummary.setVisibility(View.VISIBLE);
                    txtSummary.setText(summary);
                    btnEnhanceSummary.setText("Enhanced");
                    btnEnhanceSummary.setEnabled(false);
                }
            });

        viewModel
            .getSummaryLoading()
            .observe(this, loading -> {
                if (loading == null) return;
                progressEnhanceSummary.setVisibility(
                    loading ? View.VISIBLE : View.GONE
                );
                btnEnhanceSummary.setEnabled(!loading);
            });
    }

    // =========================
    // BIND DOCUMENT
    // =========================

    private void bindDocument(DocumentEntity doc) {
        txtDocName.setText(doc.name);

        String fileType =
            doc.fileType != null ? doc.fileType.toUpperCase() : "FILE";
        txtFileType.setText(fileType);

        chipCategory.setText(doc.category != null ? doc.category : "General");

        String wordCountLabel =
            doc.wordCount >= 1000
                ? String.format("%.1fk", doc.wordCount / 1000f)
                : String.valueOf(doc.wordCount);
        txtWordCount.setText(wordCountLabel);
        txtReadTime.setText(String.valueOf(Math.max(1, doc.wordCount / 200)));

        if (doc.summary != null && !doc.summary.isEmpty()) {
            txtSummary.setVisibility(View.VISIBLE);
            txtSummary.setText(doc.summary);
        } else {
            txtSummary.setVisibility(View.GONE);
        }

        // Enhance summary only available if document has been re-indexed with chunks
        if (doc.chunks != null && !doc.chunks.isEmpty()) {
            btnEnhanceSummary.setVisibility(View.VISIBLE);
        } else {
            btnEnhanceSummary.setVisibility(View.GONE);
        }

        chipGroupKeywords.removeAllViews();
        if (doc.keywords != null && !doc.keywords.isEmpty()) {
            for (String kw : doc.keywords.split(",")) {
                String trimmed = kw.trim();
                if (trimmed.isEmpty()) continue;
                Chip chip = new Chip(this);
                chip.setText(trimmed);
                chip.setClickable(false);
                chip.setFocusable(false);
                chip.setTypeface(
                    ResourcesCompat.getFont(this, R.font.bricolage_grotesque)
                );
                chipGroupKeywords.addView(chip);
            }
        }

        chipGroupEntities.removeAllViews();
        List<String> entities = EntityExtractorHelper.entitiesFromString(
            doc.entities
        );
        txtEntityCount.setText(String.valueOf(entities.size()));

        if (!entities.isEmpty()) {
            labelEntities.setVisibility(View.VISIBLE);
            chipGroupEntities.setVisibility(View.VISIBLE);
            for (String entity : entities) {
                Chip chip = new Chip(this);
                chip.setText(entity);
                chip.setClickable(false);
                chip.setFocusable(false);
                chip.setTypeface(
                    ResourcesCompat.getFont(this, R.font.bricolage_grotesque)
                );
                chipGroupEntities.addView(chip);
            }
        } else {
            labelEntities.setVisibility(View.GONE);
            chipGroupEntities.setVisibility(View.GONE);
        }

        preselectCorrection(doc.category);

        chipGroupCorrection.setOnCheckedStateChangeListener(
            (group, checkedIds) -> {
                if (checkedIds.isEmpty() || currentDoc == null) return;
                int id = checkedIds.get(0);
                String selected = chipIdToCategory(id);
                if (
                    selected == null || selected.equals(currentDoc.category)
                ) return;
                viewModel.correctCategory(currentDoc, selected);
                currentDoc.category = selected;
                chipCategory.setText(selected);
                Snackbar.make(
                    btnOpenFile,
                    "Category updated to " + selected,
                    Snackbar.LENGTH_SHORT
                ).show();
            }
        );

        btnOpenFile.setOnClickListener(v -> openFile(doc));
        btnEnhanceSummary.setOnClickListener(v ->
            viewModel.fetchEnhancedSummary()
        );
    }

    // =========================
    // OPEN FILE
    // =========================

    private void openFile(DocumentEntity doc) {
        try {
            Uri uri = Uri.parse(doc.uri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getContentResolver().getType(uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) {
            Snackbar.make(
                btnOpenFile,
                "Could not open file.",
                Snackbar.LENGTH_SHORT
            ).show();
        }
    }

    // =========================
    // HELPERS
    // =========================

    private void preselectCorrection(String category) {
        int chipId;
        if (category == null) {
            chipId = R.id.chipCorGeneral;
        } else switch (category) {
            case "Technical":
                chipId = R.id.chipCorTechnical;
                break;
            case "Research":
                chipId = R.id.chipCorResearch;
                break;
            case "Legal":
                chipId = R.id.chipCorLegal;
                break;
            case "Medical":
                chipId = R.id.chipCorMedical;
                break;
            default:
                chipId = R.id.chipCorGeneral;
                break;
        }
        Chip chip = chipGroupCorrection.findViewById(chipId);
        if (chip != null) chip.setChecked(true);
    }

    private String chipIdToCategory(int id) {
        if (id == R.id.chipCorTechnical) return "Technical";
        if (id == R.id.chipCorResearch) return "Research";
        if (id == R.id.chipCorLegal) return "Legal";
        if (id == R.id.chipCorMedical) return "Medical";
        if (id == R.id.chipCorGeneral) return "General";
        return null;
    }
}
