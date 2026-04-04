package com.semantic.ekko.processing.extractor;

public final class ExtractedTextSanitizer {

    private ExtractedTextSanitizer() {}

    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }

        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace("\u00AD", "");

        // Join words broken across wrapped lines such as "embed-\nding".
        text = text.replaceAll("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{L})", "");

        // Promote likely paragraph boundaries before flattening line wraps.
        text = text.replaceAll("\\n\\s*\\n+", "\n\n");

        // Most PDF/DOC line breaks are visual wraps, not semantic separators.
        text = text.replaceAll(
            "(?<=[\\p{L}\\d,;:])\\s*\\n\\s*(?=[\\p{Ll}\\d])",
            " "
        );
        text = text.replaceAll("(?<=[)])\\s*\\n\\s*(?=[\\p{L}\\d])", " ");

        // Keep headings and bullet-style lines readable without exploding chunks.
        text = text.replaceAll("\\n(?=[\\-•*])", "\n");
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll(" ?\\n ?", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");

        return text.trim();
    }
}
