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

public class QAViewModel extends AndroidViewModel {

    public static class UiEvent {

        public static final int ADD_MESSAGE = 0;
        public static final int UPDATE_LAST = 1;
        public static final int REPLACE_LAST = 2;

        public final int type;
        public final QAMessage message;
        public final String token;
        public final String source;

        private UiEvent(
            int type,
            QAMessage message,
            String token,
            String source
        ) {
            this.type = type;
            this.message = message;
            this.token = token;
            this.source = source;
        }

        public static UiEvent add(QAMessage message) {
            return new UiEvent(ADD_MESSAGE, message, null, null);
        }

        public static UiEvent updateToken(String token) {
            return new UiEvent(UPDATE_LAST, null, token, null);
        }

        public static UiEvent updateSource(String source) {
            return new UiEvent(UPDATE_LAST, null, null, source);
        }

        public static UiEvent replace(QAMessage message) {
            return new UiEvent(REPLACE_LAST, message, null, null);
        }
    }

    private final MutableLiveData<UiEvent> uiEvent = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(
        false
    );

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RagRepository ragRepository;

    // Token batching: accumulate tokens and flush to UI every 80ms
    // instead of posting on every single token. Reduces adapter rebinds
    // from ~10/sec to ~12/sec max while keeping the streaming feel.
    private static final long FLUSH_INTERVAL_MS = 80;
    private final StringBuilder pendingTokens = new StringBuilder();
    private boolean flushScheduled = false;

    private final Runnable flushRunnable = () -> {
        flushScheduled = false;
        if (pendingTokens.length() > 0) {
            String batch = pendingTokens.toString();
            pendingTokens.setLength(0);
            uiEvent.setValue(UiEvent.updateToken(batch));
        }
    };

    public QAViewModel(@NonNull Application application) {
        super(application);
        EkkoApp app = EkkoApp.getInstance();
        if (app.isMlReady()) {
            EmbeddingEngine engine = app.getEmbeddingEngine();
            ragRepository = new RagRepository(application, engine);
        }
    }

    // =========================
    // ASK
    // =========================

    public void ask(String question) {
        if (question == null || question.trim().isEmpty()) return;

        if (ragRepository == null) {
            post(
                UiEvent.add(
                    new QAMessage(
                        QAMessage.TYPE_ERROR,
                        "ML not ready. Please wait.",
                        null
                    )
                )
            );
            return;
        }

        pendingTokens.setLength(0);
        flushScheduled = false;

        post(
            UiEvent.add(
                new QAMessage(QAMessage.TYPE_USER, question.trim(), null)
            )
        );
        post(UiEvent.add(new QAMessage(QAMessage.TYPE_ANSWER, "", null)));

        isLoading.postValue(true);

        ragRepository.queryStream(
            question.trim(),
            new RagRepository.RagStreamCallback() {
                @Override
                public void onToken(String token) {
                    // Accumulate tokens and schedule a flush if not already scheduled.
                    // This runs on the background thread so synchronize on pendingTokens.
                    synchronized (pendingTokens) {
                        pendingTokens.append(token);
                    }
                    mainHandler.post(() -> {
                        if (!flushScheduled) {
                            flushScheduled = true;
                            mainHandler.postDelayed(
                                flushRunnable,
                                FLUSH_INTERVAL_MS
                            );
                        }
                    });
                }

                @Override
                public void onComplete(String sourceDocumentName) {
                    // Flush any remaining tokens immediately before marking complete
                    mainHandler.post(() -> {
                        mainHandler.removeCallbacks(flushRunnable);
                        flushScheduled = false;
                        synchronized (pendingTokens) {
                            if (pendingTokens.length() > 0) {
                                uiEvent.setValue(
                                    UiEvent.updateToken(
                                        pendingTokens.toString()
                                    )
                                );
                                pendingTokens.setLength(0);
                            }
                        }
                        uiEvent.setValue(
                            UiEvent.updateSource(sourceDocumentName)
                        );
                        isLoading.setValue(false);
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        mainHandler.removeCallbacks(flushRunnable);
                        flushScheduled = false;
                        pendingTokens.setLength(0);
                        uiEvent.setValue(
                            UiEvent.replace(
                                new QAMessage(
                                    QAMessage.TYPE_ERROR,
                                    message,
                                    null
                                )
                            )
                        );
                        isLoading.setValue(false);
                    });
                }
            }
        );
    }

    private void post(UiEvent event) {
        mainHandler.post(() -> uiEvent.setValue(event));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mainHandler.removeCallbacks(flushRunnable);
    }

    // =========================
    // LIVEDATA
    // =========================

    public LiveData<UiEvent> getUiEvent() {
        return uiEvent;
    }

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }
}
