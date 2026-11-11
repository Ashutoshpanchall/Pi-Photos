package com.example.piphotos.db;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
    entities = {FavoriteEntity.class, AlbumEntity.class, AlbumItemEntity.class},
    version = 2,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract FavoriteDao favoriteDao();
    public abstract AlbumDao albumDao();

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
                    .addMigrations(MIGRATION_1_2)
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
