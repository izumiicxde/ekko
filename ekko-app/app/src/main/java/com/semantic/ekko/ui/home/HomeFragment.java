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
import androidx.work.WorkInfo;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ml.EntityExtractorHelper;
import com.semantic.ekko.processing.extractor.PdfTextExtractor;
import com.semantic.ekko.ui.detail.DetailActivity;
import com.semantic.ekko.ui.graph.GraphActivity;
import com.semantic.ekko.ui.main.MainActivity;
import com.semantic.ekko.util.PrefsManager;
import com.semantic.ekko.util.StorageAccessHelper;
import com.semantic.ekko.work.BackgroundIndexWorker;
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
    private TextView txtIndexingRecent;
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
    private boolean isAppIndexing = false;
    private String latestIndexingStage = "";
    private String latestIndexingDoc = "";
    private int latestIndexCurrent = 0;
    private int latestIndexTotal = 0;
    private boolean hasDeterminateIndexingProgress = false;

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
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );
                } catch (SecurityException | IllegalArgumentException e) {
                    try {
                        getActivity()
                            .getContentResolver()
                            .takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                    } catch (SecurityException | IllegalArgumentException ignored) {
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
                }
                viewModel.addFolderAndIndex(uri);
            }
        );

    private final ActivityResultLauncher<Intent> allFilesAccessLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (!isAdded() || getContext() == null) {
                    return;
                }
                if (StorageAccessHelper.hasAllFilesAccess()) {
                    viewModel.importDetectedPublicFolders();
                    return;
                }
                View root = getView();
                if (root != null) {
                    Snackbar.make(
                        root,
                        "Allow full storage access to index shared folders at once.",
                        Snackbar.LENGTH_LONG
                    ).show();
                }
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
        txtIndexingRecent = view.findViewById(R.id.txtIndexingRecent);
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
                updateRecentIndexedFiles(safeDocs);
            });

        viewModel
            .getFolderNames()
            .observe(getViewLifecycleOwner(), adapter::submitFolderNames);

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

        BackgroundIndexWorker
            .getWorkInfoLiveData(requireContext())
            .observe(getViewLifecycleOwner(), this::handleBackgroundIndexState);
    }

    private void setupClickListeners(View root) {
        root
            .findViewById(R.id.btnSortFilter)
            .setOnClickListener(v -> showSortFilterSheet());

        root
            .findViewById(R.id.btnGraph)
            .setOnClickListener(v ->
                startActivity(new Intent(requireContext(), GraphActivity.class))
            );

        root
            .findViewById(R.id.btnFolderBack)
            .setOnClickListener(v -> adapter.navigateUp());

        root
            .findViewById(R.id.btnEmptyAddFolder)
            .setOnClickListener(v -> {
                launchFolderImport();
            });

        root
            .findViewById(R.id.btnEmptyOpenSettings)
            .setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).navigateToSettings();
                }
            });
    }

    private void launchFolderImport() {
        if (StorageAccessHelper.supportsAllFilesAccess()) {
            if (StorageAccessHelper.hasAllFilesAccess()) {
                viewModel.importDetectedPublicFolders();
            } else if (getContext() != null) {
                allFilesAccessLauncher.launch(
                    StorageAccessHelper.createManageAllFilesAccessIntent(
                        requireContext()
                    )
                );
            }
            return;
        }
        folderPicker.launch(null);
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
            Set<String> entityTerms = extractEntityTerms(doc);
            if (doc.keywords == null || doc.keywords.isEmpty()) continue;
            for (String kw : doc.keywords.split(",")) {
                String trimmed = kw.trim();
                if (
                    !trimmed.isEmpty() &&
                    !entityTerms.contains(trimmed.toLowerCase())
                ) {
                    keywords.add(trimmed);
                }
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

    private Set<String> extractEntityTerms(DocumentEntity doc) {
        Set<String> entityTerms = new LinkedHashSet<>();
        if (doc == null) {
            return entityTerms;
        }
        for (String entity : EntityExtractorHelper.entitiesFromString(doc.entities)) {
            String normalized = entity.replaceFirst("^[A-Za-z]+:\\s*", "").trim();
            if (!normalized.isEmpty()) {
                entityTerms.add(normalized.toLowerCase());
            }
        }
        return entityTerms;
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

    private void updateIndexingUi() {
        boolean showBanner = isAppIndexing;
        layoutIndexingProgress.setVisibility(showBanner ? View.VISIBLE : View.GONE);
        if (!showBanner) {
            latestIndexingStage = "";
            latestIndexingDoc = "";
            latestIndexCurrent = 0;
            latestIndexTotal = 0;
            hasDeterminateIndexingProgress = false;
            progressIndexing.setIndeterminate(false);
            progressIndexing.setProgress(0);
            txtIndexingStage.setText("");
            txtIndexingDoc.setText("");
            txtIndexingRecent.setText("");
            txtIndexingRecent.setVisibility(View.GONE);
            return;
        }
        renderIndexingState();
    }

    private void updateRecentIndexedFiles(List<DocumentEntity> docs) {
        if (txtIndexingRecent == null || !isAppIndexing) {
            return;
        }
        List<DocumentEntity> safeDocs = docs == null ? new ArrayList<>() : docs;
        if (safeDocs.isEmpty()) {
            txtIndexingRecent.setText("");
            txtIndexingRecent.setVisibility(View.GONE);
            return;
        }

        StringBuilder builder = new StringBuilder();
        int limit = Math.min(4, safeDocs.size());
        for (int i = 0; i < limit; i++) {
            DocumentEntity doc = safeDocs.get(i);
            if (doc == null || doc.name == null || doc.name.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("• ").append(doc.name);
        }

        if (builder.length() == 0) {
            txtIndexingRecent.setText("");
            txtIndexingRecent.setVisibility(View.GONE);
            return;
        }

        txtIndexingRecent.setText(builder.toString());
        txtIndexingRecent.setVisibility(View.VISIBLE);
    }

    private void renderIndexingState() {
        if (!isAdded()) {
            return;
        }
        if (!isAppIndexing) {
            return;
        }
        if (hasDeterminateIndexingProgress && latestIndexTotal > 0) {
            progressIndexing.setIndeterminate(false);
            progressIndexing.setMax(latestIndexTotal);
            progressIndexing.setProgress(latestIndexCurrent);
            txtIndexingStage.setText(
                latestIndexingDoc.isEmpty() ? latestIndexingStage : latestIndexingDoc
            );
            txtIndexingDoc.setText(
                latestIndexCurrent + " / " + latestIndexTotal
            );
            return;
        }
        progressIndexing.setIndeterminate(true);
        txtIndexingStage.setText(
            latestIndexingStage.isEmpty() ? "Preparing index..." : latestIndexingStage
        );
        txtIndexingDoc.setText("Getting documents ready");
    }

    private void handleBackgroundIndexState(List<WorkInfo> workInfos) {
        boolean running = false;
        boolean finished = false;
        String stage = "";
        String docName = "";
        int current = 0;
        int total = 0;

        if (workInfos != null) {
            for (WorkInfo workInfo : workInfos) {
                if (workInfo == null) {
                    continue;
                }
                WorkInfo.State state = workInfo.getState();
                if (
                    state == WorkInfo.State.ENQUEUED ||
                    state == WorkInfo.State.RUNNING ||
                    state == WorkInfo.State.BLOCKED
                ) {
                    running = true;
                    stage = workInfo.getProgress().getString(
                        BackgroundIndexWorker.KEY_STAGE
                    );
                    docName = workInfo.getProgress().getString(
                        BackgroundIndexWorker.KEY_DOC_NAME
                    );
                    current = workInfo.getProgress().getInt(
                        BackgroundIndexWorker.KEY_CURRENT,
                        0
                    );
                    total = workInfo.getProgress().getInt(
                        BackgroundIndexWorker.KEY_TOTAL,
                        0
                    );
                }
                if (state.isFinished()) {
                    finished = true;
                }
            }
        }

        isAppIndexing = running;
        latestIndexingStage = stage == null ? "" : stage;
        latestIndexingDoc = docName == null ? "" : docName;
        latestIndexCurrent = current;
        latestIndexTotal = total;
        hasDeterminateIndexingProgress = total > 0;
        updateIndexingUi();
        if (running) {
            viewModel.loadDocuments();
        }

        if (finished && !running) {
            isAppIndexing = false;
            viewModel.loadDocuments();
            refreshFolderAvailabilityState();
            if (getView() != null) {
                Snackbar.make(
                    getView(),
                    "Indexing finished.",
                    Snackbar.LENGTH_SHORT
                ).show();
            }
        }
    }
}
