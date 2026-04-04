package com.semantic.ekko.data.repository;

import android.content.Context;
import androidx.lifecycle.LiveData;
import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.ChunkDao;
import com.semantic.ekko.data.db.DocumentDao;
import com.semantic.ekko.data.model.ChunkEntity;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.SearchResult;
import com.semantic.ekko.ml.EmbeddingEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentRepository {

    private final DocumentDao documentDao;
    private final ChunkDao chunkDao;
    private final ExecutorService executor;

    // =========================
    // INIT
    // =========================

    public DocumentRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.documentDao = db.documentDao();
        this.chunkDao = db.chunkDao();
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

    public void deleteMissingByFolderId(
        long folderId,
        List<String> uris,
        Runnable onComplete
    ) {
        executor.execute(() -> {
            if (uris == null || uris.isEmpty()) {
                documentDao.deleteByFolderId(folderId);
            } else {
                documentDao.deleteMissingByFolderId(folderId, uris);
            }
            if (onComplete != null) onComplete.run();
        });
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

            String normalizedQuery = normalizeSearchText(rawQuery);
            String[] queryTerms = tokenizeQuery(normalizedQuery);

            for (DocumentDao.DocumentEmbeddingRow row : rows) {
                boolean obviousTextMatch = hasAnyFieldMatch(
                    queryTerms,
                    row.name,
                    row.relative_path,
                    row.keywords,
                    row.summary,
                    row.raw_text
                );
                boolean exactPhraseMatch = hasPhraseMatch(
                    normalizedQuery,
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
                float phraseScore = computePhraseScore(
                    normalizedQuery,
                    row.name,
                    row.relative_path,
                    row.summary,
                    row.raw_text
                );
                ChunkSearchSignals chunkSignals = computeChunkSignals(
                    row.id,
                    queryEmbedding,
                    normalizedQuery,
                    queryTerms
                );
                float chunkOpportunityPenalty = computeChunkOpportunityPenalty(
                    chunkSignals.chunkCount
                );

                float finalScore =
                    0.24f * embeddingScore +
                    0.18f * (chunkSignals.embeddingScore * chunkOpportunityPenalty) +
                    0.08f * (chunkSignals.averageEmbeddingScore * chunkOpportunityPenalty) +
                    0.14f * keywordScore +
                    0.10f * summaryScore +
                    0.14f * fullTextScore +
                    0.06f * filenameScore +
                    0.10f * phraseScore;

                finalScore = Math.max(
                    finalScore,
                    0.18f * (chunkSignals.lexicalScore * chunkOpportunityPenalty)
                );

                if (obviousTextMatch || chunkSignals.obviousTextMatch) {
                    finalScore = Math.max(
                        finalScore,
                        exactPhraseMatch || chunkSignals.phraseMatch
                            ? 0.28f
                            : 0.14f
                    );
                }

                if (
                    finalScore >= minScore ||
                    obviousTextMatch ||
                    chunkSignals.obviousTextMatch
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
        String lowerField = normalizeSearchText(fieldText);
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
            String lowerField = normalizeSearchText(fieldText);
            for (String term : queryTerms) {
                if (term == null || term.length() < 2) continue;
                if (lowerField.contains(term)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasPhraseMatch(String query, String... fieldTexts) {
        if (query == null || query.isEmpty() || fieldTexts == null) {
            return false;
        }
        for (String fieldText : fieldTexts) {
            if (fieldText == null || fieldText.isEmpty()) continue;
            if (normalizeSearchText(fieldText).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private float computePhraseScore(String query, String... fieldTexts) {
        return hasPhraseMatch(query, fieldTexts) ? 1f : 0f;
    }

    private ChunkSearchSignals computeChunkSignals(
        long documentId,
        float[] queryEmbedding,
        String normalizedQuery,
        String[] queryTerms
    ) {
        List<ChunkEntity> chunks = chunkDao.getByDocumentId(documentId);
        if (chunks == null || chunks.isEmpty()) {
            return ChunkSearchSignals.empty();
        }

        float bestChunkEmbedding = 0f;
        float totalChunkEmbedding = 0f;
        int embeddingCount = 0;
        float bestChunkLexical = 0f;
        boolean obviousTextMatch = false;
        boolean phraseMatch = false;
        int chunkCount = 0;

        for (ChunkEntity chunk : chunks) {
            if (chunk == null || chunk.chunkText == null) {
                continue;
            }
            chunkCount++;

            if (queryEmbedding != null && chunk.chunkEmbedding != null) {
                float[] chunkEmbedding = EmbeddingEngine.fromBytes(
                    chunk.chunkEmbedding
                );
                if (chunkEmbedding != null) {
                    float similarity = EmbeddingEngine.cosineSimilarity(
                        queryEmbedding,
                        chunkEmbedding
                    );
                    bestChunkEmbedding = Math.max(
                        bestChunkEmbedding,
                        similarity
                    );
                    totalChunkEmbedding += similarity;
                    embeddingCount++;
                }
            }

            float lexicalScore = computeFieldScore(chunk.chunkText, queryTerms);
            bestChunkLexical = Math.max(bestChunkLexical, lexicalScore);
            if (!obviousTextMatch) {
                obviousTextMatch = hasAnyFieldMatch(queryTerms, chunk.chunkText);
            }
            if (!phraseMatch) {
                phraseMatch = hasPhraseMatch(normalizedQuery, chunk.chunkText);
            }
        }

        return new ChunkSearchSignals(
            bestChunkEmbedding,
            embeddingCount > 0 ? totalChunkEmbedding / embeddingCount : 0f,
            bestChunkLexical,
            obviousTextMatch,
            phraseMatch,
            chunkCount
        );
    }

    private String[] tokenizeQuery(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return new String[0];
        }
        return normalizedQuery.split("\\s+");
    }

    private String normalizeSearchText(String text) {
        if (text == null) {
            return "";
        }
        return text
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    }

    private float computeChunkOpportunityPenalty(int chunkCount) {
        int extraChunks = Math.max(0, chunkCount - 6);
        return 1f / (1f + (extraChunks * 0.04f));
    }

    private static class ChunkSearchSignals {

        final float embeddingScore;
        final float averageEmbeddingScore;
        final float lexicalScore;
        final boolean obviousTextMatch;
        final boolean phraseMatch;
        final int chunkCount;

        ChunkSearchSignals(
            float embeddingScore,
            float averageEmbeddingScore,
            float lexicalScore,
            boolean obviousTextMatch,
            boolean phraseMatch,
            int chunkCount
        ) {
            this.embeddingScore = embeddingScore;
            this.averageEmbeddingScore = averageEmbeddingScore;
            this.lexicalScore = lexicalScore;
            this.obviousTextMatch = obviousTextMatch;
            this.phraseMatch = phraseMatch;
            this.chunkCount = chunkCount;
        }

        static ChunkSearchSignals empty() {
            return new ChunkSearchSignals(0f, 0f, 0f, false, false, 0);
        }
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
