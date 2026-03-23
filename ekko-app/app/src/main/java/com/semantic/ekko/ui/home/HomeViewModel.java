package com.semantic.ekko.ui.home;

import android.app.Application;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.DocumentRepository;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ml.EntityExtractorHelper;
import com.semantic.ekko.processing.DocumentIndexer;
import com.semantic.ekko.processing.DocumentScanner;
import com.semantic.ekko.util.FileUtils;
import com.semantic.ekko.util.PrefsManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeViewModel extends AndroidViewModel {

    private final MutableLiveData<List<DocumentEntity>> documents =
        new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> indexingStage = new MutableLiveData<>(
        ""
    );
    private final MutableLiveData<IndexingProgress> indexingProgress =
        new MutableLiveData<>();
    private final MutableLiveData<Boolean> isIndexing = new MutableLiveData<>(
        false
    );
    private final MutableLiveData<String> errorMessage =
        new MutableLiveData<>();

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final PrefsManager prefsManager;

    private EntityExtractorHelper entityExtractor;
    private DocumentIndexer indexer;

    private String activeKeywordFilter = null;
    private String activeSortOrder = "recent";
    private String activeFileTypeFilter = "all";

    // =========================
    // INIT
    // =========================

    public HomeViewModel(@NonNull Application application) {
        super(application);
        documentRepository = new DocumentRepository(application);
        folderRepository = new FolderRepository(application);
        prefsManager = new PrefsManager(application);
    }

    public void initMl() {
        EkkoApp app = EkkoApp.getInstance();

        if (!app.isMlReady()) {
            errorMessage.postValue(
                "ML models are still loading. Please wait a moment."
            );
            return;
        }

        entityExtractor = new EntityExtractorHelper();
        indexer = new DocumentIndexer(
            getApplication(),
            app.getEmbeddingEngine(),
            app.getDocumentClassifier(),
            app.getTextSummarizer(),
            entityExtractor
        );

        entityExtractor.prepareModel(success -> {
            if (!success) {
                errorMessage.postValue(
                    "Entity extraction unavailable. Check internet connection."
                );
            }
        });
    }

    // =========================
    // LOAD DOCUMENTS
    // =========================

    public void loadDocuments() {
        folderRepository.getAll(folders -> {
            Set<String> excludedUris = prefsManager.getExcludedFolderUris();
            Set<Long> excludedFolderIds = new HashSet<>();
            for (FolderEntity folder : folders) {
                if (excludedUris.contains(folder.uri)) {
                    excludedFolderIds.add(folder.id);
                }
            }

            documentRepository.getAll(docs -> {
                List<DocumentEntity> visibleDocs = new ArrayList<>();
                for (DocumentEntity doc : docs) {
                    if (!excludedFolderIds.contains(doc.folderId)) {
                        visibleDocs.add(doc);
                    }
                }
                List<DocumentEntity> filtered = applyFilterAndSort(visibleDocs);
                documents.postValue(filtered);
            });
        });
    }

    public LiveData<List<DocumentEntity>> getDocuments() {
        return documents;
    }

    // =========================
    // ADD FOLDER AND INDEX
    // =========================

    public void addFolderAndIndex(Uri folderUri) {
        if (isIndexing.getValue() == Boolean.TRUE) return;

        if (!EkkoApp.getInstance().isMlReady()) {
            errorMessage.postValue(
                "ML models are still initializing. Please try again in a moment."
            );
            return;
        }

        if (indexer == null) {
            initMl();
            if (indexer == null) {
                errorMessage.postValue("Failed to initialize indexer.");
                return;
            }
        }

        isIndexing.postValue(true);

        String folderName = FileUtils.getFolderName(folderUri);
        FolderEntity folder = new FolderEntity(
            folderUri.toString(),
            folderName
        );

        folderRepository.insertIfNotExists(
            folder,
            (folderId, alreadyExists) -> {
                if (alreadyExists) {
                    isIndexing.postValue(false);
                    errorMessage.postValue(
                        "This folder has already been added."
                    );
                    return;
                }

                folder.id = folderId;

                List<Uri> uris = Collections.singletonList(folderUri);
                List<Long> ids = Collections.singletonList(folderId);
                DocumentScanner.ScanResult scanResult =
                    DocumentScanner.scanFolders(getApplication(), uris, ids);

                if (scanResult.documents.isEmpty()) {
                    isIndexing.postValue(false);
                    errorMessage.postValue(
                        "No supported documents found in this folder."
                    );
                    return;
                }

                indexer.indexDocuments(
                    scanResult.documents,
                    new DocumentIndexer.ProgressListener() {
                        @Override
                        public void onStageChanged(String stage) {
                            indexingStage.postValue(stage);
                        }

                        @Override
                        public void onDocumentProcessed(
                            int current,
                            int total,
                            String docName
                        ) {
                            indexingProgress.postValue(
                                new IndexingProgress(current, total, docName)
                            );
                        }

                        @Override
                        public void onComplete(
                            int indexed,
                            int failed,
                            List<String> failedNames
                        ) {
                            isIndexing.postValue(false);
                            indexingStage.postValue("");
                            loadDocuments();
                            if (!failedNames.isEmpty()) {
                                StringBuilder msg = new StringBuilder();
                                msg
                                    .append(failedNames.size())
                                    .append(
                                        failedNames.size() == 1
                                            ? " file"
                                            : " files"
                                    )
                                    .append(" could not be fully indexed: ");
                                for (
                                    int i = 0;
                                    i < Math.min(failedNames.size(), 3);
                                    i++
                                ) {
                                    if (i > 0) msg.append(", ");
                                    msg.append(failedNames.get(i));
                                }
                                if (failedNames.size() > 3) {
                                    msg
                                        .append(" and ")
                                        .append(failedNames.size() - 3)
                                        .append(" more");
                                }
                                errorMessage.postValue(msg.toString());
                            }
                        }
                    }
                );
            }
        );
    }

    // =========================
    // FILTER AND SORT
    // =========================

    public void setKeywordFilter(String keyword) {
        this.activeKeywordFilter = keyword;
        loadDocuments();
    }

    public void clearKeywordFilter() {
        this.activeKeywordFilter = null;
        loadDocuments();
    }

    public void setSortOrder(String sortOrder) {
        this.activeSortOrder = sortOrder;
        loadDocuments();
    }

    public void setFileTypeFilter(String fileType) {
        this.activeFileTypeFilter = fileType;
        loadDocuments();
    }

    private List<DocumentEntity> applyFilterAndSort(List<DocumentEntity> docs) {
        List<DocumentEntity> result = new ArrayList<>(docs);

        if (!"all".equals(activeFileTypeFilter)) {
            List<DocumentEntity> typed = new ArrayList<>();
            for (DocumentEntity d : result) {
                if (activeFileTypeFilter.equals(d.fileType)) typed.add(d);
            }
            result = typed;
        }

        if (activeKeywordFilter != null && !activeKeywordFilter.isEmpty()) {
            List<DocumentEntity> keywordFiltered = new ArrayList<>();
            for (DocumentEntity d : result) {
                if (
                    d.keywords != null &&
                    d.keywords
                        .toLowerCase()
                        .contains(activeKeywordFilter.toLowerCase())
                ) {
                    keywordFiltered.add(d);
                }
            }
            result = keywordFiltered;
        }

        switch (activeSortOrder) {
            case "name":
                result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                break;
            case "word_count":
            case "read_time":
                result.sort((a, b) ->
                    Integer.compare(b.wordCount, a.wordCount)
                );
                break;
            case "recent":
            default:
                result.sort((a, b) -> Long.compare(b.indexedAt, a.indexedAt));
                break;
        }

        return result;
    }

    // =========================
    // CORRECTION
    // =========================

    public void correctCategory(DocumentEntity doc, String newCategory) {
        doc.category = newCategory;
        documentRepository.updateCategory(doc.id, newCategory);
    }

    // =========================
    // OBSERVABLES
    // =========================

    public LiveData<String> getIndexingStage() {
        return indexingStage;
    }

    public LiveData<IndexingProgress> getIndexingProgress() {
        return indexingProgress;
    }

    public LiveData<Boolean> getIsIndexing() {
        return isIndexing;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    public String getCurrentSortOrder() {
        return activeSortOrder;
    }

    public String getCurrentFileTypeFilter() {
        return activeFileTypeFilter;
    }

    // =========================
    // CLEANUP
    // =========================

    @Override
    protected void onCleared() {
        super.onCleared();
        if (entityExtractor != null) entityExtractor.close();
        if (indexer != null) indexer.shutdown();
    }

    // =========================
    // INNER CLASS
    // =========================

    public static class IndexingProgress {

        public final int current;
        public final int total;
        public final String docName;

        public IndexingProgress(int current, int total, String docName) {
            this.current = current;
            this.total = total;
            this.docName = docName;
        }
    }
}
