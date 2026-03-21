package com.semantic.ekko.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.semantic.ekko.data.model.FolderEntity;

import java.util.List;

@Dao
public interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(FolderEntity folder);

    @Delete
    void delete(FolderEntity folder);

    @Query("SELECT * FROM folders ORDER BY added_at DESC")
    LiveData<List<FolderEntity>> getAllLive();

    @Query("SELECT * FROM folders ORDER BY added_at DESC")
    List<FolderEntity> getAll();

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    FolderEntity getById(long id);

    @Query("SELECT COUNT(*) FROM folders")
    int getCount();
}
