package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.FavoriteItem

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorites WHERE parent=0 AND home_page_bookmark=:homePageBookmarks ORDER BY id DESC")
    suspend fun getAll(homePageBookmarks: Boolean = false): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE parent=0 AND home_page_bookmark=1 ORDER BY i_order ASC")
    suspend fun getHomePageBookmarks(): List<FavoriteItem>

    // Every normal bookmark (not folders): home-page-flagged ones first (in their order), then the rest newest first
    @Query("SELECT * FROM favorites WHERE parent=0 AND url IS NOT NULL ORDER BY home_page_bookmark DESC, i_order ASC, id DESC")
    suspend fun getAllForHomePage(): List<FavoriteItem>

    @Insert
    suspend fun insert(item: FavoriteItem): Long

    @Update
    suspend fun update(item: FavoriteItem)

    @Delete
    suspend fun delete(item: FavoriteItem)

    @Query("DELETE FROM favorites WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM favorites WHERE id=:id")
    suspend fun getById(id: Long): FavoriteItem?

    @Query("UPDATE favorites SET useful=1 WHERE id=:favoriteId")
    suspend fun markAsUseful(favoriteId: Long)
}