package com.semantic.ekko.data.repository;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class SearchTextMatcher {

    private SearchTextMatcher() {}

    static Signals analyze(
        String normalizedQuery,
        String[] queryTerms,
        String... fieldTexts
    ) {
        if (
            normalizedQuery == null ||
            normalizedQuery.isEmpty() ||
            queryTerms == null ||
            queryTerms.length == 0
        ) {
            return Signals.empty();
        }

        boolean phraseMatch = false;
        boolean[] matchedTerms = new boolean[queryTerms.length];
        int matchedCount = 0;

        for (String fieldText : fieldTexts) {
            String normalizedField = normalize(fieldText);
            if (normalizedField.isEmpty()) {
                continue;
            }

            String paddedField = " " + normalizedField + " ";
            if (!phraseMatch && paddedField.contains(" " + normalizedQuery + " ")) {
                phraseMatch = true;
            }

            for (int i = 0; i < queryTerms.length; i++) {
                if (matchedTerms[i]) {
                    continue;
                }
                String term = queryTerms[i];
                if (term.length() < 2) {
                    continue;
                }
                if (paddedField.contains(" " + term + " ")) {
                    matchedTerms[i] = true;
                    matchedCount++;
                }
            }
        }

        return new Signals(matchedCount, queryTerms.length, phraseMatch);
    }

    static float fieldScore(String fieldText, String[] queryTerms) {
        if (
            fieldText == null || fieldText.isEmpty() || queryTerms == null || queryTerms.length == 0
        ) {
            return 0f;
        }

        String normalizedField = normalize(fieldText);
        if (normalizedField.isEmpty()) {
            return 0f;
        }

        String paddedField = " " + normalizedField + " ";
        int matches = 0;
        int validTerms = 0;
        for (String term : queryTerms) {
            if (term == null || term.length() < 2) {
                continue;
            }
            validTerms++;
            if (paddedField.contains(" " + term + " ")) {
                matches++;
            }
        }
        if (validTerms == 0) {
            return 0f;
        }
        return (float) matches / validTerms;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    }

    static String[] tokenize(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return new String[0];
        }
        String[] rawTerms = normalizedQuery.split("\\s+");
        Set<String> uniqueTerms = new LinkedHashSet<>();
        for (String term : rawTerms) {
            if (term != null && !term.isEmpty()) {
                uniqueTerms.add(term);
            }
        }
        return uniqueTerms.toArray(new String[0]);
    }

    static final class Signals {

        final int matchedTermCount;
        final int totalTermCount;
        final boolean phraseMatch;

        Signals(int matchedTermCount, int totalTermCount, boolean phraseMatch) {
            this.matchedTermCount = matchedTermCount;
            this.totalTermCount = totalTermCount;
            this.phraseMatch = phraseMatch;
        }

        boolean hasAnyMatch() {
            return matchedTermCount > 0;
        }

        float coverageScore() {
            if (totalTermCount == 0) {
                return 0f;
            }
            return (float) matchedTermCount / totalTermCount;
        }

        boolean hasStrongMatch() {
            if (phraseMatch) {
                return true;
            }
            if (totalTermCount <= 1) {
                return matchedTermCount >= 1;
            }
            return matchedTermCount >= 2;
        }

        static Signals empty() {
            return new Signals(0, 0, false);
        }
    }
}
