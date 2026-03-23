package com.semantic.ekko.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.processing.extractor.PdfTextExtractor;
import com.semantic.ekko.ui.detail.DetailActivity;
import com.semantic.ekko.ui.qa.QAActivity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {

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

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        if (getActivity() != null) {
            PdfTextExtractor.init(getActivity().getApplicationContext());
        }

        bindViews(view);
        setupRecycler();
        setupViewModel();
        setupClickListeners(view);

        Executors.newSingleThreadExecutor().execute(() -> {
            viewModel.initMl();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> viewModel.loadDocuments());
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.loadDocuments();
        }
    }

    private void bindViews(View view) {
        recyclerDocuments = view.findViewById(R.id.recyclerDocuments);
        layoutIndexingProgress = view.findViewById(R.id.layoutIndexingProgress);
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState);
        txtIndexingStage = view.findViewById(R.id.txtIndexingStage);
        txtIndexingDoc = view.findViewById(R.id.txtIndexingDoc);
        txtDocCount = view.findViewById(R.id.txtDocCount);
        progressIndexing = view.findViewById(R.id.progressIndexing);
        chipGroupFilters = view.findViewById(R.id.chipGroupFilters);
    }

    private void setupRecycler() {
        adapter = new DocumentAdapter(doc -> {
            Intent intent = new Intent(getActivity(), DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_DOCUMENT_ID, doc.id);
            startActivity(intent);
        });
        recyclerDocuments.setLayoutManager(
            new LinearLayoutManager(getContext())
        );
        recyclerDocuments.setAdapter(adapter);
        recyclerDocuments.setNestedScrollingEnabled(false);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);

        viewModel
            .getDocuments()
            .observe(getViewLifecycleOwner(), docs -> {
                adapter.submitList(docs);
                updateEmptyState(docs);
                updateDocCount(docs.size());
                buildFilterChips(docs);
            });

        viewModel
            .getIsIndexing()
            .observe(getViewLifecycleOwner(), indexing -> {
                layoutIndexingProgress.setVisibility(
                    indexing ? View.VISIBLE : View.GONE
                );
            });

        viewModel
            .getIndexingStage()
            .observe(getViewLifecycleOwner(), stage -> {
                if (stage != null) txtIndexingStage.setText(stage);
            });

        viewModel
            .getIndexingProgress()
            .observe(getViewLifecycleOwner(), progress -> {
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
            .observe(getViewLifecycleOwner(), msg -> {
                if (msg != null && !msg.isEmpty()) {
                    Snackbar.make(
                        recyclerDocuments,
                        msg,
                        Snackbar.LENGTH_LONG
                    ).show();
                }
            });
    }

    private void setupClickListeners(View root) {
        root
            .findViewById(R.id.btnSortFilter)
            .setOnClickListener(v -> showSortFilterSheet());

        root
            .findViewById(R.id.btnHeroWiseBot)
            .setOnClickListener(v -> {
                Intent intent = new Intent(getActivity(), QAActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            });
    }

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
        Chip chip = new Chip(getContext());
        chip.setText(text);
        chip.setCheckable(false);
        chip.setCheckedIconVisible(false);
        applyChipColor(chip, active);
        return chip;
    }

    private void applyChipColor(Chip chip, boolean active) {
        int bg = active
            ? com.google.android.material.color.MaterialColors.getColor(
                  requireContext(),
                  com.google.android.material.R.attr.colorPrimaryContainer,
                  0
              )
            : com.google.android.material.color.MaterialColors.getColor(
                  requireContext(),
                  com.google.android.material.R.attr.colorSurfaceVariant,
                  0
              );
        int fg = active
            ? com.google.android.material.color.MaterialColors.getColor(
                  requireContext(),
                  com.google.android.material.R.attr.colorOnPrimaryContainer,
                  0
              )
            : com.google.android.material.color.MaterialColors.getColor(
                  requireContext(),
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

    private void showSortFilterSheet() {
        SortFilterBottomSheet sheet = new SortFilterBottomSheet(
            viewModel.getCurrentSortOrder(),
            viewModel.getCurrentFileTypeFilter(),
            (sortOrder, fileType) -> {
                viewModel.setSortOrder(sortOrder);
                viewModel.setFileTypeFilter(fileType);
            }
        );
        sheet.show(getChildFragmentManager(), "sort_filter");
    }

    private void updateEmptyState(List<DocumentEntity> docs) {
        boolean isEmpty = docs == null || docs.isEmpty();
        layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerDocuments.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void updateDocCount(int count) {
        txtDocCount.setText(
            count + (count == 1 ? " upload in vault" : " uploads in vault")
        );
    }
}
