package com.semantic.ekko.ml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public class TextSummarizer {

    private static final int TARGET_SENTENCES = 3;
    private static final int MIN_SENTENCE_LENGTH = 40;
    private static final int MIN_WORD_COUNT = 8;
    private static final float MMR_LAMBDA = 0.7f;
    private static final int MAX_SENTENCES_TO_EMBED = 40;

    // Patterns to reject noise lines
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(unit|chapter|section|module|part|topic|lesson)[-\\s]?[\\divxlc]+.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("^\\d{1,4}$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^[•\\-*>]\\s*.*");
    private static final Pattern SHORT_HEADER_PATTERN = Pattern.compile(
            "^[A-Z][A-Z\\s\\d\\-:]{0,40}$");

    private final EmbeddingEngine embeddingEngine;

    public TextSummarizer(EmbeddingEngine embeddingEngine) {
        this.embeddingEngine = embeddingEngine;
    }

    // =========================
    // SUMMARIZE
    // =========================

    public String summarize(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return "";

        List<String> sentences = splitSentences(rawText);
        if (sentences.isEmpty()) return "";
        if (sentences.size() <= TARGET_SENTENCES) return joinSentences(sentences);

        List<String> candidates = sentences.size() > MAX_SENTENCES_TO_EMBED
                ? selectCandidates(sentences)
                : sentences;

        List<ScoredSentence> embedded = embedSentences(candidates);
        if (embedded.isEmpty()) return "";
        if (embedded.size() <= TARGET_SENTENCES) return joinSentences(extractText(embedded));

        float[][] allVectors = new float[embedded.size()][];
        for (int i = 0; i < embedded.size(); i++) allVectors[i] = embedded.get(i).embedding;

        float[] centroid = EmbeddingEngine.centroid(allVectors);
        if (centroid == null) return joinSentences(extractText(embedded.subList(0, TARGET_SENTENCES)));

        List<ScoredSentence> selected = mmrSelect(embedded, centroid);
        selected.sort(Comparator.comparingInt(s -> s.originalIndex));

        return joinSentences(extractText(selected));
    }

    // =========================
    // CANDIDATE SELECTION
    // =========================

    private List<String> selectCandidates(List<String> sentences) {
        List<String> result = new ArrayList<>();
        int total = sentences.size();

        for (int i = 0; i < Math.min(10, total); i++) result.add(sentences.get(i));

        int middleCount = MAX_SENTENCES_TO_EMBED - 15;
        int step = Math.max(1, (total - 15) / middleCount);
        for (int i = 10; i < total - 5 && result.size() < MAX_SENTENCES_TO_EMBED - 5; i += step) {
            result.add(sentences.get(i));
        }

        for (int i = Math.max(0, total - 5); i < total; i++) result.add(sentences.get(i));

        return result;
    }

    // =========================
    // MMR SELECTION
    // =========================

    private List<ScoredSentence> mmrSelect(List<ScoredSentence> candidates, float[] centroid) {
        List<ScoredSentence> selected = new ArrayList<>();
        List<ScoredSentence> remaining = new ArrayList<>(candidates);

        while (selected.size() < TARGET_SENTENCES && !remaining.isEmpty()) {
            ScoredSentence best = null;
            float bestScore = Float.NEGATIVE_INFINITY;

            for (ScoredSentence candidate : remaining) {
                float relevance = EmbeddingEngine.cosineSimilarity(candidate.embedding, centroid);

                float maxRedundancy = 0f;
                for (ScoredSentence s : selected) {
                    float sim = EmbeddingEngine.cosineSimilarity(candidate.embedding, s.embedding);
                    if (sim > maxRedundancy) maxRedundancy = sim;
                }

                float mmrScore = MMR_LAMBDA * relevance - (1f - MMR_LAMBDA) * maxRedundancy;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    best = candidate;
                }
            }

            if (best != null) {
                selected.add(best);
                remaining.remove(best);
            }
        }

        return selected;
    }

    // =========================
    // SENTENCE SPLITTING
    // =========================

    private List<String> splitSentences(String text) {
        // Split on sentence-ending punctuation
        String[] raw = text.split("(?<=[.!?])\\s+");
        List<String> result = new ArrayList<>();
        for (String s : raw) {
            String clean = s.trim();
            if (isValidSentence(clean)) result.add(clean);
        }
        return result;
    }

    private boolean isValidSentence(String s) {
        if (s == null || s.length() < MIN_SENTENCE_LENGTH) return false;

        // Reject page numbers
        if (PAGE_NUMBER_PATTERN.matcher(s.trim()).matches()) return false;

        // Reject unit/chapter headings
        if (HEADING_PATTERN.matcher(s.trim()).matches()) return false;

        // Reject bullet points
        if (BULLET_PATTERN.matcher(s.trim()).matches()) return false;

        // Reject short all-caps headers like "UNIT-I", "INTRODUCTION", "REFERENCES"
        if (SHORT_HEADER_PATTERN.matcher(s.trim()).matches()) return false;

        // Reject lines with too few alphabetic characters
        long alphaCount = s.chars().filter(Character::isLetter).count();
        if (alphaCount < s.length() * 0.5) return false;

        // Reject fragments with too few words
        String[] words = s.split("\\s+");
        if (words.length < MIN_WORD_COUNT) return false;

        // Reject lines that start with a number followed by a dot (numbered headings)
        if (s.matches("^\\d+\\.\\s.*") && words.length < 10) return false;

        return true;
    }

    // =========================
    // EMBEDDING
    // =========================

    private List<ScoredSentence> embedSentences(List<String> sentences) {
        List<ScoredSentence> result = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            float[] embedding = embeddingEngine.embed(sentences.get(i));
            if (embedding != null) result.add(new ScoredSentence(sentences.get(i), embedding, i));
        }
        return result;
    }

    // =========================
    // UTILS
    // =========================

    private String joinSentences(List<String> sentences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(sentences.get(i));
        }
        return sb.toString().trim();
    }

    private List<String> extractText(List<ScoredSentence> scored) {
        List<String> texts = new ArrayList<>();
        for (ScoredSentence s : scored) texts.add(s.sentence);
        return texts;
    }

    // =========================
    // DATA CLASS
    // =========================

    private static class ScoredSentence {
        final String sentence;
        final float[] embedding;
        final int originalIndex;

        ScoredSentence(String sentence, float[] embedding, int originalIndex) {
            this.sentence = sentence;
            this.embedding = embedding;
            this.originalIndex = originalIndex;
        }
    }
}
