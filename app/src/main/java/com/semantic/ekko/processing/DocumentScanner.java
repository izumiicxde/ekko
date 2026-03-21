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
            "text/plain"
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
     * Returns all supported documents found in the folder.
     * Does not recurse into subfolders.
     */
    public static ScanResult scanFolder(Context context, Uri folderUri, long folderId) {
        List<DocumentEntity> found = new ArrayList<>();
        int skipped = 0;

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
        );

        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };

        try (Cursor cursor = context.getContentResolver().query(
                childrenUri, projection, null, null, null)) {

            if (cursor == null) return new ScanResult(found, skipped);

            while (cursor.moveToNext()) {
                String docId   = cursor.getString(0);
                String name    = cursor.getString(1);
                String mime    = cursor.getString(2);
                long size      = cursor.getLong(3);

                if (!isSupportedMime(mime)) {
                    skipped++;
                    continue;
                }

                // Skip empty files
                if (size == 0) {
                    skipped++;
                    continue;
                }

                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId);
                String fileType = mimeToFileType(mime);

                DocumentEntity doc = new DocumentEntity(name, docUri.toString(), folderId, fileType);
                found.add(doc);
            }

        } catch (Exception e) {
            // Return whatever was found before the error
        }

        return new ScanResult(found, skipped);
    }

    /**
     * Scans multiple folder URIs and merges results.
     */
    public static ScanResult scanFolders(Context context,
                                         List<Uri> folderUris,
                                         List<Long> folderIds) {
        List<DocumentEntity> allDocs = new ArrayList<>();
        int totalSkipped = 0;

        for (int i = 0; i < folderUris.size(); i++) {
            ScanResult result = scanFolder(context, folderUris.get(i), folderIds.get(i));
            allDocs.addAll(result.documents);
            totalSkipped += result.skipped;
        }

        return new ScanResult(allDocs, totalSkipped);
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
            case "application/pdf": return "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
            case "text/plain": return "txt";
            default: return "unknown";
        }
    }
}
