package com.semantic.ekko.ml;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class DocumentClassifier {

    private static final String[] LABELS = {
            "Technical", "Research", "Legal", "Medical", "General"
    };

    private static final float MIN_CONFIDENCE_GAP = 0.15f;

    private static final Map<String, String> KEYWORD_RULES = new HashMap<>();

    static {
        KEYWORD_RULES.put("algorithm",      "Technical");
        KEYWORD_RULES.put("database",       "Technical");
        KEYWORD_RULES.put("network",        "Technical");
        KEYWORD_RULES.put("software",       "Technical");
        KEYWORD_RULES.put("hardware",       "Technical");
        KEYWORD_RULES.put("programming",    "Technical");
        KEYWORD_RULES.put("function",       "Technical");
        KEYWORD_RULES.put("compiler",       "Technical");
        KEYWORD_RULES.put("protocol",       "Technical");
        KEYWORD_RULES.put("architecture",   "Technical");
        KEYWORD_RULES.put("abstract",       "Research");
        KEYWORD_RULES.put("hypothesis",     "Research");
        KEYWORD_RULES.put("methodology",    "Research");
        KEYWORD_RULES.put("findings",       "Research");
        KEYWORD_RULES.put("experiment",     "Research");
        KEYWORD_RULES.put("literature",     "Research");
        KEYWORD_RULES.put("conclusion",     "Research");
        KEYWORD_RULES.put("citation",       "Research");
        KEYWORD_RULES.put("journal",        "Research");
        KEYWORD_RULES.put("survey",         "Research");
        KEYWORD_RULES.put("contract",       "Legal");
        KEYWORD_RULES.put("agreement",      "Legal");
        KEYWORD_RULES.put("clause",         "Legal");
        KEYWORD_RULES.put("jurisdiction",   "Legal");
        KEYWORD_RULES.put("plaintiff",      "Legal");
        KEYWORD_RULES.put("defendant",      "Legal");
        KEYWORD_RULES.put("statute",        "Legal");
        KEYWORD_RULES.put("liability",      "Legal");
        KEYWORD_RULES.put("indemnity",      "Legal");
        KEYWORD_RULES.put("arbitration",    "Legal");
        KEYWORD_RULES.put("diagnosis",      "Medical");
        KEYWORD_RULES.put("treatment",      "Medical");
        KEYWORD_RULES.put("patient",        "Medical");
        KEYWORD_RULES.put("symptoms",       "Medical");
        KEYWORD_RULES.put("clinical",       "Medical");
        KEYWORD_RULES.put("dosage",         "Medical");
        KEYWORD_RULES.put("surgery",        "Medical");
        KEYWORD_RULES.put("prescription",   "Medical");
        KEYWORD_RULES.put("pathology",      "Medical");
        KEYWORD_RULES.put("pharmaceutical", "Medical");
    }

    private final EmbeddingEngine embeddingEngine;
    private float[][] categoryCentroids;
    private boolean centroidsReady = false;

    // =========================
    // INIT
    // =========================

    public DocumentClassifier(EmbeddingEngine embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
        // Centroids are built lazily on first classify() call
    }

    // =========================
    // CLASSIFY
    // =========================

    public String classify(String text) {
        if (text == null || text.trim().isEmpty()) return "General";

        ensureCentroids();

        String excerpt = extractExcerpt(text);
        float[] docEmbedding = embeddingEngine.embed(excerpt);
        if (docEmbedding == null) return fallbackClassify(text);

        float[] scores = new float[LABELS.length];
        for (int i = 0; i < categoryCentroids.length; i++) {
            if (categoryCentroids[i] == null) continue;
            scores[i] = EmbeddingEngine.cosineSimilarity(docEmbedding, categoryCentroids[i]);
        }

        int topIndex = argmax(scores);
        int secondIndex = argSecond(scores, topIndex);
        float gap = scores[topIndex] - scores[secondIndex];

        if (gap >= MIN_CONFIDENCE_GAP) return LABELS[topIndex];
        return fallbackClassify(text);
    }

    public float confidence(String text) {
        if (text == null || text.trim().isEmpty()) return 0f;

        ensureCentroids();

        String excerpt = extractExcerpt(text);
        float[] docEmbedding = embeddingEngine.embed(excerpt);
        if (docEmbedding == null) return 0f;

        float[] scores = new float[LABELS.length];
        for (int i = 0; i < categoryCentroids.length; i++) {
            if (categoryCentroids[i] == null) continue;
            scores[i] = EmbeddingEngine.cosineSimilarity(docEmbedding, categoryCentroids[i]);
        }

        return scores[argmax(scores)];
    }

    // =========================
    // LAZY CENTROID BUILD
    // =========================

    /**
     * Builds category centroids on first use.
     * Uses only 2 seed sentences per category instead of 5
     * to reduce startup embedding time significantly.
     */
    private synchronized void ensureCentroids() {
        if (centroidsReady) return;
        buildCategoryCentroids();
        centroidsReady = true;
    }

    private void buildCategoryCentroids() {
        // Reduced to 2 seeds per category for faster init
        String[][] categorySeeds = {
                // Technical
                {
                        "This document covers algorithms, data structures, and software systems.",
                        "Network protocols, database schemas, and system architecture are explained."
                },
                // Research
                {
                        "This paper presents findings from experimental methodology and hypothesis testing.",
                        "Literature review and citation of prior work is included in this study."
                },
                // Legal
                {
                        "This agreement outlines the terms, clauses, and obligations of all parties.",
                        "Jurisdiction, liability, and arbitration are defined in the following sections."
                },
                // Medical
                {
                        "The patient presents with symptoms requiring clinical evaluation and treatment.",
                        "Diagnosis was confirmed following laboratory tests and pharmaceutical review."
                },
                // General
                {
                        "This document contains general information on various topics.",
                        "The content covers a wide range of subjects without specialization."
                }
        };

        categoryCentroids = new float[LABELS.length][];

        for (int i = 0; i < categorySeeds.length; i++) {
            float[][] seedEmbeddings = new float[categorySeeds[i].length][];
            int valid = 0;
            for (int j = 0; j < categorySeeds[i].length; j++) {
                float[] emb = embeddingEngine.embed(categorySeeds[i][j]);
                if (emb != null) seedEmbeddings[valid++] = emb;
            }
            if (valid > 0) {
                categoryCentroids[i] = EmbeddingEngine.centroid(
                        Arrays.copyOf(seedEmbeddings, valid));
            }
        }
    }

    // =========================
    // FALLBACK
    // =========================

    private String fallbackClassify(String text) {
        String lower = text.toLowerCase();
        Map<String, Integer> counts = new HashMap<>();

        for (Map.Entry<String, String> entry : KEYWORD_RULES.entrySet()) {
            if (lower.contains(entry.getKey())) {
                String cat = entry.getValue();
                counts.put(cat, counts.getOrDefault(cat, 0) + 1);
            }
        }

        String best = "General";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    // =========================
    // UTILS
    // =========================

    private String extractExcerpt(String text) {
        String trimmed = text.trim();
        if (trimmed.length() <= 600) return trimmed;
        String start = trimmed.substring(0, 300);
        int mid = trimmed.length() / 2;
        String middle = trimmed.substring(mid, Math.min(mid + 300, trimmed.length()));
        return start + " " + middle;
    }

    private int argmax(float[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) if (arr[i] > arr[idx]) idx = i;
        return idx;
    }

    private int argSecond(float[] arr, int skipIndex) {
        int idx = (skipIndex == 0) ? 1 : 0;
        for (int i = 0; i < arr.length; i++) {
            if (i == skipIndex) continue;
            if (arr[i] > arr[idx]) idx = i;
        }
        return idx;
    }

    public String[] getLabels() { return LABELS; }
}
