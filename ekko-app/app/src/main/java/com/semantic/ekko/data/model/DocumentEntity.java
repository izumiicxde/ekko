package com.semantic.ekko.data.model;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "documents",
    foreignKeys = @ForeignKey(
        entity = FolderEntity.class,
        parentColumns = "id",
        childColumns = "folder_id",
        onDelete = ForeignKey.CASCADE
    ),
    indices = { @Index("folder_id") }
)
public class DocumentEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "uri")
    public String uri;

    @ColumnInfo(name = "relative_path")
    public String relativePath;

    @ColumnInfo(name = "folder_id")
    public long folderId;

    @ColumnInfo(name = "file_type")
    public String fileType; // pdf, docx, pptx, txt

    @ColumnInfo(name = "raw_text")
    public String rawText;

    @ColumnInfo(name = "summary")
    public String summary;

    @ColumnInfo(name = "keywords")
    public String keywords; // comma-separated

    @ColumnInfo(name = "entities")
    public String entities; // double-comma-separated via EntityExtractorHelper

    @ColumnInfo(name = "category")
    public String category;

    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    public byte[] embedding; // float[] serialized via EmbeddingEngine.toBytes()

    @ColumnInfo(name = "word_count")
    public int wordCount;

    @ColumnInfo(name = "indexed_at")
    public long indexedAt; // System.currentTimeMillis()

    @ColumnInfo(name = "source_size")
    public long sourceSize;

    @ColumnInfo(name = "source_modified_at")
    public long sourceModifiedAt;

    /**
     * JSON array string of overlapping text chunks produced during indexing.
     * Used by RagRepository to find the top-k most relevant chunks for a query
     * and send them to the FastAPI backend for answer generation.
     * Null for documents indexed before version 2.
     */
    @ColumnInfo(name = "chunks")
    public String chunks;

    public DocumentEntity() {}

    @Ignore
    public DocumentEntity(
        String name,
        String uri,
        String relativePath,
        long folderId,
        String fileType
    ) {
        this.name = name;
        this.uri = uri;
        this.relativePath = relativePath;
        this.folderId = folderId;
        this.fileType = fileType;
        this.indexedAt = System.currentTimeMillis();
    }
}
