package com.semantic.ekko.data.repository;

import android.content.Context;
import android.util.Log;
import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.ChunkDao;
import com.semantic.ekko.data.db.DocumentDao;
import com.semantic.ekko.data.model.ChunkEntity;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.network.RagApiService;
import com.semantic.ekko.network.RagClient;
import com.semantic.ekko.network.RagRequest;
import com.semantic.ekko.network.RagResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import retrofit2.Call;
import retrofit2.Callback;

public class RagRepository {

    private static final String TAG = "RagRepository";
    private static final int TOP_DOCS = 2;
    private static final int CHUNKS_PER_DOC = 6;
    private static final int MIN_CHUNKS = 3;
    private static final int SCORE_TOP_N = 3;

    private final EmbeddingEngine embeddingEngine;
    private final ChunkDao chunkDao;
    private final DocumentDao documentDao;
    private final RagApiService apiService;
    private final OkHttpClient streamingClient;
    private final String baseUrl;

    // =========================
    // CALLBACKS
    // =========================

    public interface RagCallback {
        void onAnswer(String answer, String sourceDocumentName);
        void onError(String message);
    }

    public interface RagStreamCallback {
        void onToken(String token);
        void onComplete(String sourceDocumentName);
        void onError(String message);
    }

    // =========================
    // INIT
    // =========================

    public RagRepository(Context context, EmbeddingEngine embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
        AppDatabase db = AppDatabase.getInstance(context);
        this.chunkDao = db.chunkDao();
        this.documentDao = db.documentDao();
        this.apiService = RagClient.getInstance();
        this.streamingClient = RagClient.getStreamingClient();
        this.baseUrl = RagClient.getBaseUrl();
    }

    // =========================
    // SELECTION
    // =========================

    private static class ScoredChunk {

        final ChunkEntity chunk;
        final float score;

        ScoredChunk(ChunkEntity chunk, float score) {
            this.chunk = chunk;
            this.score = score;
        }
    }

    private static class ScoredDoc {

        final long docId;
        final String docName;
        final float score;
        final List<ScoredChunk> scoredChunks;

        ScoredDoc(
            long docId,
            String docName,
            float score,
            List<ScoredChunk> scoredChunks
        ) {
            this.docId = docId;
            this.docName = docName;
            this.score = score;
            this.scoredChunks = scoredChunks;
        }
    }

    private static class SelectionResult {

        final List<String> chunks;
        final String sourceDocName;

        SelectionResult(List<String> chunks, String sourceDocName) {
            this.chunks = chunks;
            this.sourceDocName = sourceDocName;
        }
    }

