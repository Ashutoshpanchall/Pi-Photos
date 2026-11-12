package com.example.piphotos.db;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
    entities = {FavoriteEntity.class, AlbumEntity.class, AlbumItemEntity.class, MediaIndexEntity.class},
    version = 3,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract FavoriteDao favoriteDao();
    public abstract AlbumDao albumDao();
    public abstract MediaIndexDao mediaIndexDao();

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `favorite` (`media_store_id` INTEGER NOT NULL, `added_at_ms` INTEGER NOT NULL, PRIMARY KEY(`media_store_id`))");
            database.execSQL("INSERT OR IGNORE INTO `favorite` (media_store_id, added_at_ms) SELECT mediaId, addedAt FROM favorites");
            database.execSQL("DROP TABLE IF EXISTS `favorites`");

            database.execSQL("CREATE TABLE IF NOT EXISTS `album` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `created_at_ms` INTEGER NOT NULL, `cover_media_store_id` INTEGER)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `album_item` (`album_id` INTEGER NOT NULL, `media_store_id` INTEGER NOT NULL, `is_video` INTEGER NOT NULL, `added_at_ms` INTEGER NOT NULL, PRIMARY KEY(`album_id`, `media_store_id`), FOREIGN KEY(`album_id`) REFERENCES `album`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_album_item_album_id` ON `album_item` (`album_id`)");
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `media_index` ("
                + "`id` INTEGER NOT NULL, "
                + "`is_video` INTEGER NOT NULL, "
                + "`mime_type` TEXT, "
                + "`date_taken_ms` INTEGER NOT NULL, "
                + "`date_added_sec` INTEGER NOT NULL, "
                + "`date_modified_sec` INTEGER NOT NULL, "
                + "`bucket_id` INTEGER NOT NULL, "
                + "`bucket_name` TEXT, "
                + "`size_bytes` INTEGER NOT NULL, "
                + "`duration_ms` INTEGER NOT NULL, "
                + "`generation_modified` INTEGER NOT NULL, "
                + "PRIMARY KEY(`id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_media_index_date_taken_ms_date_modified_sec` ON `media_index` (`date_taken_ms`, `date_modified_sec`)");
        }
    };

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        "piphotos_local.db"
                    )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(new Callback() {
                        @Override
                        public void onOpen(@NonNull SupportSQLiteDatabase db) {
                            super.onOpen(db);
                            db.execSQL("PRAGMA foreign_keys = ON;");
                        }
                    })
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
