package com.semantic.ekko.ui.settings;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ui.home.HomeViewModel;
import com.semantic.ekko.util.NotificationPermissionHelper;
import com.semantic.ekko.util.PrefsManager;
import com.semantic.ekko.util.StorageAccessHelper;
import com.semantic.ekko.work.BackgroundIndexWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SettingsFragment extends Fragment {

    private FolderRepository folderRepository;
    private PrefsManager prefsManager;
    private FolderAdapter folderAdapter;
    private HomeViewModel homeViewModel;

    private RecyclerView recyclerFolders;
    private TextView txtNoFolders;
    private TextView txtFolderStats;
    private TextView txtReindexStage;
    private TextView txtThemeSummary;
    private View layoutFolderSectionHeader;
    private View layoutFolderListContainer;
    private ImageView imgFolderSectionToggle;
    private MaterialButtonToggleGroup toggleTheme;
    private MaterialButton btnAddFolder;
    private MaterialButton btnIncludeAllFolders;
    private MaterialButton btnResetExcluded;
    private MaterialButton btnReindexIncluded;
    private View layoutReindexProgress;
    private LinearProgressIndicator progressReindex;
    private boolean mlReady = false;
    private boolean isAppIndexing = false;
    private List<FolderEntity> currentFolders = new ArrayList<>();
    private boolean foldersExpanded = false;

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
                homeViewModel.addFolderAndIndex(uri);
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
                    homeViewModel.importDetectedPublicFolders();
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
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
        registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {}
        );
    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        folderRepository = new FolderRepository(requireContext());
        prefsManager = new PrefsManager(requireContext());
        homeViewModel = new ViewModelProvider(requireActivity()).get(
            HomeViewModel.class
        );
        observeReadiness();

        recyclerFolders = view.findViewById(R.id.recyclerFolders);
        txtNoFolders = view.findViewById(R.id.txtNoFolders);
        txtFolderStats = view.findViewById(R.id.txtFolderStats);
        txtReindexStage = view.findViewById(R.id.txtReindexStage);
        txtThemeSummary = view.findViewById(R.id.txtThemeSummary);
        layoutFolderSectionHeader = view.findViewById(
            R.id.layoutFolderSectionHeader
        );
        layoutFolderListContainer = view.findViewById(
            R.id.layoutFolderListContainer
        );
        imgFolderSectionToggle = view.findViewById(R.id.imgFolderSectionToggle);
        toggleTheme = view.findViewById(R.id.toggleTheme);
        btnAddFolder = view.findViewById(R.id.btnAddFolder);
        btnIncludeAllFolders = view.findViewById(R.id.btnIncludeAllFolders);
        btnResetExcluded = view.findViewById(R.id.btnResetExcluded);
        btnReindexIncluded = view.findViewById(R.id.btnReindexIncluded);
        layoutReindexProgress = view.findViewById(R.id.layoutReindexProgress);
        progressReindex = view.findViewById(R.id.progressReindex);

        folderAdapter = new FolderAdapter(
            new FolderAdapter.Listener() {
                @Override
                public void onFolderIncludeChanged(
                    FolderEntity folder,
                    boolean included
                ) {
                    homeViewModel.setFolderIncluded(folder, included);
                    refreshFolderUi();
                }

                @Override
                public void onFolderRemove(FolderEntity folder) {
                    confirmFolderRemoval(folder);
                }
            }
        );

        recyclerFolders.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerFolders.setAdapter(folderAdapter);
        recyclerFolders.setNestedScrollingEnabled(false);

        folderRepository
            .getAllLive()
            .observe(getViewLifecycleOwner(), folders -> {
                currentFolders =
                    folders == null
                        ? new ArrayList<>()
                        : new ArrayList<>(folders);
                refreshFolderUi();
            });

        setupThemeToggle(view);
        setupFolderSection();

        view
            .findViewById(R.id.btnAddFolder)
            .setOnClickListener(v -> {
                if (!mlReady) {
                    Snackbar.make(
                        view,
                        "Indexing tools are still loading. Please wait a moment.",
                        Snackbar.LENGTH_SHORT
                    ).show();
                    return;
                }
                requestIndexingNotificationsIfNeeded();
                launchFolderImport();
            });

        view
            .findViewById(R.id.btnIncludeAllFolders)
            .setOnClickListener(v -> {
                if (!mlReady) {
                    Snackbar.make(
                        view,
                        "Indexing tools are still loading. Please wait a moment.",
                        Snackbar.LENGTH_SHORT
                    ).show();
                    return;
                }
                requestIndexingNotificationsIfNeeded();
                launchIncludeAllFolders();
            });

        view
            .findViewById(R.id.btnResetExcluded)
            .setOnClickListener(v -> {
                prefsManager.clearExcludedFolders();
                refreshFolderUi();
                homeViewModel.loadDocuments();
            });

        view
            .findViewById(R.id.btnReindexIncluded)
            .setOnClickListener(v -> {
                if (
                    StorageAccessHelper.supportsAllFilesAccess() &&
                    StorageAccessHelper.hasAllFilesAccess()
                ) {
                    requestIndexingNotificationsIfNeeded();
                    homeViewModel.importDetectedPublicFolders();
                    Snackbar.make(
                        view,
                        "Refreshing included folders and checking for anything new.",
                        Snackbar.LENGTH_SHORT
                    ).show();
                } else {
                    List<FolderEntity> included = getIncludedFolders();
                    if (included.isEmpty()) {
                        Snackbar.make(
                            view,
                            "No included folders selected for re-indexing.",
                            Snackbar.LENGTH_SHORT
                        ).show();
                        return;
                    }
                    requestIndexingNotificationsIfNeeded();
                    homeViewModel.reindexFolders(included);
                    Snackbar.make(
                        view,
                        "Re-index started for included folders.",
                        Snackbar.LENGTH_SHORT
                    ).show();
                }
            });

        BackgroundIndexWorker
            .getWorkInfoLiveData(requireContext())
            .observe(getViewLifecycleOwner(), this::handleBackgroundIndexState);
    }

    private void observeReadiness() {
        EkkoApp.getInstance()
            .getMlReadyState()
            .observe(getViewLifecycleOwner(), ready -> {
                mlReady = ready != null && ready;
                updateActionButtons(
                    homeViewModel != null &&
                        Boolean.TRUE.equals(
                            homeViewModel.getIsIndexing().getValue()
                        )
                );
            });
    }

    private void updateActionButtons(boolean indexing) {
        if (btnAddFolder == null) {
            return;
        }
        boolean includeAllSupported = StorageAccessHelper.supportsAllFilesAccess();
        btnAddFolder.setEnabled(mlReady && !indexing);
        btnIncludeAllFolders.setVisibility(
            includeAllSupported ? View.VISIBLE : View.GONE
        );
        btnIncludeAllFolders.setEnabled(
            includeAllSupported && mlReady && !indexing
        );
        btnReindexIncluded.setEnabled(mlReady && !indexing);
        btnResetExcluded.setEnabled(!indexing && hasExcludedFolders());
        btnAddFolder.setAlpha(mlReady ? 1f : 0.55f);
        btnIncludeAllFolders.setAlpha(
            includeAllSupported && mlReady ? 1f : 0.55f
        );
        btnReindexIncluded.setAlpha(mlReady ? 1f : 0.55f);
        btnResetExcluded.setAlpha(hasExcludedFolders() ? 1f : 0.55f);
    }

    private void launchIncludeAllFolders() {
        if (!StorageAccessHelper.supportsAllFilesAccess()) {
            return;
        }
        if (StorageAccessHelper.hasAllFilesAccess()) {
            homeViewModel.importDetectedPublicFolders();
            if (getView() != null) {
                Snackbar.make(
                    getView(),
                    "Including detectable public folders.",
                    Snackbar.LENGTH_SHORT
                ).show();
            }
            return;
        }
        allFilesAccessLauncher.launch(
            StorageAccessHelper.createManageAllFilesAccessIntent(
                requireContext()
            )
        );
    }

    private void requestIndexingNotificationsIfNeeded() {
        if (
            isAdded() &&
            NotificationPermissionHelper.shouldRequestNotificationPermission(
                requireContext()
            )
        ) {
            notificationPermissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            );
        }
    }

    private void launchFolderImport() {
        if (StorageAccessHelper.supportsAllFilesAccess()) {
            if (StorageAccessHelper.hasAllFilesAccess()) {
                homeViewModel.importDetectedPublicFolders();
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

    private void refreshFolderUi() {
        Set<String> excluded = prefsManager.getExcludedFolderUris();
        folderAdapter.submit(currentFolders, excluded);

        int total = currentFolders.size();
        int hidden = 0;
        for (FolderEntity folder : currentFolders) {
            if (excluded.contains(folder.uri)) hidden++;
        }

        txtNoFolders.setVisibility(
            foldersExpanded && total == 0 ? View.VISIBLE : View.GONE
        );
        txtFolderStats.setText(
            total +
                " source" +
                (total == 1 ? "" : "s") +
                "  •  " +
                (total - hidden) +
                " included  •  " +
                hidden +
                " hidden"
        );
        applyFolderExpansionState();
        updateActionButtons(isAppIndexing);
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
        layoutReindexProgress.setVisibility(
            running ? View.VISIBLE : View.GONE
        );
        if (running) {
            if (total > 0) {
                progressReindex.setIndeterminate(false);
                progressReindex.setMax(total);
                progressReindex.setProgress(current);
                txtReindexStage.setText(
                    current + " / " + total + "  •  " + (docName == null ? "" : docName)
                );
            } else {
                progressReindex.setIndeterminate(true);
                txtReindexStage.setText(
                    stage == null || stage.isEmpty() ? "Preparing indexing..." : stage
                );
            }
            homeViewModel.loadDocuments();
        }
        updateActionButtons(running);

        if (finished && !running) {
            homeViewModel.loadDocuments();
            if (getView() != null) {
                Snackbar.make(
                    getView(),
                    "Indexing finished.",
                    Snackbar.LENGTH_SHORT
                ).show();
            }
        }
    }

    private void setupFolderSection() {
        layoutFolderSectionHeader.setOnClickListener(v -> {
            foldersExpanded = !foldersExpanded;
            applyFolderExpansionState();
        });
        applyFolderExpansionState();
    }

    private void applyFolderExpansionState() {
        if (
            layoutFolderListContainer == null || imgFolderSectionToggle == null
        ) {
            return;
        }
        layoutFolderListContainer.setVisibility(
            foldersExpanded ? View.VISIBLE : View.GONE
        );
        imgFolderSectionToggle
            .animate()
            .rotation(foldersExpanded ? 270f : 90f)
            .setDuration(160)
            .start();
        if (!foldersExpanded) {
            txtNoFolders.setVisibility(View.GONE);
        } else {
            txtNoFolders.setVisibility(
                currentFolders.isEmpty() ? View.VISIBLE : View.GONE
            );
        }
    }

    private List<FolderEntity> getIncludedFolders() {
        Set<String> excluded = prefsManager.getExcludedFolderUris();
        List<FolderEntity> included = new ArrayList<>();
        for (FolderEntity folder : currentFolders) {
            if (!excluded.contains(folder.uri)) {
                included.add(folder);
            }
        }
        return included;
    }

    private boolean hasExcludedFolders() {
        return !prefsManager.getExcludedFolderUris().isEmpty();
    }

    private void confirmFolderRemoval(FolderEntity folder) {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Remove source folder?")
            .setMessage(
                "This removes the folder and its indexed files from Ekko only. Files on your device are not deleted."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove", (dialog, which) -> {
                // Hide related files immediately while delete runs in background.
                prefsManager.setFolderExcluded(folder.uri, true);
                for (int i = currentFolders.size() - 1; i >= 0; i--) {
                    if (currentFolders.get(i).id == folder.id) {
                        currentFolders.remove(i);
                    }
                }
                refreshFolderUi();
                homeViewModel.loadDocuments();
                folderRepository.delete(folder, () -> {
                    prefsManager.setFolderExcluded(folder.uri, false);
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) {
                                return;
                            }
                            refreshFolderUi();
                            homeViewModel.loadDocuments();
                        });
                    }
                });
            })
            .show();
    }

    private void setupThemeToggle(View root) {
        int btnLight = R.id.btnThemeLight;
        int btnDark = R.id.btnThemeDark;

        String theme = prefsManager.getTheme();
        if ("light".equals(theme)) {
            toggleTheme.check(btnLight);
            txtThemeSummary.setText(R.string.theme_summary_light);
        } else if ("dark".equals(theme)) {
            toggleTheme.check(btnDark);
            txtThemeSummary.setText(R.string.theme_summary_dark);
        } else {
            toggleTheme.check(btnLight);
            txtThemeSummary.setText(R.string.theme_summary_light);
        }
        updateThemeLabels();

        toggleTheme.addOnButtonCheckedListener(
            (group, checkedId, isChecked) -> {
                if (!isChecked) return;

                String selected;
                int mode;
                if (checkedId == btnLight) {
                    selected = "light";
                    mode = AppCompatDelegate.MODE_NIGHT_NO;
                    txtThemeSummary.setText(R.string.theme_summary_light);
                } else if (checkedId == btnDark) {
                    selected = "dark";
                    mode = AppCompatDelegate.MODE_NIGHT_YES;
                    txtThemeSummary.setText(R.string.theme_summary_dark);
                } else {
                    selected = "light";
                    mode = AppCompatDelegate.MODE_NIGHT_NO;
                    txtThemeSummary.setText(R.string.theme_summary_light);
                }

                updateThemeLabels();
                if (!selected.equals(prefsManager.getTheme())) {
                    prefsManager.setTheme(selected);
                    AppCompatDelegate.setDefaultNightMode(mode);
                    if (getActivity() != null) getActivity().recreate();
                }
            }
        );
    }

    private void updateThemeLabels() {
        MaterialButton btnLight = toggleTheme.findViewById(R.id.btnThemeLight);
        MaterialButton btnDark = toggleTheme.findViewById(R.id.btnThemeDark);

        boolean lightSelected =
            toggleTheme.getCheckedButtonId() == R.id.btnThemeLight;
        boolean darkSelected =
            toggleTheme.getCheckedButtonId() == R.id.btnThemeDark;

        int activeBg = MaterialColors.getColor(
            toggleTheme,
            com.google.android.material.R.attr.colorPrimaryContainer
        );
        int activeFg = MaterialColors.getColor(
            toggleTheme,
            com.google.android.material.R.attr.colorOnPrimaryContainer
        );
        int inactiveBg = android.graphics.Color.TRANSPARENT;
        int inactiveFg = MaterialColors.getColor(
            toggleTheme,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        );

        btnLight.setText("Light");
        btnDark.setText("Dark");

        btnLight.setBackgroundTintList(
            ColorStateList.valueOf(lightSelected ? activeBg : inactiveBg)
        );
        btnDark.setBackgroundTintList(
            ColorStateList.valueOf(darkSelected ? activeBg : inactiveBg)
        );
        btnLight.setTextColor(lightSelected ? activeFg : inactiveFg);
        btnDark.setTextColor(darkSelected ? activeFg : inactiveFg);
        btnLight.setStrokeWidth(0);
        btnDark.setStrokeWidth(0);
    }
}
