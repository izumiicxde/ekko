package com.semantic.ekko.processing;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.semantic.ekko.data.model.DocumentEntity;
import java.util.ArrayList;
import java.util.List;

public class DocumentScanner {

    private static final String[] SUPPORTED_MIME_TYPES = {
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
    };

    // =========================
    // SCAN RESULT
    // =========================

    public static class ScanResult {

        public final List<DocumentEntity> documents;
        public final int skipped;

        public ScanResult(List<DocumentEntity> documents, int skipped) {
            this.documents = documents;
            this.skipped = skipped;
        }
    }

    // =========================
    // SCAN
    // =========================

    /**
     * Scans a folder URI obtained from ACTION_OPEN_DOCUMENT_TREE.
     * Returns all supported documents found in the folder and nested subfolders.
     */
    public static ScanResult scanFolder(
        Context context,
        Uri folderUri,
        long folderId
    ) {
        List<DocumentEntity> found = new ArrayList<>();
        int skipped = scanFolderRecursive(
            context,
            folderUri,
            folderUri,
            folderId,
            "",
            found
        );
        return new ScanResult(found, skipped);
    }

    private static int scanFolderRecursive(
        Context context,
        Uri treeUri,
        Uri parentUri,
        long folderId,
        String relativeDir,
        List<DocumentEntity> found
    ) {
        int skipped = 0;
        String parentDocumentId = resolveDocumentId(treeUri, parentUri);
        if (parentDocumentId == null || parentDocumentId.isEmpty()) {
            return skipped;
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        );

        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        };

        try (
            Cursor cursor = context
                .getContentResolver()
                .query(childrenUri, projection, null, null, null)
        ) {
            if (cursor == null) return skipped;

            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.getLong(3);
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    docId
                );

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    String childRelativeDir = appendSegment(relativeDir, name);
                    skipped += scanFolderRecursive(
                        context,
                        treeUri,
                        docUri,
                        folderId,
                        childRelativeDir,
                        found
                    );
                    continue;
                }

                if (!isSupportedMime(mime)) {
                    skipped++;
                    continue;
                }

                // Skip empty files
                if (size == 0) {
                    skipped++;
                    continue;
                }

                String fileType = mimeToFileType(mime);
                String relativePath = appendSegment(relativeDir, name);
                DocumentEntity doc = new DocumentEntity(
                    name,
                    docUri.toString(),
                    relativePath,
                    folderId,
                    fileType
                );
                found.add(doc);
            }
        } catch (Exception e) {
            // Return whatever was found before the error
        }

        return skipped;
    }

    /**
     * Scans multiple folder URIs and merges results.
     */
    public static ScanResult scanFolders(
        Context context,
        List<Uri> folderUris,
        List<Long> folderIds
    ) {
        List<DocumentEntity> allDocs = new ArrayList<>();
        int totalSkipped = 0;
        if (
            folderUris == null ||
            folderIds == null ||
            folderUris.isEmpty() ||
            folderIds.isEmpty()
        ) {
            return new ScanResult(allDocs, totalSkipped);
        }

        int limit = Math.min(folderUris.size(), folderIds.size());
        for (int i = 0; i < limit; i++) {
            if (folderUris.get(i) == null || folderIds.get(i) == null) {
                totalSkipped++;
                continue;
            }
            ScanResult result = scanFolder(
                context,
                folderUris.get(i),
                folderIds.get(i)
            );
            allDocs.addAll(result.documents);
            totalSkipped += result.skipped;
        }

        return new ScanResult(allDocs, totalSkipped);
    }

    private static String appendSegment(String base, String segment) {
        if (segment == null || segment.trim().isEmpty()) return base;
        if (base == null || base.isEmpty()) return segment;
        return base + "/" + segment;
    }

    private static String resolveDocumentId(Uri treeUri, Uri candidateUri) {
        if (candidateUri == null) return null;
        try {
            if (candidateUri.equals(treeUri)) {
                return DocumentsContract.getTreeDocumentId(treeUri);
            }
            return DocumentsContract.getDocumentId(candidateUri);
        } catch (IllegalArgumentException ignored) {
            try {
                return DocumentsContract.getTreeDocumentId(candidateUri);
            } catch (IllegalArgumentException ignoredAgain) {
                return null;
            }
        }
    }

    // =========================
    // HELPERS
    // =========================

    private static boolean isSupportedMime(String mime) {
        if (mime == null) return false;
        for (String supported : SUPPORTED_MIME_TYPES) {
            if (supported.equals(mime)) return true;
        }
        return false;
    }

    private static String mimeToFileType(String mime) {
        switch (mime) {
            case "application/pdf":
                return "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
                return "docx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
                return "pptx";
            case "text/plain":
                return "txt";
            default:
                return "unknown";
        }
    }
}
