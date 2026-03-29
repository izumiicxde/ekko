package com.semantic.ekko.ui.detail;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.repository.DocumentRepository;
import com.semantic.ekko.data.repository.RagRepository;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.ml.TextSummarizer;
import com.semantic.ekko.processing.TextPreprocessor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailViewModel extends AndroidViewModel {

    private final MutableLiveData<DocumentEntity> document =
        new MutableLiveData<>();
    private final MutableLiveData<String> errorMessage =
        new MutableLiveData<>();
    private final MutableLiveData<String> aiSummary = new MutableLiveData<>();
    private final MutableLiveData<Boolean> summaryLoading =
        new MutableLiveData<>(false);

    private final DocumentRepository repository;
    private RagRepository ragRepository;
    private TextSummarizer textSummarizer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long currentDocumentId = -1L;

    public DetailViewModel(@NonNull Application application) {
        super(application);
        repository = new DocumentRepository(application);
        EkkoApp app = EkkoApp.getInstance();
        if (app.isMlReady()) {
            EmbeddingEngine engine = app.getEmbeddingEngine();
            ragRepository = new RagRepository(application, engine);
            textSummarizer = app.getTextSummarizer();
        }
    }

    public void loadDocument(long documentId) {
        currentDocumentId = documentId;
        repository.getById(documentId, doc -> {
            if (doc != null) document.postValue(doc);
            else errorMessage.postValue("Document not found.");
        });
    }

    public void correctCategory(DocumentEntity doc, String newCategory) {
        doc.category = newCategory;
        repository.updateCategory(doc.id, newCategory);
    }

    public void generateAiSummary() {
        if (textSummarizer == null && ragRepository == null) {
            errorMessage.postValue("ML not ready.");
            return;
        }
        if (currentDocumentId <= 0) {
            errorMessage.postValue("Document not loaded.");
            return;
        }
        summaryLoading.postValue(true);
        aiSummary.postValue(null);
        repository.getById(currentDocumentId, doc -> {
            if (doc == null) {
                summaryLoading.postValue(false);
                errorMessage.postValue("Document not found.");
                return;
            }

            String localSummary = buildLocalSummary(doc);
            if (localSummary != null && !localSummary.isEmpty()) {
                repository.updateSummary(currentDocumentId, localSummary);
                summaryLoading.postValue(false);
                aiSummary.postValue(localSummary);
                return;
            }

            if (ragRepository == null) {
                summaryLoading.postValue(false);
                errorMessage.postValue(
                    "Could not generate summary: indexed content not available."
                );
                return;
            }

            ragRepository.queryForDocument(
                "Provide a clear and concise summary of this document.",
                currentDocumentId,
                new RagRepository.RagCallback() {
                    @Override
                    public void onAnswer(
                        String answer,
                        String sourceDocumentName
                    ) {
                        repository.updateSummary(currentDocumentId, answer);
                        summaryLoading.postValue(false);
                        aiSummary.postValue(answer);
                    }

                    @Override
                    public void onError(String message) {
                        summaryLoading.postValue(false);
                        errorMessage.postValue(
                            "Could not generate summary: " + message
                        );
                    }
                }
            );
        });
    }

    private String buildLocalSummary(DocumentEntity doc) {
        if (textSummarizer == null || doc == null) return null;

        String rawText = doc.rawText != null ? doc.rawText.trim() : "";
        if (rawText.length() < 120) return null;

        String cleaned = TextPreprocessor.clean(rawText);
        if (cleaned.length() < 120) return null;

        try {
            return textSummarizer.summarize(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    public LiveData<DocumentEntity> getDocument() {
        return document;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    public LiveData<String> getAiSummary() {
        return aiSummary;
    }

    public LiveData<Boolean> getSummaryLoading() {
        return summaryLoading;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
    }
}
