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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ui.home.HomeViewModel;
import com.semantic.ekko.ui.qa.QAActivity;
import com.semantic.ekko.util.PrefsManager;
import java.util.ArrayList;
import java.util.List;

public class SettingsFragment extends Fragment {

    private FolderRepository folderRepository;
    private PrefsManager prefsManager;
    private FolderAdapter folderAdapter;
    private HomeViewModel homeViewModel;
    private RecyclerView recyclerFolders;
    private TextView txtNoFolders;
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
                // Reuse the existing indexing flow from Home.
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

        folderAdapter = new FolderAdapter((folder, included) -> {
            prefsManager.setFolderExcluded(folder.uri, !included);
            folderAdapter.submit(
                currentFolders,
                prefsManager.getExcludedFolderUris()
            );
            homeViewModel.loadDocuments();
        });
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
                folderAdapter.submit(
                    folders,
                    prefsManager.getExcludedFolderUris()
                );
                txtNoFolders.setVisibility(
                    folders == null || folders.isEmpty()
                        ? View.VISIBLE
                        : View.GONE
                );
            });

        view
            .findViewById(R.id.btnAddFolder)
            .setOnClickListener(v -> folderPicker.launch(null));
        view
            .findViewById(R.id.btnSettingsWiseBot)
            .setOnClickListener(v -> {
                Intent intent = new Intent(getActivity(), QAActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            });
    }
}
