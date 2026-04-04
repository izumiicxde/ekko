package com.semantic.ekko;

import android.app.Application;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.ml.DocumentClassifier;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.ml.TextSummarizer;
import com.semantic.ekko.network.RagClient;
import com.semantic.ekko.processing.extractor.PdfTextExtractor;
import com.semantic.ekko.ui.qa.QAMessage;
import com.semantic.ekko.util.CrashLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EkkoApp extends Application {

    private static final long BACKEND_CHECK_INTERVAL_HEALTHY_MS = 15_000L;
    private static final long BACKEND_CHECK_INTERVAL_UNHEALTHY_MS = 3_000L;
    private static final int BACKEND_FAILURES_BEFORE_OFFLINE = 4;
    private static final int MAX_CHAT_MESSAGES = 80;
    private static final int MAX_STREAM_BUFFER_CHARS = 24000;
    private static EkkoApp instance;

    private EmbeddingEngine embeddingEngine;
    private DocumentClassifier documentClassifier;
    private TextSummarizer textSummarizer;
    private boolean mlReady = false;
    private boolean backendReachable = false;
    private volatile boolean backendCheckInFlight = false;
    private int consecutiveBackendFailures = 0;
    private long lastBackendCheckAt = 0L;
    private final MutableLiveData<Boolean> mlReadyState = new MutableLiveData<>(
        false
    );
    private final MutableLiveData<Boolean> backendReachableState =
        new MutableLiveData<>(null);
    private final MutableLiveData<SummaryState> summaryState =
        new MutableLiveData<>(SummaryState.idle());

    // =========================
    // CHAT STORE
    // =========================

    public static class ChatStore {

        private final List<QAMessage> globalHistory = new ArrayList<>();
        private final Map<Long, List<QAMessage>> docHistories = new HashMap<>();
        private final Map<Long, String> docStreamBufs = new HashMap<>();
        private String globalStreamBuf = "";
        private boolean globalStreamingActive = false;
        private final Map<Long, Boolean> docStreamingActive = new HashMap<>();

        public List<QAMessage> getGlobalHistory() {
            return Collections.unmodifiableList(globalHistory);
        }

        public void addGlobalMessage(QAMessage message) {
            globalHistory.add(message);
            trimHistory(globalHistory);
        }

        public void updateLastGlobalMessage(QAMessage message) {
            if (!globalHistory.isEmpty()) globalHistory.set(
                globalHistory.size() - 1,
                message
            );
        }

        public void clearGlobal() {
            globalHistory.clear();
            globalStreamBuf = "";
            globalStreamingActive = false;
        }

        public String getGlobalStreamBuf() {
            return globalStreamBuf;
        }

        public void setGlobalStreamBuf(String buf) {
            globalStreamBuf = trimBuffer(buf);
        }

        public boolean isGlobalStreamingActive() {
            return globalStreamingActive;
        }

        public void setGlobalStreamingActive(boolean active) {
            globalStreamingActive = active;
        }

        public List<QAMessage> getDocHistory(long docId) {
            List<QAMessage> list = docHistories.get(docId);
            return list != null
                ? Collections.unmodifiableList(list)
                : Collections.emptyList();
        }

        public void addDocMessage(long docId, QAMessage message) {
            if (!docHistories.containsKey(docId)) docHistories.put(
                docId,
                new ArrayList<>()
            );
            docHistories.get(docId).add(message);
            trimHistory(docHistories.get(docId));
        }

        public void updateLastDocMessage(long docId, QAMessage message) {
            List<QAMessage> list = docHistories.get(docId);
            if (list != null && !list.isEmpty()) list.set(
                list.size() - 1,
                message
            );
        }

        public void clearDoc(long docId) {
            docHistories.remove(docId);
            docStreamBufs.remove(docId);
            docStreamingActive.remove(docId);
        }

        public String getDocStreamBuf(long docId) {
            String buf = docStreamBufs.get(docId);
            return buf != null ? buf : "";
        }

        public void setDocStreamBuf(long docId, String buf) {
            docStreamBufs.put(docId, trimBuffer(buf));
        }

        public boolean isDocStreamingActive(long docId) {
            Boolean active = docStreamingActive.get(docId);
            return active != null && active;
        }

        public void setDocStreamingActive(long docId, boolean active) {
            docStreamingActive.put(docId, active);
        }

        private void trimHistory(List<QAMessage> history) {
            if (history == null) {
                return;
            }
            while (history.size() > MAX_CHAT_MESSAGES) {
                history.remove(0);
            }
        }

        private String trimBuffer(String buf) {
            String safe = buf != null ? buf : "";
            if (safe.length() <= MAX_STREAM_BUFFER_CHARS) {
                return safe;
            }
            return safe.substring(safe.length() - MAX_STREAM_BUFFER_CHARS);
        }
    }

    public static class SummaryState {

        public final long documentId;
        public final boolean loading;
        public final String summary;
        public final String error;

        private SummaryState(
            long documentId,
            boolean loading,
            String summary,
            String error
        ) {
            this.documentId = documentId;
            this.loading = loading;
            this.summary = summary;
            this.error = error;
        }

        public static SummaryState idle() {
            return new SummaryState(-1L, false, null, null);
        }

        public static SummaryState loading(long documentId) {
            return new SummaryState(documentId, true, null, null);
        }

        public static SummaryState success(long documentId, String summary) {
            return new SummaryState(documentId, false, summary, null);
        }

        public static SummaryState error(long documentId, String error) {
            return new SummaryState(documentId, false, null, error);
        }
    }

    private final ChatStore chatStore = new ChatStore();

    // =========================
    // LIFECYCLE
    // =========================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashLogger.install(this);
        PdfTextExtractor.init(this);
        initMlAsync();
        refreshBackendHealthAsync(true);
    }

    // =========================
    // SINGLETON
    // =========================

    public static EkkoApp getInstance() {
        return instance;
    }

    // =========================
    // ML INIT
    // =========================

    private void initMlAsync() {
        new Thread(() -> {
            try {
                embeddingEngine = new EmbeddingEngine(this);
                documentClassifier = new DocumentClassifier(embeddingEngine);
                textSummarizer = new TextSummarizer(embeddingEngine);
                mlReady = true;
                mlReadyState.postValue(true);
            } catch (Exception e) {
                mlReady = false;
                mlReadyState.postValue(false);
                CrashLogger.logHandled(this, "ML init failure", e);
            }
        })
            .start();
    }

    public void refreshBackendHealthAsync() {
        refreshBackendHealthAsync(false);
    }

    public void refreshBackendHealthAsync(boolean force) {
        long now = System.currentTimeMillis();
        long minInterval = backendReachable
            ? BACKEND_CHECK_INTERVAL_HEALTHY_MS
            : BACKEND_CHECK_INTERVAL_UNHEALTHY_MS;
        if (!force && (now - lastBackendCheckAt) < minInterval) {
            return;
        }
        if (backendCheckInFlight) {
            return;
        }
        backendCheckInFlight = true;
        lastBackendCheckAt = now;
        new Thread(() -> {
            boolean requestSucceeded = false;
            String resolvedBaseUrl = null;
            try {
                for (String candidateBaseUrl : RagClient.getCandidateBaseUrls()) {
                    try {
                        retrofit2.Response<Void> response = RagClient.getService(
                            candidateBaseUrl
                        )
                            .health()
                            .execute();
                        if (response.isSuccessful()) {
                            requestSucceeded = true;
                            resolvedBaseUrl = candidateBaseUrl;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
            } finally {
                boolean resolvedState;
                if (requestSucceeded) {
                    RagClient.setActiveBaseUrl(resolvedBaseUrl);
                    consecutiveBackendFailures = 0;
                    resolvedState = true;
                } else {
                    consecutiveBackendFailures++;
                    resolvedState =
                        backendReachable &&
                        consecutiveBackendFailures <
                        BACKEND_FAILURES_BEFORE_OFFLINE;
                }
                backendReachable = resolvedState;
                backendReachableState.postValue(resolvedState);
                backendCheckInFlight = false;
            }
        })
            .start();
    }

    // =========================
    // ACCESSORS
    // =========================

    public EmbeddingEngine getEmbeddingEngine() {
        return embeddingEngine;
    }

    public DocumentClassifier getDocumentClassifier() {
        return documentClassifier;
    }

    public TextSummarizer getTextSummarizer() {
        return textSummarizer;
    }

    public boolean isMlReady() {
        return mlReady;
    }

    public boolean isBackendReachable() {
        return backendReachable;
    }

    public LiveData<Boolean> getMlReadyState() {
        return mlReadyState;
    }

    public LiveData<Boolean> getBackendReachableState() {
        return backendReachableState;
    }

    public ChatStore getChatStore() {
        return chatStore;
    }

    public LiveData<SummaryState> getSummaryState() {
        return summaryState;
    }

    public void startSummary(long documentId) {
        summaryState.postValue(SummaryState.loading(documentId));
    }

    public void finishSummary(long documentId, String summary) {
        summaryState.postValue(SummaryState.success(documentId, summary));
    }

    public void failSummary(long documentId, String error) {
        summaryState.postValue(SummaryState.error(documentId, error));
    }
}
