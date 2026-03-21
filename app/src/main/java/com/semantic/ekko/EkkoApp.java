package com.semantic.ekko;

import android.app.Application;
import android.util.Log;
import com.semantic.ekko.ml.DocumentClassifier;
import com.semantic.ekko.ml.EmbeddingEngine;
import com.semantic.ekko.ml.TextSummarizer;
import com.semantic.ekko.processing.extractor.PdfTextExtractor;

public class EkkoApp extends Application {

    private static final String TAG = "EkkoApp";

    private static EkkoApp instance;
    private EmbeddingEngine embeddingEngine;
    private DocumentClassifier documentClassifier;
    private TextSummarizer textSummarizer;
    private boolean mlReady = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PdfTextExtractor.init(this);
        initMlAsync();
    }

    // =========================
    // SINGLETON
    // =========================

    public static EkkoApp getInstance() {
        return instance;
    }

    // =========================
    // ML INIT
    // =========================

    private void initMlAsync() {
        new Thread(() -> {
            try {
                embeddingEngine = new EmbeddingEngine(this);
                documentClassifier = new DocumentClassifier(embeddingEngine);
                textSummarizer = new TextSummarizer(embeddingEngine);
                mlReady = true;
                Log.d(TAG, "ML components initialized successfully");
            } catch (Exception e) {
                Log.e(
                    TAG,
                    "Failed to initialize ML components: " + e.getMessage()
                );
            }
        })
            .start();
    }

    // =========================
    // ACCESSORS
    // =========================

    public EmbeddingEngine getEmbeddingEngine() {
        return embeddingEngine;
    }

    public DocumentClassifier getDocumentClassifier() {
        return documentClassifier;
    }

    public TextSummarizer getTextSummarizer() {
        return textSummarizer;
    }

    public boolean isMlReady() {
        return mlReady;
    }
}
