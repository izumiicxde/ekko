package com.semantic.ekko.ui.qa;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.data.repository.RagRepository;
import com.semantic.ekko.ml.EmbeddingEngine;
import java.util.ArrayList;
import java.util.List;

public class QAViewModel extends AndroidViewModel {

    public static final long NO_DOCUMENT = -1;

    public static class UiEvent {

        public static final int ADD_MESSAGE = 0;
        public static final int UPDATE_LAST = 1;
        public static final int REPLACE_LAST = 2;
        public static final int RESTORE_HISTORY = 3;

        public final int type;
        public final QAMessage message;
        public final String token;
        public final String source;
        public final List<QAMessage> history;

        private UiEvent(
            int type,
            QAMessage message,
            String token,
            String source,
            List<QAMessage> history
        ) {
            this.type = type;
            this.message = message;
            this.token = token;
            this.source = source;
            this.history = history;
        }

        public static UiEvent add(QAMessage m) {
            return new UiEvent(ADD_MESSAGE, m, null, null, null);
        }

        public static UiEvent updateToken(String t) {
            return new UiEvent(UPDATE_LAST, null, t, null, null);
        }

        public static UiEvent updateSource(String s) {
            return new UiEvent(UPDATE_LAST, null, null, s, null);
        }

        public static UiEvent replace(QAMessage m) {
            return new UiEvent(REPLACE_LAST, m, null, null, null);
        }

        public static UiEvent restore(List<QAMessage> h) {
            return new UiEvent(RESTORE_HISTORY, null, null, null, h);
        }
    }

    private final MutableLiveData<UiEvent> uiEvent = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(
        false
    );

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RagRepository ragRepository;
    private EkkoApp.ChatStore chatStore;

    private long documentId = NO_DOCUMENT;
    private boolean docModeSet = false;

    private static final long FLUSH_MS = 80;
    private final StringBuilder pendingTokens = new StringBuilder();
    private boolean flushScheduled = false;
    private volatile boolean cancelled = false;
    private boolean bubbleAdded = false;

    private final StringBuilder streamingBuffer = new StringBuilder();

    private final Runnable flushRunnable = () -> {
        flushScheduled = false;
        if (pendingTokens.length() > 0 && !cancelled) {
            String batch = pendingTokens.toString();
            pendingTokens.setLength(0);
            streamingBuffer.append(batch);
            uiEvent.setValue(UiEvent.updateToken(batch));
        }
    };

    public QAViewModel(@NonNull Application application) {
        super(application);
        chatStore = EkkoApp.getInstance().getChatStore();
        EkkoApp app = EkkoApp.getInstance();
        if (app.isMlReady()) {
            ragRepository = new RagRepository(
                application,
                app.getEmbeddingEngine()
            );
        }
    }

    // =========================
    // DOCUMENT MODE
    // =========================

    public void setDocumentMode(long docId) {
        if (docModeSet) return;
        docModeSet = true;
        documentId = docId;
    }

    public boolean isDocumentMode() {
        return documentId != NO_DOCUMENT;
    }

    public long getDocumentId() {
        return documentId;
    }

    // =========================
    // RESTORE
    // =========================

    public void restoreIfNeeded() {
        List<QAMessage> history =
            documentId != NO_DOCUMENT
                ? chatStore.getDocHistory(documentId)
                : chatStore.getGlobalHistory();

        if (!history.isEmpty()) {
            post(UiEvent.restore(new ArrayList<>(history)));
        }

        String buf =
            documentId != NO_DOCUMENT
                ? chatStore.getDocStreamBuf(documentId)
                : chatStore.getGlobalStreamBuf();
        streamingBuffer.setLength(0);
        streamingBuffer.append(buf);
    }

    public String getStreamingBuffer() {
        return streamingBuffer.toString();
    }

    // =========================
    // ASK
    // =========================

