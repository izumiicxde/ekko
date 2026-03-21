package com.semantic.ekko.data.repository;

import android.content.Context;
import androidx.lifecycle.LiveData;
import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.DocumentDao;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.SearchResult;
import com.semantic.ekko.ml.EmbeddingEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentRepository {

    private final DocumentDao documentDao;
    private final ExecutorService executor;

    // =========================
    // INIT
    // =========================

    public DocumentRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.documentDao = db.documentDao();
        this.executor = Executors.newSingleThreadExecutor();
    }

    // =========================
    // INSERT / UPDATE / DELETE
    // =========================

    public void insert(DocumentEntity document, InsertCallback callback) {
        executor.execute(() -> {
            long id = documentDao.insert(document);
            if (callback != null) callback.onInserted(id);
        });
    }

    public void update(DocumentEntity document) {
        executor.execute(() -> documentDao.update(document));
    }

    public void delete(DocumentEntity document) {
        executor.execute(() -> documentDao.delete(document));
    }

    public void deleteByFolderId(long folderId) {
        executor.execute(() -> documentDao.deleteByFolderId(folderId));
    }

    public void deleteAll() {
        executor.execute(documentDao::deleteAll);
    }

    public void updateCategory(long id, String category) {
        executor.execute(() -> documentDao.updateCategory(id, category));
    }

    // =========================
    // QUERIES
    // =========================

    public LiveData<List<DocumentEntity>> getAllLive() {
        return documentDao.getAllLive();
    }

    public void getAll(QueryCallback<List<DocumentEntity>> callback) {
        executor.execute(() -> {
            List<DocumentEntity> result = documentDao.getAll();
            if (callback != null) callback.onResult(result);
        });
    }

    public void getById(long id, QueryCallback<DocumentEntity> callback) {
        executor.execute(() -> {
            DocumentEntity result = documentDao.getById(id);
            if (callback != null) callback.onResult(result);
        });
    }

    public void getByCategory(
        String category,
        QueryCallback<List<DocumentEntity>> callback
    ) {
        executor.execute(() -> {
            List<DocumentEntity> result = documentDao.getByCategory(category);
            if (callback != null) callback.onResult(result);
        });
    }

    public void getByFileType(
        String fileType,
        QueryCallback<List<DocumentEntity>> callback
    ) {
        executor.execute(() -> {
            List<DocumentEntity> result = documentDao.getByFileType(fileType);
            if (callback != null) callback.onResult(result);
        });
    }

    // =========================
    // SEMANTIC SEARCH
    // =========================

    /**
     * Hybrid search combining embedding cosine similarity with keyword and
     * filename text matching. This compensates for the limited 128-token
     * embedding window by boosting documents whose name or keywords
     * contain the query terms directly.
     *
     * Final score = 0.4 * embedding_score + 0.6 * text_score
     */
    public void search(
        float[] queryEmbedding,
        String rawQuery,
        float minScore,
        QueryCallback<List<SearchResult>> callback
    ) {
        executor.execute(() -> {
            List<DocumentDao.DocumentEmbeddingRow> rows =
                documentDao.getAllEmbeddings();
            List<SearchResult> results = new ArrayList<>();

            String queryLower =
                rawQuery != null ? rawQuery.toLowerCase().trim() : "";
            String[] queryTerms = queryLower.split("\\s+");

            for (DocumentDao.DocumentEmbeddingRow row : rows) {
                // Embedding similarity score
                float embeddingScore = 0f;
                if (row.embedding != null) {
                    float[] docEmbedding = EmbeddingEngine.fromBytes(
                        row.embedding
                    );
                    embeddingScore = EmbeddingEngine.cosineSimilarity(
                        queryEmbedding,
                        docEmbedding
                    );
                }

                // Text match score from filename and keywords
                float textScore = computeTextScore(row, queryTerms);

                // Hybrid score weighted toward text matching
                float finalScore = 0.4f * embeddingScore + 0.6f * textScore;

                if (finalScore >= minScore || textScore > 0) {
                    DocumentEntity doc = documentDao.getById(row.id);
                    if (doc != null) {
                        results.add(new SearchResult(doc, finalScore));
                    }
                }
            }

            Collections.sort(results, (a, b) ->
                Float.compare(b.getScore(), a.getScore())
            );

            if (callback != null) callback.onResult(results);
        });
    }

    /**
     * Computes a text match score based on how many query terms appear
     * in the document filename, keywords, category, and summary.
     * Returns a value between 0.0 and 1.0.
     */
    private float computeTextScore(
        DocumentDao.DocumentEmbeddingRow row,
        String[] queryTerms
    ) {
        if (queryTerms.length == 0) return 0f;

        // Build searchable text from available fields
        StringBuilder searchable = new StringBuilder();
        if (row.name != null) searchable
            .append(row.name.toLowerCase())
            .append(" ");
        if (row.category != null) searchable
            .append(row.category.toLowerCase())
            .append(" ");

        String searchText = searchable.toString();
        int matches = 0;

        for (String term : queryTerms) {
            if (term.length() < 2) continue;
            if (searchText.contains(term)) {
                // Filename match is weighted heavily
                if (row.name != null && row.name.toLowerCase().contains(term)) {
                    matches += 3;
                } else {
                    matches += 1;
                }
            }
        }

        return Math.min(1.0f, (float) matches / (queryTerms.length * 3));
    }

    // =========================
    // STATISTICS
    // =========================

    public void getStatistics(QueryCallback<Statistics> callback) {
        executor.execute(() -> {
            Statistics stats = new Statistics();
            stats.totalDocuments = documentDao.getTotalCount();
            stats.totalWordCount = documentDao.getTotalWordCount();
            stats.categoryDistribution = documentDao.getCategoryDistribution();
            stats.fileTypeDistribution = documentDao.getFileTypeDistribution();
            stats.largestDocument = documentDao.getLargestDocument();
            stats.mostRecentDocument = documentDao.getMostRecentDocument();
            if (callback != null) callback.onResult(stats);
        });
    }

    // =========================
    // CALLBACKS
    // =========================

    public interface InsertCallback {
        void onInserted(long id);
    }

    public interface QueryCallback<T> {
        void onResult(T result);
    }

    // =========================
    // STATISTICS MODEL
    // =========================

    public static class Statistics {

        public int totalDocuments;
        public int totalWordCount;
        public List<DocumentDao.CategoryCount> categoryDistribution;
        public List<DocumentDao.FileTypeCount> fileTypeDistribution;
        public DocumentEntity largestDocument;
        public DocumentEntity mostRecentDocument;

        public int getAverageReadTime() {
            if (totalDocuments == 0) return 0;
            int avgWords = totalWordCount / totalDocuments;
            return Math.max(1, avgWords / 200);
        }
    }
}
