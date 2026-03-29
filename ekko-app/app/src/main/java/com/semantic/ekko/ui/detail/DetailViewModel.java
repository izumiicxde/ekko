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
    private long currentDocumentId = -1L;

    public DetailViewModel(@NonNull Application application) {
        super(application);
        repository = new DocumentRepository(application);
        EkkoApp app = EkkoApp.getInstance();
        if (app.isMlReady()) {
            EmbeddingEngine engine = app.getEmbeddingEngine();
            ragRepository = new RagRepository(application, engine);
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
        if (ragRepository == null) {
            errorMessage.postValue("ML not ready.");
            return;
        }
        if (currentDocumentId <= 0) {
            errorMessage.postValue("Document not loaded.");
            return;
        }
        summaryLoading.postValue(true);
        aiSummary.postValue(null);
        ragRepository.summarizeDocument(
            currentDocumentId,
            new RagRepository.RagCallback() {
                @Override
                public void onAnswer(String answer, String sourceDocumentName) {
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
}
