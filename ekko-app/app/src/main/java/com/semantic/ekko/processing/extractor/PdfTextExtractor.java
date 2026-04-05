package com.semantic.ekko.processing.extractor;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.content.Context;
import android.net.Uri;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.semantic.ekko.util.FileUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PdfTextExtractor {

    private static final int OCR_MAX_PAGES = 6;
    private static final int OCR_TARGET_WIDTH = 1440;
    private static final long OCR_PAGE_TIMEOUT_SECONDS = 8L;
    private static final int STRONG_TEXT_SCORE = 1400;
    private static final int MIN_USEFUL_PAGE_SCORE = 180;

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
        String embeddedText = "";
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
                embeddedText = extractBestCandidate(document);
            }
        } catch (IOException e) {
            embeddedText = "";
        }

        if (qualityScore(embeddedText) >= STRONG_TEXT_SCORE) {
            return embeddedText;
        }

        String ocrText = extractWithOcr(context, uri);
        return chooseBestText(embeddedText, ocrText);
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

    private static String chooseBestText(String embeddedText, String ocrText) {
        String normalizedEmbedded = ExtractedTextSanitizer.normalize(embeddedText);
        String normalizedOcr = ExtractedTextSanitizer.normalize(ocrText);

        int embeddedScore = qualityScore(normalizedEmbedded);
        int ocrScore = qualityScore(normalizedOcr);

        if (ocrScore <= Integer.MIN_VALUE) {
            return normalizedEmbedded;
        }
        if (embeddedScore <= Integer.MIN_VALUE) {
            return normalizedOcr;
        }
        if (ocrScore > embeddedScore + 220) {
            return normalizedOcr;
        }
        if (
            normalizedOcr.length() > normalizedEmbedded.length() * 1.35f &&
            ocrScore >= embeddedScore
        ) {
            return normalizedOcr;
        }
        return normalizedEmbedded;
    }

    private static String extractWithOcr(Context context, Uri uri) {
        ParcelFileDescriptor descriptor = null;
        PdfRenderer renderer = null;
        TextRecognizer recognizer = null;
        try {
            descriptor = FileUtils.openFileDescriptor(context, uri);
            if (descriptor == null) {
                return "";
            }
            renderer = new PdfRenderer(descriptor);
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            List<Integer> pageIndexes = chooseOcrPages(renderer.getPageCount());
            StringBuilder builder = new StringBuilder();
            for (int pageIndex : pageIndexes) {
                String pageText = extractPageWithOcr(renderer, recognizer, pageIndex);
                String normalized = ExtractedTextSanitizer.normalize(pageText);
                if (qualityScore(normalized) >= MIN_USEFUL_PAGE_SCORE) {
                    if (builder.length() > 0) {
                        builder.append("\n\n");
                    }
                    builder.append(normalized);
                }
            }
            return ExtractedTextSanitizer.normalize(builder.toString());
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (recognizer != null) {
                recognizer.close();
            }
            if (renderer != null) {
                renderer.close();
            }
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static String extractPageWithOcr(
        PdfRenderer renderer,
        TextRecognizer recognizer,
        int pageIndex
    ) {
        PdfRenderer.Page page = null;
        Bitmap bitmap = null;
        try {
            page = renderer.openPage(pageIndex);
            int width = page.getWidth();
            int height = page.getHeight();
            if (width <= 0 || height <= 0) {
                return "";
            }

            float scale = Math.min(
                2f,
                Math.max(1f, OCR_TARGET_WIDTH / (float) width)
            );
            int bitmapWidth = Math.max(1, Math.round(width * scale));
            int bitmapHeight = Math.max(1, Math.round(height * scale));
            bitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888
            );
            bitmap.eraseColor(Color.WHITE);

            Matrix matrix = new Matrix();
            matrix.postScale(scale, scale);
            page.render(
                bitmap,
                null,
                matrix,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            );

            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
            return awaitTextRecognition(recognizer.process(inputImage));
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (page != null) {
                page.close();
            }
        }
    }

    private static String awaitTextRecognition(Task<Text> task) {
        if (task == null) {
            return "";
        }
        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = { "" };
        task
            .addOnSuccessListener(text -> {
                result[0] = text == null ? "" : text.getText();
                latch.countDown();
            })
            .addOnFailureListener(error -> latch.countDown());
        try {
            if (!latch.await(OCR_PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return "";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        return result[0] == null ? "" : result[0];
    }

    private static List<Integer> chooseOcrPages(int pageCount) {
        List<Integer> selected = new ArrayList<>();
        if (pageCount <= 0) {
            return selected;
        }

        if (pageCount <= OCR_MAX_PAGES) {
            for (int i = 0; i < pageCount; i++) {
                selected.add(i);
            }
            return selected;
        }

        int[] preferred = {
            0,
            1,
            Math.max(0, (pageCount / 3) - 1),
            pageCount / 2,
            Math.max(0, pageCount - 2),
            pageCount - 1,
        };

        for (int index : preferred) {
            int safeIndex = Math.max(0, Math.min(index, pageCount - 1));
            if (!selected.contains(safeIndex)) {
                selected.add(safeIndex);
            }
            if (selected.size() >= OCR_MAX_PAGES) {
                break;
            }
        }
        return selected;
    }
}
