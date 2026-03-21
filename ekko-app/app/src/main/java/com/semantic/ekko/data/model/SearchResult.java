package com.semantic.ekko.data.model;

public class SearchResult {

    private final DocumentEntity document;
    private final float score;

    public SearchResult(DocumentEntity document, float score) {
        this.document = document;
        this.score = score;
    }

    public DocumentEntity getDocument() {
        return document;
    }

    /**
     * Cosine similarity score between query embedding and document embedding.
     * Range: 0.0 (no match) to 1.0 (identical).
     */
    public float getScore() {
        return score;
    }

    /**
     * Returns score as a percentage string for display in the UI.
     * Example: 0.87 -> "87%"
     */
    public String getScoreLabel() {
        int percent = Math.round(score * 100);
        return percent + "%";
    }

    /**
     * Returns a relevance tier label based on score range.
     * Used for badge color in SearchResultAdapter.
     */
    public String getRelevanceTier() {
        if (score >= 0.75f) return "High";
        if (score >= 0.50f) return "Medium";
        return "Low";
    }
}
