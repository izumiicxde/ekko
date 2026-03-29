package com.semantic.ekko.data.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.semantic.ekko.data.model.ChunkEntity;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;

@Database(
    entities = { DocumentEntity.class, FolderEntity.class, ChunkEntity.class },
    version = 4,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DB_NAME = "ekko.db";
    private static volatile AppDatabase instance;

    public abstract DocumentDao documentDao();
    public abstract FolderDao folderDao();
    public abstract ChunkDao chunkDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "ALTER TABLE documents ADD COLUMN chunks TEXT DEFAULT NULL"
            );
        }
    };

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS chunks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "document_id INTEGER NOT NULL, " +
                "chunk_index INTEGER NOT NULL, " +
                "chunk_text TEXT, " +
                "chunk_embedding BLOB, " +
                "FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE)"
            );
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chunks_document_id ON chunks(document_id)"
            );
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "ALTER TABLE documents ADD COLUMN relative_path TEXT DEFAULT NULL"
            );
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        DB_NAME
                    )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build();
                }
            }
        }
        return instance;
    }
}
