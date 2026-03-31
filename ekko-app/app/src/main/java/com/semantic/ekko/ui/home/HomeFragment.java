package com.semantic.ekko.ui.home;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.processing.extractor.PdfTextExtractor;
import com.semantic.ekko.ui.detail.DetailActivity;
import com.semantic.ekko.ui.main.MainActivity;
import com.semantic.ekko.util.PrefsManager;
import java.util.ArrayList;
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
    private LinearLayout layoutFolderNavigation;
    private TextView txtIndexingStage;
    private TextView txtIndexingDoc;
    private TextView txtDocCount;
    private TextView txtDocMeta;
    private TextView txtFolderPath;
    private LinearProgressIndicator progressIndexing;
    private ChipGroup chipGroupFilters;
    private View btnEmptyAddFolder;
    private View btnEmptyOpenSettings;
    private TextView txtEmptySubtitle;

    private FolderRepository folderRepository;
    private PrefsManager prefsManager;
    private boolean hasIncludedFolders = false;

    private final ActivityResultLauncher<Uri> folderPicker =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null || getActivity() == null) return;
                try {
                    getActivity()
                        .getContentResolver()
                        .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                } catch (SecurityException | IllegalArgumentException e) {
                    View root = getView();
                    if (root != null) {
                        Snackbar.make(
                            root,
                            "Could not access that folder. Please try selecting it again.",
                            Snackbar.LENGTH_LONG
                        ).show();
                    }
                    return;
                }
                viewModel.addFolderAndIndex(uri);
            }
        );

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
        folderRepository = new FolderRepository(requireContext());
        prefsManager = new PrefsManager(requireContext());
        folderRepository
            .getAllLive()
            .observe(getViewLifecycleOwner(), folders ->
                refreshFolderAvailabilityState()
            );
        refreshFolderAvailabilityState();

        Executors.newSingleThreadExecutor().execute(() -> {
            viewModel.initMl();
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded()) {
                        viewModel.loadDocuments();
                    }
                });
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.loadDocuments();
        }
        refreshFolderAvailabilityState();
    }

    private void bindViews(View view) {
        recyclerDocuments = view.findViewById(R.id.recyclerDocuments);
        layoutIndexingProgress = view.findViewById(R.id.layoutIndexingProgress);
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState);
        layoutFolderNavigation = view.findViewById(R.id.layoutFolderNavigation);
        txtIndexingStage = view.findViewById(R.id.txtIndexingStage);
        txtIndexingDoc = view.findViewById(R.id.txtIndexingDoc);
        txtDocCount = view.findViewById(R.id.txtDocCount);
        txtDocMeta = view.findViewById(R.id.txtDocMeta);
        txtFolderPath = view.findViewById(R.id.txtFolderPath);
        progressIndexing = view.findViewById(R.id.progressIndexing);
        chipGroupFilters = view.findViewById(R.id.chipGroupFilters);
        btnEmptyAddFolder = view.findViewById(R.id.btnEmptyAddFolder);
        btnEmptyOpenSettings = view.findViewById(R.id.btnEmptyOpenSettings);
        txtEmptySubtitle = view.findViewById(R.id.txtEmptySubtitle);
    }

    private void setupRecycler() {
        adapter = new DocumentAdapter(
            doc -> {
                Intent intent = new Intent(getActivity(), DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_DOCUMENT_ID, doc.id);
                startActivity(intent);
            },
            this::updateFolderNavigation
        );
        recyclerDocuments.setLayoutManager(
            new LinearLayoutManager(getContext())
        );
        recyclerDocuments.setAdapter(adapter);
        recyclerDocuments.setNestedScrollingEnabled(false);
        adapter.setDisplayMode(
            viewModel != null ? viewModel.getCurrentViewMode() : "grouped"
        );
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(requireActivity()).get(
            HomeViewModel.class
        );
        adapter.setDisplayMode(viewModel.getCurrentViewMode());

        viewModel
            .getDocuments()
            .observe(getViewLifecycleOwner(), docs -> {
                List<DocumentEntity> safeDocs =
                    docs == null ? new ArrayList<>() : docs;
                adapter.submitDocuments(safeDocs);
                updateEmptyState(safeDocs);
                updateDocCount(safeDocs.size());
                buildFilterChips(safeDocs);
            });

        viewModel
            .getFolderNames()
            .observe(getViewLifecycleOwner(), adapter::submitFolderNames);

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
                if (stage != null && progressIndexing.getProgress() == 0) {
                    txtIndexingStage.setText(stage);
                }
            });

        viewModel
            .getIndexingProgress()
            .observe(getViewLifecycleOwner(), progress -> {
                if (progress == null) return;
                progressIndexing.setMax(progress.total);
                progressIndexing.setProgress(progress.current);
                txtIndexingStage.setText(progress.docName);
                txtIndexingDoc.setText(
                    progress.current + " / " + progress.total
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
            .findViewById(R.id.btnFolderBack)
            .setOnClickListener(v -> adapter.navigateUp());

        root
            .findViewById(R.id.btnEmptyAddFolder)
            .setOnClickListener(v -> folderPicker.launch(null));

        root
            .findViewById(R.id.btnEmptyOpenSettings)
            .setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).navigateToSettings();
                }
            });
    }

    public boolean handleSystemBackPressed() {
        if (adapter != null && adapter.canNavigateUpInFolders()) {
            adapter.navigateUp();
            return true;
        }
        return false;
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
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(34f);
        chip.setChipCornerRadius(16f);
        chip.setChipStrokeWidth(0f);
        chip.setCloseIconVisible(false);
        chip.setTextSize(12f);
        chip.setTypeface(
            androidx.core.content.res.ResourcesCompat.getFont(
                requireContext(),
                R.font.bricolage_grotesque
            )
        );
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
            viewModel.getCurrentViewMode(),
            (sortOrder, fileType, viewMode) -> {
                viewModel.setSortOrder(sortOrder);
                viewModel.setFileTypeFilter(fileType);
                viewModel.setViewMode(viewMode);
                adapter.setDisplayMode(viewMode);
            }
        );
        sheet.show(getChildFragmentManager(), "sort_filter");
    }

    private void updateFolderNavigation(DocumentAdapter.NavigationState state) {
        if (state == null) return;
        layoutFolderNavigation.setVisibility(
            state.visible ? View.VISIBLE : View.GONE
        );
        txtFolderPath.setText(state.pathLabel);
        View backButton = layoutFolderNavigation.findViewById(
            R.id.btnFolderBack
        );
        backButton.setEnabled(state.canNavigateUp);
        backButton.setAlpha(state.canNavigateUp ? 1f : 0.45f);
    }

    private void updateEmptyState(List<DocumentEntity> docs) {
        boolean isEmpty = docs == null || docs.isEmpty();
        layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerDocuments.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

        if (!isEmpty) return;

        if (hasIncludedFolders) {
            txtEmptySubtitle.setText(
                "No indexed files found in included folders yet. Add documents and re-index from Settings."
            );
            btnEmptyAddFolder.setVisibility(View.GONE);
            btnEmptyOpenSettings.setVisibility(View.VISIBLE);
        } else {
            txtEmptySubtitle.setText(
                "No source folders selected. Add a folder to start indexing and unlock Search + Ekko Bot."
            );
            btnEmptyAddFolder.setVisibility(View.VISIBLE);
            btnEmptyOpenSettings.setVisibility(View.VISIBLE);
        }
    }

    private void updateDocCount(int count) {
        if (count <= 0) {
            txtDocCount.setText("Your vault is empty");
            txtDocMeta.setText(
                hasIncludedFolders
                    ? "Included folders are ready. Add files or re-index to fill it."
                    : "Add a source folder to start building your library."
            );
            return;
        }

        txtDocCount.setText("Vault");
        txtDocMeta.setText(
            count + (count == 1 ? " upload indexed" : " uploads indexed")
        );
    }

    private void refreshFolderAvailabilityState() {
        if (
            folderRepository == null ||
            prefsManager == null ||
            getActivity() == null
        ) return;
        folderRepository.getAll(folders -> {
            Set<String> excluded = prefsManager.getExcludedFolderUris();
            int includedCount = 0;
            List<FolderEntity> safeFolders =
                folders == null ? new ArrayList<>() : folders;
            for (FolderEntity folder : safeFolders) {
                if (!excluded.contains(folder.uri)) includedCount++;
            }
            boolean hasAnyIncluded = includedCount > 0;
            if (!isAdded() || getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                hasIncludedFolders = hasAnyIncluded;
                List<DocumentEntity> currentDocs = viewModel
                    .getDocuments()
                    .getValue();
                updateEmptyState(
                    currentDocs == null ? new ArrayList<>() : currentDocs
                );
            });
        });
    }
}
