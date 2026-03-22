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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class RagRepository {

    private static final String TAG   = "RagRepository";
    private static final int    TOP_K = 5;

    private final EmbeddingEngine embeddingEngine;
    private final ChunkDao        chunkDao;
    private final DocumentDao     documentDao;
    private final RagApiService   apiService;

    public interface RagCallback {
        void onAnswer(String answer, String sourceDocumentName);
        void onError(String message);
    }

    public RagRepository(Context context, EmbeddingEngine embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
        AppDatabase db = AppDatabase.getInstance(context);
        this.chunkDao    = db.chunkDao();
        this.documentDao = db.documentDao();
        this.apiService  = RagClient.getInstance();
    }

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

                List<ChunkEntity> allChunks = chunkDao.getAll();
                if (allChunks.isEmpty()) {
                    callback.onError(
                        "No indexed content found. Add and index some documents first."
                    );
                    return;
                }

                List<ScoredChunk> scored = new ArrayList<>();
                for (ChunkEntity chunk : allChunks) {
                    if (chunk.chunkEmbedding == null) continue;
                    float[] chunkEmbedding = EmbeddingEngine.fromBytes(chunk.chunkEmbedding);
                    float score = EmbeddingEngine.cosineSimilarity(queryEmbedding, chunkEmbedding);
                    scored.add(new ScoredChunk(chunk, score));
                }

                if (scored.isEmpty()) {
                    callback.onError("No chunk embeddings found. Please re-index your documents.");
                    return;
                }

                Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));

                int limit = Math.min(TOP_K, scored.size());
                List<String> topChunkTexts = new ArrayList<>();
                long sourceDocId = scored.get(0).chunk.documentId;

                for (int i = 0; i < limit; i++) {
                    topChunkTexts.add(scored.get(i).chunk.chunkText);
                }

                Log.d(TAG, "Selected " + topChunkTexts.size() + " chunks from " + allChunks.size());

                String sourceDocName = "";
                DocumentEntity sourceDoc = documentDao.getById(sourceDocId);
                if (sourceDoc != null) sourceDocName = sourceDoc.name;

                RagRequest request = new RagRequest(question.trim(), topChunkTexts, sourceDocName);
                final String finalSourceDocName = sourceDocName;

                apiService.ask(request).enqueue(new Callback<RagResponse>() {
                    @Override
                    public void onResponse(Call<RagResponse> call, Response<RagResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            String answer = response.body().answer;
                            if (answer == null || answer.trim().isEmpty()) {
                                callback.onError("The model returned an empty answer.");
                            } else {
                                callback.onAnswer(answer.trim(), finalSourceDocName);
                            }
                        } else {
                            Log.e(TAG, "Backend error: HTTP " + response.code());
                            callback.onError("Backend returned an error. Is the server running?");
                        }
                    }

                    @Override
                    public void onFailure(Call<RagResponse> call, Throwable t) {
                        Log.e(TAG, "Network failure: " + t.getMessage());
                        callback.onError(
                            "Could not reach the Q&A server. Make sure it is running on port 8000."
                        );
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Query failed: " + e.getMessage(), e);
                callback.onError("Something went wrong. Please try again.");
            }
        }).start();
    }

    private static class ScoredChunk {
        final ChunkEntity chunk;
        final float       score;
        ScoredChunk(ChunkEntity chunk, float score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
