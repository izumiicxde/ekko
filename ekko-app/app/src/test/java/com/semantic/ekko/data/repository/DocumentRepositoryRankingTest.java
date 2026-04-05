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
    public void compareRankedResults_prioritizes_filename_match_over_broader_content() {
        DocumentRepository.RankedSearchResult fileSpecific = rankedResult(
            1,
            0.42f,
            "java-wrapper-class.pdf",
            0.40f,
            0.39f,
            1f,
            1f,
            1f
        );
        DocumentRepository.RankedSearchResult broadDocument = rankedResult(
            1,
            0.64f,
            "semester-notes.pdf",
            0.66f,
            0.61f,
            0.2f,
            0f,
            1f
        );

        assertTrue(
            DocumentRepository.compareRankedResults(fileSpecific, broadDocument) < 0
        );
    }

    @Test
    public void filterRankedResults_returns_empty_when_no_proper_match_exists() {
        List<DocumentRepository.RankedSearchResult> filtered =
            DocumentRepository.filterRankedResults(
                Arrays.asList(
                    rankedResult(0, 0.16f, "Syllabus.txt"),
                    rankedResult(0, 0.14f, "Notes.txt")
                )
            );

        assertTrue(filtered.isEmpty());
    }

    @Test
    public void filterRankedResults_keeps_semantic_matches_when_no_lexical_hit_exists() {
        List<DocumentRepository.RankedSearchResult> filtered =
            DocumentRepository.filterRankedResults(
                Arrays.asList(
                    rankedResult(0, 0.61f, "Semantic-A.txt", 0.64f, 0.58f),
                    rankedResult(0, 0.44f, "Semantic-B.txt", 0.49f, 0.41f),
                    rankedResult(0, 0.12f, "Weak.txt", 0.18f, 0.16f)
                )
            );

        assertEquals(2, filtered.size());
        assertEquals("Semantic-A.txt", filtered.get(0).document.name);
        assertEquals("Semantic-B.txt", filtered.get(1).document.name);
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
        return rankedResult(lexicalTier, score, name, 0.5f, 0.5f, 1f, 1f, lexicalTier >= 3 ? 1f : 0f);
    }

    private static DocumentRepository.RankedSearchResult rankedResult(
        int lexicalTier,
        float score,
        String name,
        float docEmbedding,
        float bestChunkEmbedding
    ) {
        return rankedResult(
            lexicalTier,
            score,
            name,
            docEmbedding,
            bestChunkEmbedding,
            1f,
            1f,
            lexicalTier >= 3 ? 1f : 0f
        );
    }

    private static DocumentRepository.RankedSearchResult rankedResult(
        int lexicalTier,
        float score,
        String name,
        float docEmbedding,
        float bestChunkEmbedding,
        float metadataCoverage,
        float filenameScore,
        float metadataPhraseScore
    ) {
        DocumentEntity document = new DocumentEntity();
        document.name = name;
        return new DocumentRepository.RankedSearchResult(
            document,
            score,
            lexicalTier,
            1f,
            metadataCoverage,
            filenameScore,
            metadataPhraseScore,
            1f,
            docEmbedding,
            bestChunkEmbedding
        );
    }
}
