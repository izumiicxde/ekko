package com.semantic.ekko.processing;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class TextPreprocessor {

    // Common stopwords to strip during keyword extraction
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to",
            "for", "of", "with", "by", "from", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "could", "should", "may", "might", "shall",
            "this", "that", "these", "those", "it", "its", "they", "them",
            "their", "we", "our", "you", "your", "he", "she", "his", "her",
            "i", "me", "my", "not", "no", "so", "if", "as", "up", "out",
            "about", "into", "than", "then", "also", "can", "just", "more",
            "which", "who", "what", "when", "where", "how", "all", "any",
            "both", "each", "few", "other", "such", "only", "same", "very"
    ));

    // =========================
    // CLEAN FOR DISPLAY
    // =========================

    /**
     * Cleans raw extracted text for display and ML input.
     * Removes excessive whitespace, control characters, and PDF artifacts.
     */
    public static String clean(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";

        String text = raw;

        // Remove null bytes and control characters except newlines and tabs
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        // Normalize unicode dashes and quotes
        text = text.replaceAll("[\\u2013\\u2014]", "-");
        text = text.replaceAll("[\\u2018\\u2019]", "'");
        text = text.replaceAll("[\\u201C\\u201D]", "\"");

        // Remove repeated punctuation
        text = text.replaceAll("[.]{3,}", "...");
        text = text.replaceAll("[-]{2,}", "-");

        // Collapse multiple spaces into one
        text = text.replaceAll("[ \\t]+", " ");

        // Collapse more than two consecutive newlines into two
        text = text.replaceAll("\\n{3,}", "\n\n");

        // Remove lines that are purely numeric (page numbers)
        text = text.replaceAll("(?m)^\\s*\\d+\\s*$", "");

        // Remove lines shorter than 3 characters (noise lines)
        text = text.replaceAll("(?m)^.{0,2}$\\n?", "");

        return text.trim();
    }

    // =========================
    // CLEAN FOR ML INPUT
    // =========================

    /**
     * Produces a flattened, lowercased plain text string suitable for
     * embedding and classification. Strips formatting and special characters.
     */
    public static String cleanForMl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";

        String text = clean(raw);

        // Flatten to single line
        text = text.replaceAll("\\s+", " ");

        // Lowercase
        text = text.toLowerCase();

        // Remove non-alphanumeric except spaces and basic punctuation
        text = text.replaceAll("[^a-z0-9 .,!?;:()'\"\\-]", "");

        return text.trim();
    }

    // =========================
    // KEYWORD EXTRACTION SUPPORT
    // =========================

    /**
     * Tokenizes and filters text for keyword extraction.
     * Removes stopwords, short tokens, and purely numeric tokens.
     */
    public static String[] tokenizeForKeywords(String text) {
        if (text == null || text.trim().isEmpty()) return new String[0];

        String lower = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
        String[] tokens = lower.trim().split("\\s+");

        return Arrays.stream(tokens)
                .filter(t -> t.length() >= 4)
                .filter(t -> !STOPWORDS.contains(t))
                .filter(t -> !t.matches("\\d+"))
                .toArray(String[]::new);
    }

    /**
     * Extracts simple top keywords from text using term frequency.
     * Used as a lightweight fallback when embedding-based extraction is unavailable.
     */
    public static java.util.List<String> extractKeywords(String text, int topN) {
        if (text == null || text.trim().isEmpty()) return new java.util.ArrayList<>();

        String[] tokens = tokenizeForKeywords(text);
        java.util.Map<String, Integer> freq = new java.util.LinkedHashMap<>();

        for (String token : tokens) {
            freq.put(token, freq.getOrDefault(token, 0) + 1);
        }

        return freq.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(topN)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    // =========================
    // WORD COUNT
    // =========================

    public static int wordCount(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    public static int estimatedReadTime(int wordCount) {
        return Math.max(1, wordCount / 200);
    }

    // =========================
    // STOPWORDS
    // =========================

    public static boolean isStopword(String word) {
        return STOPWORDS.contains(word.toLowerCase());
    }
}
