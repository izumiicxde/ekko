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
import com.semantic.ekko.util.UserFacingMessages;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger searchVersion = new AtomicInteger(0);

    public SearchViewModel(@NonNull Application application) {
        super(application);
        repository = new DocumentRepository(application);
        folderRepository = new FolderRepository(application);
        prefsManager = new PrefsManager(application);
    }

    public void search(String query) {
        if (query == null || query.trim().isEmpty()) return;
        final String trimmedQuery = query.trim();
        final int requestVersion = searchVersion.incrementAndGet();

        EkkoApp app = EkkoApp.getInstance();
        if (!app.isMlReady()) {
            if (isActiveRequest(requestVersion)) {
                errorMessage.postValue(UserFacingMessages.FEATURE_PREPARING);
            }
            return;
        }

        isSearching.postValue(true);

        new Thread(() -> {
            try {
                EmbeddingEngine engine = app.getEmbeddingEngine();
                float[] queryEmbedding = engine.embedQuery(trimmedQuery);

                if (!isActiveRequest(requestVersion)) {
                    return;
                }

                if (queryEmbedding == null) {
                    if (isActiveRequest(requestVersion)) {
                        isSearching.postValue(false);
                        errorMessage.postValue(
                            UserFacingMessages.SEARCH_UNAVAILABLE
                        );
                    }
                    return;
                }

                repository.search(
                    queryEmbedding,
                    trimmedQuery,
                    MIN_SCORE,
                    searchResults -> {
                        if (!isActiveRequest(requestVersion)) {
                            return;
                        }
                        folderRepository.getAll(folders -> {
                            if (!isActiveRequest(requestVersion)) {
                                return;
                            }
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
                            if (isActiveRequest(requestVersion)) {
                                results.postValue(visibleResults);
                                isSearching.postValue(false);
                            }
                        });
                    }
                );
            } catch (Exception e) {
                if (isActiveRequest(requestVersion)) {
                    isSearching.postValue(false);
                    errorMessage.postValue(
                        UserFacingMessages.SEARCH_UNAVAILABLE
                    );
                }
            }
        })
            .start();
    }

    public void cancelActiveSearch() {
        searchVersion.incrementAndGet();
        isSearching.postValue(false);
    }

    private boolean isActiveRequest(int requestVersion) {
        return searchVersion.get() == requestVersion;
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
