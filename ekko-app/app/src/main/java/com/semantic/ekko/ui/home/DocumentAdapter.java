package com.semantic.ekko.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.util.FileUtils;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class DocumentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_DOCUMENT = 1;
    private static final int VIEW_TYPE_FOLDER = 2;

    public interface OnDocumentClickListener {
        void onClick(DocumentEntity document);
    }

    public interface OnFolderNavigationListener {
        void onNavigationChanged(NavigationState state);
    }

    public static class NavigationState {

        public final boolean visible;
        public final boolean canNavigateUp;
        public final String pathLabel;

        NavigationState(boolean visible, boolean canNavigateUp, String pathLabel) {
            this.visible = visible;
            this.canNavigateUp = canNavigateUp;
            this.pathLabel = pathLabel;
        }
    }

    private final OnDocumentClickListener listener;
    private final OnFolderNavigationListener navigationListener;
    private final List<RowItem> items = new ArrayList<>();
    private List<DocumentEntity> documents = new ArrayList<>();
    private Map<Long, String> folderNames = new TreeMap<>();
    private String displayMode = "grouped";
    private Long currentRootFolderId = null;
    private List<String> currentPathSegments = new ArrayList<>();

    public DocumentAdapter(
        OnDocumentClickListener listener,
        OnFolderNavigationListener navigationListener
    ) {
        this.listener = listener;
        this.navigationListener = navigationListener;
    }

    public void submitDocuments(List<DocumentEntity> docs) {
        documents = docs != null ? new ArrayList<>(docs) : new ArrayList<>();
        rebuildItems();
    }

    public void submitFolderNames(Map<Long, String> names) {
        folderNames = names != null ? new TreeMap<>(names) : new TreeMap<>();
        rebuildItems();
    }

    public void setDisplayMode(String mode) {
        String nextMode = mode != null ? mode : "grouped";
        if (!nextMode.equals(displayMode)) {
            displayMode = nextMode;
            resetFolderNavigationState();
            rebuildItems();
        }
    }

    public void navigateUp() {
        if (!"folders".equals(displayMode)) return;

        if (currentRootFolderId == null) {
            rebuildItems();
            return;
        }

        if (currentPathSegments.isEmpty()) {
            currentRootFolderId = null;
        } else {
            currentPathSegments.remove(currentPathSegments.size() - 1);
        }
        rebuildItems();
    }

    public boolean canNavigateUpInFolders() {
        return "folders".equals(displayMode) && currentRootFolderId != null;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).viewType;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(
        @NonNull ViewGroup parent,
        int viewType
    ) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_HEADER) {
            View view = inflater.inflate(
                R.layout.item_folder_header,
                parent,
                false
            );
            return new HeaderViewHolder(view);
        }

        if (viewType == VIEW_TYPE_FOLDER) {
            View view = inflater.inflate(
                R.layout.item_browser_folder,
                parent,
                false
            );
            return new FolderViewHolder(view);
        }

        View view = inflater.inflate(R.layout.item_document, parent, false);
        return new DocumentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
        @NonNull RecyclerView.ViewHolder holder,
        int position
    ) {
        RowItem item = items.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(item.headerTitle);
            return;
        }
        if (holder instanceof FolderViewHolder) {
            ((FolderViewHolder) holder).bind(item.folderNode, this::openFolder);
            return;
        }
        ((DocumentViewHolder) holder).bind(
            item.document,
            getFolderName(item.document),
            "list".equals(displayMode),
            listener
        );
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private void openFolder(ExplorerNode node) {
        if (node == null) return;
        currentRootFolderId = node.rootFolderId;
        currentPathSegments = new ArrayList<>(node.pathSegments);
        rebuildItems();
    }

    private void rebuildItems() {
        items.clear();

        switch (displayMode) {
            case "list":
                buildFlatListItems();
                break;
            case "folders":
                buildFolderExplorerItems();
                break;
            case "grouped":
            default:
                buildGroupedItems();
                break;
        }

        notifyDataSetChanged();
        dispatchNavigationState();
    }

    private void buildFlatListItems() {
        for (DocumentEntity doc : documents) {
            items.add(RowItem.document(doc));
        }
    }

    private void buildGroupedItems() {
        TreeMap<String, List<DocumentEntity>> groupedDocs = new TreeMap<>(
            String.CASE_INSENSITIVE_ORDER
        );
        for (DocumentEntity doc : documents) {
            String folderName = getFolderName(doc);
            List<DocumentEntity> bucket = groupedDocs.get(folderName);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groupedDocs.put(folderName, bucket);
            }
            bucket.add(doc);
        }

        for (Map.Entry<String, List<DocumentEntity>> entry : groupedDocs.entrySet()) {
            items.add(RowItem.header(entry.getKey()));
            for (DocumentEntity doc : entry.getValue()) {
                items.add(RowItem.document(doc));
            }
        }
    }

    private void buildFolderExplorerItems() {
        ExplorerNode treeRoot = buildExplorerTree();
        ExplorerNode currentNode = resolveCurrentNode(treeRoot);
        if (currentNode == null) {
            resetFolderNavigationState();
            currentNode = treeRoot;
        }

        for (ExplorerNode child : currentNode.getSortedChildren()) {
            items.add(RowItem.folder(child));
        }
        for (DocumentEntity doc : currentNode.documents) {
            items.add(RowItem.document(doc));
        }
    }

    private ExplorerNode buildExplorerTree() {
        ExplorerNode root = new ExplorerNode("Folders", null, new ArrayList<>());
        Map<Long, ExplorerNode> rootFolders = new HashMap<>();

        for (DocumentEntity doc : documents) {
            long folderId = doc.folderId;
            ExplorerNode folderNode = rootFolders.get(folderId);
            if (folderNode == null) {
                folderNode = new ExplorerNode(
                    getFolderName(doc),
                    folderId,
                    new ArrayList<>()
                );
                rootFolders.put(folderId, folderNode);
                root.children.put(String.valueOf(folderId), folderNode);
            }

            List<String> segments = getFolderSegments(doc);
            ExplorerNode current = folderNode;
            for (int i = 0; i < segments.size(); i++) {
                String segment = segments.get(i);
                if (i == segments.size() - 1) {
                    current.documents.add(doc);
                } else {
                    current = current.getOrCreateChild(segment);
                }
            }
        }

        return root;
    }

    private ExplorerNode resolveCurrentNode(ExplorerNode treeRoot) {
        if (currentRootFolderId == null) {
            return treeRoot;
        }

        ExplorerNode current = null;
        for (ExplorerNode node : treeRoot.children.values()) {
            if (
                node.rootFolderId != null &&
                node.rootFolderId.longValue() == currentRootFolderId.longValue()
            ) {
                current = node;
                break;
            }
        }
        if (current == null) return null;

        for (String segment : currentPathSegments) {
            current = current.children.get(segment.toLowerCase(Locale.ROOT));
            if (current == null) return null;
        }

        return current;
    }

    private void dispatchNavigationState() {
        if (navigationListener == null) return;

        if (!"folders".equals(displayMode)) {
            navigationListener.onNavigationChanged(
                new NavigationState(false, false, "")
            );
            return;
        }

        if (currentRootFolderId == null) {
            navigationListener.onNavigationChanged(
                new NavigationState(true, false, "Folders")
            );
            return;
        }

        StringBuilder label = new StringBuilder();
        label.append(getFolderNameById(currentRootFolderId));
        for (String segment : currentPathSegments) {
            label.append(" / ").append(segment);
        }

        navigationListener.onNavigationChanged(
            new NavigationState(true, true, label.toString())
        );
    }

    private void resetFolderNavigationState() {
        currentRootFolderId = null;
        currentPathSegments = new ArrayList<>();
    }

    private String getFolderName(DocumentEntity doc) {
        if (doc == null) return "Unknown folder";
        return getFolderNameById(doc.folderId);
    }

    private String getFolderNameById(long folderId) {
        String folderName = folderNames.get(folderId);
        if (folderName == null || folderName.trim().isEmpty()) {
            return "Unknown folder";
        }
        return folderName;
    }

    private List<String> getFolderSegments(DocumentEntity doc) {
        List<String> segments = new ArrayList<>();
        String relativePath = doc != null ? doc.relativePath : null;
        if (relativePath == null || relativePath.trim().isEmpty()) {
            if (doc != null && doc.name != null && !doc.name.isEmpty()) {
                segments.add(doc.name);
            }
            return segments;
        }

        String[] split = relativePath.split("/");
        for (String segment : split) {
            if (segment == null) continue;
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                segments.add(trimmed);
            }
        }

        if (segments.isEmpty() && doc != null && doc.name != null) {
            segments.add(doc.name);
        }
        return segments;
    }

    private static class RowItem {

        final int viewType;
        final String headerTitle;
        final DocumentEntity document;
        final ExplorerNode folderNode;

        private RowItem(
            int viewType,
            String headerTitle,
            DocumentEntity document,
            ExplorerNode folderNode
        ) {
            this.viewType = viewType;
            this.headerTitle = headerTitle;
            this.document = document;
            this.folderNode = folderNode;
        }

        static RowItem header(String title) {
            return new RowItem(VIEW_TYPE_HEADER, title, null, null);
        }

        static RowItem document(DocumentEntity document) {
            return new RowItem(VIEW_TYPE_DOCUMENT, null, document, null);
        }

        static RowItem folder(ExplorerNode folderNode) {
            return new RowItem(VIEW_TYPE_FOLDER, null, null, folderNode);
        }
    }

    private static class ExplorerNode {

        final String name;
        final Long rootFolderId;
        final List<String> pathSegments;
        final Map<String, ExplorerNode> children = new HashMap<>();
        final List<DocumentEntity> documents = new ArrayList<>();

        ExplorerNode(String name, Long rootFolderId, List<String> pathSegments) {
            this.name = name;
            this.rootFolderId = rootFolderId;
            this.pathSegments = pathSegments;
        }

        ExplorerNode getOrCreateChild(String folderName) {
            String key = folderName.toLowerCase(Locale.ROOT);
            ExplorerNode child = children.get(key);
            if (child != null) return child;

            List<String> childPath = new ArrayList<>(pathSegments);
            childPath.add(folderName);
            child = new ExplorerNode(folderName, rootFolderId, childPath);
            children.put(key, child);
            return child;
        }

        List<ExplorerNode> getSortedChildren() {
            List<ExplorerNode> sorted = new ArrayList<>(children.values());
            sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return sorted;
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {

        private final TextView txtFolderHeader;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            txtFolderHeader = itemView.findViewById(R.id.txtFolderHeader);
        }

        void bind(String title) {
            txtFolderHeader.setText(title);
        }
    }

    static class FolderViewHolder extends RecyclerView.ViewHolder {

        interface FolderClickListener {
            void onClick(ExplorerNode node);
        }

        private final TextView txtFolderName;
        private final TextView txtFolderMeta;

        FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            txtFolderName = itemView.findViewById(R.id.txtBrowserFolderName);
            txtFolderMeta = itemView.findViewById(R.id.txtBrowserFolderMeta);
        }

        void bind(ExplorerNode node, FolderClickListener listener) {
            txtFolderName.setText(node.name);

            int subfolderCount = node.children.size();
            int fileCount = node.documents.size();
            StringBuilder meta = new StringBuilder();
            if (subfolderCount > 0) {
                meta
                    .append(subfolderCount)
                    .append(subfolderCount == 1 ? " folder" : " folders");
            }
            if (fileCount > 0) {
                if (meta.length() > 0) meta.append("  •  ");
                meta.append(fileCount).append(fileCount == 1 ? " file" : " files");
            }
            if (meta.length() == 0) {
                meta.append("Open folder");
            }
            txtFolderMeta.setText(meta.toString());

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(node);
            });
        }
    }

    static class DocumentViewHolder extends RecyclerView.ViewHolder {

        private final ImageView imgFileIcon;
        private final TextView txtDocName;
        private final TextView txtCategory;
        private final TextView txtFolderName;
        private final TextView chipFileType;
        private final ChipGroup chipGroupKeywords;
        private final TextView txtIndexedAt;
        private final TextView txtWordCount;
        private final TextView txtReadTime;

        DocumentViewHolder(@NonNull View itemView) {
            super(itemView);
            imgFileIcon = itemView.findViewById(R.id.imgFileIcon);
            txtDocName = itemView.findViewById(R.id.txtDocName);
            txtCategory = itemView.findViewById(R.id.txtCategory);
            txtFolderName = itemView.findViewById(R.id.txtFolderName);
            chipFileType = itemView.findViewById(R.id.chipFileType);
            chipGroupKeywords = itemView.findViewById(R.id.chipGroupKeywords);
            txtIndexedAt = itemView.findViewById(R.id.txtIndexedAt);
            txtWordCount = itemView.findViewById(R.id.txtWordCount);
            txtReadTime = itemView.findViewById(R.id.txtReadTime);
        }

        void bind(
            DocumentEntity doc,
            String folderName,
            boolean showFolderName,
            OnDocumentClickListener listener
        ) {
            Context ctx = itemView.getContext();

            txtDocName.setText(doc.name);
            txtCategory.setText(doc.category != null ? doc.category : "General");
            txtFolderName.setText(folderName);
            txtFolderName.setVisibility(showFolderName ? View.VISIBLE : View.GONE);

            String fileType =
                doc.fileType != null ? doc.fileType.toUpperCase(Locale.ROOT) : "FILE";
            chipFileType.setText(fileType);
            imgFileIcon.setImageResource(resolveFileIcon(doc.fileType));

            chipGroupKeywords.removeAllViews();
            if (doc.keywords != null && !doc.keywords.isEmpty()) {
                String[] keywords = doc.keywords.split(",");
                int max = Math.min(keywords.length, 2);
                for (int i = 0; i < max; i++) {
                    String kw = keywords[i].trim();
                    if (kw.isEmpty()) continue;
                    Chip chip = new Chip(ctx);
                    chip.setText(kw);
                    chip.setClickable(false);
                    chip.setFocusable(false);
                    chip.setEnsureMinTouchTargetSize(false);
                    chip.setChipMinHeight(28f);
                    chip.setChipCornerRadius(14f);
                    chip.setChipStrokeWidth(0f);
                    chip.setCloseIconVisible(false);
                    chip.setTypeface(
                        ResourcesCompat.getFont(ctx, R.font.bricolage_grotesque)
                    );
                    int chipBg = MaterialColors.getColor(
                        ctx,
                        com.google.android.material.R.attr.colorSurfaceVariant,
                        0
                    );
                    int chipFg = MaterialColors.getColor(
                        ctx,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        0
                    );
                    chip.setChipBackgroundColor(ColorStateList.valueOf(chipBg));
                    chip.setTextColor(chipFg);
                    chip.setTextSize(11f);
                    chipGroupKeywords.addView(chip);
                }
            }

            txtIndexedAt.setText(formatIndexedDate(doc.indexedAt));
            txtWordCount.setText(formatWordCount(doc.wordCount));
            txtReadTime.setText(FileUtils.readTimeLabel(doc.wordCount));

            itemView.setOnClickListener(v -> listener.onClick(doc));
        }

        private static String formatIndexedDate(long timestamp) {
            if (timestamp <= 0) return "Indexed recently";
            return "Indexed " +
            new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(
                new Date(timestamp)
            );
        }

        private static String formatWordCount(int count) {
            if (count >= 1000) {
                return String.format(
                    Locale.getDefault(),
                    "%.1fk words",
                    count / 1000f
                );
            }
            return count + " words";
        }

        private static int resolveFileIcon(String fileType) {
            if (fileType == null) return R.drawable.ic_description;
            switch (fileType) {
                case "pdf":
                    return R.drawable.ic_pdf;
                case "docx":
                    return R.drawable.ic_doc;
                case "pptx":
                    return R.drawable.ic_slideshow;
                case "txt":
                    return R.drawable.ic_txt;
                default:
                    return R.drawable.ic_description;
            }
        }
    }
}
