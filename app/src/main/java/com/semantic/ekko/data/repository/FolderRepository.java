package com.semantic.ekko.data.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.semantic.ekko.data.db.AppDatabase;
import com.semantic.ekko.data.db.FolderDao;
import com.semantic.ekko.data.model.FolderEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FolderRepository {

    private final FolderDao folderDao;
    private final DocumentRepository documentRepository;
    private final ExecutorService executor;

    // =========================
    // INIT
    // =========================

    public FolderRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.folderDao = db.folderDao();
        this.documentRepository = new DocumentRepository(context);
        this.executor = Executors.newSingleThreadExecutor();
    }

    // =========================
    // INSERT / DELETE
    // =========================

    public void insert(FolderEntity folder, InsertCallback callback) {
        executor.execute(() -> {
            long id = folderDao.insert(folder);
            if (callback != null) callback.onInserted(id);
        });
    }

    /**
     * Deletes the folder and all its associated documents via CASCADE.
     * Also clears documents from the repository to keep data consistent.
     */
    public void delete(FolderEntity folder) {
        executor.execute(() -> {
            documentRepository.deleteByFolderId(folder.id);
            folderDao.delete(folder);
        });
    }

    // =========================
    // QUERIES
    // =========================

    public LiveData<List<FolderEntity>> getAllLive() {
        return folderDao.getAllLive();
    }

    public void getAll(QueryCallback<List<FolderEntity>> callback) {
        executor.execute(() -> {
            List<FolderEntity> result = folderDao.getAll();
            if (callback != null) callback.onResult(result);
        });
    }

    public void getById(long id, QueryCallback<FolderEntity> callback) {
        executor.execute(() -> {
            FolderEntity result = folderDao.getById(id);
            if (callback != null) callback.onResult(result);
        });
    }

    public void getCount(QueryCallback<Integer> callback) {
        executor.execute(() -> {
            int count = folderDao.getCount();
            if (callback != null) callback.onResult(count);
        });
    }

    // =========================
    // CALLBACKS
    // =========================

    public interface InsertCallback {
        void onInserted(long id);
    }

    public interface QueryCallback<T> {
        void onResult(T result);
    }
}
