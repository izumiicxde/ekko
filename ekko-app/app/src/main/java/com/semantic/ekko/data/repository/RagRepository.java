package com.semantic.ekko.data.repository;

import android.content.Context;
import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.ChunkDao;
import com.semantic.ekko.data.db.DocumentDao;
import com.semantic.ekko.data.db.FolderDao;
import com.semantic.ekko.data.model.ChunkEntity;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.network.RagApiService;
import com.semantic.ekko.network.RagClient;
import com.semantic.ekko.network.RagRequest;
import com.semantic.ekko.network.RagResponse;
import com.semantic.ekko.network.SummaryRequest;
import com.semantic.ekko.network.SummaryResponse;
import com.semantic.ekko.util.PrefsManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import retrofit2.Callback;

public class RagRepository {
    private static final int TOP_DOCS = 2;
    private static final int CHUNKS_PER_DOC = 5;
    private static final int SCORE_TOP_N = 3;
    private static final String GENERIC_QA_UNAVAILABLE =
        "The assistant is temporarily unavailable. Please try again in a moment.";
    private static final String GENERIC_SUMMARY_UNAVAILABLE =
        "Summary is temporarily unavailable. Please try again in a moment.";

    // Minimum average chunk score required to proceed with a query.
    // Below this threshold the query is considered irrelevant to the
    // indexed content and the model is not called at all.
    private static final float MIN_RELEVANCE = 0.55f;

    private final EmbeddingEngine embeddingEngine;
    private final ChunkDao chunkDao;
    private final DocumentDao documentDao;
    private final FolderDao folderDao;
    private final PrefsManager prefsManager;
    private final OkHttpClient streamingClient;

    private volatile Call activeCall = null;
    private static volatile retrofit2.Call<SummaryResponse> activeSummaryCall =
        null;

    public interface RagCallback {
        void onAnswer(String answer, String sourceDocumentName);
        void onError(String message);
    }

    public interface RagStreamCallback {
        void onToken(String token);
        void onComplete(String sourceDocumentName);
        void onError(String message);
    }

    public static String getGenericSummaryErrorMessage() {
        return GENERIC_SUMMARY_UNAVAILABLE;
    }

    public static synchronized void cancelActiveSummaryRequest() {
        if (activeSummaryCall != null && !activeSummaryCall.isCanceled()) {
            activeSummaryCall.cancel();
        }
        activeSummaryCall = null;
    }

    public RagRepository(Context context, EmbeddingEngine embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
        AppDatabase db = AppDatabase.getInstance(context);
        this.chunkDao = db.chunkDao();
        this.documentDao = db.documentDao();
        this.folderDao = db.folderDao();
        this.prefsManager = new PrefsManager(context);
        this.streamingClient = RagClient.getStreamingClient();
    }

    public void cancelStream() {
        Call call = activeCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        activeCall = null;
    }

    // =========================
    // INTERNAL MODELS
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
        final float bestScore;

