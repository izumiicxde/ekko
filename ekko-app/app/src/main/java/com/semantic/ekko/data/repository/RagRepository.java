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
import java.util.List;
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

    // Bonus added to a document's score when its filename contains
    // a query term. Large enough to override embedding score differences
    // but not so large that it completely ignores semantic relevance.
    private static final float FILENAME_MATCH_BONUS = 0.5f;

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

    private static class ScoredDoc {

        final DocumentEntity doc;
        final float score;

        ScoredDoc(DocumentEntity doc, float score) {
            this.doc = doc;
            this.score = score;
        }
    }

    private static class ScoredChunk {

        final ChunkEntity chunk;
        final float score;

        ScoredChunk(ChunkEntity chunk, float score) {
            this.chunk = chunk;
            this.score = score;
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
     * Returns a filename match bonus for a document based on query terms.
     *
     * Strips the file extension and splits the filename on common separators
     * (spaces, hyphens, underscores, dots) then checks if any query term of
     * length >= 3 appears in the filename tokens.
     *
     * Example: query "what is in the maths file"
     *   doc name "basic_maths.pdf" -> tokens ["basic", "maths"] -> "maths" matches -> bonus
     *   doc name "Java-Programming-Language-Handbook.pdf" -> no match -> no bonus
     */
    private float filenameBonus(String docName, String[] queryTerms) {
        if (docName == null || queryTerms.length == 0) return 0f;

        // Strip extension and normalize
        String baseName = docName.replaceAll("\\.[^.]+$", "").toLowerCase();
        String[] fileTokens = baseName.split("[\\s\\-_\\.]+");

        for (String term : queryTerms) {
            if (term.length() < 3) continue;
            // Check if query term appears in filename or vice versa
            for (String token : fileTokens) {
                if (token.length() < 3) continue;
                if (token.contains(term) || term.contains(token)) {
                    return FILENAME_MATCH_BONUS;
                }
            }
        }
        return 0f;
    }

    /**
     * Two-stage retrieval with filename-aware document ranking:
     *
     * Stage 1 - Document ranking:
     *   Score = cosine_similarity(query_embedding, doc_embedding) + filename_bonus
     *   The filename bonus ensures that queries mentioning a document name
     *   (e.g. "what is in the maths file") correctly retrieve that document
     *   even if its embedding similarity is lower than another document.
     *
     * Stage 2 - Chunk retrieval per document:
     *   Always include the first 2 chunks (intro/definition sections) plus
     *   top scored chunks up to CHUNKS_PER_DOC.
     */
    private SelectionResult selectChunks(
        float[] queryEmbedding,
        String rawQuery
    ) {
        List<DocumentEntity> allDocs = documentDao.getAll();
        if (allDocs.isEmpty()) return null;

        String[] queryTerms =
            rawQuery != null
                ? rawQuery.toLowerCase().trim().split("\\s+")
                : new String[0];

        List<ScoredDoc> scoredDocs = new ArrayList<>();
        for (DocumentEntity doc : allDocs) {
            if (doc.embedding == null) continue;
            float[] docEmb = EmbeddingEngine.fromBytes(doc.embedding);
            float embScore = EmbeddingEngine.cosineSimilarity(
                queryEmbedding,
                docEmb
            );
            float bonus = filenameBonus(doc.name, queryTerms);
            float total = embScore + bonus;
            scoredDocs.add(new ScoredDoc(doc, total));
        }

        if (scoredDocs.isEmpty()) return null;

        Collections.sort(scoredDocs, (a, b) -> Float.compare(b.score, a.score));

        int docLimit = Math.min(TOP_DOCS, scoredDocs.size());
        String primaryDocName = scoredDocs.get(0).doc.name;

        StringBuilder docLog = new StringBuilder("Top docs: ");
        for (int i = 0; i < docLimit; i++) {
            docLog
                .append(scoredDocs.get(i).doc.name)
                .append(" (")
                .append(String.format("%.3f", scoredDocs.get(i).score))
                .append(")");
            if (i < docLimit - 1) docLog.append(", ");
        }
        Log.d(TAG, docLog.toString());

        List<String> selectedChunks = new ArrayList<>();

        for (int d = 0; d < docLimit; d++) {
            long docId = scoredDocs.get(d).doc.id;
            List<ChunkEntity> docChunks = chunkDao.getByDocumentId(docId);

            if (docChunks.isEmpty()) {
                Log.w(
                    TAG,
                    scoredDocs.get(d).doc.name +
                        " has no chunks - needs re-indexing"
                );
                continue;
            }

            List<ScoredChunk> scoredChunks = new ArrayList<>();
            ScoredChunk firstChunk = null;
            ScoredChunk secondChunk = null;

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
                ScoredChunk sc = new ScoredChunk(chunk, score);
                scoredChunks.add(sc);
                if (chunk.chunkIndex == 0) firstChunk = sc;
                if (chunk.chunkIndex == 1) secondChunk = sc;
            }

            Collections.sort(scoredChunks, (a, b) ->
                Float.compare(b.score, a.score)
            );

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
                scoredDocs.get(d).doc.name +
                    ": picked " +
                    selected.size() +
                    " chunks"
            );
        }

        if (selectedChunks.isEmpty()) return null;

        Log.d(TAG, "Total chunks sent to backend: " + selectedChunks.size());
        return new SelectionResult(selectedChunks, primaryDocName);
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

                SelectionResult selection = selectChunks(
                    queryEmbedding,
                    question
                );
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

                SelectionResult selection = selectChunks(
                    queryEmbedding,
                    question
                );
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
