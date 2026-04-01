package com.semantic.ekko.ui.home;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.processing.extractor.PdfTextExtractor;
import com.semantic.ekko.ui.detail.DetailActivity;
import com.semantic.ekko.ui.qa.QAActivity;
import com.semantic.ekko.ui.search.SearchActivity;
import com.semantic.ekko.ui.settings.SettingsActivity;
import com.semantic.ekko.ui.statistics.StatisticsActivity;
import com.semantic.ekko.util.StorageAccessHelper;
import com.semantic.ekko.work.PublicStorageImportWorker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class HomeActivity extends AppCompatActivity {

    private HomeViewModel viewModel;
    private DocumentAdapter adapter;

    private RecyclerView recyclerDocuments;
    private LinearLayout layoutIndexingProgress;
    private LinearLayout layoutEmptyState;
    private TextView txtIndexingStage;
    private TextView txtIndexingDoc;
    private TextView txtDocCount;
    private LinearProgressIndicator progressIndexing;
    private ChipGroup chipGroupFilters;
    private View searchBar;
    private Map<Long, String> currentFolderNames = new HashMap<>();
    private final ActivityResultLauncher<Intent> manageStorageAccessLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (StorageAccessHelper.hasAllFilesAccess()) {
                    PublicStorageImportWorker.enqueue(this);
                    Snackbar.make(
                        recyclerDocuments,
                        "Public folder import started in the background.",
                        Snackbar.LENGTH_LONG
                    ).show();
                }
            }
        );

    // =========================
    // FOLDER PICKER
    // =========================

    private final ActivityResultLauncher<Uri> folderPicker =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (SecurityException | IllegalArgumentException e) {
                    getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                }
                viewModel.addFolderAndIndex(uri);
            }
        );

    // =========================
    // LIFECYCLE
    // =========================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        PdfTextExtractor.init(getApplicationContext());

        bindViews();
        setupRecycler();
        setupViewModel();
        setupSearchBar();
        setupClickListeners();

        Executors.newSingleThreadExecutor().execute(() -> {
            viewModel.initMl();
            runOnUiThread(() -> viewModel.loadDocuments());
        });
    }

    // =========================
    // BIND
    // =========================

    private void bindViews() {
        recyclerDocuments = findViewById(R.id.recyclerDocuments);
        layoutIndexingProgress = findViewById(R.id.layoutIndexingProgress);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        txtIndexingStage = findViewById(R.id.txtIndexingStage);
        txtIndexingDoc = findViewById(R.id.txtIndexingDoc);
        txtDocCount = findViewById(R.id.txtDocCount);
        progressIndexing = findViewById(R.id.progressIndexing);
        chipGroupFilters = findViewById(R.id.chipGroupFilters);
        searchBar = findViewById(R.id.searchBar);
    }

    // =========================
    // RECYCLER
    // =========================

    private void setupRecycler() {
        adapter =
            new DocumentAdapter(
                doc -> {
                    Intent intent = new Intent(this, DetailActivity.class);
                    intent.putExtra(DetailActivity.EXTRA_DOCUMENT_ID, doc.id);
                    startActivity(intent);
                },
                state -> {}
            );

        recyclerDocuments.setLayoutManager(new LinearLayoutManager(this));
        recyclerDocuments.setAdapter(adapter);
        recyclerDocuments.setNestedScrollingEnabled(false);
    }

    // =========================
    // VIEWMODEL
    // =========================

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);
        adapter.setDisplayMode(viewModel.getCurrentViewMode());

        viewModel
            .getDocuments()
            .observe(this, docs -> {
                adapter.submitDocuments(docs);
                updateEmptyState(docs);
                updateDocCount(docs.size());
                buildFilterChips(docs);
            });

        viewModel
            .getFolderNames()
            .observe(this, folderNames -> {
                currentFolderNames =
                    folderNames != null ? folderNames : new HashMap<>();
                adapter.submitFolderNames(currentFolderNames);
            });

        viewModel
            .getIsIndexing()
            .observe(this, indexing -> {
                layoutIndexingProgress.setVisibility(
                    indexing ? View.VISIBLE : View.GONE
                );
                findViewById(R.id.fabAddFolder).setEnabled(!indexing);
            });

        viewModel
            .getIndexingStage()
            .observe(this, stage -> {
                if (stage != null) txtIndexingStage.setText(stage);
            });

        viewModel
            .getIndexingProgress()
            .observe(this, progress -> {
                if (progress == null) return;
                progressIndexing.setMax(progress.total);
                progressIndexing.setProgress(progress.current);
                txtIndexingDoc.setText(
                    progress.docName +
                        " (" +
                        progress.current +
                        "/" +
                        progress.total +
                        ")"
                );
            });

        viewModel
            .getErrorMessage()
            .observe(this, msg -> {
                if (msg != null && !msg.isEmpty()) {
                    Snackbar.make(
                        recyclerDocuments,
                        msg,
                        Snackbar.LENGTH_LONG
                    ).show();
                }
            });
    }

    // =========================
    // SEARCH BAR
    // =========================

    private void setupSearchBar() {
        searchBar.setOnClickListener(v ->
            startActivity(new Intent(this, SearchActivity.class))
        );
    }

    // =========================
    // CLICK LISTENERS
    // =========================

    private void setupClickListeners() {
        findViewById(R.id.fabAddFolder).setOnClickListener(v -> {
            folderPicker.launch(null);
        });

        findViewById(R.id.btnStatistics).setOnClickListener(v ->
            startActivity(new Intent(this, StatisticsActivity.class))
        );

        findViewById(R.id.btnSettings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class))
        );

        findViewById(R.id.btnSortFilter).setOnClickListener(v ->
            showSortFilterSheet()
        );

        // Q&A entry point
        findViewById(R.id.btnAskEkko).setOnClickListener(v ->
            startActivity(new Intent(this, QAActivity.class))
        );
    }

    // =========================
    // FILTER CHIPS
    // =========================

    private String activeChipKeyword = null;
    private boolean chipsBuilt = false;

    private void buildFilterChips(List<DocumentEntity> docs) {
        if (chipsBuilt) return;

        Set<String> keywords = new LinkedHashSet<>();
        for (DocumentEntity doc : docs) {
            if (doc.keywords == null || doc.keywords.isEmpty()) continue;
            for (String kw : doc.keywords.split(",")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) keywords.add(trimmed);
            }
        }

        if (keywords.isEmpty()) return;

        chipGroupFilters.removeAllViews();
        chipsBuilt = true;

        Chip chipAll = makeFilterChip("All", true);
        chipAll.setOnClickListener(v -> {
            activeChipKeyword = null;
            viewModel.clearKeywordFilter();
            refreshChipColors();
        });
        chipGroupFilters.addView(chipAll);

        int count = 0;
        for (String keyword : keywords) {
            if (count >= 4) break;
            Chip chip = makeFilterChip(keyword, false);
            chip.setOnClickListener(v -> {
                activeChipKeyword = keyword;
                viewModel.setKeywordFilter(keyword);
                refreshChipColors();
            });
            chipGroupFilters.addView(chip);
            count++;
        }
    }

    private Chip makeFilterChip(String text, boolean active) {
        Chip chip = new Chip(this);
        chip.setText(text);
        chip.setCheckable(false);
        chip.setCheckedIconVisible(false);
        applyChipColor(chip, active);
        return chip;
    }

    private void applyChipColor(Chip chip, boolean active) {
        int bg = active
            ? com.google.android.material.color.MaterialColors.getColor(
                  this,
                  com.google.android.material.R.attr.colorPrimaryContainer,
                  0
              )
            : com.google.android.material.color.MaterialColors.getColor(
                  this,
                  com.google.android.material.R.attr.colorSurfaceVariant,
                  0
              );
        int fg = active
            ? com.google.android.material.color.MaterialColors.getColor(
                  this,
                  com.google.android.material.R.attr.colorOnPrimaryContainer,
                  0
              )
            : com.google.android.material.color.MaterialColors.getColor(
                  this,
                  com.google.android.material.R.attr.colorOnSurfaceVariant,
                  0
              );
        chip.setChipBackgroundColor(
            android.content.res.ColorStateList.valueOf(bg)
        );
        chip.setTextColor(fg);
    }

    private void refreshChipColors() {
        for (int i = 0; i < chipGroupFilters.getChildCount(); i++) {
            View child = chipGroupFilters.getChildAt(i);
            if (!(child instanceof Chip)) continue;
            Chip chip = (Chip) child;
            String text = chip.getText().toString();
            boolean active =
                (activeChipKeyword == null && text.equals("All")) ||
                text.equals(activeChipKeyword);
            applyChipColor(chip, active);
        }
    }

    // =========================
    // SORT FILTER SHEET
    // =========================

    private void showSortFilterSheet() {
        SortFilterBottomSheet sheet = new SortFilterBottomSheet(
            viewModel.getCurrentSortOrder(),
            viewModel.getCurrentFileTypeFilter(),
            viewModel.getCurrentViewMode(),
            (sortOrder, fileType, viewMode) -> {
                viewModel.setSortOrder(sortOrder);
                viewModel.setFileTypeFilter(fileType);
                viewModel.setViewMode(viewMode);
                adapter.setDisplayMode(viewMode);
            }
        );
        sheet.show(getSupportFragmentManager(), "sort_filter");
    }

    // =========================
    // HELPERS
    // =========================

    private void updateEmptyState(List<DocumentEntity> docs) {
        boolean isEmpty = docs == null || docs.isEmpty();
        layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerDocuments.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void updateDocCount(int count) {
        txtDocCount.setText(count + (count == 1 ? " document" : " documents"));
    }
}
