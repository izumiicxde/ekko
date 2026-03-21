package com.semantic.ekko.processing;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DocumentIndexer {

    private static final String TAG = "DocumentIndexer";

    public interface ProgressListener {
        void onStageChanged(String stage);
        void onDocumentProcessed(int current, int total, String docName);
        void onComplete(int indexed, int failed, List<String> failedNames);
    }

    private final Context context;
    private final EmbeddingEngine embeddingEngine;
    private final DocumentClassifier classifier;
    private final TextSummarizer summarizer;
    private final EntityExtractorHelper entityExtractor;
    private final DocumentRepository repository;
    private final ExecutorService executor;

    public DocumentIndexer(
        Context context,
        EmbeddingEngine embeddingEngine,
        DocumentClassifier classifier,
        TextSummarizer summarizer,
        EntityExtractorHelper entityExtractor
    ) {
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

    public void indexDocuments(
        List<DocumentEntity> documents,
        ProgressListener listener
    ) {
        executor.execute(() -> {
            int total = documents.size();
            AtomicInteger indexed = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            List<String> failedNames = new ArrayList<>();

            if (listener != null) listener.onStageChanged(
                "Scanning documents..."
            );

            for (int i = 0; i < total; i++) {
                DocumentEntity doc = documents.get(i);
                if (listener != null) listener.onDocumentProcessed(
                    i + 1,
                    total,
                    doc.name
                );

                try {
                    // Step 1: Extract text
                    if (listener != null) listener.onStageChanged(
                        "Extracting text..."
                    );
                    String rawText = extractText(doc);

                    if (rawText == null || rawText.trim().length() < 20) {
                        Log.w(
                            TAG,
                            doc.name +
                                ": text extraction poor, using filename only"
                        );
                        rawText = doc.name
                            .replaceAll("\\.[^.]+$", "")
                            .replaceAll("[_\\-]", " ");
                        failedNames.add(doc.name + " (no readable text)");
                    }

                    Log.d(
                        TAG,
                        "Extracted " +
                            rawText.length() +
                            " chars from " +
                            doc.name
                    );

                    // Step 2: Clean text
                    String cleanedText = TextPreprocessor.clean(rawText);
                    String mlText = TextPreprocessor.cleanForMl(rawText);

                    doc.rawText = cleanedText;
                    doc.wordCount = TextPreprocessor.wordCount(cleanedText);

                    // Step 3: Classify
                    if (listener != null) listener.onStageChanged(
                        "Classifying..."
                    );
                    doc.category = classifier.classify(mlText);
                    Log.d(TAG, doc.name + " -> category: " + doc.category);

                    // Step 4: Embed
                    if (listener != null) listener.onStageChanged(
                        "Embedding..."
                    );
                    String embeddingInput = buildEmbeddingInput(
                        doc.name,
                        mlText
                    );
                    float[] embedding = embeddingEngine.embed(embeddingInput);
                    if (embedding != null) {
                        doc.embedding = EmbeddingEngine.toBytes(embedding);
                    } else {
                        Log.w(TAG, doc.name + ": embedding returned null");
                    }

                    // Step 5: Summarize
                    if (listener != null) listener.onStageChanged(
                        "Summarizing..."
                    );
                    try {
                        doc.summary = summarizer.summarize(cleanedText);
                    } catch (Exception e) {
                        Log.w(
                            TAG,
                            doc.name +
                                ": summarization failed: " +
                                e.getMessage()
                        );
                        doc.summary = "";
                    }

                    // Step 6: Extract keywords
                    if (listener != null) listener.onStageChanged(
                        "Extracting keywords..."
                    );
                    try {
                        List<String> keywords =
                            TextPreprocessor.extractKeywords(mlText, 5);
                        doc.keywords = String.join(",", keywords);
                    } catch (Exception e) {
                        Log.w(
                            TAG,
                            doc.name +
                                ": keyword extraction failed: " +
                                e.getMessage()
                        );
                        doc.keywords = "";
                    }

                    // Step 7: Extract entities
                    if (listener != null) listener.onStageChanged(
                        "Extracting entities..."
                    );
                    try {
                        CountDownLatch latch = new CountDownLatch(1);
                        entityExtractor.extractEntities(
                            cleanedText,
                            entities -> {
                                doc.entities =
                                    EntityExtractorHelper.entitiesToString(
                                        entities
                                    );
                                latch.countDown();
                            }
                        );
                        latch.await();
                    } catch (Exception e) {
                        Log.w(
                            TAG,
                            doc.name +
                                ": entity extraction failed: " +
                                e.getMessage()
                        );
                        doc.entities = "";
                    }

                    // Step 8: Save to DB
                    if (listener != null) listener.onStageChanged("Saving...");
                    repository.insert(doc, null);

                    indexed.incrementAndGet();
                    Log.d(TAG, "Indexed: " + doc.name);
                } catch (Exception e) {
                    Log.e(
                        TAG,
                        "Failed to index " + doc.name + ": " + e.getMessage(),
                        e
                    );
                    failed.incrementAndGet();
                    failedNames.add(doc.name + " (error)");
                }
            }

            Log.d(
                TAG,
                "Indexing complete. Indexed: " +
                    indexed.get() +
                    " Failed: " +
                    failed.get()
            );
            if (listener != null) listener.onComplete(
                indexed.get(),
                failed.get(),
                failedNames
            );
        });
    }

    // =========================
    // EMBEDDING INPUT
    // =========================

    private String buildEmbeddingInput(String name, String text) {
        String title = name
            .replaceAll("\\.[^.]+$", "")
            .replaceAll("[_\\-]", " ")
            .trim();

        String[] words = text.split("\\s+");
        int total = words.length;

        StringBuilder sb = new StringBuilder(title).append(" ");

        int chunk = Math.min(60, total);
        for (int i = 0; i < chunk; i++) sb.append(words[i]).append(" ");

        if (total > 120) {
            int mid = total / 2;
            int end = Math.min(mid + 30, total);
            for (int i = mid; i < end; i++) sb.append(words[i]).append(" ");
        }

        return sb.toString().trim();
    }

    // =========================
    // TEXT EXTRACTION
    // =========================

    private String extractText(DocumentEntity doc) {
        Uri uri = Uri.parse(doc.uri);
        try {
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
                    Log.w(TAG, "Unsupported file type: " + doc.fileType);
                    return "";
            }
        } catch (Exception e) {
            Log.e(
                TAG,
                "Text extraction failed for " + doc.name + ": " + e.getMessage()
            );
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
