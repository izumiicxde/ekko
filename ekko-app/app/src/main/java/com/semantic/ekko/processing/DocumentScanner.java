package com.semantic.ekko.processing;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.util.FileUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DocumentScanner {

    private static final String[] SUPPORTED_MIME_TYPES = {
        "application/pdf",
        "application/x-pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-powerpoint",
        "text/plain",
        "text/markdown",
        "application/rtf",
        "text/rtf",
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
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
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
                long lastModified = cursor.isNull(4) ? 0L : cursor.getLong(4);
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

                if (!isSupportedMime(mime, name)) {
                    skipped++;
                    continue;
                }

                // Skip empty files
                if (size == 0) {
                    skipped++;
                    continue;
                }

                String fileType = mimeToFileType(mime, name);
                String relativePath = appendSegment(relativeDir, name);
                DocumentEntity doc = new DocumentEntity(
                    name,
                    docUri.toString(),
                    relativePath,
                    folderId,
                    fileType
                );
                doc.sourceSize = size;
                doc.sourceModifiedAt = lastModified;
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

    public static ScanResult scanFilesystemFolders(
        Context context,
        List<File> folderFiles,
        List<Long> folderIds
    ) {
        List<DocumentEntity> allDocs = new ArrayList<>();
        int totalSkipped = 0;
        if (
            folderFiles == null ||
            folderIds == null ||
            folderFiles.isEmpty() ||
            folderIds.isEmpty()
        ) {
            return new ScanResult(allDocs, totalSkipped);
        }

        int limit = Math.min(folderFiles.size(), folderIds.size());
        for (int i = 0; i < limit; i++) {
            File folderFile = folderFiles.get(i);
            Long folderId = folderIds.get(i);
            if (folderFile == null || folderId == null) {
                totalSkipped++;
                continue;
            }
            totalSkipped += scanFilesystemFolderRecursive(
                folderFile,
                folderFile,
                folderId,
                "",
                allDocs
            );
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

    private static boolean isSupportedMime(String mime, String fileName) {
        if (mime != null) {
            for (String supported : SUPPORTED_MIME_TYPES) {
                if (supported.equalsIgnoreCase(mime)) return true;
            }
        }
        return FileUtils.isSupportedFile(fileName);
    }

    private static int scanFilesystemFolderRecursive(
        File rootFolder,
        File currentFolder,
        long folderId,
        String relativeDir,
        List<DocumentEntity> found
    ) {
        if (
            currentFolder == null ||
            !currentFolder.exists() ||
            !currentFolder.isDirectory() ||
            !currentFolder.canRead()
        ) {
            return 0;
        }

        File[] children = currentFolder.listFiles();
        if (children == null) return 0;

        int skipped = 0;
        for (File child : children) {
            if (child == null || !child.canRead()) {
                skipped++;
                continue;
            }
            if (child.isDirectory()) {
                if (shouldSkipDirectory(child)) {
                    skipped++;
                    continue;
                }
                String childRelativeDir = appendSegment(
                    relativeDir,
                    child.getName()
                );
                skipped += scanFilesystemFolderRecursive(
                    rootFolder,
                    child,
                    folderId,
                    childRelativeDir,
                    found
                );
                continue;
            }

            String fileName = child.getName();
            if (!FileUtils.isSupportedFile(fileName) || child.length() == 0L) {
                skipped++;
                continue;
            }

            String relativePath = appendSegment(relativeDir, fileName);
            DocumentEntity doc = new DocumentEntity(
                fileName,
                Uri.fromFile(child).toString(),
                relativePath,
                folderId,
                FileUtils.getExtension(fileName)
            );
            doc.sourceSize = child.length();
            doc.sourceModifiedAt = child.lastModified();
            found.add(doc);
        }
        return skipped;
    }

    private static boolean shouldSkipDirectory(File folder) {
        String name = folder.getName();
        if (name == null || name.trim().isEmpty()) return true;
        return (
            name.startsWith(".") ||
            "Android".equalsIgnoreCase(name) ||
            "data".equalsIgnoreCase(name) ||
            "obb".equalsIgnoreCase(name)
        );
    }

    private static String mimeToFileType(String mime, String fileName) {
        if (mime == null || mime.trim().isEmpty()) {
            return FileUtils.getExtension(fileName);
        }
        switch (mime.toLowerCase()) {
            case "application/pdf":
            case "application/x-pdf":
                return "pdf";
            case "application/msword":
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
                return "doc".equals(FileUtils.getExtension(fileName))
                    ? "doc"
                    : "docx";
            case "application/vnd.ms-powerpoint":
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
                return "ppt".equals(FileUtils.getExtension(fileName))
                    ? "ppt"
                    : "pptx";
            case "text/plain":
            case "text/markdown":
            case "application/rtf":
            case "text/rtf":
                return "txt";
            default:
                return FileUtils.getExtension(fileName);
        }
    }
}
