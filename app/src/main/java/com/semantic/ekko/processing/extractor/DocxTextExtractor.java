package com.semantic.ekko.processing.extractor;

import android.content.Context;
import android.net.Uri;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.IOException;
import java.io.InputStream;

public class DocxTextExtractor {

    /**
     * Extracts plain text from a DOCX file at the given URI.
     * Returns empty string on failure.
     */
    public static String extract(Context context, Uri uri) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return "";

            try (XWPFDocument document = new XWPFDocument(is);
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                return extractor.getText();
            }

        } catch (IOException e) {
            return "";
        }
    }
}
