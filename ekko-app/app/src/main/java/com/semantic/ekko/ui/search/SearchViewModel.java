package com.semantic.ekko.ui.search;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.model.SearchResult;
import com.semantic.ekko.data.repository.DocumentRepository;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.util.PrefsManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SearchViewModel extends AndroidViewModel {

    // Keep a floor for semantic noise, but allow exact text hits through in the
    // repository fallback path.
    private static final float MIN_SCORE = 0.08f;

    private final MutableLiveData<List<SearchResult>> results =
        new MutableLiveData<>();
    private final MutableLiveData<Boolean> isSearching = new MutableLiveData<>(
        false
    );
    private final MutableLiveData<String> errorMessage =
        new MutableLiveData<>();

    private final DocumentRepository repository;
    private final FolderRepository folderRepository;
    private final PrefsManager prefsManager;

    public SearchViewModel(@NonNull Application application) {
        super(application);
        repository = new DocumentRepository(application);
        folderRepository = new FolderRepository(application);
        prefsManager = new PrefsManager(application);
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
            try {
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
                        folderRepository.getAll(folders -> {
                            List<FolderEntity> safeFolders =
                                folders == null ? new ArrayList<>() : folders;
                            Set<String> excludedUris =
                                prefsManager.getExcludedFolderUris();
                            Set<Long> excludedFolderIds = new HashSet<>();
                            for (FolderEntity folder : safeFolders) {
                                if (
                                    folder != null &&
                                    excludedUris.contains(folder.uri)
                                ) {
                                    excludedFolderIds.add(folder.id);
                                }
                            }

                            List<SearchResult> safeSearchResults =
                                searchResults == null
                                    ? new ArrayList<>()
                                    : searchResults;
                            List<SearchResult> visibleResults =
                                new ArrayList<>();
                            for (SearchResult result : safeSearchResults) {
                                if (
                                    result != null &&
                                    result.getDocument() != null &&
                                    !excludedFolderIds.contains(
                                        result.getDocument().folderId
                                    )
                                ) {
                                    visibleResults.add(result);
                                }
                            }
                            results.postValue(visibleResults);
                            isSearching.postValue(false);
                        });
                    }
                );
            } catch (Exception e) {
                isSearching.postValue(false);
                errorMessage.postValue("Search failed. Try a shorter query.");
            }
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
