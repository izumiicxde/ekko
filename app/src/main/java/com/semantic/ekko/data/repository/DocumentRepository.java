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

    public void getByCategory(String category, QueryCallback<List<DocumentEntity>> callback) {
        executor.execute(() -> {
            List<DocumentEntity> result = documentDao.getByCategory(category);
            if (callback != null) callback.onResult(result);
        });
    }

    public void getByFileType(String fileType, QueryCallback<List<DocumentEntity>> callback) {
        executor.execute(() -> {
            List<DocumentEntity> result = documentDao.getByFileType(fileType);
            if (callback != null) callback.onResult(result);
        });
    }

    // =========================
    // SEMANTIC SEARCH
    // =========================

    /**
     * Performs semantic search by comparing the query embedding against
     * all stored document embeddings using cosine similarity.
     * Results are sorted descending by score and filtered by a minimum
     * threshold to remove irrelevant matches.
     */
    public void search(float[] queryEmbedding,
                       float minScore,
                       QueryCallback<List<SearchResult>> callback) {
        executor.execute(() -> {
            List<DocumentDao.DocumentEmbeddingRow> rows = documentDao.getAllEmbeddings();
            List<SearchResult> results = new ArrayList<>();

            for (DocumentDao.DocumentEmbeddingRow row : rows) {
                if (row.embedding == null) continue;
                float[] docEmbedding = EmbeddingEngine.fromBytes(row.embedding);
                float score = EmbeddingEngine.cosineSimilarity(queryEmbedding, docEmbedding);

                if (score >= minScore) {
                    DocumentEntity doc = documentDao.getById(row.id);
                    if (doc != null) {
                        results.add(new SearchResult(doc, score));
                    }
                }
            }

            Collections.sort(results, (a, b) -> Float.compare(b.getScore(), a.getScore()));

            if (callback != null) callback.onResult(results);
        });
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
