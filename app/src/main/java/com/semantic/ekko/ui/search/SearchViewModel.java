package com.semantic.ekko.ui.search;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.data.model.SearchResult;
import com.semantic.ekko.data.repository.DocumentRepository;
import com.semantic.ekko.ml.EmbeddingEngine;
import java.util.List;

public class SearchViewModel extends AndroidViewModel {

    private static final float MIN_SCORE = 0.05f;

    private final MutableLiveData<List<SearchResult>> results =
        new MutableLiveData<>();
    private final MutableLiveData<Boolean> isSearching = new MutableLiveData<>(
        false
    );
    private final MutableLiveData<String> errorMessage =
        new MutableLiveData<>();

    private final DocumentRepository repository;

    public SearchViewModel(@NonNull Application application) {
        super(application);
        repository = new DocumentRepository(application);
    }

    public void search(String query) {
        if (query == null || query.trim().isEmpty()) return;

        EkkoApp app = EkkoApp.getInstance();
        if (!app.isMlReady()) {
            errorMessage.postValue("ML models are still loading. Please wait.");
            return;
        }

        isSearching.postValue(true);

        new Thread(() -> {
            EmbeddingEngine engine = app.getEmbeddingEngine();
            float[] queryEmbedding = engine.embedQuery(query.trim());

            if (queryEmbedding == null) {
                isSearching.postValue(false);
                errorMessage.postValue("Could not process query.");
                return;
            }

            repository.search(
                queryEmbedding,
                query.trim(),
                MIN_SCORE,
                searchResults -> {
                    results.postValue(searchResults);
                    isSearching.postValue(false);
                }
            );
        })
            .start();
    }

    public LiveData<List<SearchResult>> getResults() {
        return results;
    }

    public LiveData<Boolean> getIsSearching() {
        return isSearching;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }
}
