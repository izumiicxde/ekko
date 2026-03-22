package com.semantic.ekko.data.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;

@Database(
    entities = { DocumentEntity.class, FolderEntity.class },
    version = 2,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DB_NAME = "ekko.db";
    private static volatile AppDatabase instance;

    public abstract DocumentDao documentDao();

    public abstract FolderDao folderDao();

    // =========================
    // MIGRATIONS
    // =========================

    /**
     * Version 1 -> 2: adds the chunks column to the documents table.
     * Existing rows will have chunks = NULL until they are re-indexed.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                "ALTER TABLE documents ADD COLUMN chunks TEXT DEFAULT NULL"
            );
        }
    };

    // =========================
    // INSTANCE
    // =========================

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        DB_NAME
                    )
                        .addMigrations(MIGRATION_1_2)
                        .build();
                }
            }
        }
        return instance;
    }
}
