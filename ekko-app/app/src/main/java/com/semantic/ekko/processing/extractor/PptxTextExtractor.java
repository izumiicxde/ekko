package com.semantic.ekko.processing.extractor;

import android.content.Context;
import android.net.Uri;
import com.semantic.ekko.util.FileUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PptxTextExtractor {

    private static final Pattern TEXT_RUN_PATTERN = Pattern.compile(
        "<a:t>(.*?)</a:t>"
    );

    /**
     * Extracts plain text from a PPTX file at the given URI.
     * Reads slide XML directly to avoid Apache POI paths that depend on AWT classes.
     * Returns empty string on failure.
     */
    public static String extract(Context context, Uri uri) {
        try (InputStream is = FileUtils.openInputStream(context, uri)) {
            if (is == null) return "";
            try (ZipInputStream zipInputStream = new ZipInputStream(is)) {
                StringBuilder builder = new StringBuilder();
                ZipEntry entry;
                while ((entry = zipInputStream.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (
                        name == null ||
                        !name.startsWith("ppt/slides/slide") ||
                        !name.endsWith(".xml")
                    ) {
                        continue;
                    }

                    String xml = readEntry(zipInputStream);
                    appendSlideText(builder, xml);
                    builder.append("\n\n");
                }
                return ExtractedTextSanitizer.normalize(builder.toString());
            }
        } catch (Throwable e) {
            return "";
        }
    }

    private static String readEntry(ZipInputStream zipInputStream)
        throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(zipInputStream, StandardCharsets.UTF_8)
        );
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }

    private static void appendSlideText(StringBuilder builder, String xml) {
        if (xml == null || xml.isEmpty()) {
            return;
        }
        Matcher matcher = TEXT_RUN_PATTERN.matcher(xml);
        boolean first = true;
        while (matcher.find()) {
            String text = decodeXmlEntities(matcher.group(1)).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (!first) {
                builder.append('\n');
            }
            builder.append(text);
            first = false;
        }
    }

    private static String decodeXmlEntities(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#10;", "\n");
    }
}
