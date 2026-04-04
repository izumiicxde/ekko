package com.semantic.ekko.processing;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * Splits document text into overlapping chunks suitable for RAG retrieval.
 *
 * Each chunk is a window of words with a configurable size and overlap.
 * Overlap ensures that sentences spanning chunk boundaries are not lost.
 *
 * Default configuration:
 *   CHUNK_SIZE    = 100 words  (fits comfortably within the 128-token embedding window)
 *   CHUNK_OVERLAP = 20  words  (20% overlap to preserve boundary context)
 */
public class ChunkUtils {

    private static final int CHUNK_SIZE = 120;
    private static final int CHUNK_OVERLAP = 30;

    // =========================
    // CHUNKING
    // =========================

    /**
     * Splits the given text into overlapping word-window chunks.
     * Returns an empty list if the text is null or too short to chunk.
     */
    public static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return chunks;

        List<String> segments = splitIntoSegments(text);
        if (segments.isEmpty()) {
            return chunks;
        }

        List<String> currentWords = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.trim().isEmpty()) {
                continue;
            }

            String[] segmentWords = segment.trim().split("\\s+");
            if (segmentWords.length == 0) {
                continue;
            }

            if (segmentWords.length > CHUNK_SIZE) {
                flushChunk(chunks, currentWords);
                addOversizedSegmentChunks(chunks, segmentWords);
                currentWords.clear();
                continue;
            }

            if (
                !currentWords.isEmpty() &&
                currentWords.size() + segmentWords.length > CHUNK_SIZE
            ) {
                flushChunk(chunks, currentWords);
                currentWords = overlapTail(currentWords);
            }

            for (String word : segmentWords) {
                currentWords.add(word);
            }
        }

        flushChunk(chunks, currentWords);
        return chunks;
    }

    private static List<String> splitIntoSegments(String text) {
        List<String> segments = new ArrayList<>();
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return segments;
        }

        String[] paragraphs = normalized.split("\\n\\s*\\n");
        for (String paragraph : paragraphs) {
            if (paragraph == null) {
                continue;
            }
            String compact = paragraph.replaceAll("\\s+", " ").trim();
            if (compact.isEmpty()) {
                continue;
            }

            String[] sentences = compact.split(
                "(?<=[.!?])\\s+|(?<=[:;])\\s+(?=[A-Z0-9])"
            );
            for (String sentence : sentences) {
                String trimmed = sentence.trim();
                if (!trimmed.isEmpty()) {
                    segments.add(trimmed);
                }
            }
        }
        return segments;
    }

    private static void addOversizedSegmentChunks(
        List<String> chunks,
        String[] words
    ) {
        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        int start = 0;
        while (start < words.length) {
            int end = Math.min(start + CHUNK_SIZE, words.length);
            List<String> window = new ArrayList<>();
            for (int i = start; i < end; i++) {
                window.add(words[i]);
            }
            flushChunk(chunks, window);
            if (end == words.length) {
                break;
            }
            start += step;
        }
    }

    private static void flushChunk(List<String> chunks, List<String> words) {
        if (words == null || words.isEmpty()) {
            return;
        }

        String chunk = String.join(" ", words).trim();
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
    }

    private static List<String> overlapTail(List<String> words) {
        if (words == null || words.isEmpty()) {
            return new ArrayList<>();
        }
        int start = Math.max(0, words.size() - CHUNK_OVERLAP);
        return new ArrayList<>(words.subList(start, words.size()));
    }

    // =========================
    // SERIALIZATION
    // =========================

    /**
     * Serializes a list of chunk strings to a JSON array string for Room storage.
     * Returns an empty JSON array string on failure.
     */
    public static String toJson(List<String> chunks) {
        JSONArray array = new JSONArray();
        for (String chunk : chunks) array.put(chunk);
        return array.toString();
    }

    /**
     * Deserializes a JSON array string back into a list of chunk strings.
     * Returns an empty list if the input is null, empty, or malformed.
     */
    public static List<String> fromJson(String json) {
        List<String> chunks = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return chunks;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                chunks.add(array.getString(i));
            }
        } catch (JSONException e) {
            // Return empty list on malformed JSON
        }
        return chunks;
    }
}
