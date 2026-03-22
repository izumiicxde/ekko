package com.semantic.ekko.data.model;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "chunks",
    foreignKeys = @ForeignKey(
        entity = DocumentEntity.class,
        parentColumns = "id",
        childColumns = "document_id",
        onDelete = ForeignKey.CASCADE
    ),
    indices = { @Index("document_id") }
)
public class ChunkEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "document_id")
    public long documentId;

    @ColumnInfo(name = "chunk_index")
    public int chunkIndex;

    @ColumnInfo(name = "chunk_text")
    public String chunkText;

    @ColumnInfo(name = "chunk_embedding", typeAffinity = ColumnInfo.BLOB)
    public byte[] chunkEmbedding;

    public ChunkEntity() {}

    public ChunkEntity(long documentId, int chunkIndex, String chunkText, byte[] chunkEmbedding) {
        this.documentId     = documentId;
        this.chunkIndex     = chunkIndex;
        this.chunkText      = chunkText;
        this.chunkEmbedding = chunkEmbedding;
    }
}
