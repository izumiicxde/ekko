package com.semantic.ekko.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

public class FileUtils {

    // =========================
    // FILE NAME
    // =========================

    /**
     * Resolves the display name of a file from its URI.
     * Returns the URI's last path segment as fallback.
     */
    public static String getFileName(Context context, Uri uri) {
        String result = null;

        if ("content".equals(uri.getScheme())) {
            try (
                Cursor cursor = context
                    .getContentResolver()
                    .query(
                        uri,
                        new String[] { OpenableColumns.DISPLAY_NAME },
                        null,
                        null,
                        null
                    )
            ) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(0);
                }
            } catch (Exception e) {
                // fall through
            }
        }

        if (result == null) {
            result = uri.getLastPathSegment();
            if (result != null && result.contains("/")) {
                result = result.substring(result.lastIndexOf("/") + 1);
            }
        }

        return result != null ? result : "Unknown";
    }

    // =========================
    // FILE TYPE
    // =========================

    /**
     * Returns the lowercase file extension from a filename.
     * Example: "report.pdf" -> "pdf"
     */
    public static String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * Maps a filename extension to a MIME type string.
     */
    public static String getMimeType(String fileName) {
        switch (getExtension(fileName)) {
            case "pdf":
                return "application/pdf";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt":
                return "text/plain";
            default:
                return "*/*";
        }
    }

    /**
     * Returns true if the filename has a supported extension.
     */
    public static boolean isSupportedFile(String fileName) {
        String ext = getExtension(fileName);
        return (
            ext.equals("pdf") ||
            ext.equals("docx") ||
            ext.equals("pptx") ||
            ext.equals("txt")
        );
    }

    // =========================
    // READ TIME
    // =========================

    /**
     * Returns a human-readable read time string.
     * Example: "3 min read"
     */
    public static String readTimeLabel(int wordCount) {
        int minutes = Math.max(1, wordCount / 200);
        return minutes + " min read";
    }

    // =========================
    // FILE SIZE
    // =========================

    /**
     * Returns the file size in bytes from a content URI.
     * Returns -1 if size cannot be determined.
     */
    public static long getFileSize(Context context, Uri uri) {
        try (
            Cursor cursor = context
                .getContentResolver()
                .query(
                    uri,
                    new String[] { OpenableColumns.SIZE },
                    null,
                    null,
                    null
                )
        ) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            // fall through
        }
        return -1;
    }

    // =========================
    // FOLDER NAME
    // =========================

    /**
     * Extracts a human-readable folder name from a tree URI.
     */
    public static String getFolderName(Uri treeUri) {
        String path = getFolderDisplayPath(treeUri);
        if (path.contains("/")) {
            path = path.substring(path.lastIndexOf('/') + 1);
        }
        return path.isEmpty() ? "Unknown Folder" : path;
    }

    /**
     * Extracts the full relative path selected from a tree URI.
     * Example: primary:Notes/Sem3 -> Notes/Sem3
     */
    public static String getFolderDisplayPath(Uri treeUri) {
        if (treeUri == null) return "Unknown Folder";

        String path = null;
        try {
            path = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            path = treeUri.getLastPathSegment();
        }

        if (path == null) return "Unknown Folder";
        if (path.contains(":")) {
            path = path.substring(path.indexOf(':') + 1);
        }
        if (path.contains("/")) {
            path = path.replaceAll("/+", "/");
        }
        return path.isEmpty() ? "Unknown Folder" : path;
    }
}
