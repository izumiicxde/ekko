package com.semantic.ekko.ui.settings;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.FolderEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.FolderViewHolder> {

    public interface Listener {
        void onFolderIncludeChanged(FolderEntity folder, boolean included);
    }

    private final Listener listener;
    private final List<FolderEntity> folders = new ArrayList<>();
    private Set<String> excludedUris = new HashSet<>();

    public FolderAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<FolderEntity> newFolders, Set<String> excluded) {
        folders.clear();
        if (newFolders != null) folders.addAll(newFolders);
        excludedUris = excluded != null ? new HashSet<>(excluded) : new HashSet<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_folder_setting, parent, false);
        return new FolderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
        holder.bind(folders.get(position));
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    class FolderViewHolder extends RecyclerView.ViewHolder {

        private final TextView txtFolderName;
        private final TextView txtFolderUri;
        private final MaterialSwitch switchInclude;

        FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            txtFolderName = itemView.findViewById(R.id.txtFolderName);
            txtFolderUri = itemView.findViewById(R.id.txtFolderUri);
            switchInclude = itemView.findViewById(R.id.switchInclude);
        }

        void bind(FolderEntity folder) {
            txtFolderName.setText(folder.name != null && !folder.name.isEmpty() ? folder.name : "Unnamed folder");
            txtFolderUri.setText(folder.uri);

            boolean included = !excludedUris.contains(folder.uri);
            switchInclude.setOnCheckedChangeListener(null);
            switchInclude.setChecked(included);
            switchInclude.setOnCheckedChangeListener((button, isChecked) -> {
                if (listener != null) listener.onFolderIncludeChanged(folder, isChecked);
            });
        }
    }
}
