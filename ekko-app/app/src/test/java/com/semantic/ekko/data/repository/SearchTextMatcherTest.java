package com.semantic.ekko.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchTextMatcherTest {

    @Test
    public void analyze_requires_strong_coverage_for_multi_term_queries() {
        String normalizedQuery = SearchTextMatcher.normalize("wrapper class");
        String[] terms = SearchTextMatcher.tokenize(normalizedQuery);

        SearchTextMatcher.Signals weakSignals = SearchTextMatcher.analyze(
            normalizedQuery,
            terms,
            "Course syllabus for class participation and grading"
        );
        SearchTextMatcher.Signals strongSignals = SearchTextMatcher.analyze(
            normalizedQuery,
            terms,
            "In Java, a wrapper class boxes primitive values."
        );

        assertEquals(0.5f, weakSignals.coverageScore(), 0.0001f);
        assertFalse(weakSignals.hasStrongMatch());
        assertEquals(1f, strongSignals.coverageScore(), 0.0001f);
        assertTrue(strongSignals.hasStrongMatch());
        assertTrue(strongSignals.phraseMatch);
    }

    @Test
    public void field_score_uses_whole_terms_not_substrings() {
        String[] terms = SearchTextMatcher.tokenize(
            SearchTextMatcher.normalize("wrap class")
        );

        float score = SearchTextMatcher.fieldScore(
            "wrapper classification notes",
            terms
        );

        assertEquals(0f, score, 0.0001f);
    }
}
