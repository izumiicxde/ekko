package com.semantic.ekko.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.semantic.ekko.data.model.ChunkEntity;
import java.util.List;

@Dao
public interface ChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ChunkEntity chunk);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ChunkEntity> chunks);

    @Query("DELETE FROM chunks WHERE document_id = :documentId")
    void deleteByDocumentId(long documentId);

    @Query("DELETE FROM chunks")
    void deleteAll();

    @Query("SELECT * FROM chunks")
    List<ChunkEntity> getAll();

    @Query("SELECT * FROM chunks WHERE document_id = :documentId ORDER BY chunk_index ASC")
    List<ChunkEntity> getByDocumentId(long documentId);

    @Query("SELECT COUNT(*) FROM chunks")
    int getTotalCount();
}
