package com.semantic.ekko.processing.extractor;

import android.content.Context;
import android.net.Uri;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.semantic.ekko.util.FileUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PdfTextExtractor {

    /**
     * Must be called once before any PDF extraction, ideally in Application.onCreate().
     */
    public static void init(Context context) {
        PDFBoxResourceLoader.init(context);
    }

    /**
     * Extracts plain text from a PDF file at the given URI.
     * Returns empty string on failure.
     */
    public static String extract(Context context, Uri uri) {
        try (InputStream is = FileUtils.openInputStream(context, uri)) {
            if (is == null) return "";

            try (PDDocument document = PDDocument.load(is)) {
                if (document.isEncrypted()) {
                    try {
                        document.setAllSecurityToBeRemoved(true);
                    } catch (Exception ignored) {
                        return "";
                    }
                }
                return extractBestCandidate(document);
            }

        } catch (IOException e) {
            return "";
        }
    }

    private static String extractBestCandidate(PDDocument document)
        throws IOException {
        List<String> candidates = new ArrayList<>();
        candidates.add(extractText(document, true, false, false));
        candidates.add(extractText(document, true, true, false));
        candidates.add(extractText(document, false, false, false));
        candidates.add(extractText(document, true, false, true));

        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (String candidate : candidates) {
            String normalized = ExtractedTextSanitizer.normalize(candidate);
            int score = qualityScore(normalized);
            if (score > bestScore) {
                bestScore = score;
                best = normalized;
            }
        }
        return best;
    }

    private static String extractText(
        PDDocument document,
        boolean sortByPosition,
        boolean suppressDuplicateOverlappingText,
        boolean pageByPage
    ) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(sortByPosition);
        stripper.setSuppressDuplicateOverlappingText(
            suppressDuplicateOverlappingText
        );
        stripper.setLineSeparator("\n");
        stripper.setParagraphStart("");
        stripper.setParagraphEnd("\n\n");
        stripper.setPageStart("");
        stripper.setPageEnd("\n\n");
        stripper.setWordSeparator(" ");

        if (!pageByPage) {
            return stripper.getText(document);
        }

        StringBuilder builder = new StringBuilder();
        int pageCount = document.getNumberOfPages();
        for (int page = 1; page <= pageCount; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            builder.append(stripper.getText(document)).append("\n\n");
        }
        return builder.toString();
    }

    private static int qualityScore(String text) {
        if (text == null || text.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int letters = 0;
        int digits = 0;
        int lines = 0;
        int repeatedPenalty = 0;
        String previousLine = "";
        String[] splitLines = text.split("\\n");
        for (String line : splitLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines++;
            }
            if (!trimmed.isEmpty() && trimmed.equals(previousLine)) {
                repeatedPenalty += 12;
            }
            previousLine = trimmed;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
            } else if (Character.isDigit(ch)) {
                digits++;
            }
        }
        int lengthScore = Math.min(text.length(), 24000);
        int contentScore = (letters * 2) + digits;
        int structureBonus = Math.min(lines, 120) * 8;
        int garbagePenalty = mostlyReplacementGlyphs(text) ? 2400 : 0;
        return lengthScore + contentScore + structureBonus - repeatedPenalty - garbagePenalty;
    }

    private static boolean mostlyReplacementGlyphs(String text) {
        String lower = text.toLowerCase(Locale.US);
        int replacementCount = 0;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch == '\uFFFD' || ch == '\u0000') {
                replacementCount++;
            }
        }
        return replacementCount > 0 && replacementCount > (lower.length() / 18);
    }
}
