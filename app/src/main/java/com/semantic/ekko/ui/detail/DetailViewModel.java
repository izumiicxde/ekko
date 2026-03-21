package com.semantic.ekko.ui.detail;

import android.app.Application;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.repository.DocumentRepository;

public class DetailViewModel extends AndroidViewModel {

    private final MutableLiveData<DocumentEntity> document =
        new MutableLiveData<>();
    private final MutableLiveData<String> errorMessage =
        new MutableLiveData<>();
    private final DocumentRepository repository;

    public DetailViewModel(@NonNull Application application) {
        super(application);
        repository = new DocumentRepository(application);
    }

    public void loadDocument(long documentId) {
        repository.getById(documentId, doc -> {
            if (doc != null) {
                document.postValue(doc);
            } else {
                errorMessage.postValue("Document not found.");
            }
        });
    }

    public void correctCategory(DocumentEntity doc, String newCategory) {
        doc.category = newCategory;
        repository.updateCategory(doc.id, newCategory);
    }

    public LiveData<DocumentEntity> getDocument() {
        return document;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }
}
