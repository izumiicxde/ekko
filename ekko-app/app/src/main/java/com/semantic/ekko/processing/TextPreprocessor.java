package com.semantic.ekko.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TextPreprocessor {

    private static final Set<String> STOPWORDS = new HashSet<>(
        Arrays.asList(
            "a",
            "an",
            "the",
            "and",
            "or",
            "but",
            "in",
            "on",
            "at",
            "to",
            "for",
            "of",
            "with",
            "by",
            "from",
            "is",
            "are",
            "was",
            "were",
            "be",
            "been",
            "being",
            "have",
            "has",
            "had",
            "do",
            "does",
            "did",
            "will",
            "would",
            "could",
            "should",
            "may",
            "might",
            "shall",
            "this",
            "that",
            "these",
            "those",
            "it",
            "its",
            "they",
            "them",
            "their",
            "we",
            "our",
            "you",
            "your",
            "he",
            "she",
            "his",
            "her",
            "i",
            "me",
            "my",
            "not",
            "no",
            "so",
            "if",
            "as",
            "up",
            "out",
            "about",
            "into",
            "than",
            "then",
            "also",
            "can",
            "just",
            "more",
            "which",
            "who",
            "what",
            "when",
            "where",
            "how",
            "all",
            "any",
            "both",
            "each",
            "few",
            "other",
            "such",
            "only",
            "same",
            "very",
            "page",
            "chapter",
            "unit",
            "section",
            "module",
            "topic",
            "part",
            "introduction",
            "conclusion",
            "summary",
            "definition",
            "example",
            "figure",
            "table",
            "note",
            "notes",
            "reference",
            "references",
            "department",
            "college",
            "university",
            "institute",
            "faculty",
            "prepared",
            "presented",
            "submitted",
            "staff",
            "professor",
            "lecturer",
            "author",
            "name",
            "date",
            "year",
            "semester",
            "subject",
            "course",
            "code",
            "marks",
            "exam",
            "question",
            "answer",
            "solution",
            "problem",
            "given",
            "find",
            "show",
            "prove",
            "derive",
            "explain",
            "describe",
            "define",
            "list",
            "following",
            "above",
            "below",
            "hence",
            "therefore",
            "thus",
            "since",
            "because",
            "while",
            "although",
            "however",
            "moreover",
            "furthermore",
            "whereas",
            "according",
            "based",
            "using",
            "used",
            "used",
            "called",
            "known",
            "said",
            "made",
            "given",
            "taken"
        )
    );

    // =========================
    // CLEAN FOR DISPLAY
    // =========================

    public static String clean(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";

        String text = raw;
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        text = text.replaceAll("[\\u2013\\u2014]", "-");
        text = text.replaceAll("[\\u2018\\u2019]", "'");
        text = text.replaceAll("[\\u201C\\u201D]", "\"");
        text = text.replaceAll("[.]{3,}", "...");
        text = text.replaceAll("[-]{2,}", "-");
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("(?m)^\\s*\\d+\\s*$", "");
        text = text.replaceAll("(?m)^.{0,2}$\\n?", "");

        return text.trim();
    }

    // =========================
    // CLEAN FOR ML
    // =========================

    public static String cleanForMl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";

        String text = clean(raw);
        text = text.replaceAll("\\s+", " ");
        text = text.toLowerCase();
        text = text.replaceAll("[^a-z0-9 .,!?;:()'\"\\-]", "");

        return text.trim();
    }

    // =========================
    // KEYWORD EXTRACTION
    // =========================

    /**
     * Extracts meaningful keywords from text using term frequency.
     * Filters stopwords, short tokens, numbers, and common academic noise
     * like professor names, page numbers, and section headers.
     */
    public static String[] tokenizeForKeywords(String text) {
        if (text == null || text.trim().isEmpty()) return new String[0];

        String lower = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
        String[] tokens = lower.trim().split("\\s+");

        return Arrays.stream(tokens)
            .filter(t -> t.length() >= 5)
            .filter(t -> !STOPWORDS.contains(t))
            .filter(t -> !t.matches("\\d+"))
            .filter(t -> !t.matches("[a-z]{1,2}\\d+.*"))
            .filter(t -> !t.matches(".*\\d{4,}.*"))
            .toArray(String[]::new);
    }

    public static List<String> extractKeywords(String text, int topN) {
        if (text == null || text.trim().isEmpty()) return new ArrayList<>();

        String[] tokens = tokenizeForKeywords(text);
        Map<String, Integer> freq = new LinkedHashMap<>();

        for (String token : tokens) {
            freq.put(token, freq.getOrDefault(token, 0) + 1);
        }

        return freq
            .entrySet()
            .stream()
            .filter(e -> e.getValue() >= 2)
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(topN)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
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

    public static boolean isStopword(String word) {
        return STOPWORDS.contains(word.toLowerCase());
    }
}
