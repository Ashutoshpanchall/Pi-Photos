package com.example.piphotos.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface AlbumDao {
    @Insert
    long insertAlbum(AlbumEntity album);

    @Update
    void updateAlbum(AlbumEntity album);

    @Query("UPDATE album SET name = :newName WHERE id = :albumId")
    void renameAlbum(long albumId, String newName);

    @Query("UPDATE album SET cover_media_store_id = :coverId WHERE id = :albumId")
    void updateCover(long albumId, Long coverId);

    @Query("DELETE FROM album WHERE id = :albumId")
    void deleteAlbum(long albumId);

    @Query("SELECT * FROM album ORDER BY created_at_ms DESC")
    List<AlbumEntity> getAllAlbums();

    @Query("SELECT * FROM album WHERE id = :albumId")
    AlbumEntity getAlbumById(long albumId);

    @Query("SELECT * FROM album WHERE name = :name LIMIT 1")
    AlbumEntity getAlbumByName(String name);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertItems(List<AlbumItemEntity> items);

    @Query("DELETE FROM album_item WHERE album_id = :albumId AND media_store_id = :mediaStoreId")
    void removeItem(long albumId, long mediaStoreId);

    @Query("SELECT media_store_id FROM album_item WHERE album_id = :albumId ORDER BY added_at_ms DESC")
    List<Long> getItemIdsForAlbum(long albumId);

    @Query("SELECT COUNT(*) FROM album_item WHERE album_id = :albumId")
    int getItemCountForAlbum(long albumId);
}

