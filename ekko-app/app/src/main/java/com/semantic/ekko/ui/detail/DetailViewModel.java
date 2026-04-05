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
import com.semantic.ekko.util.UserFacingMessages;

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
            else errorMessage.postValue(UserFacingMessages.DOCUMENT_UNAVAILABLE);
        });
    }

    public void correctCategory(DocumentEntity doc, String newCategory) {
        doc.category = newCategory;
        repository.updateCategory(doc.id, newCategory);
    }

    public void generateAiSummary() {
        ensureRagRepository();
        if (ragRepository == null) {
            errorMessage.postValue(UserFacingMessages.SUMMARY_UNAVAILABLE);
            return;
        }
        if (currentDocumentId <= 0) {
            errorMessage.postValue(UserFacingMessages.DOCUMENT_UNAVAILABLE);
            return;
        }
        RagRepository.cancelActiveSummaryRequest();
        EkkoApp.getInstance().startSummary(currentDocumentId);
        summaryLoading.postValue(true);
        aiSummary.postValue(null);
        ragRepository.summarizeDocument(
            currentDocumentId,
            new RagRepository.RagCallback() {
                @Override
                public void onAnswer(String answer, String sourceDocumentName) {
                    repository.updateSummary(currentDocumentId, answer);
                    EkkoApp.getInstance().finishSummary(
                        currentDocumentId,
                        answer
                    );
                    summaryLoading.postValue(false);
                    aiSummary.postValue(answer);
                }

                @Override
                public void onError(String message) {
                    EkkoApp.getInstance().failSummary(
                        currentDocumentId,
                        RagRepository.getGenericSummaryErrorMessage()
                    );
                    summaryLoading.postValue(false);
                    errorMessage.postValue(
                        RagRepository.getGenericSummaryErrorMessage()
                    );
                }
            }
        );
    }

    private void ensureRagRepository() {
        if (ragRepository != null) {
            return;
        }
        EkkoApp app = EkkoApp.getInstance();
        if (app.isMlReady() && app.getEmbeddingEngine() != null) {
            ragRepository = new RagRepository(
                getApplication(),
                app.getEmbeddingEngine()
            );
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
}
