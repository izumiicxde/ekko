package com.semantic.ekko.ml;

import com.google.mlkit.nl.entityextraction.Entity;
import com.google.mlkit.nl.entityextraction.EntityAnnotation;
import com.google.mlkit.nl.entityextraction.EntityExtraction;
import com.google.mlkit.nl.entityextraction.EntityExtractionParams;
import com.google.mlkit.nl.entityextraction.EntityExtractor;
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class EntityExtractorHelper {

    // Entity types we care about for document intelligence
    private static final List<Integer> ACCEPTED_TYPES = Arrays.asList(
            Entity.TYPE_DATE_TIME,
            Entity.TYPE_ADDRESS,
            Entity.TYPE_PHONE,
            Entity.TYPE_EMAIL,
            Entity.TYPE_URL,
            Entity.TYPE_MONEY,
            Entity.TYPE_FLIGHT_NUMBER
    );

    private final EntityExtractor extractor;
    private boolean isModelDownloaded = false;

    // =========================
    // INIT
    // =========================

    public EntityExtractorHelper() {
        extractor = EntityExtraction.getClient(
                new EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH)
                        .build()
        );
    }

    /**
     * Downloads the ML Kit entity extraction model if not already present.
     * Must be called once before extractEntities().
     * Callback fires on completion with success or failure.
     */
    public void prepareModel(ModelReadyCallback callback) {
        extractor.downloadModelIfNeeded()
                .addOnSuccessListener(unused -> {
                    isModelDownloaded = true;
                    callback.onReady(true);
                })
                .addOnFailureListener(e -> {
                    isModelDownloaded = false;
                    callback.onReady(false);
                });
    }

    // =========================
    // EXTRACT
    // =========================

    /**
     * Extracts entities from the given text asynchronously.
     * Returns results via callback as a deduplicated list of entity strings.
     * If the model is not downloaded or extraction fails, returns an empty list.
     */
    public void extractEntities(String text, EntityResultCallback callback) {
        if (!isModelDownloaded || text == null || text.trim().isEmpty()) {
            callback.onResult(new ArrayList<>());
            return;
        }

        // ML Kit has a text length limit; use a representative excerpt
        String excerpt = text.length() > 5000 ? text.substring(0, 5000) : text;

        EntityExtractionParams params = new EntityExtractionParams.Builder(excerpt).build();

        extractor.annotate(params)
                .addOnSuccessListener(annotations -> {
                    List<String> entities = parseAnnotations(annotations);
                    callback.onResult(entities);
                })
                .addOnFailureListener(e -> {
                    callback.onResult(new ArrayList<>());
                });
    }

    // =========================
    // PARSE
    // =========================

    private List<String> parseAnnotations(List<EntityAnnotation> annotations) {
        Set<String> seen = new LinkedHashSet<>();

        for (EntityAnnotation annotation : annotations) {
            for (Entity entity : annotation.getEntities()) {
                if (ACCEPTED_TYPES.contains(entity.getType())) {
                    String text = annotation.getAnnotatedText().trim();
                    if (!text.isEmpty() && text.length() <= 80) {
                        seen.add(formatEntity(entity.getType(), text));
                    }
                }
            }
        }

        return new ArrayList<>(seen);
    }

    private String formatEntity(int type, String text) {
        switch (type) {
            case Entity.TYPE_DATE_TIME:     return "Date: " + text;
            case Entity.TYPE_ADDRESS:       return "Address: " + text;
            case Entity.TYPE_PHONE:         return "Phone: " + text;
            case Entity.TYPE_EMAIL:         return "Email: " + text;
            case Entity.TYPE_URL:           return "URL: " + text;
            case Entity.TYPE_MONEY:         return "Amount: " + text;
            case Entity.TYPE_FLIGHT_NUMBER: return "Flight: " + text;
            default:                        return text;
        }
    }

    // =========================
    // SERIALIZATION
    // =========================

    /**
     * Converts entity list to a single comma-separated string for DB storage.
     */
    public static String entitiesToString(List<String> entities) {
        if (entities == null || entities.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) sb.append(",,");
            sb.append(entities.get(i));
        }
        return sb.toString();
    }

    /**
     * Parses a comma-separated entity string back into a list.
     */
    public static List<String> entitiesFromString(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        String[] parts = raw.split(",,");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    // =========================
    // CLEANUP
    // =========================

    public void close() {
        if (extractor != null) extractor.close();
    }

    // =========================
    // CALLBACKS
    // =========================

    public interface ModelReadyCallback {
        void onReady(boolean success);
    }

    public interface EntityResultCallback {
        void onResult(List<String> entities);
    }
}
