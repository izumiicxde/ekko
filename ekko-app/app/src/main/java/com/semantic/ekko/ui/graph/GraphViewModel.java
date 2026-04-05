package com.semantic.ekko.ui.graph;

import android.app.Application;
import android.graphics.Color;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.data.db.DocumentDao;
import com.semantic.ekko.data.repository.DocumentRepository;
import com.semantic.ekko.ml.EmbeddingEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GraphViewModel extends AndroidViewModel {

    public static final int MODE_CLUSTER = 0;
    public static final int MODE_FOLDER = 1;

    private static final String OVERVIEW_HUB_ID = "__overview__";
    private static final String CATEGORY_HUB_PREFIX = "__category__:";
    private static final String FOLDER_HUB_PREFIX = "__folder__:";
    private static final int MAX_DOC_EDGES = 24;
    private static final float MIN_CATEGORY_EDGE_SIMILARITY = 0.28f;
    private static final float MIN_DOCUMENT_EDGE_SIMILARITY = 0.56f;
    private static final int[] GRAPH_COLORS = {
        Color.parseColor("#2054D7"),
        Color.parseColor("#0D9488"),
        Color.parseColor("#D97706"),
        Color.parseColor("#DC2626"),
        Color.parseColor("#7C3AED"),
        Color.parseColor("#0891B2"),
    };

    private final DocumentRepository repository;
    private final MutableLiveData<GraphUiState> state = new MutableLiveData<>();
    private List<GraphDocument> cachedDocuments = Collections.emptyList();

    private int selectedMode = MODE_CLUSTER;
    private String selectedCategory = null;
    private String selectedFolder = null;

    public GraphViewModel(@NonNull Application application) {
        super(application);
        repository = new DocumentRepository(application.getApplicationContext());
        loadGraph();
    }

    public LiveData<GraphUiState> getState() {
        return state;
    }

    public void loadGraph() {
        state.setValue(GraphUiState.loading());
        repository.getGraphRows(rows -> {
            cachedDocuments = mapDocuments(rows);
            state.postValue(buildCurrentState());
        });
    }

    public void showOverview() {
        selectedCategory = null;
        selectedFolder = null;
        state.setValue(buildCurrentState());
    }

    public void showClusterMode() {
        selectedMode = MODE_CLUSTER;
        selectedCategory = null;
        selectedFolder = null;
        state.setValue(buildOverviewState());
    }

    public void showFolderMode() {
        selectedMode = MODE_FOLDER;
        selectedCategory = null;
        selectedFolder = null;
        state.setValue(buildFolderOverviewState());
    }

    public int getSelectedMode() {
        return selectedMode;
    }

    public boolean isShowingOverview() {
        return selectedCategory == null && selectedFolder == null;
    }

    public void openCategory(String category) {
        selectedMode = MODE_CLUSTER;
        selectedCategory = category;
        selectedFolder = null;
        state.setValue(buildCategoryState(category));
    }

    public void openFolder(String folderPath) {
        selectedMode = MODE_FOLDER;
        selectedFolder = folderPath;
        selectedCategory = null;
        state.setValue(buildFolderState(folderPath));
    }

    private GraphUiState buildCurrentState() {
        if (selectedMode == MODE_FOLDER) {
            return selectedFolder == null
                ? buildFolderOverviewState()
                : buildFolderState(selectedFolder);
        }
        return selectedCategory == null
            ? buildOverviewState()
            : buildCategoryState(selectedCategory);
    }

    private GraphUiState buildOverviewState() {
        Map<String, List<GraphDocument>> grouped = groupByCategory(cachedDocuments);
        if (grouped.isEmpty()) {
            return GraphUiState.empty(
                MODE_CLUSTER,
                "Knowledge Map",
                "Index a few files to see how categories connect."
            );
        }

        List<String> categories = new ArrayList<>(grouped.keySet());
        categories.sort((a, b) -> {
            int countCompare = Integer.compare(
                grouped.get(b).size(),
                grouped.get(a).size()
            );
            if (countCompare != 0) {
                return countCompare;
            }
            return a.compareToIgnoreCase(b);
        });

        int maxCount = 1;
        Map<String, float[]> centroids = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>();
        for (String category : categories) {
            List<GraphDocument> docs = grouped.get(category);
            maxCount = Math.max(maxCount, docs.size());
            centroids.put(category, computeCentroid(docs));
        }

        for (int i = 0; i < categories.size(); i++) {
            String category = categories.get(i);
            List<GraphDocument> docs = grouped.get(category);
            float weight = 0.58f + (0.34f * docs.size() / (float) maxCount);
            nodes.add(
                new GraphNode(
                    categoryNodeId(category),
                    category,
                    docs.size() + (docs.size() == 1 ? " file" : " files"),
                    weight,
                    GRAPH_COLORS[i % GRAPH_COLORS.length],
                    GraphNode.TYPE_CLUSTER,
                    -1L
                )
            );
        }

        List<GraphEdge> edges = buildCategoryEdges(categories, centroids);
        nodes.add(
            0,
            new GraphNode(
                OVERVIEW_HUB_ID,
                "Library",
                cachedDocuments.size() +
                (cachedDocuments.size() == 1 ? " indexed file" : " indexed files"),
                0.78f,
                Color.parseColor("#0F172A"),
                GraphNode.TYPE_HUB,
                -1L
            )
        );
        for (String category : categories) {
            edges.add(new GraphEdge(OVERVIEW_HUB_ID, categoryNodeId(category), 0.68f));
        }

        return GraphUiState.ready(
            MODE_CLUSTER,
            "Knowledge Map",
            "Minimal category view. Tap a cluster to open all files in it.",
            null,
            new GraphScene(true, nodes, edges)
        );
    }

    private GraphUiState buildCategoryState(String category) {
        Map<String, List<GraphDocument>> grouped = groupByCategory(cachedDocuments);
        List<GraphDocument> docs = grouped.get(category);
        if (docs == null || docs.isEmpty()) {
            selectedCategory = null;
            return buildOverviewState();
        }

        List<GraphDocument> visibleDocs = selectVisibleDocuments(docs);
        int accentColor = colorForCategory(category);
        int maxWords = 1;
        for (GraphDocument doc : visibleDocs) {
            maxWords = Math.max(maxWords, doc.wordCount);
        }

        List<GraphNode> nodes = new ArrayList<>();
        String hubId = categoryNodeId(category);
        nodes.add(
            new GraphNode(
                hubId,
                category,
                docs.size() + (docs.size() == 1 ? " file" : " files"),
                0.76f,
                accentColor,
                GraphNode.TYPE_HUB,
                -1L
            )
        );
        for (GraphDocument doc : visibleDocs) {
            float weight = 0.46f + (0.28f * doc.wordCount / (float) maxWords);
            nodes.add(
                new GraphNode(
                    "doc:" + doc.id,
                    trimExtension(doc.name),
                    buildDocumentDetail(doc),
                    weight,
                    accentColor,
                    GraphNode.TYPE_DOCUMENT,
                    doc.id
                )
            );
        }

        List<GraphEdge> edges = buildDocumentEdges(visibleDocs);
        for (GraphDocument doc : visibleDocs) {
            edges.add(new GraphEdge(hubId, "doc:" + doc.id, 0.72f));
        }

        return GraphUiState.ready(
            MODE_CLUSTER,
            category + " Cluster",
            "All files in " + category + ". Tap a node to open it.",
            "All clusters",
            new GraphScene(false, nodes, edges)
        );
    }

    private GraphUiState buildFolderOverviewState() {
        Map<String, List<GraphDocument>> grouped = groupByFolder(cachedDocuments);
        if (grouped.isEmpty()) {
            return GraphUiState.empty(
                MODE_FOLDER,
                "Folder Graph",
                "Index a few files to see folders and files."
            );
        }

        List<String> folders = new ArrayList<>(grouped.keySet());
        folders.sort((a, b) -> {
            int countCompare = Integer.compare(
                grouped.get(b).size(),
                grouped.get(a).size()
            );
            if (countCompare != 0) {
                return countCompare;
            }
            return a.compareToIgnoreCase(b);
        });

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        nodes.add(
            new GraphNode(
                OVERVIEW_HUB_ID,
                "Folders",
                cachedDocuments.size() +
                (cachedDocuments.size() == 1 ? " indexed file" : " indexed files"),
                0.78f,
                Color.parseColor("#0F172A"),
                GraphNode.TYPE_HUB,
                -1L
            )
        );

        for (int i = 0; i < folders.size(); i++) {
            String folder = folders.get(i);
            List<GraphDocument> docs = grouped.get(folder);
            nodes.add(
                new GraphNode(
                    folderNodeId(folder),
                    folderLabel(folder),
                    docs.size() + (docs.size() == 1 ? " file" : " files"),
                    0.56f + (Math.min(docs.size(), 12) * 0.025f),
                    GRAPH_COLORS[i % GRAPH_COLORS.length],
                    GraphNode.TYPE_FOLDER,
                    -1L
                )
            );
            edges.add(new GraphEdge(OVERVIEW_HUB_ID, folderNodeId(folder), 0.72f));
        }

        return GraphUiState.ready(
            MODE_FOLDER,
            "Folder Graph",
            "Folder-first view. Tap a folder to open all files inside it.",
            null,
            new GraphScene(true, nodes, edges)
        );
    }

    private GraphUiState buildFolderState(String folderPath) {
        Map<String, List<GraphDocument>> grouped = groupByFolder(cachedDocuments);
        List<GraphDocument> docs = grouped.get(folderPath);
        if (docs == null || docs.isEmpty()) {
            selectedFolder = null;
            return buildFolderOverviewState();
        }

        int accentColor = colorForFolder(folderPath);
        int maxWords = 1;
        for (GraphDocument doc : docs) {
            maxWords = Math.max(maxWords, doc.wordCount);
        }

        List<GraphNode> nodes = new ArrayList<>();
        String hubId = folderNodeId(folderPath);
        nodes.add(
            new GraphNode(
                hubId,
                folderLabel(folderPath),
                docs.size() + (docs.size() == 1 ? " file" : " files"),
                0.76f,
                accentColor,
                GraphNode.TYPE_HUB,
                -1L
            )
        );
        for (GraphDocument doc : docs) {
            float weight = 0.46f + (0.28f * doc.wordCount / (float) maxWords);
            nodes.add(
                new GraphNode(
                    "doc:" + doc.id,
                    trimExtension(doc.name),
                    buildDocumentDetail(doc),
                    weight,
                    accentColor,
                    GraphNode.TYPE_DOCUMENT,
                    doc.id
                )
            );
        }

        List<GraphEdge> edges = buildDocumentEdges(docs);
        for (GraphDocument doc : docs) {
            edges.add(new GraphEdge(hubId, "doc:" + doc.id, 0.74f));
        }

        return GraphUiState.ready(
            MODE_FOLDER,
            folderLabel(folderPath),
            "All files in this folder. Tap a file to open it.",
            "All folders",
            new GraphScene(false, nodes, edges)
        );
    }

    private List<GraphDocument> mapDocuments(List<DocumentDao.DocumentEmbeddingRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<GraphDocument> documents = new ArrayList<>();
        for (DocumentDao.DocumentEmbeddingRow row : rows) {
            if (row == null || row.id <= 0L || row.name == null) {
                continue;
            }
            documents.add(
                new GraphDocument(
                    row.id,
                    row.name,
                    normalizeCategory(row.category),
                    row.file_type,
                    resolveFolderPath(row.relative_path),
                    row.relative_path,
                    row.word_count,
                    row.embedding == null ? null : EmbeddingEngine.fromBytes(row.embedding)
                )
            );
        }
        return documents;
    }

    private Map<String, List<GraphDocument>> groupByCategory(List<GraphDocument> documents) {
        Map<String, List<GraphDocument>> grouped = new LinkedHashMap<>();
        if (documents == null) {
            return grouped;
        }
        for (GraphDocument document : documents) {
            if (document == null) {
                continue;
            }
            grouped.computeIfAbsent(document.category, key -> new ArrayList<>()).add(document);
        }
        return grouped;
    }

    private Map<String, List<GraphDocument>> groupByFolder(List<GraphDocument> documents) {
        Map<String, List<GraphDocument>> grouped = new LinkedHashMap<>();
        if (documents == null) {
            return grouped;
        }
        for (GraphDocument document : documents) {
            if (document == null) {
                continue;
            }
            grouped.computeIfAbsent(document.folderPath, key -> new ArrayList<>()).add(document);
        }
        return grouped;
    }

    private List<GraphEdge> buildCategoryEdges(
        List<String> categories,
        Map<String, float[]> centroids
    ) {
        List<GraphEdge> candidates = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            for (int j = i + 1; j < categories.size(); j++) {
                String left = categories.get(i);
                String right = categories.get(j);
                float[] leftCentroid = centroids.get(left);
                float[] rightCentroid = centroids.get(right);
                if (leftCentroid == null || rightCentroid == null) {
                    continue;
                }
                float similarity = EmbeddingEngine.cosineSimilarity(leftCentroid, rightCentroid);
                if (similarity >= MIN_CATEGORY_EDGE_SIMILARITY) {
                    candidates.add(
                        new GraphEdge(categoryNodeId(left), categoryNodeId(right), similarity)
                    );
                }
            }
        }
        return pruneEdges(candidates, 2, 10);
    }

    private List<GraphDocument> selectVisibleDocuments(List<GraphDocument> docs) {
        List<GraphDocument> all = new ArrayList<>(docs);
        all.sort(Comparator.comparing(doc -> doc.name.toLowerCase(Locale.US)));
        return all;
    }

    private List<GraphEdge> buildDocumentEdges(List<GraphDocument> docs) {
        List<GraphEdge> candidates = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            GraphDocument left = docs.get(i);
            for (int j = i + 1; j < docs.size(); j++) {
                GraphDocument right = docs.get(j);
                if (left.embedding == null || right.embedding == null) {
                    continue;
                }
                float similarity = EmbeddingEngine.cosineSimilarity(left.embedding, right.embedding);
                if (similarity >= MIN_DOCUMENT_EDGE_SIMILARITY) {
                    candidates.add(new GraphEdge("doc:" + left.id, "doc:" + right.id, similarity));
                }
            }
        }
        return pruneEdges(candidates, 3, MAX_DOC_EDGES);
    }

    private List<GraphEdge> pruneEdges(List<GraphEdge> candidates, int maxDegree, int maxEdges) {
        candidates.sort((a, b) -> Float.compare(b.weight, a.weight));
        Map<String, Integer> degree = new HashMap<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphEdge edge : candidates) {
            int fromDegree = degree.getOrDefault(edge.fromId, 0);
            int toDegree = degree.getOrDefault(edge.toId, 0);
            if (fromDegree >= maxDegree || toDegree >= maxDegree) {
                continue;
            }
            edges.add(edge);
            degree.put(edge.fromId, fromDegree + 1);
            degree.put(edge.toId, toDegree + 1);
            if (edges.size() >= maxEdges) {
                break;
            }
        }
        return edges;
    }

    private float[] computeCentroid(List<GraphDocument> docs) {
        List<float[]> vectors = new ArrayList<>();
        for (GraphDocument doc : docs) {
            if (doc.embedding != null) {
                vectors.add(doc.embedding);
            }
        }
        if (vectors.isEmpty()) {
            return null;
        }
        return EmbeddingEngine.centroid(vectors.toArray(new float[0][]));
    }

    private int colorForCategory(String category) {
        int index = Math.abs(category.hashCode()) % GRAPH_COLORS.length;
        return GRAPH_COLORS[index];
    }

    private int colorForFolder(String folderPath) {
        int index = Math.abs(folderPath.hashCode()) % GRAPH_COLORS.length;
        return GRAPH_COLORS[index];
    }

    private String normalizeCategory(String category) {
        if (category == null || category.trim().isEmpty()) {
            return "General";
        }
        return category.trim();
    }

    private String trimExtension(String name) {
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex <= 0) {
            return name;
        }
        return name.substring(0, dotIndex);
    }

    private String buildDocumentDetail(GraphDocument doc) {
        String type = doc.fileType == null || doc.fileType.isEmpty()
            ? "file"
            : doc.fileType.toUpperCase(Locale.US);
        if (doc.wordCount > 0) {
            return type + " • " + doc.wordCount + " words";
        }
        return type;
    }

    private String resolveFolderPath(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return "Library";
        }
        String normalized = relativePath.replace('\\', '/').trim();
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex <= 0) {
            return "Library";
        }
        return normalized.substring(0, slashIndex);
    }

    private String folderLabel(String folderPath) {
        if (folderPath == null || folderPath.trim().isEmpty()) {
            return "Library";
        }
        String normalized = folderPath.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex < 0 || slashIndex >= normalized.length() - 1) {
            return normalized;
        }
        return normalized.substring(slashIndex + 1);
    }

    private String categoryNodeId(String category) {
        return CATEGORY_HUB_PREFIX + category;
    }

    private String folderNodeId(String folderPath) {
        return FOLDER_HUB_PREFIX + folderPath;
    }

    private static class GraphDocument {

        final long id;
        final String name;
        final String category;
        final String fileType;
        final String folderPath;
        final String relativePath;
        final int wordCount;
        final float[] embedding;

        GraphDocument(
            long id,
            String name,
            String category,
            String fileType,
            String folderPath,
            String relativePath,
            int wordCount,
            float[] embedding
        ) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.fileType = fileType;
            this.folderPath = folderPath;
            this.relativePath = relativePath;
            this.wordCount = wordCount;
            this.embedding = embedding;
        }
    }

    public static class GraphUiState {

        public final boolean loading;
        public final int mode;
        public final String title;
        public final String subtitle;
        public final String actionLabel;
        public final GraphScene scene;
        public final String emptyMessage;

        private GraphUiState(
            boolean loading,
            int mode,
            String title,
            String subtitle,
            String actionLabel,
            GraphScene scene,
            String emptyMessage
        ) {
            this.loading = loading;
            this.mode = mode;
            this.title = title;
            this.subtitle = subtitle;
            this.actionLabel = actionLabel;
            this.scene = scene;
            this.emptyMessage = emptyMessage;
        }

        static GraphUiState loading() {
            return new GraphUiState(
                true,
                MODE_CLUSTER,
                "Knowledge Map",
                "Reading indexed documents…",
                null,
                null,
                null
            );
        }

        static GraphUiState empty(int mode, String title, String message) {
            return new GraphUiState(false, mode, title, null, null, null, message);
        }

        static GraphUiState ready(
            int mode,
            String title,
            String subtitle,
            String actionLabel,
            GraphScene scene
        ) {
            return new GraphUiState(false, mode, title, subtitle, actionLabel, scene, null);
        }
    }
}
