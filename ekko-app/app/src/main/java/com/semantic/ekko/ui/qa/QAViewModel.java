package com.semantic.ekko.ui.qa;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.DocumentRepository;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.data.repository.RagRepository;
import com.semantic.ekko.util.PrefsManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final PrefsManager prefsManager;

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
        documentRepository = new DocumentRepository(application);
        folderRepository = new FolderRepository(application);
        prefsManager = new PrefsManager(application);
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

        resolveScopedQuestion(
            question.trim(),
            new ScopeResolutionCallback() {
                @Override
                public void onResolved(ScopedQuestion scopedQuestion) {
                    if (scopedQuestion.error != null) {
                        QAMessage err = new QAMessage(
                            QAMessage.TYPE_ERROR,
                            scopedQuestion.error,
                            null
                        );
                        addToHistory(err);
                        post(UiEvent.add(err));
                        isLoading.postValue(false);
                        return;
                    }
                    askWithResolvedScope(scopedQuestion);
                }
            }
        );
    }

    private void askWithResolvedScope(ScopedQuestion scopedQuestion) {
        long targetDocumentId = scopedQuestion.documentId;
        String cleanedQuestion = scopedQuestion.question;

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

        if (targetDocumentId != NO_DOCUMENT) {
            ragRepository.queryStreamForDocument(
                cleanedQuestion,
                targetDocumentId,
                callback
            );
        } else {
            ragRepository.queryStream(cleanedQuestion, callback);
        }
    }

    private void resolveScopedQuestion(
        String rawQuestion,
        ScopeResolutionCallback callback
    ) {
        // Detail-page bot already scoped to one file.
        if (documentId != NO_DOCUMENT) {
            callback.onResolved(
                new ScopedQuestion(rawQuestion, documentId, null)
            );
            return;
        }

        ParsedScope parsed = parseScopePrefix(rawQuestion);
        if (!parsed.hasScopePrefix) {
            callback.onResolved(
                new ScopedQuestion(rawQuestion, NO_DOCUMENT, null)
            );
            return;
        }

        if (parsed.fileName == null || parsed.fileName.isEmpty()) {
            callback.onResolved(
                new ScopedQuestion(
                    rawQuestion,
                    NO_DOCUMENT,
                    "Missing file name. Use @filename: question or /file filename: question."
                )
            );
            return;
        }

        if (parsed.question == null || parsed.question.isEmpty()) {
            callback.onResolved(
                new ScopedQuestion(
                    rawQuestion,
                    NO_DOCUMENT,
                    "Missing question after file name. Example: @policy.pdf: summarize this."
                )
            );
            return;
        }

        findDocumentByName(parsed.fileName, doc -> {
            if (doc == null) {
                callback.onResolved(
                    new ScopedQuestion(
                        rawQuestion,
                        NO_DOCUMENT,
                        "Could not find included file '" +
                            parsed.fileName +
                            "'. Try exact name or use @latest: ... "
                    )
                );
                return;
            }
            callback.onResolved(
                new ScopedQuestion(parsed.question, doc.id, null)
            );
        });
    }

    private void findDocumentByName(
        String fileName,
        DocumentResolutionCallback callback
    ) {
        folderRepository.getAll(folders -> {
            Set<String> excludedUris = prefsManager.getExcludedFolderUris();
            Set<Long> includedFolderIds = new HashSet<>();
            if (folders != null) {
                for (FolderEntity folder : folders) {
                    if (!excludedUris.contains(folder.uri)) {
                        includedFolderIds.add(folder.id);
                    }
                }
            }

            documentRepository.getAll(docs -> {
                DocumentEntity best = pickBestMatch(
                    fileName,
                    docs,
                    includedFolderIds
                );
                callback.onResolved(best);
            });
        });
    }

    private DocumentEntity pickBestMatch(
        String fileName,
        List<DocumentEntity> docs,
        Set<Long> includedFolderIds
    ) {
        if (docs == null || docs.isEmpty()) return null;
        String target = fileName.trim().toLowerCase(Locale.US);

        DocumentEntity exact = null;
        DocumentEntity contains = null;
        DocumentEntity latest = null;

        for (DocumentEntity doc : docs) {
            if (doc == null || !includedFolderIds.contains(doc.folderId)) {
                continue;
            }
            if (latest == null || doc.indexedAt > latest.indexedAt) {
                latest = doc;
            }
            if ("latest".equals(target)) continue;

            String name =
                doc.name == null ? "" : doc.name.toLowerCase(Locale.US);

            if (name.equals(target)) {
                if (exact == null || doc.indexedAt > exact.indexedAt) {
                    exact = doc;
                }
            } else if (name.contains(target)) {
                if (contains == null || doc.indexedAt > contains.indexedAt) {
                    contains = doc;
                }
            }
        }

        if ("latest".equals(target)) return latest;
        if (exact != null) return exact;
        return contains;
    }

    private ParsedScope parseScopePrefix(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return new ParsedScope(false, null, null);
        }

        if (trimmed.startsWith("@")) {
            int separator = trimmed.indexOf(':');
            if (separator <= 1) {
                return new ParsedScope(true, "", "");
            }
            String fileName = trimmed.substring(1, separator).trim();
            String question = trimmed.substring(separator + 1).trim();
            return new ParsedScope(true, fileName, question);
        }

        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.startsWith("/file ")) {
            int separator = trimmed.indexOf(':');
            if (separator <= 5) {
                return new ParsedScope(true, "", "");
            }
            String fileName = trimmed.substring(6, separator).trim();
            String question = trimmed.substring(separator + 1).trim();
            return new ParsedScope(true, fileName, question);
        }

        return new ParsedScope(false, null, null);
    }

    private interface ScopeResolutionCallback {
        void onResolved(ScopedQuestion scopedQuestion);
    }

    private interface DocumentResolutionCallback {
        void onResolved(DocumentEntity document);
    }

    private static class ParsedScope {

        final boolean hasScopePrefix;
        final String fileName;
        final String question;

        ParsedScope(boolean hasScopePrefix, String fileName, String question) {
            this.hasScopePrefix = hasScopePrefix;
            this.fileName = fileName;
            this.question = question;
        }
    }

    private static class ScopedQuestion {

        final String question;
        final long documentId;
        final String error;

        ScopedQuestion(String question, long documentId, String error) {
            this.question = question;
            this.documentId = documentId;
            this.error = error;
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
