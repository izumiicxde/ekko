package com.semantic.ekko;

import android.app.Application;
import android.util.Log;
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

    private static final String TAG = "EkkoApp";
    private static EkkoApp instance;

    private EmbeddingEngine embeddingEngine;
    private DocumentClassifier documentClassifier;
    private TextSummarizer textSummarizer;
    private boolean mlReady = false;
    private boolean backendReachable = false;
    private volatile boolean backendCheckInFlight = false;
    private long lastBackendCheckAt = 0L;
    private final MutableLiveData<Boolean> mlReadyState = new MutableLiveData<>(
        false
    );
    private final MutableLiveData<Boolean> backendReachableState =
        new MutableLiveData<>(false);

    // =========================
    // CHAT STORE
    // =========================

    public static class ChatStore {

        private final List<QAMessage> globalHistory = new ArrayList<>();
        private final Map<Long, List<QAMessage>> docHistories = new HashMap<>();
        private final Map<Long, String> docStreamBufs = new HashMap<>();
        private String globalStreamBuf = "";

        public List<QAMessage> getGlobalHistory() {
            return Collections.unmodifiableList(globalHistory);
        }

        public void addGlobalMessage(QAMessage message) {
            globalHistory.add(message);
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
        }

        public String getGlobalStreamBuf() {
            return globalStreamBuf;
        }

        public void setGlobalStreamBuf(String buf) {
            globalStreamBuf = buf != null ? buf : "";
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
        }

        public String getDocStreamBuf(long docId) {
            String buf = docStreamBufs.get(docId);
            return buf != null ? buf : "";
        }

        public void setDocStreamBuf(long docId, String buf) {
            docStreamBufs.put(docId, buf != null ? buf : "");
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
                Log.d(TAG, "ML components initialized successfully");
            } catch (Exception e) {
                mlReady = false;
                mlReadyState.postValue(false);
                Log.e(
                    TAG,
                    "Failed to initialize ML components: " + e.getMessage()
                );
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
        if (!force && (now - lastBackendCheckAt) < 15_000L) {
            return;
        }
        if (backendCheckInFlight) {
            return;
        }
        backendCheckInFlight = true;
        lastBackendCheckAt = now;
        new Thread(() -> {
            boolean healthy = false;
            try {
                retrofit2.Response<Void> response = RagClient.getInstance()
                    .health()
                    .execute();
                healthy = response.isSuccessful();
            } catch (Exception e) {
                Log.d(TAG, "Backend health check failed: " + e.getMessage());
            } finally {
                backendReachable = healthy;
                backendReachableState.postValue(healthy);
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
}
