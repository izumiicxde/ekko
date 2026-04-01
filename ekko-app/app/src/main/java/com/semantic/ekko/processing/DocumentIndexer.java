package com.semantic.ekko.processing;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.ChunkDao;
import com.semantic.ekko.data.model.ChunkEntity;
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
    private static final long PER_DOCUMENT_COOLDOWN_MS = 120L;

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
    private final ChunkDao chunkDao;
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
        this.chunkDao = AppDatabase.getInstance(context).chunkDao();
        this.executor = Executors.newSingleThreadExecutor();
    }

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

                    String cleanedText = TextPreprocessor.clean(rawText);
                    String mlText = TextPreprocessor.cleanForMl(rawText);
                    doc.rawText = cleanedText;
                    doc.wordCount = TextPreprocessor.wordCount(cleanedText);

                    if (listener != null) listener.onStageChanged(
                        "Classifying..."
                    );
                    doc.category = classifier.classify(mlText);

                    if (listener != null) listener.onStageChanged(
                        "Embedding..."
                    );
                    String embeddingInput = buildEmbeddingInput(
                        doc.name,
                        mlText
                    );
                    float[] embedding = embeddingEngine.embed(embeddingInput);
                    if (embedding != null) doc.embedding =
                        EmbeddingEngine.toBytes(embedding);

                    // Summary is generated on-demand in Detail screen.
                    doc.summary = "";

                    if (listener != null) listener.onStageChanged(
                        "Extracting keywords..."
                    );
                    try {
                        List<String> keywords =
                            TextPreprocessor.extractKeywords(mlText, 5);
                        doc.keywords = String.join(",", keywords);
                    } catch (Exception e) {
                        doc.keywords = "";
                    }

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
                        doc.entities = "";
                    }

                    if (listener != null) listener.onStageChanged("Saving...");
                    final long[] docId = { -1 };
                    CountDownLatch insertLatch = new CountDownLatch(1);
                    repository.insert(doc, id -> {
                        docId[0] = id;
                        insertLatch.countDown();
                    });
                    insertLatch.await();

                    if (listener != null) listener.onStageChanged(
                        "Chunking for Q&A..."
                    );
                    try {
                        List<String> chunks = ChunkUtils.chunk(cleanedText);
                        List<ChunkEntity> chunkEntities = new ArrayList<>();
                        for (int c = 0; c < chunks.size(); c++) {
                            String chunkText = chunks.get(c);
                            float[] chunkEmbedding = embeddingEngine.embed(
                                chunkText
                            );
                            byte[] embeddingBytes =
                                chunkEmbedding != null
                                    ? EmbeddingEngine.toBytes(chunkEmbedding)
                                    : null;
                            chunkEntities.add(
                                new ChunkEntity(
                                    docId[0],
                                    c,
                                    chunkText,
                                    embeddingBytes
                                )
                            );
                        }
                        chunkDao.deleteByDocumentId(docId[0]);
                        chunkDao.insertAll(chunkEntities);
                        Log.d(
                            TAG,
                            doc.name +
                                ": " +
                                chunkEntities.size() +
                                " chunks indexed"
                        );
                    } catch (Exception e) {
                        Log.w(
                            TAG,
                            doc.name +
                                ": chunk indexing failed: " +
                                e.getMessage()
                        );
                    }

                    indexed.incrementAndGet();
                    Log.d(TAG, "Indexed: " + doc.name);
                    SystemClock.sleep(PER_DOCUMENT_COOLDOWN_MS);
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

            if (listener != null) listener.onComplete(
                indexed.get(),
                failed.get(),
                failedNames
            );
        });
    }

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

    public void shutdown() {
        executor.shutdown();
    }
}
