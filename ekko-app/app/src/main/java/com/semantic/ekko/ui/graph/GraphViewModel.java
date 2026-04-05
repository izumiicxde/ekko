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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GraphViewModel extends AndroidViewModel {

    private static final String OVERVIEW_HUB_ID = "__overview__";
    private static final String CATEGORY_HUB_PREFIX = "__category__:";
    private static final int MAX_VISIBLE_DOCUMENTS = 12;
    private static final int MAX_DOC_EDGES = 18;
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
    private String selectedCategory = null;

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
            if (selectedCategory == null) {
                state.postValue(buildOverviewState());
            } else {
                state.postValue(buildCategoryState(selectedCategory));
            }
        });
    }

    public void showOverview() {
        selectedCategory = null;
        state.setValue(buildOverviewState());
    }

    public boolean isShowingOverview() {
        GraphUiState current = state.getValue();
        return current == null || current.scene == null || current.scene.overview;
    }

    public void openCategory(String category) {
        selectedCategory = category;
        state.setValue(buildCategoryState(category));
    }

    private GraphUiState buildOverviewState() {
        Map<String, List<GraphDocument>> grouped = groupByCategory(cachedDocuments);
        if (grouped.isEmpty()) {
            return GraphUiState.empty(
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
        for (int i = 0; i < categories.size(); i++) {
            String category = categories.get(i);
            List<GraphDocument> docs = grouped.get(category);
            maxCount = Math.max(maxCount, docs.size());
            centroids.put(category, computeCentroid(docs));
        }

        for (int i = 0; i < categories.size(); i++) {
            String category = categories.get(i);
            List<GraphDocument> docs = grouped.get(category);
            float weight = 0.58f + (0.42f * docs.size() / (float) maxCount);
            nodes.add(
                new GraphNode(
                    category,
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
        int connectedCount = 0;
        nodes.add(
            0,
            new GraphNode(
                OVERVIEW_HUB_ID,
                "Library",
                cachedDocuments.size() +
                (cachedDocuments.size() == 1 ? " indexed file" : " indexed files"),
                0.92f,
                Color.parseColor("#0F172A"),
                GraphNode.TYPE_HUB,
                -1L
            )
        );
        for (String category : categories) {
            if (!grouped.containsKey(category)) {
                continue;
            }
            edges.add(
                new GraphEdge(
                    OVERVIEW_HUB_ID,
                    category,
                    0.68f + (0.06f * (connectedCount % 3))
                )
            );
            connectedCount++;
        }
        return GraphUiState.ready(
            "Knowledge Map",
            "Category clusters orbit your library. Tap a cluster to open its files.",
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
        String hubId = CATEGORY_HUB_PREFIX + category;
        nodes.add(
            new GraphNode(
                hubId,
                category,
                docs.size() + (docs.size() == 1 ? " file" : " files"),
                0.9f,
                accentColor,
                GraphNode.TYPE_HUB,
                -1L
            )
        );
        for (GraphDocument doc : visibleDocs) {
            float weight = 0.56f + (0.34f * doc.wordCount / (float) maxWords);
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
            edges.add(
                new GraphEdge(
                    hubId,
                    "doc:" + doc.id,
                    0.7f
                )
            );
        }
        String subtitle =
            visibleDocs.size() < docs.size()
                ? "Showing the " +
                visibleDocs.size() +
                " strongest files around the category hub out of " +
                docs.size() +
                ". Tap a node to open it."
                : "Tap a node to open its file details.";
        return GraphUiState.ready(
            category + " Cluster",
            subtitle,
            "All clusters",
            new GraphScene(false, nodes, edges)
        );
    }

    private List<GraphDocument> mapDocuments(
        List<DocumentDao.DocumentEmbeddingRow> rows
    ) {
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
                    row.relative_path,
                    row.word_count,
                    row.embedding == null ? null : EmbeddingEngine.fromBytes(row.embedding)
                )
            );
        }
        return documents;
    }

    private Map<String, List<GraphDocument>> groupByCategory(
        List<GraphDocument> documents
    ) {
        Map<String, List<GraphDocument>> grouped = new LinkedHashMap<>();
        if (documents == null) {
            return grouped;
        }
        for (GraphDocument document : documents) {
            if (document == null) {
                continue;
            }
            grouped
                .computeIfAbsent(document.category, key -> new ArrayList<>())
                .add(document);
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
                float similarity = EmbeddingEngine.cosineSimilarity(
                    leftCentroid,
                    rightCentroid
                );
                if (similarity >= MIN_CATEGORY_EDGE_SIMILARITY) {
                    candidates.add(new GraphEdge(left, right, similarity));
                }
            }
        }
        return pruneEdges(candidates, 2, 10);
    }

    private List<GraphDocument> selectVisibleDocuments(List<GraphDocument> docs) {
        if (docs.size() <= MAX_VISIBLE_DOCUMENTS) {
            return new ArrayList<>(docs);
        }
        float[] centroid = computeCentroid(docs);
        List<GraphDocument> ranked = new ArrayList<>(docs);
        ranked.sort((a, b) -> {
            float scoreA = documentPriority(a, centroid);
            float scoreB = documentPriority(b, centroid);
            int scoreCompare = Float.compare(scoreB, scoreA);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        return new ArrayList<>(ranked.subList(0, MAX_VISIBLE_DOCUMENTS));
    }

    private float documentPriority(GraphDocument doc, float[] centroid) {
        float score = Math.min(doc.wordCount, 4000) / 4000f;
        if (centroid != null && doc.embedding != null) {
            score += 0.75f * EmbeddingEngine.cosineSimilarity(centroid, doc.embedding);
        }
        return score;
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
                float similarity = EmbeddingEngine.cosineSimilarity(
                    left.embedding,
                    right.embedding
                );
                if (similarity >= MIN_DOCUMENT_EDGE_SIMILARITY) {
                    candidates.add(
                        new GraphEdge("doc:" + left.id, "doc:" + right.id, similarity)
                    );
                }
            }
        }
        return pruneEdges(candidates, 3, MAX_DOC_EDGES);
    }

    private List<GraphEdge> pruneEdges(
        List<GraphEdge> candidates,
        int maxDegree,
        int maxEdges
    ) {
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

    private static class GraphDocument {

        final long id;
        final String name;
        final String category;
        final String fileType;
        final String relativePath;
        final int wordCount;
        final float[] embedding;

        GraphDocument(
            long id,
            String name,
            String category,
            String fileType,
            String relativePath,
            int wordCount,
            float[] embedding
        ) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.fileType = fileType;
            this.relativePath = relativePath;
            this.wordCount = wordCount;
            this.embedding = embedding;
        }
    }

    public static class GraphUiState {

        public final boolean loading;
        public final String title;
        public final String subtitle;
        public final String actionLabel;
        public final GraphScene scene;
        public final String emptyMessage;

        private GraphUiState(
            boolean loading,
            String title,
            String subtitle,
            String actionLabel,
            GraphScene scene,
            String emptyMessage
        ) {
            this.loading = loading;
            this.title = title;
            this.subtitle = subtitle;
            this.actionLabel = actionLabel;
            this.scene = scene;
            this.emptyMessage = emptyMessage;
        }

        static GraphUiState loading() {
            return new GraphUiState(
                true,
                "Knowledge Map",
                "Reading indexed documents…",
                null,
                null,
                null
            );
        }

        static GraphUiState empty(String title, String message) {
            return new GraphUiState(false, title, null, null, null, message);
        }

        static GraphUiState ready(
            String title,
            String subtitle,
            String actionLabel,
            GraphScene scene
        ) {
            return new GraphUiState(
                false,
                title,
                subtitle,
                actionLabel,
                scene,
                null
            );
        }
    }
}
