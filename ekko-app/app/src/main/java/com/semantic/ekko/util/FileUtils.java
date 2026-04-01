package com.semantic.ekko.util;

import android.content.Context;
import android.content.UriPermission;
import android.os.ParcelFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

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
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "md":
                return "text/markdown";
            case "rtf":
                return "application/rtf";
            case "txt":
                return "text/plain";
            default:
                return "*/*";
        }
    }

    public static String resolveMimeType(
        Context context,
        Uri uri,
        String fileName
    ) {
        if (context != null && uri != null) {
            try {
                String contentType = context.getContentResolver().getType(uri);
                if (contentType != null && !contentType.trim().isEmpty()) {
                    return contentType;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }

        String extension = getExtension(fileName);
        if (!extension.isEmpty()) {
            String mapped = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension);
            if (mapped != null && !mapped.trim().isEmpty()) {
                return mapped;
            }
        }

        return getMimeType(fileName);
    }

    /**
     * Copies a document into app cache and returns a FileProvider URI that can
     * be safely granted to external viewer apps.
     */
    public static Uri copyToViewerCache(
        Context context,
        Uri sourceUri,
        String fileName
    ) throws Exception {
        File cacheDir = new File(context.getCacheDir(), "viewer");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IllegalStateException("Could not create viewer cache");
        }
        File outFile = new File(
            cacheDir,
            buildSafeCacheFileName(fileName, sourceUri)
        );
        try (
            InputStream inputStream = openInputStream(context, sourceUri);
            FileOutputStream outputStream = new FileOutputStream(outFile, false)
        ) {
            if (inputStream == null) {
                throw new IllegalStateException("Could not open source file");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        }

        return FileProvider.getUriForFile(
            context,
            context.getPackageName() + ".fileprovider",
            outFile
        );
    }

    private static String buildSafeCacheFileName(String fileName, Uri sourceUri) {
        String normalized =
            (fileName == null || fileName.trim().isEmpty())
                ? "document"
                : fileName.trim();
        String sanitized = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        int dotIndex = sanitized.lastIndexOf('.');
        String baseName = dotIndex > 0
            ? sanitized.substring(0, dotIndex)
            : sanitized;
        String extension = dotIndex > 0 ? sanitized.substring(dotIndex) : "";
        String suffix = Integer.toHexString(
            sourceUri != null ? sourceUri.toString().hashCode() : sanitized.hashCode()
        );
        return baseName + "_" + suffix + extension;
    }

    public static InputStream openInputStream(Context context, Uri uri)
        throws IOException {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            return path != null ? new FileInputStream(path) : null;
        }
        return context.getContentResolver().openInputStream(uri);
    }

    public static ParcelFileDescriptor openFileDescriptor(
        Context context,
        Uri uri
    ) throws IOException {
        if (context == null || uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) return null;
            return ParcelFileDescriptor.open(
                new File(path),
                ParcelFileDescriptor.MODE_READ_ONLY
            );
        }
        return context.getContentResolver().openFileDescriptor(uri, "r");
    }

    public static boolean hasPersistedReadPermission(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        if (!"content".equalsIgnoreCase(uri.getScheme())) return true;

        List<UriPermission> permissions = context
            .getContentResolver()
            .getPersistedUriPermissions();
        if (permissions == null) return false;

        for (UriPermission permission : permissions) {
            if (!isReadPermissionMatch(context, permission, uri)) continue;
            return true;
        }
        return false;
    }

    private static boolean isReadPermissionMatch(
        Context context,
        UriPermission permission,
        Uri targetUri
    ) {
        if (
            permission == null ||
            !permission.isReadPermission() ||
            permission.getUri() == null
        ) {
            return false;
        }

        Uri persistedUri = permission.getUri();
        if (targetUri.toString().startsWith(persistedUri.toString())) {
            return true;
        }

        try {
            if (DocumentsContract.isTreeUri(persistedUri)) {
                String treeDocumentId = DocumentsContract.getTreeDocumentId(
                    persistedUri
                );
                String targetDocumentId = DocumentsContract.isDocumentUri(
                    context,
                    targetUri
                )
                    ? DocumentsContract.getDocumentId(targetUri)
                    : DocumentsContract.getTreeDocumentId(targetUri);
                return (
                    treeDocumentId != null &&
                    targetDocumentId != null &&
                    (
                        targetDocumentId.equals(treeDocumentId) ||
                        targetDocumentId.startsWith(treeDocumentId + "/")
                    )
                );
            }
        } catch (Exception ignored) {
            // fall through
        }

        return false;
    }

    public static boolean canReadUri(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        try (
            InputStream inputStream = openInputStream(context, uri)
        ) {
            if (inputStream == null) {
                return false;
            }
            inputStream.read(new byte[1], 0, 0);
                return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static Uri resolveDocumentUriFromTree(
        Context context,
        Uri treeUri,
        String relativePath
    ) {
        if (context == null || treeUri == null) return null;

        String treeDocumentId;
        try {
            treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return null;
        }

        if (
            relativePath == null ||
            relativePath.trim().isEmpty() ||
            "Unknown".equalsIgnoreCase(relativePath.trim())
        ) {
            return DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                treeDocumentId
            );
        }

        String[] segments = relativePath.split("/");
        String currentDocumentId = treeDocumentId;
        for (String rawSegment : segments) {
            String segment = rawSegment != null ? rawSegment.trim() : "";
            if (segment.isEmpty()) continue;

            String childDocumentId = findChildDocumentId(
                context,
                treeUri,
                currentDocumentId,
                segment
            );
            if (childDocumentId == null) {
                return null;
            }
            currentDocumentId = childDocumentId;
        }

        return DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            currentDocumentId
        );
    }

    private static String findChildDocumentId(
        Context context,
        Uri treeUri,
        String parentDocumentId,
        String childName
    ) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        );
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        };

        try (
            Cursor cursor = context
                .getContentResolver()
                .query(childrenUri, projection, null, null, null)
        ) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String displayName = cursor.getString(1);
                if (childName.equals(displayName)) {
                    return documentId;
                }
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
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
        if ("file".equalsIgnoreCase(treeUri.getScheme())) {
            String path = treeUri.getPath();
            return (path == null || path.isEmpty()) ? "Unknown Folder" : path;
        }

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
