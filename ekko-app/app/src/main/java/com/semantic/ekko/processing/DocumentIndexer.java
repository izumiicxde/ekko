package com.semantic.ekko.processing;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.ChunkDao;
import com.semantic.ekko.data.db.DocumentDao;
import com.semantic.ekko.data.model.ChunkEntity;
import com.semantic.ekko.data.model.DocumentEntity;
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
    private static final int MAX_CHUNKS_TO_EMBED = 24;

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
    private final AppDatabase database;
    private final DocumentDao documentDao;
    private final ChunkDao chunkDao;
    private final ExecutorService executor;
    private volatile boolean cancelled = false;

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
        this.database = AppDatabase.getInstance(context);
        this.documentDao = database.documentDao();
        this.chunkDao = database.chunkDao();
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void indexDocuments(
        List<DocumentEntity> documents,
        ProgressListener listener
    ) {
        executor.execute(() -> {
            cancelled = false;
            int total = documents.size();
            AtomicInteger indexed = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            List<String> failedNames = new ArrayList<>();

            if (listener != null) listener.onStageChanged(
                "Scanning documents..."
            );

            for (int i = 0; i < total; i++) {
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    break;
                }

                DocumentEntity doc = documents.get(i);
                DocumentEntity existing = documentDao.getByUri(doc.uri);

                try {
                    if (shouldSkipReindex(existing, doc)) {
                        indexed.incrementAndGet();
                        if (listener != null) listener.onDocumentProcessed(
                            indexed.get(),
                            total,
                            doc.name
                        );
                        continue;
                    }

                    if (listener != null) listener.onStageChanged(
                        "Extracting text..."
                    );
                    String rawText = extractText(doc);

                    if (rawText == null || rawText.trim().length() < 20) {
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

                    if (existing != null) {
                        doc.id = existing.id;
                    }
                    doc.indexedAt = System.currentTimeMillis();

                    if (listener != null) listener.onStageChanged("Saving...");
                    final long[] docId = { -1 };
                    database.runInTransaction(() -> {
                        if (doc.id > 0) {
                            documentDao.update(doc);
                            docId[0] = doc.id;
                        } else {
                            docId[0] = documentDao.insert(doc);
                            doc.id = docId[0];
                        }
                        chunkDao.deleteByDocumentId(docId[0]);
                    });

                    if (listener != null) listener.onStageChanged(
                        "Chunking for Q&A..."
                    );
                    try {
                        List<String> chunks = limitChunksForEmbedding(
                            ChunkUtils.chunk(cleanedText)
                        );
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
                        chunkDao.insertAll(chunkEntities);
                    } catch (Exception ignored) {
                    }

                    indexed.incrementAndGet();
                    if (listener != null) listener.onDocumentProcessed(
                        indexed.get(),
                        total,
                        doc.name
                    );
                } catch (Exception e) {
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

    private boolean shouldSkipReindex(
        DocumentEntity existing,
        DocumentEntity scanned
    ) {
        if (existing == null) return false;
        if (existing.sourceSize != scanned.sourceSize) return false;
        if (
            scanned.sourceModifiedAt > 0 &&
            existing.sourceModifiedAt != scanned.sourceModifiedAt
        ) {
            return false;
        }
        if (existing.rawText == null || existing.rawText.trim().isEmpty()) {
            return false;
        }
        if (existing.embedding == null || existing.embedding.length == 0) {
            return false;
        }
        if (existing.wordCount < 20) {
            return false;
        }
        return chunkDao.getCountByDocumentId(existing.id) > 0;
    }

    private String buildEmbeddingInput(String name, String text) {
        String title = name
            .replaceAll("\\.[^.]+$", "")
            .replaceAll("[_\\-]", " ")
            .trim();
        String[] words = text.split("\\s+");
        int total = words.length;
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) {
            sb.append(title).append(" ");
        }

        appendWordWindow(sb, words, 0, Math.min(60, total));

        if (total > 120) {
            int midStart = Math.max(0, (total / 2) - 20);
            appendWordWindow(sb, words, midStart, Math.min(midStart + 40, total));
        }

        if (total > 220) {
            int tailStart = Math.max(0, total - 50);
            appendWordWindow(sb, words, tailStart, total);
        }
        return sb.toString().trim();
    }

    private void appendWordWindow(
        StringBuilder sb,
        String[] words,
        int start,
        int end
    ) {
        for (int i = start; i < end; i++) {
            sb.append(words[i]).append(" ");
        }
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
            return "";
        }
    }

    private List<String> limitChunksForEmbedding(List<String> chunks) {
        if (chunks == null || chunks.size() <= MAX_CHUNKS_TO_EMBED) {
            return chunks != null ? chunks : new ArrayList<>();
        }

        List<String> limited = new ArrayList<>();
        double step = (double) (chunks.size() - 1) / (MAX_CHUNKS_TO_EMBED - 1);
        for (int i = 0; i < MAX_CHUNKS_TO_EMBED; i++) {
            int index = (int) Math.round(i * step);
            index = Math.max(0, Math.min(index, chunks.size() - 1));
            limited.add(chunks.get(index));
        }
        return limited;
    }

    public void shutdown() {
        cancelled = true;
        executor.shutdownNow();
    }
}
