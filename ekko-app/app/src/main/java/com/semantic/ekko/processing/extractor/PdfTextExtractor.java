package com.semantic.ekko.processing.extractor;

import android.content.Context;
import android.net.Uri;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.semantic.ekko.util.FileUtils;
import java.io.IOException;
import java.io.InputStream;

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
                if (document.isEncrypted()) return "";

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(document);
            }

        } catch (IOException e) {
            return "";
        }
    }
}
