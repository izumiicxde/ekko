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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentRepository {

    private static final int MAX_SEARCH_RESULTS = 5;
    private static final int MAX_SEMANTIC_FALLBACK_RESULTS = 3;
    private static final float MIN_SEMANTIC_FALLBACK_SCORE = 0.18f;
    private static final float MIN_SEMANTIC_EMBEDDING_SCORE = 0.42f;
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

    public void deleteByFolderId(long folderId, Runnable onComplete) {
        executor.execute(() -> {
            documentDao.deleteByFolderId(folderId);
            if (onComplete != null) onComplete.run();
        });
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

    public void getGraphRows(
        QueryCallback<List<DocumentDao.DocumentEmbeddingRow>> callback
    ) {
        executor.execute(() -> {
            List<DocumentDao.DocumentEmbeddingRow> result =
                documentDao.getAllGraphRows();
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
            Map<Long, List<ChunkEntity>> chunksByDocumentId = groupChunksByDocumentId(
                chunkDao.getAll()
            );
            List<RankedSearchResult> rankedResults = new ArrayList<>();

            String normalizedQuery = SearchTextMatcher.normalize(rawQuery);
            String[] queryTerms = SearchTextMatcher.tokenize(normalizedQuery);

            for (DocumentDao.DocumentEmbeddingRow row : rows) {
                SearchTextMatcher.Signals metadataSignals = SearchTextMatcher.analyze(
                    normalizedQuery,
                    queryTerms,
                    row.name,
                    row.relative_path,
                    row.keywords
                );
                SearchTextMatcher.Signals contentSignals = SearchTextMatcher.analyze(
                    normalizedQuery,
                    queryTerms,
                    row.summary,
                    row.raw_text
                );
                boolean obviousTextMatch =
                    metadataSignals.hasAnyMatch() || contentSignals.hasAnyMatch();
                boolean exactPhraseMatch =
                    metadataSignals.phraseMatch || contentSignals.phraseMatch;
                float metadataCoverage = metadataSignals.coverageScore();
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

                float keywordScore = SearchTextMatcher.fieldScore(
                    row.keywords,
                    queryTerms
                );
                float summaryScore = SearchTextMatcher.fieldScore(
                    row.summary,
                    queryTerms
                );
                float fullTextScore = SearchTextMatcher.fieldScore(
                    row.raw_text,
                    queryTerms
                );
                float filenameScore = Math.max(
                    SearchTextMatcher.fieldScore(row.name, queryTerms),
                    SearchTextMatcher.fieldScore(row.relative_path, queryTerms)
                );
                float phraseScore = exactPhraseMatch ? 1f : 0f;
                ChunkSearchSignals chunkSignals = computeChunkSignals(
                    chunksByDocumentId.get(row.id),
                    queryEmbedding,
                    normalizedQuery,
                    queryTerms
                );
                float chunkOpportunityPenalty = computeChunkOpportunityPenalty(
                    chunkSignals.chunkCount
                );
                float contentBreadthPenalty = computeContentBreadthPenalty(
                    row.word_count
                );
                float lexicalCoverage = Math.max(
                    metadataCoverage,
                    Math.max(
                        contentSignals.coverageScore(),
                        chunkSignals.coverageScore
                    )
                );
                float semanticCoverageMultiplier =
                    computeSemanticCoverageMultiplier(queryTerms, lexicalCoverage);

                float finalScore =
                    semanticCoverageMultiplier *
                    (
                        0.24f * embeddingScore +
                        0.18f * (chunkSignals.embeddingScore * chunkOpportunityPenalty) +
                        0.08f * (chunkSignals.averageEmbeddingScore * chunkOpportunityPenalty) +
                        0.14f * keywordScore +
                        0.10f * summaryScore +
                        0.14f * (fullTextScore * contentBreadthPenalty) +
                        0.14f * filenameScore +
                        0.10f * phraseScore
                    );

                finalScore = Math.max(
                    finalScore,
                    0.18f *
                    (
                        chunkSignals.lexicalScore *
                        chunkOpportunityPenalty *
                        contentBreadthPenalty
                    )
                );
                finalScore = Math.max(
                    finalScore,
                    0.16f * lexicalCoverage
                );

                boolean strongLexicalMatch =
                    metadataSignals.hasStrongMatch() ||
                    contentSignals.hasStrongMatch() ||
                    chunkSignals.hasStrongMatch;

                if (strongLexicalMatch) {
                    finalScore = Math.max(
                        finalScore,
                        exactPhraseMatch || chunkSignals.phraseMatch
                            ? 0.34f
                            : 0.22f
                    );
                }

                if (metadataSignals.phraseMatch) {
                    finalScore = Math.max(finalScore, 0.58f);
                } else if (metadataCoverage >= 1f) {
                    finalScore = Math.max(finalScore, 0.46f);
                } else if (filenameScore >= 0.5f) {
                    finalScore = Math.max(finalScore, 0.3f);
                }

                if (
                    finalScore >= minScore ||
                    obviousTextMatch ||
                    chunkSignals.obviousTextMatch
                ) {
                    DocumentEntity doc = documentDao.getById(row.id);
                    if (doc != null) {
                        int lexicalTier = computeLexicalTier(
                            queryTerms.length,
                            metadataSignals,
                            contentSignals,
                            chunkSignals
                        );
                        rankedResults.add(
                            new RankedSearchResult(
                                doc,
                                finalScore,
                                lexicalTier,
                                lexicalCoverage,
                                metadataCoverage,
                                filenameScore,
                                metadataSignals.phraseMatch ? 1f : 0f,
                                phraseScore,
                                embeddingScore,
                                chunkSignals.embeddingScore
                            )
                        );
                    }
                }
            }

            Collections.sort(rankedResults, DocumentRepository::compareRankedResults);

            List<RankedSearchResult> filteredResults = filterRankedResults(
                rankedResults
            );
            List<SearchResult> results = new ArrayList<>();
            for (RankedSearchResult ranked : filteredResults) {
                results.add(new SearchResult(ranked.document, ranked.score));
            }

            if (callback != null) callback.onResult(results);
        });
    }

    static int computeLexicalTier(
        int queryTermCount,
        SearchTextMatcher.Signals metadataSignals,
        SearchTextMatcher.Signals contentSignals,
        ChunkSearchSignals chunkSignals
    ) {
        boolean exactPhrase =
            (metadataSignals != null && metadataSignals.phraseMatch) ||
            (contentSignals != null && contentSignals.phraseMatch) ||
            (chunkSignals != null && chunkSignals.phraseMatch);
        float metadataCoverage =
            metadataSignals != null ? metadataSignals.coverageScore() : 0f;
        float contentCoverage =
            contentSignals != null ? contentSignals.coverageScore() : 0f;
        float chunkCoverage = chunkSignals != null ? chunkSignals.coverageScore : 0f;
        float bestCoverage = Math.max(
            metadataCoverage,
            Math.max(contentCoverage, chunkCoverage)
        );
        boolean strongMatch =
            (metadataSignals != null && metadataSignals.hasStrongMatch()) ||
            (contentSignals != null && contentSignals.hasStrongMatch()) ||
            (chunkSignals != null && chunkSignals.hasStrongMatch);
        boolean anyMatch =
            (metadataSignals != null && metadataSignals.hasAnyMatch()) ||
            (contentSignals != null && contentSignals.hasAnyMatch()) ||
            (chunkSignals != null && chunkSignals.obviousTextMatch);

        if (exactPhrase || metadataCoverage >= 1f) {
            return 3;
        }
        if (queryTermCount > 1 && bestCoverage >= 1f) {
            return 2;
        }
        if (strongMatch) {
            return 1;
        }
        return anyMatch ? 0 : -1;
    }

    static List<RankedSearchResult> filterRankedResults(
        List<RankedSearchResult> rankedResults
    ) {
        List<RankedSearchResult> filtered = new ArrayList<>();
        if (rankedResults == null || rankedResults.isEmpty()) {
            return filtered;
        }

        RankedSearchResult bestResult = rankedResults.get(0);
        if (bestResult == null) {
            return filtered;
        }

        if (bestResult.lexicalTier < 1) {
            return filterSemanticFallbackResults(rankedResults);
        }

        int bestTier = bestResult.lexicalTier;
        for (RankedSearchResult ranked : rankedResults) {
            if (ranked == null) {
                continue;
            }
            if (ranked.lexicalTier != bestTier) {
                continue;
            }
            filtered.add(ranked);
            if (filtered.size() >= MAX_SEARCH_RESULTS) {
                break;
            }
        }
        return filtered;
    }

    static List<RankedSearchResult> filterSemanticFallbackResults(
        List<RankedSearchResult> rankedResults
    ) {
        List<RankedSearchResult> filtered = new ArrayList<>();
        if (rankedResults == null || rankedResults.isEmpty()) {
            return filtered;
        }

        float bestScore = rankedResults.get(0).score;
        for (RankedSearchResult ranked : rankedResults) {
            if (ranked == null) {
                continue;
            }
            if (ranked.score < MIN_SEMANTIC_FALLBACK_SCORE) {
                continue;
            }
            float strongestEmbedding = Math.max(
                ranked.docEmbedding,
                ranked.bestChunkEmbedding
            );
            if (strongestEmbedding < MIN_SEMANTIC_EMBEDDING_SCORE) {
                continue;
            }
            if (ranked.score < (bestScore * 0.72f)) {
                continue;
            }
            filtered.add(ranked);
            if (filtered.size() >= MAX_SEMANTIC_FALLBACK_RESULTS) {
                break;
            }
        }
        return filtered;
    }

    static int compareRankedResults(
        RankedSearchResult a,
        RankedSearchResult b
    ) {
        int tierCompare = Integer.compare(b.lexicalTier, a.lexicalTier);
        if (tierCompare != 0) return tierCompare;

        int metadataPhraseCompare = Float.compare(
            b.metadataPhraseScore,
            a.metadataPhraseScore
        );
        if (metadataPhraseCompare != 0) return metadataPhraseCompare;

        int metadataCoverageCompare = Float.compare(
            b.metadataCoverage,
            a.metadataCoverage
        );
        if (metadataCoverageCompare != 0) return metadataCoverageCompare;

        int phraseCompare = Float.compare(b.phraseScore, a.phraseScore);
        if (phraseCompare != 0) return phraseCompare;

        int filenameCompare = Float.compare(
            b.filenameScore,
            a.filenameScore
        );
        if (filenameCompare != 0) return filenameCompare;

        int coverageCompare = Float.compare(
            b.lexicalCoverage,
            a.lexicalCoverage
        );
        if (coverageCompare != 0) return coverageCompare;

        int finalScoreCompare = Float.compare(b.score, a.score);
        if (finalScoreCompare != 0) return finalScoreCompare;

        int chunkEmbeddingCompare = Float.compare(
            b.bestChunkEmbedding,
            a.bestChunkEmbedding
        );
        if (chunkEmbeddingCompare != 0) return chunkEmbeddingCompare;

        return Float.compare(b.docEmbedding, a.docEmbedding);
    }

    private ChunkSearchSignals computeChunkSignals(
        List<ChunkEntity> chunks,
        float[] queryEmbedding,
        String normalizedQuery,
        String[] queryTerms
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return ChunkSearchSignals.empty();
        }

        float bestChunkEmbedding = 0f;
        float totalChunkEmbedding = 0f;
        int embeddingCount = 0;
        float bestChunkLexical = 0f;
        float bestCoverage = 0f;
        boolean obviousTextMatch = false;
        boolean phraseMatch = false;
        boolean strongMatch = false;
        int chunkCount = 0;

        for (ChunkEntity chunk : chunks) {
            if (chunk == null || chunk.chunkText == null) {
                continue;
            }
            chunkCount++;
            SearchTextMatcher.Signals chunkTextSignals = SearchTextMatcher.analyze(
                normalizedQuery,
                queryTerms,
                chunk.chunkText
            );

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

            float lexicalScore = SearchTextMatcher.fieldScore(
                chunk.chunkText,
                queryTerms
            );
            bestChunkLexical = Math.max(bestChunkLexical, lexicalScore);
            bestCoverage = Math.max(bestCoverage, chunkTextSignals.coverageScore());
            obviousTextMatch = obviousTextMatch || chunkTextSignals.hasAnyMatch();
            phraseMatch = phraseMatch || chunkTextSignals.phraseMatch;
            strongMatch = strongMatch || chunkTextSignals.hasStrongMatch();
        }

        return new ChunkSearchSignals(
            bestChunkEmbedding,
            embeddingCount > 0 ? totalChunkEmbedding / embeddingCount : 0f,
            bestChunkLexical,
            obviousTextMatch,
            phraseMatch,
            bestCoverage,
            strongMatch,
            chunkCount
        );
    }

    private Map<Long, List<ChunkEntity>> groupChunksByDocumentId(
        List<ChunkEntity> chunks
    ) {
        Map<Long, List<ChunkEntity>> grouped = new HashMap<>();
        if (chunks == null || chunks.isEmpty()) {
            return grouped;
        }
        for (ChunkEntity chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            List<ChunkEntity> bucket = grouped.get(chunk.documentId);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(chunk.documentId, bucket);
            }
            bucket.add(chunk);
        }
        return grouped;
    }

    private float computeChunkOpportunityPenalty(int chunkCount) {
        int extraChunks = Math.max(0, chunkCount - 6);
        return 1f / (1f + (extraChunks * 0.04f));
    }

    private float computeContentBreadthPenalty(int wordCount) {
        if (wordCount <= 0) {
            return 1f;
        }
        if (wordCount <= 1600) {
            return 1f;
        }
        float overage = (wordCount - 1600) / 2400f;
        return Math.max(0.52f, 1f / (1f + overage));
    }

    private float computeSemanticCoverageMultiplier(
        String[] queryTerms,
        float lexicalCoverage
    ) {
        if (queryTerms == null || queryTerms.length <= 1) {
            return 1f;
        }
        return 0.35f + (0.65f * lexicalCoverage);
    }

    static class ChunkSearchSignals {

        final float embeddingScore;
        final float averageEmbeddingScore;
        final float lexicalScore;
        final boolean obviousTextMatch;
        final boolean phraseMatch;
        final float coverageScore;
        final boolean hasStrongMatch;
        final int chunkCount;

        ChunkSearchSignals(
            float embeddingScore,
            float averageEmbeddingScore,
            float lexicalScore,
            boolean obviousTextMatch,
            boolean phraseMatch,
            float coverageScore,
            boolean hasStrongMatch,
            int chunkCount
        ) {
            this.embeddingScore = embeddingScore;
            this.averageEmbeddingScore = averageEmbeddingScore;
            this.lexicalScore = lexicalScore;
            this.obviousTextMatch = obviousTextMatch;
            this.phraseMatch = phraseMatch;
            this.coverageScore = coverageScore;
            this.hasStrongMatch = hasStrongMatch;
            this.chunkCount = chunkCount;
        }

        static ChunkSearchSignals empty() {
            return new ChunkSearchSignals(0f, 0f, 0f, false, false, 0f, false, 0);
        }
    }

    static class RankedSearchResult {

        final DocumentEntity document;
        final float score;
        final int lexicalTier;
        final float lexicalCoverage;
        final float metadataCoverage;
        final float filenameScore;
        final float metadataPhraseScore;
        final float phraseScore;
        final float docEmbedding;
        final float bestChunkEmbedding;

        RankedSearchResult(
            DocumentEntity document,
            float score,
            int lexicalTier,
            float lexicalCoverage,
            float metadataCoverage,
            float filenameScore,
            float metadataPhraseScore,
            float phraseScore,
            float docEmbedding,
            float bestChunkEmbedding
        ) {
            this.document = document;
            this.score = score;
            this.lexicalTier = lexicalTier;
            this.lexicalCoverage = lexicalCoverage;
            this.metadataCoverage = metadataCoverage;
            this.filenameScore = filenameScore;
            this.metadataPhraseScore = metadataPhraseScore;
            this.phraseScore = phraseScore;
            this.docEmbedding = docEmbedding;
            this.bestChunkEmbedding = bestChunkEmbedding;
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
