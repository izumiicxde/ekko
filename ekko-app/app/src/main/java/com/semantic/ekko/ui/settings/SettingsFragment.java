package com.semantic.ekko.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ui.home.HomeViewModel;
import com.semantic.ekko.ui.qa.QAActivity;
import com.semantic.ekko.util.PrefsManager;
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
    private MaterialButtonToggleGroup toggleTheme;

    private List<FolderEntity> currentFolders = new ArrayList<>();

    private final ActivityResultLauncher<Uri> folderPicker =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null || getActivity() == null) return;
                getActivity()
                    .getContentResolver()
                    .takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                homeViewModel.addFolderAndIndex(uri);
            }
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
        homeViewModel = new ViewModelProvider(this).get(HomeViewModel.class);

        recyclerFolders = view.findViewById(R.id.recyclerFolders);
        txtNoFolders = view.findViewById(R.id.txtNoFolders);
        txtFolderStats = view.findViewById(R.id.txtFolderStats);
        toggleTheme = view.findViewById(R.id.toggleTheme);

        folderAdapter = new FolderAdapter(new FolderAdapter.Listener() {
            @Override
            public void onFolderIncludeChanged(FolderEntity folder, boolean included) {
                prefsManager.setFolderExcluded(folder.uri, !included);
                refreshFolderUi();
                homeViewModel.loadDocuments();
            }

            @Override
            public void onFolderRemove(FolderEntity folder) {
                confirmFolderRemoval(folder);
            }
        });

        recyclerFolders.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerFolders.setAdapter(folderAdapter);
        recyclerFolders.setNestedScrollingEnabled(false);

        folderRepository
            .getAllLive()
            .observe(getViewLifecycleOwner(), folders -> {
                currentFolders =
                    folders == null ? new ArrayList<>() : new ArrayList<>(folders);
                refreshFolderUi();
            });

        setupThemeToggle(view);

        view
            .findViewById(R.id.btnAddFolder)
            .setOnClickListener(v -> folderPicker.launch(null));

        view
            .findViewById(R.id.btnResetExcluded)
            .setOnClickListener(v -> {
                prefsManager.clearExcludedFolders();
                refreshFolderUi();
                homeViewModel.loadDocuments();
            });

        view
            .findViewById(R.id.btnSettingsWiseBot)
            .setOnClickListener(v -> {
                Intent intent = new Intent(getActivity(), QAActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            });
    }

    private void refreshFolderUi() {
        Set<String> excluded = prefsManager.getExcludedFolderUris();
        folderAdapter.submit(currentFolders, excluded);

        int total = currentFolders.size();
        int hidden = 0;
        for (FolderEntity folder : currentFolders) {
            if (excluded.contains(folder.uri)) hidden++;
        }

        txtNoFolders.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        txtFolderStats.setText(
            total + " source" + (total == 1 ? "" : "s") +
            "  •  " +
            (total - hidden) + " included"
        );
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
                prefsManager.setFolderExcluded(folder.uri, false);
                folderRepository.delete(folder);
                homeViewModel.loadDocuments();
            })
            .show();
    }

    private void setupThemeToggle(View root) {
        int btnSystem = R.id.btnThemeSystem;
        int btnLight = R.id.btnThemeLight;
        int btnDark = R.id.btnThemeDark;

        String theme = prefsManager.getTheme();
        if ("light".equals(theme)) {
            toggleTheme.check(btnLight);
        } else if ("dark".equals(theme)) {
            toggleTheme.check(btnDark);
        } else {
            toggleTheme.check(btnSystem);
        }

        toggleTheme.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;

            String selected;
            int mode;
            if (checkedId == btnLight) {
                selected = "light";
                mode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == btnDark) {
                selected = "dark";
                mode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                selected = "system";
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }

            if (!selected.equals(prefsManager.getTheme())) {
                prefsManager.setTheme(selected);
                AppCompatDelegate.setDefaultNightMode(mode);
                if (getActivity() != null) getActivity().recreate();
            }
        });
    }
}
