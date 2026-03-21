package com.semantic.ekko.ui.home;

import com.semantic.ekko.R;
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
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.search.SearchBar;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.processing.extractor.PdfTextExtractor;
import com.semantic.ekko.ui.detail.DetailActivity;
import com.semantic.ekko.ui.search.SearchActivity;
import com.semantic.ekko.ui.settings.SettingsActivity;
import com.semantic.ekko.ui.statistics.StatisticsActivity;

import java.util.LinkedHashSet;
import java.util.List;
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

    // =========================
    // FOLDER PICKER
    // =========================

    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null) return;
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
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

        // Init ML on background thread
        Executors.newSingleThreadExecutor().execute(() -> {
            viewModel.initMl();
            runOnUiThread(() -> viewModel.loadDocuments());
        });
    }

    // =========================
    // BIND
    // =========================

    private void bindViews() {
        recyclerDocuments     = findViewById(R.id.recyclerDocuments);
        layoutIndexingProgress = findViewById(R.id.layoutIndexingProgress);
        layoutEmptyState      = findViewById(R.id.layoutEmptyState);
        txtIndexingStage      = findViewById(R.id.txtIndexingStage);
        txtIndexingDoc        = findViewById(R.id.txtIndexingDoc);
        txtDocCount           = findViewById(R.id.txtDocCount);
        progressIndexing      = findViewById(R.id.progressIndexing);
        chipGroupFilters      = findViewById(R.id.chipGroupFilters);
        searchBar             = findViewById(R.id.searchBar);
    }

    // =========================
    // RECYCLER
    // =========================

    private void setupRecycler() {
        adapter = new DocumentAdapter(doc -> {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_DOCUMENT_ID, doc.id);
            startActivity(intent);
        });

        recyclerDocuments.setLayoutManager(new LinearLayoutManager(this));
        recyclerDocuments.setAdapter(adapter);
        recyclerDocuments.setNestedScrollingEnabled(false);
    }

    // =========================
    // VIEWMODEL
    // =========================

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);

        viewModel.getDocuments().observe(this, docs -> {
            adapter.submitList(docs);
            updateEmptyState(docs);
            updateDocCount(docs.size());
            buildFilterChips(docs);
        });

        viewModel.getIsIndexing().observe(this, indexing -> {
            layoutIndexingProgress.setVisibility(
                    indexing ? View.VISIBLE : View.GONE);
            findViewById(R.id.fabAddFolder).setEnabled(!indexing);
        });

        viewModel.getIndexingStage().observe(this, stage -> {
            if (stage != null) txtIndexingStage.setText(stage);
        });

        viewModel.getIndexingProgress().observe(this, progress -> {
            if (progress == null) return;
            progressIndexing.setMax(progress.total);
            progressIndexing.setProgress(progress.current);
            txtIndexingDoc.setText(
                    progress.docName + " (" + progress.current + "/" + progress.total + ")"
            );
        });

        viewModel.getErrorMessage().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Snackbar.make(recyclerDocuments, msg, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    // =========================
    // SEARCH BAR
    // =========================

    private void setupSearchBar() {
        searchBar.setOnClickListener(v -> {
            Intent intent = new Intent(this, SearchActivity.class);
            startActivity(intent);
        });
    }

    // =========================
    // CLICK LISTENERS
    // =========================

    private void setupClickListeners() {
        findViewById(R.id.fabAddFolder).setOnClickListener(v ->
                folderPicker.launch(null));

        findViewById(R.id.btnStatistics).setOnClickListener(v ->
                startActivity(new Intent(this, StatisticsActivity.class)));

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.btnSortFilter).setOnClickListener(v ->
                showSortFilterSheet());
    }

    // =========================
    // FILTER CHIPS
    // =========================

    private void buildFilterChips(List<DocumentEntity> docs) {
        chipGroupFilters.removeAllViews();

        Set<String> keywords = new LinkedHashSet<>();
        for (DocumentEntity doc : docs) {
            if (doc.keywords == null || doc.keywords.isEmpty()) continue;
            for (String kw : doc.keywords.split(",")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) keywords.add(trimmed);
            }
        }

        // Cap at 14 chips
        int count = 0;
        for (String keyword : keywords) {
            if (count >= 14) break;
            Chip chip = new Chip(this);
            chip.setText(keyword);
            chip.setCheckable(true);
            chip.setOnCheckedChangeListener((v, checked) -> {
                if (checked) viewModel.setKeywordFilter(keyword);
                else viewModel.clearKeywordFilter();
            });
            chipGroupFilters.addView(chip);
            count++;
        }
    }

    // =========================
    // SORT FILTER SHEET
    // =========================

    private void showSortFilterSheet() {

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