    public void ask(String question) {
        if (question == null || question.trim().isEmpty()) return;

        if (ragRepository == null) {
            QAMessage err = new QAMessage(
                QAMessage.TYPE_ERROR,
                "ML not ready. Please wait.",
                null
            );
            addToHistory(err);
            post(UiEvent.add(err));
            return;
        }

        cancelled = false;
        bubbleAdded = false;
        pendingTokens.setLength(0);
        streamingBuffer.setLength(0);
        flushScheduled = false;
        saveStreamBuf("");

        QAMessage userMsg = new QAMessage(
            QAMessage.TYPE_USER,
            question.trim(),
            null
        );
        addToHistory(userMsg);
        post(UiEvent.add(userMsg));

        isLoading.postValue(true);

        RagRepository.RagStreamCallback callback =
            new RagRepository.RagStreamCallback() {
                @Override
                public void onToken(String token) {
                    if (cancelled) return;
                    synchronized (pendingTokens) {
                        pendingTokens.append(token);
                    }
                    mainHandler.post(() -> {
                        if (cancelled) return;
                        if (!bubbleAdded) {
                            bubbleAdded = true;
                            QAMessage placeholder = new QAMessage(
                                QAMessage.TYPE_ANSWER,
                                "",
                                null
                            );
                            addToHistory(placeholder);
                            uiEvent.setValue(UiEvent.add(placeholder));
                        }
                        if (!flushScheduled) {
                            flushScheduled = true;
                            mainHandler.postDelayed(flushRunnable, FLUSH_MS);
                        }
                    });
                }

                @Override
                public void onComplete(String sourceDocumentName) {
                    mainHandler.post(() -> {
                        mainHandler.removeCallbacks(flushRunnable);
                        flushScheduled = false;
                        synchronized (pendingTokens) {
                            if (pendingTokens.length() > 0 && !cancelled) {
                                streamingBuffer.append(
                                    pendingTokens.toString()
                                );
                                uiEvent.setValue(
                                    UiEvent.updateToken(
                                        pendingTokens.toString()
                                    )
                                );
                                pendingTokens.setLength(0);
                            }
                        }
                        if (!cancelled) {
                            QAMessage finalMsg = new QAMessage(
                                QAMessage.TYPE_ANSWER,
                                streamingBuffer.toString(),
                                sourceDocumentName
                            );
                            updateLastInHistory(finalMsg);
                            saveStreamBuf(streamingBuffer.toString());
                            uiEvent.setValue(
                                UiEvent.updateSource(sourceDocumentName)
                            );
                        }
                        isLoading.setValue(false);
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        mainHandler.removeCallbacks(flushRunnable);
                        flushScheduled = false;
                        pendingTokens.setLength(0);
                        if (!cancelled) {
                            QAMessage errMsg = new QAMessage(
                                QAMessage.TYPE_ERROR,
                                message,
                                null
                            );
                            if (bubbleAdded) {
                                updateLastInHistory(errMsg);
                                uiEvent.setValue(UiEvent.replace(errMsg));
                            } else {
                                addToHistory(errMsg);
                                uiEvent.setValue(UiEvent.add(errMsg));
                            }
                        }
                        isLoading.setValue(false);
                    });
                }
            };

        if (documentId != NO_DOCUMENT) {
            ragRepository.queryStreamForDocument(
                question.trim(),
                documentId,
                callback
            );
        } else {
            ragRepository.queryStream(question.trim(), callback);
        }
    }

    // =========================
    // STOP
    // =========================

    public void stop() {
        if (!Boolean.TRUE.equals(isLoading.getValue())) return;
        cancelled = true;
        mainHandler.removeCallbacks(flushRunnable);
        flushScheduled = false;
        pendingTokens.setLength(0);
        if (ragRepository != null) ragRepository.cancelStream();
        isLoading.postValue(false);
    }

    // =========================
    // HISTORY HELPERS
    // =========================

    private void addToHistory(QAMessage message) {
        if (documentId != NO_DOCUMENT) chatStore.addDocMessage(
            documentId,
            message
        );
        else chatStore.addGlobalMessage(message);
    }

    private void updateLastInHistory(QAMessage message) {
        if (documentId != NO_DOCUMENT) chatStore.updateLastDocMessage(
            documentId,
            message
        );
        else chatStore.updateLastGlobalMessage(message);
    }

    private void saveStreamBuf(String buf) {
        if (documentId != NO_DOCUMENT) chatStore.setDocStreamBuf(
            documentId,
            buf
        );
        else chatStore.setGlobalStreamBuf(buf);
    }

    private void post(UiEvent event) {
        mainHandler.post(() -> uiEvent.setValue(event));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mainHandler.removeCallbacks(flushRunnable);
    }

    public LiveData<UiEvent> getUiEvent() {
        return uiEvent;
    }

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }
}
