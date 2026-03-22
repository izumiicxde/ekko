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

    private static final int CHUNK_SIZE = 100;
    private static final int CHUNK_OVERLAP = 20;

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

        String[] words = text.trim().split("\\s+");
        if (words.length == 0) return chunks;

        // If the text is shorter than one chunk, return it as a single chunk
        if (words.length <= CHUNK_SIZE) {
            chunks.add(String.join(" ", words));
            return chunks;
        }

        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        int start = 0;

        while (start < words.length) {
            int end = Math.min(start + CHUNK_SIZE, words.length);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i > start) sb.append(" ");
                sb.append(words[i]);
            }
            chunks.add(sb.toString());
            if (end == words.length) break;
            start += step;
        }

        return chunks;
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
