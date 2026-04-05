package com.semantic.ekko.work;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.lifecycle.LiveData;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.R;
import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.DocumentDao;
import com.semantic.ekko.data.db.FolderDao;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.ml.EntityExtractorHelper;
import com.semantic.ekko.processing.DocumentIndexer;
import com.semantic.ekko.processing.DocumentScanner;
import com.semantic.ekko.ui.launcher.LauncherActivity;
import com.semantic.ekko.util.CrashLogger;
import com.semantic.ekko.util.PrefsManager;
import com.semantic.ekko.util.NotificationPermissionHelper;
import com.semantic.ekko.util.StorageAccessHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BackgroundIndexWorker extends Worker {

    public static final String UNIQUE_WORK_NAME = "background-document-index";
    public static final String KEY_FOLDER_IDS = "folder_ids";
    public static final String KEY_STAGE = "stage";
    public static final String KEY_DOC_NAME = "doc_name";
    public static final String KEY_CURRENT = "current";
    public static final String KEY_TOTAL = "total";
    private static final String CHANNEL_ID = "ekko_indexing";
    private static final int NOTIFICATION_ID = 4102;
    private static final int COMPLETE_NOTIFICATION_ID = 4103;
    private static final long MODEL_PREPARE_TIMEOUT_SECONDS = 10L;

    public BackgroundIndexWorker(
        @NonNull Context context,
        @NonNull WorkerParameters workerParams
    ) {
        super(context, workerParams);
    }

    public static void enqueue(Context context, List<Long> folderIds) {
        Data.Builder input = new Data.Builder();
        if (folderIds != null && !folderIds.isEmpty()) {
            long[] ids = new long[folderIds.size()];
            for (int i = 0; i < folderIds.size(); i++) {
                ids[i] = folderIds.get(i);
            }
            input.putLongArray(KEY_FOLDER_IDS, ids);
        }

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
            BackgroundIndexWorker.class
        )
            .setInputData(input.build())
            .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        );
    }

    public static void enqueueAll(Context context) {
        enqueue(context, Collections.emptyList());
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
    }

    public static LiveData<List<WorkInfo>> getWorkInfoLiveData(Context context) {
        return WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_NAME);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
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

            setForegroundAsync(
                createForegroundInfo(
                    "Starting indexing",
                    "Checking your folders",
                    0,
                    0,
                    false
                )
            );
            setProgressAsync(buildProgressData("Starting indexing", "", 0, 0));

            AppDatabase database = AppDatabase.getInstance(getApplicationContext());
            FolderDao folderDao = database.folderDao();
            DocumentDao documentDao = database.documentDao();
            PrefsManager prefsManager = new PrefsManager(getApplicationContext());

            List<FolderEntity> targetFolders = resolveTargetFolders(
                folderDao,
                prefsManager
            );
            if (targetFolders.isEmpty()) {
                return Result.success();
            }

            ScanBundle scanBundle = scanFolders(targetFolders);
            syncScannedDocuments(
                documentDao,
                scanBundle.folderIds,
                scanBundle.scanResult.documents
            );

            if (scanBundle.scanResult.documents.isEmpty()) {
                setProgressAsync(buildProgressData("Nothing new to index", "", 0, 0));
                return Result.success();
            }

            setForegroundAsync(
                createForegroundInfo(
                    "Preparing smart indexing",
                    scanBundle.scanResult.documents.size() +
                    (scanBundle.scanResult.documents.size() == 1 ? " file ready" : " files ready"),
                    0,
                    scanBundle.scanResult.documents.size(),
                    false
                )
            );
            setProgressAsync(
                buildProgressData(
                    "Preparing smart indexing",
                    scanBundle.scanResult.documents.size() +
                    (scanBundle.scanResult.documents.size() == 1 ? " file found" : " files found"),
                    0,
                    scanBundle.scanResult.documents.size()
                )
            );

            EntityExtractorHelper entityExtractor = new EntityExtractorHelper();
            CountDownLatch prepareLatch = new CountDownLatch(1);
            final boolean[] prepareSuccess = { false };
            setForegroundAsync(
                createForegroundInfo(
                    "Loading smart indexing tools",
                    "This only takes longer when models are cold.",
                    0,
                    scanBundle.scanResult.documents.size(),
                    false
                )
            );
            setProgressAsync(
                buildProgressData(
                    "Loading smart indexing tools",
                    "",
                    0,
                    scanBundle.scanResult.documents.size()
                )
            );
            entityExtractor.prepareModel(success -> {
                prepareSuccess[0] = success;
                prepareLatch.countDown();
            });

            try {
                if (isStopped()) {
                    entityExtractor.close();
                    return Result.success();
                }
                boolean modelReady =
                    prepareLatch.await(
                        MODEL_PREPARE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    ) && prepareSuccess[0];

                if (!modelReady) {
                    setForegroundAsync(
                        createForegroundInfo(
                            "Indexing files",
                            "Continuing without smart entity extraction",
                            0,
                            scanBundle.scanResult.documents.size(),
                            false
                        )
                    );
                    setProgressAsync(
                        buildProgressData(
                            "Indexing files",
                            "Continuing without smart entity extraction",
                            0,
                            scanBundle.scanResult.documents.size()
                        )
                    );
                }

                DocumentIndexer indexer = new DocumentIndexer(
                    getApplicationContext(),
                    app.getEmbeddingEngine(),
                    app.getDocumentClassifier(),
                    app.getTextSummarizer(),
                    entityExtractor
                );
                CountDownLatch indexLatch = new CountDownLatch(1);
                final boolean[] hadFailures = { false };
                final int[] lastCurrent = { 0 };
                final int[] lastTotal = { scanBundle.scanResult.documents.size() };
                final String[] lastDocName = { "" };
                if (isStopped()) {
                    indexer.shutdown();
                    entityExtractor.close();
                    return Result.success();
                }
                indexer.indexDocuments(
                    scanBundle.scanResult.documents,
                    new DocumentIndexer.ProgressListener() {
                        @Override
                        public void onStageChanged(String stage) {
                            String safeStage =
                                stage == null || stage.isEmpty()
                                    ? "Indexing"
                                    : stage;
                            String safeDocName = lastDocName[0] == null
                                ? ""
                                : lastDocName[0];
                            setForegroundAsync(
                                createForegroundInfo(
                                    safeStage,
                                    safeDocName.isEmpty()
                                        ? "Working in background"
                                        : safeDocName,
                                    lastCurrent[0],
                                    lastTotal[0],
                                    lastCurrent[0] > 0 && lastTotal[0] > 0
                                )
                            );
                            setProgressAsync(
                                buildProgressData(
                                    safeStage,
                                    safeDocName,
                                    lastCurrent[0],
                                    lastTotal[0]
                                )
                            );
                        }

                        @Override
                        public void onDocumentProcessed(
                            int current,
                            int total,
                            String docName
                        ) {
                            String safeName = docName == null ? "" : docName;
                            lastCurrent[0] = current;
                            lastTotal[0] = total;
                            lastDocName[0] = safeName;
                            setForegroundAsync(
                                createForegroundInfo(
                                    "Indexing files",
                                    safeName.isEmpty()
                                        ? current + " of " + total
                                        : safeName,
                                    current,
                                    total,
                                    true
                                )
                            );
                            setProgressAsync(
                                buildProgressData(
                                    "Indexing files",
                                    safeName,
                                    current,
                                    total
                                )
                            );
                        }

                        @Override
                        public void onComplete(
                            int indexed,
                            int failed,
                            List<String> failedNames
                        ) {
                            hadFailures[0] = failed > 0;
                            setProgressAsync(
                                buildProgressData(
                                    failed > 0
                                        ? "Indexing finished with issues"
                                        : "Indexing finished",
                                    "",
                                    indexed,
                                    Math.max(indexed + failed, indexed)
                                )
                            );
                            showCompletionNotification(indexed, failed);
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
        } catch (OutOfMemoryError oom) {
            CrashLogger.logHandled(
                getApplicationContext(),
                "Background indexing OOM",
                new RuntimeException("Background indexing ran out of memory", oom)
            );
            setProgressAsync(
                buildProgressData(
                    "Indexing paused",
                    "Large document batch was trimmed for safety",
                    0,
                    0
                )
            );
            return Result.success();
        } catch (Throwable t) {
            CrashLogger.logHandled(
                getApplicationContext(),
                "Background indexing failure",
                t
            );
            return Result.success();
        }
    }

    private List<FolderEntity> resolveTargetFolders(
        FolderDao folderDao,
        PrefsManager prefsManager
    ) {
        long[] requestedIds = getInputData().getLongArray(KEY_FOLDER_IDS);
        Set<String> excludedUris = prefsManager.getExcludedFolderUris();
        List<FolderEntity> targets = new ArrayList<>();

        if (requestedIds != null && requestedIds.length > 0) {
            for (long folderId : requestedIds) {
                FolderEntity folder = folderDao.getById(folderId);
                if (
                    folder != null &&
                    folder.uri != null &&
                    !excludedUris.contains(folder.uri)
                ) {
                    targets.add(folder);
                }
            }
            return targets;
        }

        if (StorageAccessHelper.hasAllFilesAccess()) {
            List<File> publicFolders =
                StorageAccessHelper.discoverAccessiblePublicFolders(
                    getApplicationContext()
                );
            for (File folderFile : publicFolders) {
                String uri = Uri.fromFile(folderFile).toString();
                FolderEntity existing = folderDao.getByUri(uri);
                if (existing == null) {
                    FolderEntity folder = new FolderEntity(
                        uri,
                        StorageAccessHelper.getFolderDisplayName(folderFile)
                    );
                    long id = folderDao.insert(folder);
                    if (id > 0) {
                        folder.id = id;
                    } else {
                        FolderEntity inserted = folderDao.getByUri(uri);
                        if (inserted != null) {
                            folder = inserted;
                        }
                    }
                    if (!excludedUris.contains(folder.uri)) {
                        targets.add(folder);
                    }
                }
            }
        }

        List<FolderEntity> allFolders = folderDao.getAll();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (FolderEntity folder : allFolders) {
            if (
                folder == null ||
                folder.uri == null ||
                excludedUris.contains(folder.uri) ||
                !seen.add(folder.id)
            ) {
                continue;
            }
            targets.add(folder);
        }
        return targets;
    }

    private ScanBundle scanFolders(List<FolderEntity> folders) {
        List<DocumentEntity> allDocs = new ArrayList<>();
        List<Long> folderIds = new ArrayList<>();
        int skipped = 0;
        int totalFolders = folders == null ? 0 : folders.size();
        int currentFolder = 0;

        for (FolderEntity folder : folders) {
            if (isStopped()) {
                break;
            }
            if (folder == null || folder.uri == null) {
                continue;
            }
            currentFolder++;
            String folderName =
                folder.name == null || folder.name.trim().isEmpty()
                    ? "Scanning folders"
                    : folder.name;
            String stage =
                "Scanning folders " + currentFolder + " of " + totalFolders;
            setForegroundAsync(
                createForegroundInfo(stage, folderName, currentFolder, totalFolders, true)
            );
            setProgressAsync(
                buildProgressData(stage, folderName, currentFolder, totalFolders)
            );
            Uri uri = Uri.parse(folder.uri);
            try {
                DocumentScanner.ScanResult result;
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    String path = uri.getPath();
                    if (path == null || path.isEmpty()) {
                        continue;
                    }
                    result = DocumentScanner.scanFilesystemFolders(
                        getApplicationContext(),
                        Collections.singletonList(new File(path)),
                        Collections.singletonList(folder.id)
                    );
                } else {
                    result = DocumentScanner.scanFolders(
                        getApplicationContext(),
                        Collections.singletonList(uri),
                        Collections.singletonList(folder.id)
                    );
                }
                folderIds.add(folder.id);
                allDocs.addAll(result.documents);
                skipped += result.skipped;
            } catch (Exception ignored) {}
        }

        return new ScanBundle(
            new DocumentScanner.ScanResult(allDocs, skipped),
            folderIds
        );
    }

    private void syncScannedDocuments(
        DocumentDao documentDao,
        List<Long> folderIds,
        List<DocumentEntity> scannedDocs
    ) {
        java.util.Map<Long, java.util.List<String>> urisByFolder =
            new java.util.HashMap<>();
        for (Long folderId : folderIds) {
            if (folderId != null) {
                urisByFolder.put(folderId, new java.util.ArrayList<>());
            }
        }
        for (DocumentEntity doc : scannedDocs) {
            if (doc == null || doc.uri == null) continue;
            java.util.List<String> uris = urisByFolder.get(doc.folderId);
            if (uris != null) {
                uris.add(doc.uri);
            }
        }
        for (java.util.Map.Entry<Long, java.util.List<String>> entry : urisByFolder.entrySet()) {
            java.util.List<String> uris = entry.getValue();
            if (uris == null || uris.isEmpty()) {
                documentDao.deleteByFolderId(entry.getKey());
            } else {
                documentDao.deleteMissingByFolderId(entry.getKey(), uris);
            }
        }
    }

    private Data buildProgressData(
        String stage,
        String docName,
        int current,
        int total
    ) {
        return new Data.Builder()
            .putString(KEY_STAGE, stage == null ? "" : stage)
            .putString(KEY_DOC_NAME, docName == null ? "" : docName)
            .putInt(KEY_CURRENT, current)
            .putInt(KEY_TOTAL, total)
            .build();
    }

    private ForegroundInfo createForegroundInfo(
        String title,
        String text,
        int current,
        int total,
        boolean determinate
    ) {
        ensureNotificationChannel();
        Notification notification = new NotificationCompat.Builder(
            getApplicationContext(),
            CHANNEL_ID
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(createContentIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
            .build();
        if (determinate && total > 0) {
            notification =
                new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(createContentIntent())
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setProgress(total, current, false)
                    .setForegroundServiceBehavior(
                        NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                    )
                    .build();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        }
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getApplicationContext().getSystemService(
            NotificationManager.class
        );
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Ekko indexing",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows progress while Ekko indexes your files.");
        manager.createNotificationChannel(channel);
    }

    private PendingIntent createContentIntent() {
        Intent intent = new Intent(getApplicationContext(), LauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            getApplicationContext(),
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void showCompletionNotification(int indexed, int failed) {
        if (
            NotificationPermissionHelper.shouldRequestNotificationPermission(
                getApplicationContext()
            )
        ) {
            return;
        }
        String title = failed > 0 ? "Indexing finished with issues" : "Indexing finished";
        String text =
            indexed + " file" + (indexed == 1 ? "" : "s") + " ready in Ekko";
        if (failed > 0) {
            text += " • " + failed + " skipped";
        }
        Notification notification = new NotificationCompat.Builder(
            getApplicationContext(),
            CHANNEL_ID
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(createContentIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build();
        NotificationManagerCompat
            .from(getApplicationContext())
            .notify(COMPLETE_NOTIFICATION_ID, notification);
    }

    private static class ScanBundle {

        final DocumentScanner.ScanResult scanResult;
        final List<Long> folderIds;

        ScanBundle(DocumentScanner.ScanResult scanResult, List<Long> folderIds) {
            this.scanResult = scanResult;
            this.folderIds = folderIds;
        }
    }
}
