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

    public void deleteByFolderIds(List<Long> folderIds, Runnable onComplete) {
        executor.execute(() -> {
            if (folderIds != null) {
                for (Long folderId : folderIds) {
                    if (folderId != null) {
                        documentDao.deleteByFolderId(folderId);
                    }
                }
            }
            if (onComplete != null) onComplete.run();
        });
    }

    public void deleteAll() {
        executor.execute(documentDao::deleteAll);
    }

    public void updateCategory(long id, String category) {
        executor.execute(() -> documentDao.updateCategory(id, category));
    }

    public void updateSummary(long id, String summary) {
        executor.execute(() -> documentDao.updateSummary(id, summary));
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
     * Hybrid search combining embedding cosine similarity with keyword,
     * summary, full-text, and filename/path text matching.
     *
     * Scoring breakdown:
     *   embeddingScore  - cosine similarity between query and document vectors
     *   keywordScore    - proportion of query terms found in extracted keywords
     *   summaryScore    - proportion of query terms found in document summary
     *   fullTextScore   - proportion of query terms found in indexed document text
     *   filenameScore   - whether query terms appear in the filename/path
     *
     * Final score = 0.35 * embeddingScore + 0.20 * keywordScore
     *             + 0.15 * summaryScore   + 0.20 * fullTextScore
     *             + 0.10 * filenameScore
     *
     * Exact text hits in indexed fields are also allowed through with a lower
     * score floor so obvious matches are never hidden behind a weak embedding.
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
                boolean obviousTextMatch = hasAnyFieldMatch(
                    queryTerms,
                    row.name,
                    row.relative_path,
                    row.keywords,
                    row.summary,
                    row.raw_text
                );
                if (row.word_count < 20 && !obviousTextMatch) continue;
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

                float keywordScore = computeFieldScore(
                    row.keywords,
                    queryTerms
                );
                float summaryScore = computeFieldScore(row.summary, queryTerms);
                float fullTextScore = computeFieldScore(
                    row.raw_text,
                    queryTerms
                );
                float filenameScore = Math.max(
                    computeFieldScore(row.name, queryTerms),
                    computeFieldScore(row.relative_path, queryTerms)
                );

                float finalScore =
                    0.35f * embeddingScore +
                    0.20f * keywordScore +
                    0.15f * summaryScore +
                    0.20f * fullTextScore +
                    0.10f * filenameScore;

                if (
                    finalScore >= minScore ||
                    (obviousTextMatch && finalScore >= 0.05f)
                ) {
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
     * Returns the proportion of query terms found in the given field text.
     * A term must be at least 2 characters. Returns a value in [0.0, 1.0].
     */
    private float computeFieldScore(String fieldText, String[] queryTerms) {
        if (
            fieldText == null || fieldText.isEmpty() || queryTerms.length == 0
        ) {
            return 0f;
        }
        String lowerField = fieldText.toLowerCase();
        int matches = 0;
        int validTerms = 0;
        for (String term : queryTerms) {
            if (term.length() < 2) continue;
            validTerms++;
            if (lowerField.contains(term)) matches++;
        }
        if (validTerms == 0) return 0f;
        return (float) matches / validTerms;
    }

    private boolean hasAnyFieldMatch(String[] queryTerms, String... fieldTexts) {
        if (queryTerms == null || queryTerms.length == 0 || fieldTexts == null) {
            return false;
        }
        for (String fieldText : fieldTexts) {
            if (fieldText == null || fieldText.isEmpty()) continue;
            String lowerField = fieldText.toLowerCase();
            for (String term : queryTerms) {
                if (term == null || term.length() < 2) continue;
                if (lowerField.contains(term.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
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
