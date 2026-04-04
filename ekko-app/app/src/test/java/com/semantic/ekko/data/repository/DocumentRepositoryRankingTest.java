package com.semantic.ekko.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.semantic.ekko.data.model.DocumentEntity;
import java.util.Arrays;
import java.util.List;
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

    @Test
    public void filterRankedResults_keeps_only_top_lexical_tier() {
        List<DocumentRepository.RankedSearchResult> filtered =
            DocumentRepository.filterRankedResults(
                Arrays.asList(
                    rankedResult(3, 0.95f, "WrapperClass.java"),
                    rankedResult(3, 0.72f, "PrimitiveWrapperClass.java"),
                    rankedResult(1, 0.91f, "Syllabus.txt")
                )
            );

        assertEquals(2, filtered.size());
        assertEquals("WrapperClass.java", filtered.get(0).document.name);
        assertEquals("PrimitiveWrapperClass.java", filtered.get(1).document.name);
    }

    @Test
    public void filterRankedResults_returns_empty_when_no_proper_match_exists() {
        List<DocumentRepository.RankedSearchResult> filtered =
            DocumentRepository.filterRankedResults(
                Arrays.asList(
                    rankedResult(0, 0.81f, "Syllabus.txt"),
                    rankedResult(0, 0.63f, "Notes.txt")
                )
            );

        assertTrue(filtered.isEmpty());
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

    private static DocumentRepository.RankedSearchResult rankedResult(
        int lexicalTier,
        float score,
        String name
    ) {
        DocumentEntity document = new DocumentEntity();
        document.name = name;
        return new DocumentRepository.RankedSearchResult(
            document,
            score,
            lexicalTier,
            1f,
            1f,
            lexicalTier >= 3 ? 1f : 0f,
            0.5f,
            0.5f
        );
    }
}
