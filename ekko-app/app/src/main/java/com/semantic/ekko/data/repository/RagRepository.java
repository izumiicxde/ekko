package com.semantic.ekko.data.repository;

import android.util.Log;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.network.RagApiService;
import com.semantic.ekko.network.RagClient;
import com.semantic.ekko.network.RagRequest;
import com.semantic.ekko.network.RagResponse;
import com.semantic.ekko.processing.ChunkUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Handles RAG (retrieval-augmented generation) queries against a single document.
 *
 * Flow:
 *   1. Deserialize stored chunks from the document.
 *   2. Embed each chunk using the on-device EmbeddingEngine.
 *   3. Rank chunks by cosine similarity against the query embedding.
 *   4. Send the top-k chunks and the question to the FastAPI backend.
 *   5. Return the generated answer via callback.
 */
public class RagRepository {

    private static final String TAG = "RagRepository";
    private static final int TOP_K = 5;

    private final EmbeddingEngine embeddingEngine;
    private final RagApiService apiService;

    public interface RagCallback {
        void onAnswer(String answer);
        void onError(String message);
    }

    public RagRepository(EmbeddingEngine embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
        this.apiService = RagClient.getInstance();
    }

    // =========================
    // QUERY
    // =========================

    public void query(
        String question,
        DocumentEntity document,
        RagCallback callback
    ) {
        if (question == null || question.trim().isEmpty()) {
            callback.onError("Question cannot be empty.");
            return;
        }

        if (document.chunks == null || document.chunks.isEmpty()) {
            callback.onError(
                "This document was indexed before RAG support was added. " +
                    "Please re-index it to enable Q&A."
            );
            return;
        }

        List<String> allChunks = ChunkUtils.fromJson(document.chunks);
        if (allChunks.isEmpty()) {
            callback.onError("No content chunks found for this document.");
            return;
        }

        // Embed the query
        float[] queryEmbedding = embeddingEngine.embed(question.trim());
        if (queryEmbedding == null) {
            callback.onError("Could not process your question.");
            return;
        }

        // Rank chunks by cosine similarity and pick top-k
        List<String> topChunks = selectTopChunks(queryEmbedding, allChunks);
        Log.d(
            TAG,
            "Selected " + topChunks.size() + " chunks from " + allChunks.size()
        );

        // Call backend
        RagRequest request = new RagRequest(
            question.trim(),
            topChunks,
            document.name
        );
        apiService
            .ask(request)
            .enqueue(
                new Callback<RagResponse>() {
                    @Override
                    public void onResponse(
                        Call<RagResponse> call,
                        Response<RagResponse> response
                    ) {
                        if (
                            response.isSuccessful() && response.body() != null
                        ) {
                            String answer = response.body().answer;
                            if (answer == null || answer.trim().isEmpty()) {
                                callback.onError(
                                    "The model returned an empty answer."
                                );
                            } else {
                                callback.onAnswer(answer.trim());
                            }
                        } else {
                            Log.e(
                                TAG,
                                "Backend error: HTTP " + response.code()
                            );
                            callback.onError(
                                "Backend returned an error. Is the server running?"
                            );
                        }
                    }

                    @Override
                    public void onFailure(Call<RagResponse> call, Throwable t) {
                        Log.e(TAG, "Network failure: " + t.getMessage());
                        callback.onError(
                            "Could not reach the RAG backend. " +
                                "Make sure the server is running on port 8000."
                        );
                    }
                }
            );
    }

    // =========================
    // CHUNK SELECTION
    // =========================

    private List<String> selectTopChunks(
        float[] queryEmbedding,
        List<String> chunks
    ) {
        List<ScoredChunk> scored = new ArrayList<>();

        for (String chunk : chunks) {
            float[] chunkEmbedding = embeddingEngine.embed(chunk);
            float score =
                chunkEmbedding != null
                    ? EmbeddingEngine.cosineSimilarity(
                          queryEmbedding,
                          chunkEmbedding
                      )
                    : 0f;
            scored.add(new ScoredChunk(chunk, score));
        }

        Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));

        List<String> result = new ArrayList<>();
        int limit = Math.min(TOP_K, scored.size());
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i).text);
        }
        return result;
    }

    private static class ScoredChunk {

        final String text;
        final float score;

        ScoredChunk(String text, float score) {
            this.text = text;
            this.score = score;
        }
    }
}
