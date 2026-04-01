package com.semantic.ekko.work;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.DocumentDao;
import com.semantic.ekko.data.db.FolderDao;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.ml.EntityExtractorHelper;
import com.semantic.ekko.processing.DocumentIndexer;
import com.semantic.ekko.processing.DocumentScanner;
import com.semantic.ekko.util.StorageAccessHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class PublicStorageImportWorker extends Worker {

    public static final String UNIQUE_WORK_NAME = "public-storage-import";

    public PublicStorageImportWorker(
        @NonNull Context context,
        @NonNull WorkerParameters workerParams
    ) {
        super(context, workerParams);
    }

    public static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
            PublicStorageImportWorker.class
        )
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!StorageAccessHelper.hasAllFilesAccess()) {
            return Result.failure();
        }

        EkkoApp app = EkkoApp.getInstance();
        if (
            app == null ||
            !app.isMlReady() ||
            app.getEmbeddingEngine() == null ||
            app.getDocumentClassifier() == null ||
            app.getTextSummarizer() == null
        ) {
            return Result.retry();
        }

        List<File> publicFolders =
            StorageAccessHelper.discoverAccessiblePublicFolders(
                getApplicationContext()
            );
        if (publicFolders.isEmpty()) {
            return Result.success();
        }

        AppDatabase database = AppDatabase.getInstance(getApplicationContext());
        FolderDao folderDao = database.folderDao();
        DocumentDao documentDao = database.documentDao();
        List<File> scanFolders = new ArrayList<>();
        List<Long> folderIds = new ArrayList<>();

        for (File folderFile : publicFolders) {
            String uri = Uri.fromFile(folderFile).toString();
            FolderEntity existing = folderDao.getByUri(uri);
            long folderId;
            if (existing != null) {
                folderId = existing.id;
            } else {
                FolderEntity folder = new FolderEntity(
                    uri,
                    StorageAccessHelper.getFolderDisplayName(folderFile)
                );
                folderId = folderDao.insert(folder);
            }
            documentDao.deleteByFolderId(folderId);
            scanFolders.add(folderFile);
            folderIds.add(folderId);
        }

        DocumentScanner.ScanResult scanResult =
            DocumentScanner.scanFilesystemFolders(
                getApplicationContext(),
                scanFolders,
                folderIds
            );
        if (scanResult.documents.isEmpty()) {
            return Result.success();
        }

        EntityExtractorHelper entityExtractor = new EntityExtractorHelper();
        CountDownLatch prepareLatch = new CountDownLatch(1);
        final boolean[] prepareSuccess = { false };
        entityExtractor.prepareModel(success -> {
            prepareSuccess[0] = success;
            prepareLatch.countDown();
        });

        try {
            prepareLatch.await();
            if (!prepareSuccess[0]) {
                entityExtractor.close();
                return Result.retry();
            }

            DocumentIndexer indexer = new DocumentIndexer(
                getApplicationContext(),
                app.getEmbeddingEngine(),
                app.getDocumentClassifier(),
                app.getTextSummarizer(),
                entityExtractor
            );
            CountDownLatch indexLatch = new CountDownLatch(1);
            indexer.indexDocuments(
                scanResult.documents,
                new DocumentIndexer.ProgressListener() {
                    @Override
                    public void onStageChanged(String stage) {}

                    @Override
                    public void onDocumentProcessed(
                        int current,
                        int total,
                        String docName
                    ) {}

                    @Override
                    public void onComplete(
                        int indexed,
                        int failed,
                        List<String> failedNames
                    ) {
                        indexLatch.countDown();
                    }
                }
            );
            indexLatch.await();
            indexer.shutdown();
            entityExtractor.close();
            return Result.success();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entityExtractor.close();
            return Result.retry();
        }
    }
}
