package com.semantic.ekko.data.repository;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DocumentRepositoryRankingTest {

    @Test
    public void computeLexicalTier_prioritizes_exact_phrase_matches() {
        String normalizedQuery = SearchTextMatcher.normalize("wrapper class");
        String[] queryTerms = SearchTextMatcher.tokenize(normalizedQuery);

        SearchTextMatcher.Signals weakMetadata = SearchTextMatcher.analyze(
            normalizedQuery,
            queryTerms,
            "java syllabus"
        );
        SearchTextMatcher.Signals weakContent = SearchTextMatcher.analyze(
            normalizedQuery,
            queryTerms,
            "This class covers generics and interfaces."
        );
        SearchTextMatcher.Signals strongContent = SearchTextMatcher.analyze(
            normalizedQuery,
            queryTerms,
            "A wrapper class wraps a primitive in Java."
        );

        int weakTier = DocumentRepository.computeLexicalTier(
            queryTerms.length,
            weakMetadata,
            weakContent,
            buildChunkSignals(0.5f, false)
        );
        int strongTier = DocumentRepository.computeLexicalTier(
            queryTerms.length,
            weakMetadata,
            strongContent,
            buildChunkSignals(1f, true)
        );

        assertEquals(0, weakTier);
        assertEquals(3, strongTier);
    }

    private static DocumentRepository.ChunkSearchSignals buildChunkSignals(
        float coverage,
        boolean phraseMatch
    ) {
        return new DocumentRepository.ChunkSearchSignals(
            0f,
            0f,
            coverage,
            coverage > 0f,
            phraseMatch,
            coverage,
            coverage >= 1f || phraseMatch,
            1
        );
    }
}