    /**
     * Two-stage retrieval using chunk-based document scoring:
     *
     * Stage 1 - Document ranking:
     *   Score each document by the average cosine similarity of its top
     *   SCORE_TOP_N chunks against the query. Skip documents with fewer
     *   than MIN_CHUNKS chunks.
     *
     * Stage 2 - Chunk retrieval:
     *   From top documents, always include opening chunks (index 0, 1)
     *   plus top scored chunks up to CHUNKS_PER_DOC.
     *
     * Source label shows all contributing document names since context
     * from multiple documents is sent to the backend.
     */
    private SelectionResult selectChunks(float[] queryEmbedding) {
        List<ChunkEntity> allChunks = chunkDao.getAll();
        if (allChunks.isEmpty()) return null;

        // Group chunks by document
        Map<Long, List<ChunkEntity>> chunksByDoc = new HashMap<>();
        for (ChunkEntity chunk : allChunks) {
            if (!chunksByDoc.containsKey(chunk.documentId)) {
                chunksByDoc.put(chunk.documentId, new ArrayList<>());
            }
            chunksByDoc.get(chunk.documentId).add(chunk);
        }

        // Load document names
        List<DocumentEntity> allDocs = documentDao.getAll();
        Map<Long, String> docNames = new HashMap<>();
        for (DocumentEntity doc : allDocs) {
            docNames.put(doc.id, doc.name);
        }

        // Score each document by average of top SCORE_TOP_N chunk scores
        List<ScoredDoc> scoredDocs = new ArrayList<>();

        for (Map.Entry<
            Long,
            List<ChunkEntity>
        > entry : chunksByDoc.entrySet()) {
            long docId = entry.getKey();
            List<ChunkEntity> docChunks = entry.getValue();
            String docName = docNames.getOrDefault(docId, "Unknown");

            if (docChunks.size() < MIN_CHUNKS) {
                Log.d(
                    TAG,
                    "Skipping " +
                        docName +
                        " (only " +
                        docChunks.size() +
                        " chunks)"
                );
                continue;
            }

            List<ScoredChunk> scoredChunks = new ArrayList<>();
            for (ChunkEntity chunk : docChunks) {
                if (
                    chunk.chunkEmbedding == null || chunk.chunkText == null
                ) continue;
                float[] chunkEmb = EmbeddingEngine.fromBytes(
                    chunk.chunkEmbedding
                );
                float score = EmbeddingEngine.cosineSimilarity(
                    queryEmbedding,
                    chunkEmb
                );
                scoredChunks.add(new ScoredChunk(chunk, score));
            }

            if (scoredChunks.isEmpty()) continue;

            Collections.sort(scoredChunks, (a, b) ->
                Float.compare(b.score, a.score)
            );

            int n = Math.min(SCORE_TOP_N, scoredChunks.size());
            float scoreSum = 0f;
            for (int i = 0; i < n; i++) scoreSum += scoredChunks.get(i).score;
            float docScore = scoreSum / n;

            scoredDocs.add(
                new ScoredDoc(docId, docName, docScore, scoredChunks)
            );
        }

        if (scoredDocs.isEmpty()) return null;

        Collections.sort(scoredDocs, (a, b) -> Float.compare(b.score, a.score));

        int docLimit = Math.min(TOP_DOCS, scoredDocs.size());

        // Build combined source label from all contributing documents
        StringBuilder sourceNames = new StringBuilder();
        StringBuilder docLog = new StringBuilder("Top docs: ");
        for (int i = 0; i < docLimit; i++) {
            ScoredDoc sd = scoredDocs.get(i);
            if (i > 0) {
                sourceNames.append(", ");
                docLog.append(", ");
            }
            sourceNames.append(sd.docName);
            docLog
                .append(sd.docName)
                .append(" (score=")
                .append(String.format("%.3f", sd.score))
                .append(", chunks=")
                .append(sd.scoredChunks.size())
                .append(")");
        }
        Log.d(TAG, docLog.toString());

        // Stage 2: pick chunks from each top document
        List<String> selectedChunks = new ArrayList<>();

        for (int d = 0; d < docLimit; d++) {
            ScoredDoc topDoc = scoredDocs.get(d);
            List<ScoredChunk> scoredChunks = topDoc.scoredChunks; // already sorted

            ScoredChunk firstChunk = null;
            ScoredChunk secondChunk = null;
            for (ScoredChunk sc : scoredChunks) {
                if (sc.chunk.chunkIndex == 0) firstChunk = sc;
                if (sc.chunk.chunkIndex == 1) secondChunk = sc;
                if (firstChunk != null && secondChunk != null) break;
            }

            List<ScoredChunk> selected = new ArrayList<>();
            if (firstChunk != null) selected.add(firstChunk);
            if (secondChunk != null) selected.add(secondChunk);

            int added = selected.size();
            for (ScoredChunk sc : scoredChunks) {
                if (added >= CHUNKS_PER_DOC) break;
                boolean alreadyAdded =
                    sc.chunk.chunkIndex == 0 || sc.chunk.chunkIndex == 1;
                if (!alreadyAdded) {
                    selected.add(sc);
                    added++;
                }
            }

            for (ScoredChunk sc : selected) {
                selectedChunks.add(sc.chunk.chunkText);
            }

            Log.d(
                TAG,
                topDoc.docName + ": picked " + selected.size() + " chunks"
            );
        }

        if (selectedChunks.isEmpty()) return null;

        Log.d(TAG, "Total chunks sent to backend: " + selectedChunks.size());
        return new SelectionResult(selectedChunks, sourceNames.toString());
    }

