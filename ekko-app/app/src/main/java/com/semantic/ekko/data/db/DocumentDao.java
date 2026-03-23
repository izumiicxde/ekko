package com.semantic.ekko.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.semantic.ekko.data.model.DocumentEntity;
import java.util.List;

@Dao
public interface DocumentDao {
    // =========================
    // INSERT / UPDATE / DELETE
    // =========================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(DocumentEntity document);

    @Update
    void update(DocumentEntity document);

    @Delete
    void delete(DocumentEntity document);

    @Query("DELETE FROM documents WHERE folder_id = :folderId")
    void deleteByFolderId(long folderId);

    @Query("DELETE FROM documents")
    void deleteAll();

    // =========================
    // QUERIES
    // =========================
    @Query("SELECT * FROM documents ORDER BY indexed_at DESC")
    LiveData<List<DocumentEntity>> getAllLive();

    @Query("SELECT * FROM documents ORDER BY indexed_at DESC")
    List<DocumentEntity> getAll();

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    DocumentEntity getById(long id);

    @Query(
        "SELECT * FROM documents WHERE folder_id = :folderId ORDER BY name ASC"
    )
    List<DocumentEntity> getByFolderId(long folderId);

    @Query(
        "SELECT * FROM documents WHERE category = :category ORDER BY indexed_at DESC"
    )
    List<DocumentEntity> getByCategory(String category);

    @Query(
        "SELECT * FROM documents WHERE file_type = :fileType ORDER BY indexed_at DESC"
    )
    List<DocumentEntity> getByFileType(String fileType);

    // =========================
    // CATEGORY UPDATE
    // =========================
    @Query("UPDATE documents SET category = :category WHERE id = :id")
    void updateCategory(long id, String category);

    @Query("UPDATE documents SET summary = :summary WHERE id = :id")
    void updateSummary(long id, String summary);

    // =========================
    // STATISTICS QUERIES
    // =========================
    @Query("SELECT COUNT(*) FROM documents")
    int getTotalCount();

    @Query("SELECT SUM(word_count) FROM documents")
    int getTotalWordCount();

    @Query(
        "SELECT category, COUNT(*) as count FROM documents GROUP BY category"
    )
    List<CategoryCount> getCategoryDistribution();

    @Query(
        "SELECT file_type, COUNT(*) as count FROM documents GROUP BY file_type"
    )
    List<FileTypeCount> getFileTypeDistribution();

    @Query("SELECT * FROM documents ORDER BY word_count DESC LIMIT 1")
    DocumentEntity getLargestDocument();

    @Query("SELECT * FROM documents ORDER BY indexed_at DESC LIMIT 1")
    DocumentEntity getMostRecentDocument();

    // =========================
    // EMBEDDED DOCUMENTS
    // =========================
    @Query(
        "SELECT id, name, uri, category, embedding, word_count, file_type, keywords, summary FROM documents WHERE embedding IS NOT NULL"
    )
    List<DocumentEmbeddingRow> getAllEmbeddings();

    // =========================
    // SEARCH SUPPORT
    // =========================
    @Query(
        "SELECT * FROM documents WHERE name LIKE '%' || :query || '%' ORDER BY indexed_at DESC"
    )
    List<DocumentEntity> searchByName(String query);

    // =========================
    // INNER RESULT CLASSES
    // =========================
    class CategoryCount {

        public String category;
        public int count;
    }

    class FileTypeCount {

        public String file_type;
        public int count;
    }

    class DocumentEmbeddingRow {

        public long id;
        public String name;
        public String uri;
        public String category;
        public byte[] embedding;
        public int word_count;
        public String file_type;
        public String keywords;
        public String summary;
    }
}
