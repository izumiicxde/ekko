package com.semantic.ekko.processing.extractor;

import android.content.Context;
import android.net.Uri;
import com.semantic.ekko.util.FileUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class TxtTextExtractor {

    /**
     * Extracts plain text from a TXT file at the given URI.
     * Returns empty string on failure.
     */
    public static String extract(Context context, Uri uri) {
        try (InputStream is = FileUtils.openInputStream(context, uri)) {
            if (is == null) return "";

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            return sb.toString();

        } catch (IOException e) {
            return "";
        }
    }
}
