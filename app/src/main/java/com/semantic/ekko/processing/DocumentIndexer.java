package com.semantic.ekko.processing;

import android.content.Context;
import android.net.Uri;

import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.repository.DocumentRepository;
import com.semantic.ekko.ml.DocumentClassifier;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.ml.EntityExtractorHelper;
import com.semantic.ekko.ml.TextSummarizer;
import com.semantic.ekko.processing.extractor.DocxTextExtractor;
import com.semantic.ekko.processing.extractor.PdfTextExtractor;
import com.semantic.ekko.processing.extractor.PptxTextExtractor;
import com.semantic.ekko.processing.extractor.TxtTextExtractor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DocumentIndexer {

    // =========================
    // PROGRESS CALLBACK
    // =========================

    public interface ProgressListener {
        void onStageChanged(String stage);
        void onDocumentProcessed(int current, int total, String docName);
        void onComplete(int indexed, int failed);
    }

    // =========================
    // INIT
    // =========================

    private final Context context;
    private final EmbeddingEngine embeddingEngine;
    private final DocumentClassifier classifier;
    private final TextSummarizer summarizer;
    private final EntityExtractorHelper entityExtractor;
    private final DocumentRepository repository;
    private final ExecutorService executor;

    public DocumentIndexer(Context context,
                           EmbeddingEngine embeddingEngine,
                           DocumentClassifier classifier,
                           TextSummarizer summarizer,
                           EntityExtractorHelper entityExtractor) {
        this.context = context;
        this.embeddingEngine = embeddingEngine;
        this.classifier = classifier;
        this.summarizer = summarizer;
        this.entityExtractor = entityExtractor;
        this.repository = new DocumentRepository(context);
        this.executor = Executors.newSingleThreadExecutor();
    }

    // =========================
    // INDEX
    // =========================

    /**
     * Indexes a list of DocumentEntity objects through the full ML pipeline:
     * 1. Extract text per file type
     * 2. Clean text
     * 3. Classify document category
     * 4. Generate embedding vector
     * 5. Generate summary
     * 6. Extract keywords
     * 7. Extract entities (async ML Kit)
     * 8. Save to Room DB
     *
     * Runs on a background thread. Progress reported via ProgressListener.
     */
    public void indexDocuments(List<DocumentEntity> documents, ProgressListener listener) {
        executor.execute(() -> {
            int total = documents.size();
            AtomicInteger indexed = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);

            if (listener != null) listener.onStageChanged("Scanning documents...");

            for (int i = 0; i < total; i++) {
                DocumentEntity doc = documents.get(i);

                if (listener != null) {
                    listener.onDocumentProcessed(i + 1, total, doc.name);
                }

                try {
                    // --- Step 1: Extract text ---
                    if (listener != null) listener.onStageChanged("Extracting text...");
                    String rawText = extractText(doc);

                    if (rawText == null || rawText.trim().isEmpty()) {
                        failed.incrementAndGet();
                        continue;
                    }

                    // --- Step 2: Clean text ---
                    String cleanedText = TextPreprocessor.clean(rawText);
                    String mlText = TextPreprocessor.cleanForMl(rawText);

                    doc.rawText = cleanedText;
                    doc.wordCount = TextPreprocessor.wordCount(cleanedText);

                    // --- Step 3: Classify ---
                    if (listener != null) listener.onStageChanged("Classifying...");
                    doc.category = classifier.classify(mlText);

                    // --- Step 4: Embed ---
                    if (listener != null) listener.onStageChanged("Embedding...");
                    float[] embedding = embeddingEngine.embed(mlText);
                    if (embedding != null) {
                        doc.embedding = EmbeddingEngine.toBytes(embedding);
                    }

                    // --- Step 5: Summarize ---
                    if (listener != null) listener.onStageChanged("Summarizing...");
                    doc.summary = summarizer.summarize(cleanedText);

                    // --- Step 6: Extract keywords ---
                    if (listener != null) listener.onStageChanged("Extracting keywords...");
                    List<String> keywords = TextPreprocessor.extractKeywords(mlText, 5);
                    doc.keywords = String.join(",", keywords);

                    // --- Step 7: Extract entities (async, blocking with latch) ---
                    if (listener != null) listener.onStageChanged("Extracting entities...");
                    CountDownLatch latch = new CountDownLatch(1);
                    entityExtractor.extractEntities(cleanedText, entities -> {
                        doc.entities = EntityExtractorHelper.entitiesToString(entities);
                        latch.countDown();
                    });
                    latch.await();

                    // --- Step 8: Save to DB ---
                    if (listener != null) listener.onStageChanged("Saving...");
                    repository.insert(doc, null);

                    indexed.incrementAndGet();

                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }

            if (listener != null) {
                listener.onComplete(indexed.get(), failed.get());
            }
        });
    }

    // =========================
    // TEXT EXTRACTION
    // =========================

    private String extractText(DocumentEntity doc) {
        Uri uri = Uri.parse(doc.uri);

        switch (doc.fileType) {
            case "pdf":
                return PdfTextExtractor.extract(context, uri);
            case "docx":
                return DocxTextExtractor.extract(context, uri);
            case "pptx":
                return PptxTextExtractor.extract(context, uri);
            case "txt":
                return TxtTextExtractor.extract(context, uri);
            default:
                return "";
        }
    }

    // =========================
    // CLEANUP
    // =========================

    public void shutdown() {
        executor.shutdown();
    }
}