        SelectionResult(
            List<String> chunks,
            String sourceDocName,
            float bestScore
        ) {
            this.chunks = chunks;
            this.sourceDocName = sourceDocName;
            this.bestScore = bestScore;
        }
    }

    // =========================
    // CHUNK PICKING
    // =========================

    private List<String> pickTopChunks(List<ScoredChunk> scoredChunks) {
        List<String> result = new ArrayList<>();
        Set<Integer> usedIndexes = new HashSet<>();

        for (ScoredChunk scoredChunk : scoredChunks) {
            if (result.size() >= CHUNKS_PER_DOC) {
                break;
            }
            if (scoredChunk == null || scoredChunk.chunk == null) {
                continue;
            }

            int chunkIndex = scoredChunk.chunk.chunkIndex;
            boolean nearDuplicate = false;
            for (Integer usedIndex : usedIndexes) {
                if (Math.abs(usedIndex - chunkIndex) <= 1) {
                    nearDuplicate = true;
                    break;
                }
            }
            if (nearDuplicate) {
                continue;
            }

            result.add(scoredChunk.chunk.chunkText);
            usedIndexes.add(chunkIndex);
        }

        for (ScoredChunk scoredChunk : scoredChunks) {
            if (result.size() >= CHUNKS_PER_DOC) {
                break;
            }
            if (
                scoredChunk == null ||
                scoredChunk.chunk == null ||
                usedIndexes.contains(scoredChunk.chunk.chunkIndex)
            ) {
                continue;
            }
            result.add(scoredChunk.chunk.chunkText);
            usedIndexes.add(scoredChunk.chunk.chunkIndex);
        }
        return result;
    }

    // =========================
    // APP-WIDE SELECTION
    // =========================

    private SelectionResult selectChunks(float[] queryEmbedding) {
        List<ChunkEntity> allChunks = chunkDao.getAll();
        if (allChunks == null || allChunks.isEmpty()) return null;

        Set<String> excludedUris = prefsManager.getExcludedFolderUris();
        Set<Long> excludedFolderIds = new HashSet<>();
        List<FolderEntity> folders = folderDao.getAll();
        if (folders != null) {
            for (FolderEntity folder : folders) {
                if (folder != null && excludedUris.contains(folder.uri)) {
                    excludedFolderIds.add(folder.id);
                }
            }
        }

        Map<Long, List<ChunkEntity>> chunksByDoc = new HashMap<>();
        for (ChunkEntity chunk : allChunks) {
            if (chunk == null) {
                continue;
            }
            if (!chunksByDoc.containsKey(chunk.documentId)) chunksByDoc.put(
                chunk.documentId,
                new ArrayList<>()
            );
            chunksByDoc.get(chunk.documentId).add(chunk);
        }

        List<DocumentEntity> allDocs = documentDao.getAll();
        Map<Long, String> docNames = new HashMap<>();
        if (allDocs != null) {
            for (DocumentEntity doc : allDocs) {
                if (doc != null) {
                    docNames.put(doc.id, doc.name);
                }
            }
        }

        List<ScoredDoc> scoredDocs = new ArrayList<>();
        for (Map.Entry<
            Long,
            List<ChunkEntity>
        > entry : chunksByDoc.entrySet()) {
            long docId = entry.getKey();
            List<ChunkEntity> docChunks = entry.getValue();
            DocumentEntity doc = documentDao.getById(docId);
            if (doc == null || excludedFolderIds.contains(doc.folderId)) {
                continue;
            }
            String docName = docNames.getOrDefault(docId, "Unknown");

            List<ScoredChunk> scored = new ArrayList<>();
            for (ChunkEntity chunk : docChunks) {
                if (
                    chunk.chunkEmbedding == null || chunk.chunkText == null
                ) continue;
                float[] chunkEmbedding = EmbeddingEngine.fromBytes(
                    chunk.chunkEmbedding
                );
                if (chunkEmbedding == null) {
                    continue;
                }
                float score = EmbeddingEngine.cosineSimilarity(
                    queryEmbedding,
                    chunkEmbedding
                );
                scored.add(new ScoredChunk(chunk, score));
            }
            if (scored.isEmpty()) continue;

            Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));

            int n = Math.min(SCORE_TOP_N, scored.size());
            float sum = 0f;
            for (int i = 0; i < n; i++) sum += scored.get(i).score;
            float bestScore = scored.get(0).score;
            float averageTopScore = sum / n;
            float chunkPenalty = computeChunkOpportunityPenalty(scored.size());
            float docScore =
                ((0.72f * bestScore) + (0.28f * averageTopScore)) * chunkPenalty;

            scoredDocs.add(new ScoredDoc(docId, docName, docScore, scored));
        }

        if (scoredDocs.isEmpty()) return null;
        Collections.sort(scoredDocs, (a, b) -> Float.compare(b.score, a.score));

        float bestScore = scoredDocs.get(0).score;
        int docLimit = Math.min(TOP_DOCS, scoredDocs.size());

        StringBuilder sourceNames = new StringBuilder();
        for (int i = 0; i < docLimit; i++) {
            ScoredDoc sd = scoredDocs.get(i);
            if (i > 0) {
                sourceNames.append(", ");
            }
            sourceNames.append(sd.docName);
        }

        List<String> selectedChunks = new ArrayList<>();
        for (int d = 0; d < docLimit; d++) {
            List<String> picked = pickTopChunks(scoredDocs.get(d).scoredChunks);
            selectedChunks.addAll(picked);
        }

        if (selectedChunks.isEmpty()) return null;
        return new SelectionResult(
            selectedChunks,
            sourceNames.toString(),
            bestScore
        );
    }

    // =========================
    // SINGLE DOCUMENT SELECTION
    // =========================

    private SelectionResult selectChunksForDocument(
        float[] queryEmbedding,
        long documentId
    ) {
        List<ChunkEntity> docChunks = chunkDao.getByDocumentId(documentId);
        if (docChunks == null || docChunks.isEmpty()) return null;

        DocumentEntity doc = documentDao.getById(documentId);
        String docName = doc != null ? doc.name : "Unknown";

        List<ScoredChunk> scored = new ArrayList<>();
        for (ChunkEntity chunk : docChunks) {
            if (
                chunk.chunkEmbedding == null || chunk.chunkText == null
            ) continue;
            float[] chunkEmbedding = EmbeddingEngine.fromBytes(
                chunk.chunkEmbedding
            );
            if (chunkEmbedding == null) {
                continue;
            }
            float score = EmbeddingEngine.cosineSimilarity(
                queryEmbedding,
                chunkEmbedding
            );
            scored.add(new ScoredChunk(chunk, score));
        }
        if (scored.isEmpty()) return null;

        Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));
        float bestScore = scored.get(0).score;
        int n = Math.min(SCORE_TOP_N, scored.size());
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            sum += scored.get(i).score;
        }
        float docScore =
            ((0.72f * bestScore) + (0.28f * (sum / n))) *
            computeChunkOpportunityPenalty(scored.size());

        List<String> selected = pickTopChunks(scored);
        // In document mode we do not apply a relevance threshold since the user
        // explicitly chose this document to chat with.
        return new SelectionResult(selected, docName, docScore);
    }

    private float computeChunkOpportunityPenalty(int chunkCount) {
        int extraChunks = Math.max(0, chunkCount - 6);
        return 1f / (1f + (extraChunks * 0.035f));
    }

    // =========================
    // PUBLIC STREAM METHODS
    // =========================

    public void queryStream(String question, RagStreamCallback callback) {
        executeStream(question, -1, callback);
    }

    public void queryStreamForDocument(
        String question,
        long documentId,
        RagStreamCallback callback
    ) {
        executeStream(question, documentId, callback);
    }

    // =========================
    // STREAM EXECUTION
    // =========================

    private void executeStream(
        String question,
        long documentId,
        RagStreamCallback callback
    ) {
        if (question == null || question.trim().isEmpty()) {
            callback.onError("Question cannot be empty.");
            return;
        }
        if (
            embeddingEngine == null ||
            streamingClient == null ||
            RagClient.getBaseUrl() == null ||
            RagClient.getBaseUrl().isEmpty()
        ) {
            callback.onError(
                "Q&A is not ready yet. Please try again in a moment."
            );
            return;
        }

        new Thread(() -> {
            try {
                float[] queryEmbedding = embeddingEngine.embed(question.trim());
                if (queryEmbedding == null) {
                    callback.onError("Could not process your question.");
                    return;
                }

                SelectionResult selection =
                    documentId > 0
                        ? selectChunksForDocument(queryEmbedding, documentId)
                        : selectChunks(queryEmbedding);

                if (selection == null) {
                    callback.onError(
                        documentId > 0
                            ? "This document has no indexed content. Please re-index it."
                            : "No indexed content found. Add documents and index them first."
                    );
                    return;
                }

                // For global queries, reject if no document is relevant enough.
                // Document mode skips this check since the user chose the file.
                if (documentId <= 0 && selection.bestScore < MIN_RELEVANCE) {
                    callback.onError(
                        "I could not find relevant content for this question in your documents. " +
                            "Try rephrasing or ask about a topic covered in your files."
                    );
                    return;
                }

                String baseUrl = RagClient.getBaseUrl();
                JSONObject body = new JSONObject();
                body.put("question", question.trim());
                body.put("document_name", selection.sourceDocName);
                JSONArray arr = new JSONArray();
                for (String chunk : selection.chunks) arr.put(chunk);
                body.put("chunks", arr);

                RequestBody requestBody = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    body.toString()
                );

                Request request = new Request.Builder()
                    .url(baseUrl + "rag/stream")
                    .post(requestBody)
                    .build();

                Call call = streamingClient.newCall(request);
                activeCall = call;

                try (Response response = call.execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError(GENERIC_QA_UNAVAILABLE);
                        return;
                    }
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream())
                    );
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted()) break;
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
                                callback.onError(GENERIC_QA_UNAVAILABLE);
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    if (!call.isCanceled()) throw e;
                } finally {
                    activeCall = null;
                }
            } catch (Exception e) {
                callback.onError(GENERIC_QA_UNAVAILABLE);
            }
        })
            .start();
    }

    // =========================
    // NON-STREAMING QUERY
    // =========================

    public void queryForDocument(
        String question,
        long documentId,
        RagCallback callback
    ) {
        if (question == null || question.trim().isEmpty()) {
            callback.onError("Question cannot be empty.");
            return;
        }
        if (embeddingEngine == null) {
            callback.onError(
                "Q&A is not ready yet. Please try again in a moment."
            );
            return;
        }
        new Thread(() -> {
            try {
                float[] emb = embeddingEngine.embed(question.trim());
                if (emb == null) {
                    callback.onError("Could not process your question.");
                    return;
                }

                SelectionResult selection = selectChunksForDocument(
                    emb,
                    documentId
                );
                if (selection == null) {
                    callback.onError(
                        "This document has no indexed content. Please re-index it."
                    );
                    return;
                }

                RagRequest req = new RagRequest(
                    question.trim(),
                    selection.chunks,
                    selection.sourceDocName
                );
                final String src = selection.sourceDocName;
                RagApiService apiService = RagClient.getInstance();

                apiService
                    .ask(req)
                    .enqueue(
                        new Callback<RagResponse>() {
                            @Override
                            public void onResponse(
                                retrofit2.Call<RagResponse> call,
                                retrofit2.Response<RagResponse> response
                            ) {
                                if (
                                    response.isSuccessful() &&
                                    response.body() != null
                                ) {
                                    String ans = response.body().answer;
                                    if (
                                        ans == null || ans.trim().isEmpty()
                                    ) callback.onError("Empty answer.");
                                    else callback.onAnswer(ans.trim(), src);
                                } else callback.onError(GENERIC_QA_UNAVAILABLE);
                            }

                            @Override
                            public void onFailure(
                                retrofit2.Call<RagResponse> call,
                                Throwable t
                            ) {
                                callback.onError(GENERIC_QA_UNAVAILABLE);
                            }
                        }
                    );
            } catch (Exception e) {
                callback.onError(GENERIC_QA_UNAVAILABLE);
            }
        })
            .start();
    }

    public void summarizeDocument(long documentId, RagCallback callback) {
        if (documentId <= 0) {
            callback.onError("Document not loaded.");
            return;
        }
        if (RagClient.getBaseUrl() == null || RagClient.getBaseUrl().isEmpty()) {
            callback.onError("Summary service is not ready yet.");
            return;
        }

        new Thread(() -> {
            try {
                DocumentEntity doc = documentDao.getById(documentId);
                if (doc == null) {
                    callback.onError("Document not found.");
                    return;
                }

                String context = doc.rawText != null ? doc.rawText.trim() : "";
                if (context.isEmpty()) {
                    List<ChunkEntity> chunks = chunkDao.getByDocumentId(
                        documentId
                    );
                    if (chunks == null) {
                        chunks = Collections.emptyList();
                    }
                    StringBuilder builder = new StringBuilder();
                    for (ChunkEntity chunk : chunks) {
                        if (chunk == null || chunk.chunkText == null) continue;
                        if (builder.length() > 0) builder.append("\n\n");
                        builder.append(chunk.chunkText.trim());
                    }
                    context = builder.toString().trim();
                }

                if (context.isEmpty()) {
                    callback.onError(
                        "This document has no indexed content. Please re-index it."
                    );
                    return;
                }

                SummaryRequest request = new SummaryRequest(context, doc.name);
                final String sourceName =
                    doc.name != null ? doc.name : "Document";
                RagApiService apiService = RagClient.getInstance();

                retrofit2.Call<SummaryResponse> call = apiService.summarize(
                    request
                );
                synchronized (RagRepository.class) {
                    if (
                        activeSummaryCall != null &&
                        !activeSummaryCall.isCanceled()
                    ) {
                        activeSummaryCall.cancel();
                    }
                    activeSummaryCall = call;
                }

                call
                    .enqueue(
                        new Callback<SummaryResponse>() {
                            @Override
                            public void onResponse(
                                retrofit2.Call<SummaryResponse> call,
                                retrofit2.Response<SummaryResponse> response
                            ) {
                                if (
                                    response.isSuccessful() &&
                                    response.body() != null &&
                                    response.body().summary != null &&
                                    !response.body().summary.trim().isEmpty()
                                ) {
                                    callback.onAnswer(
                                        response.body().summary.trim(),
                                        sourceName
                                    );
                                } else {
                                    callback.onError(
                                        GENERIC_SUMMARY_UNAVAILABLE
                                    );
                                }
                                clearActiveSummaryCall(call);
                            }

                            @Override
                            public void onFailure(
                                retrofit2.Call<SummaryResponse> call,
                                Throwable t
                            ) {
                                if (!call.isCanceled()) {
                                    callback.onError(
                                        GENERIC_SUMMARY_UNAVAILABLE
                                    );
                                }
                                clearActiveSummaryCall(call);
                            }
                        }
                    );
            } catch (Exception e) {
                callback.onError(GENERIC_SUMMARY_UNAVAILABLE);
            }
        })
            .start();
    }

    private static synchronized void clearActiveSummaryCall(
        retrofit2.Call<SummaryResponse> call
    ) {
        if (activeSummaryCall == call) {
            activeSummaryCall = null;
        }
    }

    public void query(String question, RagCallback callback) {
        if (question == null || question.trim().isEmpty()) {
            callback.onError("Question cannot be empty.");
            return;
        }
        if (embeddingEngine == null) {
            callback.onError(
                "Q&A is not ready yet. Please try again in a moment."
            );
            return;
        }
        new Thread(() -> {
            try {
                float[] emb = embeddingEngine.embed(question.trim());
                if (emb == null) {
                    callback.onError("Could not process your question.");
                    return;
                }

                SelectionResult selection = selectChunks(emb);
                if (selection == null) {
                    callback.onError("No indexed content found.");
                    return;
                }

                if (selection.bestScore < MIN_RELEVANCE) {
                    callback.onError(
                        "I could not find relevant content for this question."
                    );
                    return;
                }

                RagRequest req = new RagRequest(
                    question.trim(),
                    selection.chunks,
                    selection.sourceDocName
                );
                final String src = selection.sourceDocName;
                RagApiService apiService = RagClient.getInstance();

                apiService
                    .ask(req)
                    .enqueue(
                        new Callback<RagResponse>() {
                            @Override
                            public void onResponse(
                                retrofit2.Call<RagResponse> call,
                                retrofit2.Response<RagResponse> response
                            ) {
                                if (
                                    response.isSuccessful() &&
                                    response.body() != null
                                ) {
                                    String ans = response.body().answer;
                                    if (
                                        ans == null || ans.trim().isEmpty()
                                    ) callback.onError("Empty answer.");
                                    else callback.onAnswer(ans.trim(), src);
                                } else callback.onError(GENERIC_QA_UNAVAILABLE);
                            }

                            @Override
                            public void onFailure(
                                retrofit2.Call<RagResponse> call,
                                Throwable t
                            ) {
                                callback.onError(GENERIC_QA_UNAVAILABLE);
                            }
                        }
                    );
            } catch (Exception e) {
                callback.onError(GENERIC_QA_UNAVAILABLE);
            }
        })
            .start();
    }
}
