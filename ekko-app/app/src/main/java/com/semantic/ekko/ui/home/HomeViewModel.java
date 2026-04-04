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
import com.semantic.ekko.util.StorageAccessHelper;
import com.semantic.ekko.util.UserFacingMessages;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final MutableLiveData<Map<Long, String>> folderNames =
        new MutableLiveData<>(new HashMap<>());

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final PrefsManager prefsManager;

    private EntityExtractorHelper entityExtractor;
    private DocumentIndexer indexer;

    private String activeKeywordFilter = null;
    private String activeSortOrder = "recent";
    private String activeFileTypeFilter = "all";
    private String activeViewMode = "folders";

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
            errorMessage.postValue(UserFacingMessages.FEATURE_PREPARING);
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
                errorMessage.postValue(UserFacingMessages.SMART_FEATURES_LIMITED);
            }
        });
    }

    // =========================
    // LOAD DOCUMENTS
    // =========================

    public void loadDocuments() {
        folderRepository.getAll(folders -> {
            List<FolderEntity> safeFolders =
                folders == null ? Collections.emptyList() : folders;
            Set<String> excludedUris = prefsManager.getExcludedFolderUris();
            Set<Long> excludedFolderIds = new HashSet<>();
            Map<Long, String> visibleFolderNames = new HashMap<>();
            for (FolderEntity folder : safeFolders) {
                if (folder == null) continue;
                if (excludedUris.contains(folder.uri)) {
                    excludedFolderIds.add(folder.id);
                } else {
                    visibleFolderNames.put(folder.id, folder.name);
                }
            }
            folderNames.postValue(visibleFolderNames);

            documentRepository.getAll(docs -> {
                List<DocumentEntity> safeDocs =
                    docs == null ? Collections.emptyList() : docs;
                List<DocumentEntity> visibleDocs = new ArrayList<>();
                for (DocumentEntity doc : safeDocs) {
                    if (
                        doc != null && !excludedFolderIds.contains(doc.folderId)
                    ) {
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

    public LiveData<Map<Long, String>> getFolderNames() {
        return folderNames;
    }

    // =========================
    // ADD FOLDER AND INDEX
    // =========================

    public void addFolderAndIndex(Uri folderUri) {
        if (isIndexing.getValue() == Boolean.TRUE) return;

        if (!EkkoApp.getInstance().isMlReady()) {
            errorMessage.postValue(UserFacingMessages.FEATURE_PREPARING);
            return;
        }

        if (indexer == null) {
            initMl();
            if (indexer == null) {
                errorMessage.postValue(UserFacingMessages.GENERIC_ERROR);
                return;
            }
        }

        isIndexing.postValue(true);

        String folderName = FileUtils.getFolderDisplayPath(folderUri);
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

                startIndexing(scanResult.documents);
            }
        );
    }

    public void addFilesystemFoldersAndIndex(List<File> folderFiles) {
        if (isIndexing.getValue() == Boolean.TRUE) return;
        if (folderFiles == null || folderFiles.isEmpty()) {
            errorMessage.postValue("No shared folders selected.");
            return;
        }

        if (!EkkoApp.getInstance().isMlReady()) {
            errorMessage.postValue(UserFacingMessages.FEATURE_PREPARING);
            return;
        }

        if (indexer == null) {
            initMl();
            if (indexer == null) {
                errorMessage.postValue(UserFacingMessages.GENERIC_ERROR);
                return;
            }
        }

        isIndexing.postValue(true);
        indexingStage.postValue("Preparing shared folders...");

        folderRepository.getAll(existingFolders -> {
            Set<String> existingUris = new HashSet<>();
            if (existingFolders != null) {
                for (FolderEntity existingFolder : existingFolders) {
                    if (
                        existingFolder != null &&
                        existingFolder.uri != null &&
                        !existingFolder.uri.isEmpty()
                    ) {
                        existingUris.add(existingFolder.uri);
                    }
                }
            }

            List<FolderEntity> folderEntities = new ArrayList<>();
            for (File folderFile : folderFiles) {
                if (folderFile == null) continue;
                String uri = Uri.fromFile(folderFile).toString();
                if (!existingUris.contains(uri)) {
                    prefsManager.setFolderExcluded(uri, false);
                }
                folderEntities.add(
                    new FolderEntity(
                        uri,
                        StorageAccessHelper.getFolderDisplayName(folderFile)
                    )
                );
            }

            folderRepository.resolveOrInsert(folderEntities, resolvedFolders -> {
                List<File> scanFolders = new ArrayList<>();
                List<Long> folderIds = new ArrayList<>();
                List<FolderEntity> safeFolders =
                    resolvedFolders == null
                        ? Collections.emptyList()
                        : resolvedFolders;

                for (FolderEntity folder : safeFolders) {
                    if (
                        folder == null ||
                        folder.uri == null ||
                        prefsManager.isFolderExcluded(folder.uri)
                    ) {
                        continue;
                    }
                    try {
                        scanFolders.add(new File(Uri.parse(folder.uri).getPath()));
                        folderIds.add(folder.id);
                    } catch (Exception ignored) {}
                }

                if (scanFolders.isEmpty()) {
                    isIndexing.postValue(false);
                    indexingStage.postValue("");
                    loadDocuments();
                    errorMessage.postValue(
                        "No included shared folders are available to refresh."
                    );
                    return;
                }

                DocumentScanner.ScanResult scanResult =
                    DocumentScanner.scanFilesystemFolders(
                        getApplication(),
                        scanFolders,
                        folderIds
                    );

                syncScannedDocuments(folderIds, scanResult.documents, () -> {
                    if (scanResult.documents.isEmpty()) {
                        isIndexing.postValue(false);
                        indexingStage.postValue("");
                        loadDocuments();
                        errorMessage.postValue(
                            "No supported documents found in included shared folders."
                        );
                        return;
                    }

                    startIndexing(scanResult.documents);
                });
            });
        });
    }

    public void importDetectedPublicFolders() {
        List<File> publicFolders = StorageAccessHelper.discoverAccessiblePublicFolders(
            getApplication()
        );
        if (publicFolders.isEmpty()) {
            errorMessage.postValue("No readable shared folders found.");
            return;
        }
        addFilesystemFoldersAndIndex(publicFolders);
    }

    public void reindexFolders(List<FolderEntity> folders) {
        if (isIndexing.getValue() == Boolean.TRUE) return;
        if (folders == null || folders.isEmpty()) {
            errorMessage.postValue(
                "No included folders available to re-index."
            );
            return;
        }

        if (!EkkoApp.getInstance().isMlReady()) {
            errorMessage.postValue(UserFacingMessages.FEATURE_PREPARING);
            return;
        }

        if (indexer == null) {
            initMl();
            if (indexer == null) {
                errorMessage.postValue(UserFacingMessages.GENERIC_ERROR);
                return;
            }
        }

        List<Uri> uris = new ArrayList<>();
        List<Long> folderIds = new ArrayList<>();
        for (FolderEntity folder : folders) {
            if (
                folder == null || folder.uri == null || folder.uri.isEmpty()
            ) continue;
            uris.add(Uri.parse(folder.uri));
            folderIds.add(folder.id);
        }

        if (uris.isEmpty()) {
            errorMessage.postValue("No valid folders found to re-index.");
            return;
        }

        isIndexing.postValue(true);
        indexingStage.postValue("Preparing re-index...");

        DocumentScanner.ScanResult scanResult = DocumentScanner.scanFolders(
            getApplication(),
            uris,
            folderIds
        );

        syncScannedDocuments(folderIds, scanResult.documents, () -> {
            if (scanResult.documents.isEmpty()) {
                isIndexing.postValue(false);
                indexingStage.postValue("");
                loadDocuments();
                errorMessage.postValue(
                    "No supported documents found in selected folders."
                );
                return;
            }

            startIndexing(scanResult.documents);
        });
    }

    public void setFolderIncluded(FolderEntity folder, boolean included) {
        if (folder == null || folder.uri == null) {
            return;
        }

        prefsManager.setFolderExcluded(folder.uri, !included);
        if (!included) {
            documentRepository.deleteByFolderId(folder.id, this::loadDocuments);
            return;
        }

        loadDocuments();
        reindexFolders(Collections.singletonList(folder));
    }

    private void syncScannedDocuments(
        List<Long> folderIds,
        List<DocumentEntity> scannedDocs,
        Runnable onComplete
    ) {
        Map<Long, List<String>> urisByFolder = new HashMap<>();
        if (folderIds != null) {
            for (Long folderId : folderIds) {
                if (folderId != null) {
                    urisByFolder.put(folderId, new ArrayList<>());
                }
            }
        }
        if (scannedDocs != null) {
            for (DocumentEntity doc : scannedDocs) {
                if (doc == null || doc.uri == null) continue;
                List<String> folderUris = urisByFolder.get(doc.folderId);
                if (folderUris != null) {
                    folderUris.add(doc.uri);
                }
            }
        }
        syncFoldersSequentially(new ArrayList<>(urisByFolder.keySet()), urisByFolder, 0, onComplete);
    }

    private void syncFoldersSequentially(
        List<Long> folderIds,
        Map<Long, List<String>> urisByFolder,
        int index,
        Runnable onComplete
    ) {
        if (index >= folderIds.size()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        long folderId = folderIds.get(index);
        documentRepository.deleteMissingByFolderId(
            folderId,
            urisByFolder.get(folderId),
            () -> syncFoldersSequentially(folderIds, urisByFolder, index + 1, onComplete)
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

    public void setViewMode(String viewMode) {
        this.activeViewMode = viewMode;
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

    public String getCurrentViewMode() {
        return activeViewMode;
    }

    private void startIndexing(List<DocumentEntity> docs) {
        indexer.indexDocuments(
            docs,
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
                        errorMessage.postValue(
                            UserFacingMessages.INDEXING_PARTIAL_FAILURE
                        );
                    }
                }
            }
        );
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
