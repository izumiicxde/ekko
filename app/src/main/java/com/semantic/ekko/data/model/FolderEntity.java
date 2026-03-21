package com.semantic.ekko.data.model;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "folders")
public class FolderEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "uri")
    public String uri;

    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "added_at")
    public long addedAt;

    public FolderEntity() {}

    @Ignore
    public FolderEntity(String uri, String name) {
        this.uri = uri;
        this.name = name;
        this.addedAt = System.currentTimeMillis();
    }
}