    // =========================
    // STREAMING QUERY (raw OkHttp)
    // =========================

    public void queryStream(String question, RagStreamCallback callback) {
        if (question == null || question.trim().isEmpty()) {
            callback.onError("Question cannot be empty.");
            return;
        }

        new Thread(() -> {
            try {
                float[] queryEmbedding = embeddingEngine.embed(question.trim());
                if (queryEmbedding == null) {
                    callback.onError("Could not process your question.");
                    return;
                }

                SelectionResult selection = selectChunks(queryEmbedding);
                if (selection == null) {
                    callback.onError(
                        "No indexed content found. Add documents and index them first."
                    );
                    return;
                }

                JSONObject body = new JSONObject();
                body.put("question", question.trim());
                body.put("document_name", selection.sourceDocName);
                JSONArray chunksArray = new JSONArray();
                for (String chunk : selection.chunks) chunksArray.put(chunk);
                body.put("chunks", chunksArray);

                RequestBody requestBody = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    body.toString()
                );

                Request request = new Request.Builder()
                    .url(baseUrl + "rag/stream")
                    .post(requestBody)
                    .build();

                try (
                    Response response = streamingClient
                        .newCall(request)
                        .execute()
                ) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError(
                            "Backend returned an error. Is the server running?"
                        );
                        return;
                    }

                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream())
                    );

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        try {
                            JSONObject json = new JSONObject(line);
                            if (json.has("token")) {
                                callback.onToken(json.getString("token"));
                            } else if (
                                json.has("done") && json.getBoolean("done")
                            ) {
                                callback.onComplete(selection.sourceDocName);
                                break;
                            } else if (json.has("error")) {
                                callback.onError(json.getString("error"));
                                break;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to parse line: " + line);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Stream query failed: " + e.getMessage(), e);
                callback.onError(
                    "Could not reach the Q&A server. Make sure it is running on port 8000."
                );
            }
        })
            .start();
    }

    // =========================
    // NON-STREAMING QUERY (for enhanced summary in DetailViewModel)
    // =========================

    public void query(String question, RagCallback callback) {
        if (question == null || question.trim().isEmpty()) {
            callback.onError("Question cannot be empty.");
            return;
        }

        new Thread(() -> {
            try {
                float[] queryEmbedding = embeddingEngine.embed(question.trim());
                if (queryEmbedding == null) {
                    callback.onError("Could not process your question.");
                    return;
                }

                SelectionResult selection = selectChunks(queryEmbedding);
                if (selection == null) {
                    callback.onError(
                        "No indexed content found. Index some documents first."
                    );
                    return;
                }

                RagRequest ragRequest = new RagRequest(
                    question.trim(),
                    selection.chunks,
                    selection.sourceDocName
                );
                final String sourceDocName = selection.sourceDocName;

                apiService
                    .ask(ragRequest)
                    .enqueue(
                        new Callback<RagResponse>() {
                            @Override
                            public void onResponse(
                                Call<RagResponse> call,
                                retrofit2.Response<RagResponse> response
                            ) {
                                if (
                                    response.isSuccessful() &&
                                    response.body() != null
                                ) {
                                    String answer = response.body().answer;
                                    if (
                                        answer == null ||
                                        answer.trim().isEmpty()
                                    ) {
                                        callback.onError(
                                            "The model returned an empty answer."
                                        );
                                    } else {
                                        callback.onAnswer(
                                            answer.trim(),
                                            sourceDocName
                                        );
                                    }
                                } else {
                                    callback.onError(
                                        "Backend returned an error."
                                    );
                                }
                            }

                            @Override
                            public void onFailure(
                                Call<RagResponse> call,
                                Throwable t
                            ) {
                                Log.e(
                                    TAG,
                                    "Network failure: " + t.getMessage()
                                );
                                callback.onError(
                                    "Could not reach the Q&A server."
                                );
                            }
                        }
                    );
            } catch (Exception e) {
                Log.e(TAG, "Query failed: " + e.getMessage(), e);
                callback.onError("Something went wrong. Please try again.");
            }
        })
            .start();
    }
}
