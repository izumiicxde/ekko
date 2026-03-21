package com.semantic.ekko.ui.home;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.DocumentRepository;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ml.DocumentClassifier;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.ml.EntityExtractorHelper;
import com.semantic.ekko.ml.TextSummarizer;
import com.semantic.ekko.processing.DocumentIndexer;
import com.semantic.ekko.processing.DocumentScanner;
import com.semantic.ekko.util.FileUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HomeViewModel extends AndroidViewModel {

    // =========================
    // STATE
    // =========================

    private final MutableLiveData<List<DocumentEntity>> documents = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> indexingStage = new MutableLiveData<>("");
    private final MutableLiveData<IndexingProgress> indexingProgress = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isIndexing = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;

    private EmbeddingEngine embeddingEngine;
    private DocumentClassifier classifier;
    private TextSummarizer summarizer;
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
    }

    /**
     * Initializes ML components. Must be called once from HomeActivity.
     * Runs on the calling thread - call from a background thread.
     */
    public void initMl() {
        try {
            embeddingEngine = new EmbeddingEngine(getApplication());
            classifier = new DocumentClassifier(embeddingEngine);
            summarizer = new TextSummarizer(embeddingEngine);
            entityExtractor = new EntityExtractorHelper();
            indexer = new DocumentIndexer(
                    getApplication(),
                    embeddingEngine,
                    classifier,
                    summarizer,
                    entityExtractor
            );

            entityExtractor.prepareModel(success -> {
                if (!success) {
                    errorMessage.postValue("Entity extraction unavailable. Check internet connection.");
                }
            });

        } catch (IOException e) {
            errorMessage.postValue("Failed to load ML model. Check that the TFLite file is in assets.");
        }
    }

    // =========================
    // LOAD DOCUMENTS
    // =========================

    public void loadDocuments() {
        documentRepository.getAll(docs -> {
            List<DocumentEntity> filtered = applyFilterAndSort(docs);
            documents.postValue(filtered);
        });
    }

    public LiveData<List<DocumentEntity>> getDocuments() {
        return documents;
    }

    // =========================
    // ADD FOLDER AND INDEX
    // =========================

    /**
     * Adds a new folder, scans it for documents, and indexes all found documents
     * through the full ML pipeline.
     */
    public void addFolderAndIndex(Uri folderUri) {
        if (isIndexing.getValue() == Boolean.TRUE) return;
        isIndexing.postValue(true);

        String folderName = FileUtils.getFolderName(folderUri);
        FolderEntity folder = new FolderEntity(folderUri.toString(), folderName);

        folderRepository.insert(folder, folderId -> {
            folder.id = folderId;

            // Scan folder for documents
            List<Uri> uris = Collections.singletonList(folderUri);
            List<Long> ids = Collections.singletonList(folderId);
            DocumentScanner.ScanResult scanResult = DocumentScanner.scanFolders(
                    getApplication(), uris, ids);

            if (scanResult.documents.isEmpty()) {
                isIndexing.postValue(false);
                errorMessage.postValue("No supported documents found in this folder.");
                return;
            }

            // Index documents through ML pipeline
            indexer.indexDocuments(scanResult.documents, new DocumentIndexer.ProgressListener() {
                @Override
                public void onStageChanged(String stage) {
                    indexingStage.postValue(stage);
                }

                @Override
                public void onDocumentProcessed(int current, int total, String docName) {
                    indexingProgress.postValue(new IndexingProgress(current, total, docName));
                }

                @Override
                public void onComplete(int indexed, int failed) {
                    isIndexing.postValue(false);
                    indexingStage.postValue("");
                    loadDocuments();
                }
            });
        });
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

        // Filter by file type
        if (!"all".equals(activeFileTypeFilter)) {
            List<DocumentEntity> typed = new ArrayList<>();
            for (DocumentEntity d : result) {
                if (activeFileTypeFilter.equals(d.fileType)) typed.add(d);
            }
            result = typed;
        }

        // Filter by keyword
        if (activeKeywordFilter != null && !activeKeywordFilter.isEmpty()) {
            List<DocumentEntity> keywordFiltered = new ArrayList<>();
            for (DocumentEntity d : result) {
                if (d.keywords != null && d.keywords.toLowerCase()
                        .contains(activeKeywordFilter.toLowerCase())) {
                    keywordFiltered.add(d);
                }
            }
            result = keywordFiltered;
        }

        // Sort
        switch (activeSortOrder) {
            case "name":
                result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                break;
            case "word_count":
                result.sort((a, b) -> Integer.compare(b.wordCount, a.wordCount));
                break;
            case "read_time":
                result.sort((a, b) -> Integer.compare(b.wordCount, a.wordCount));
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

    public LiveData<String> getIndexingStage() { return indexingStage; }
    public LiveData<IndexingProgress> getIndexingProgress() { return indexingProgress; }
    public LiveData<Boolean> getIsIndexing() { return isIndexing; }
    public LiveData<String> getErrorMessage() { return errorMessage; }

    // =========================
    // CLEANUP
    // =========================

    @Override
    protected void onCleared() {
        super.onCleared();
        if (embeddingEngine != null) embeddingEngine.close();
        if (entityExtractor != null) entityExtractor.close();
        if (indexer != null) indexer.shutdown();
    }

    // =========================
    // INNER CLASSES
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
