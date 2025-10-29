package dev.jigen.zimusic

import android.database.SQLException
import android.os.Parcel
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.jigen.zimusic.models.Album
import dev.jigen.zimusic.models.Artist
import dev.jigen.zimusic.models.Event
import dev.jigen.zimusic.models.EventWithSong
import dev.jigen.zimusic.models.Format
import dev.jigen.zimusic.models.Info
import dev.jigen.zimusic.models.Lyrics
import dev.jigen.zimusic.models.Playlist
import dev.jigen.zimusic.models.PlaylistPreview
import dev.jigen.zimusic.models.PlaylistWithSongs
import dev.jigen.zimusic.models.QueuedMediaItem
import dev.jigen.zimusic.models.SearchQuery
import dev.jigen.zimusic.models.Song
import dev.jigen.zimusic.models.SongAlbumMap
import dev.jigen.zimusic.models.SongArtistMap
import dev.jigen.zimusic.models.SongPlaylistMap
import dev.jigen.zimusic.models.SongWithContentLength
import dev.jigen.zimusic.models.SortedSongPlaylistMap
import dev.jigen.zimusic.service.LOCAL_KEY_PREFIX
import dev.jigen.core.data.enums.AlbumSortBy
import dev.jigen.core.data.enums.ArtistSortBy
import dev.jigen.core.data.enums.PlaylistSortBy
import dev.jigen.core.data.enums.SongSortBy
import dev.jigen.core.data.enums.SortOrder
import dev.jigen.core.ui.utils.songBundle
import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions")
interface Database {
    companion object {
        val instance: Database
            get() = DatabaseInitializer.instance.database
    }

    @Transaction
    @Query("SELECT * FROM Song WHERE id NOT LIKE '$LOCAL_KEY_PREFIX%' ORDER BY ROWID ASC")
    @RewriteQueriesToDropUnusedColumns
    fun songsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id NOT LIKE '$LOCAL_KEY_PREFIX%' ORDER BY ROWID DESC")
    @RewriteQueriesToDropUnusedColumns
    fun songsByRowIdDesc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id NOT LIKE '$LOCAL_KEY_PREFIX%' ORDER BY title COLLATE NOCASE ASC")
    @RewriteQueriesToDropUnusedColumns
    fun songsByTitleAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id NOT LIKE '$LOCAL_KEY_PREFIX%' ORDER BY title COLLATE NOCASE DESC")
    @RewriteQueriesToDropUnusedColumns
    fun songsByTitleDesc(): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT * FROM Song
        WHERE id NOT LIKE '$LOCAL_KEY_PREFIX%'
        ORDER BY totalPlayTimeMs ASC
        """
    )
    @RewriteQueriesToDropUnusedColumns
    fun songsByPlayTimeAsc(): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT * FROM Song
        WHERE id NOT LIKE '$LOCAL_KEY_PREFIX%'
        ORDER BY totalPlayTimeMs DESC
        LIMIT :limit
        """
    )
    @RewriteQueriesToDropUnusedColumns
    fun songsByPlayTimeDesc(limit: Int = -1): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id LIKE '$LOCAL_KEY_PREFIX%' ORDER BY ROWID ASC")
    @RewriteQueriesToDropUnusedColumns
    fun localSongsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id LIKE '$LOCAL_KEY_PREFIX%' ORDER BY ROWID DESC")
    @RewriteQueriesToDropUnusedColumns
    fun localSongsByRowIdDesc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id LIKE '$LOCAL_KEY_PREFIX%' ORDER BY title COLLATE NOCASE ASC")
    @RewriteQueriesToDropUnusedColumns
    fun localSongsByTitleAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id LIKE '$LOCAL_KEY_PREFIX%' ORDER BY title COLLATE NOCASE DESC")
    @RewriteQueriesToDropUnusedColumns
    fun localSongsByTitleDesc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id LIKE '$LOCAL_KEY_PREFIX%' ORDER BY totalPlayTimeMs ASC")
    @RewriteQueriesToDropUnusedColumns
    fun localSongsByPlayTimeAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE id LIKE '$LOCAL_KEY_PREFIX%' ORDER BY totalPlayTimeMs DESC")
    @RewriteQueriesToDropUnusedColumns
    fun localSongsByPlayTimeDesc(): Flow<List<Song>>

    @Suppress("CyclomaticComplexMethod")
    fun songs(sortBy: SongSortBy, sortOrder: SortOrder, isLocal: Boolean = false) = when (sortBy) {
        SongSortBy.PlayTime -> when (sortOrder) {
            SortOrder.Ascending -> if (isLocal) localSongsByPlayTimeAsc() else songsByPlayTimeAsc()
            SortOrder.Descending -> if (isLocal) localSongsByPlayTimeDesc() else songsByPlayTimeDesc()
        }

        SongSortBy.Title -> when (sortOrder) {
            SortOrder.Ascending -> if (isLocal) localSongsByTitleAsc() else songsByTitleAsc()
            SortOrder.Descending -> if (isLocal) localSongsByTitleDesc() else songsByTitleDesc()
        }

        SongSortBy.Position,
        SongSortBy.DateAdded -> when (sortOrder) {
            SortOrder.Ascending -> if (isLocal) localSongsByRowIdAsc() else songsByRowIdAsc()
            SortOrder.Descending -> if (isLocal) localSongsByRowIdDesc() else songsByRowIdDesc()
        }
    }

    @Transaction
    @Query("SELECT * FROM Song WHERE likedAt IS NOT NULL ORDER BY totalPlayTimeMs ASC")
    fun favoritesByPlayTimeAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE likedAt IS NOT NULL ORDER BY totalPlayTimeMs DESC")
    fun favoritesByPlayTimeDesc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE likedAt IS NOT NULL ORDER BY likedAt ASC")
    fun favoritesByLikedAtAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE likedAt IS NOT NULL ORDER BY likedAt DESC")
    fun favoritesByLikedAtDesc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE likedAt IS NOT NULL ORDER BY title COLLATE NOCASE ASC")
    fun favoritesByTitleAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Song WHERE likedAt IS NOT NULL ORDER BY title COLLATE NOCASE DESC")
    fun favoritesByTitleDesc(): Flow<List<Song>>

    fun favorites(
        sortBy: SongSortBy = SongSortBy.DateAdded,
        sortOrder: SortOrder = SortOrder.Descending
    ) = when (sortBy) {
        SongSortBy.PlayTime -> when (sortOrder) {
            SortOrder.Ascending -> favoritesByPlayTimeAsc()
            SortOrder.Descending -> favoritesByPlayTimeDesc()
        }

        SongSortBy.Title -> when (sortOrder) {
            SortOrder.Ascending -> favoritesByTitleAsc()
            SortOrder.Descending -> favoritesByTitleDesc()
        }

        SongSortBy.Position,
        SongSortBy.DateAdded -> when (sortOrder) {
            SortOrder.Ascending -> favoritesByLikedAtAsc()
            SortOrder.Descending -> favoritesByLikedAtDesc()
        }
    }

    @Query("SELECT * FROM QueuedMediaItem")
    fun queue(): List<QueuedMediaItem>

    @Transaction
    @Query(
        """
        SELECT Song.* FROM Event
        JOIN Song ON Song.id = Event.songId
        WHERE Event.ROWID in (
            SELECT max(Event.ROWID)
            FROM Event
            GROUP BY songId
        )
        ORDER BY timestamp DESC
        LIMIT :size
        """
    )
    @RewriteQueriesToDropUnusedColumns
    fun history(size: Int = 100): Flow<List<Song>>

    @Query("DELETE FROM QueuedMediaItem")
    fun clearQueue()

    @Query("SELECT * FROM SearchQuery WHERE `query` LIKE :query ORDER BY id DESC")
    fun queries(query: String): Flow<List<SearchQuery>>

    @Query("SELECT COUNT (*) FROM SearchQuery")
    fun queriesCount(): Flow<Int>

    @Query("DELETE FROM SearchQuery")
    fun clearQueries()

    @Query("SELECT * FROM Song WHERE id = :id")
    fun song(id: String): Flow<Song?>

    @Query("SELECT likedAt FROM Song WHERE id = :songId")
    fun likedAt(songId: String): Flow<Long?>

    @Query("SELECT likedAt FROM Song WHERE id = :songId")
    fun getLikedAtSync(songId: String): Long?

    @Query("UPDATE Song SET likedAt = :likedAt WHERE id = :songId")
    fun like(songId: String, likedAt: Long?): Int

    @Query("UPDATE Song SET durationText = :durationText WHERE id = :songId")
    fun updateDurationText(songId: String, durationText: String): Int

    @Query("SELECT * FROM Lyrics WHERE songId = :songId")
    fun lyrics(songId: String): Flow<Lyrics?>

    @Query("SELECT * FROM Artist WHERE id = :id")
    fun artist(id: String): Flow<Artist?>

    @Query("SELECT * FROM Artist WHERE bookmarkedAt IS NOT NULL ORDER BY name COLLATE NOCASE DESC")
    fun artistsByNameDesc(): Flow<List<Artist>>

    @Query("SELECT * FROM Artist WHERE bookmarkedAt IS NOT NULL ORDER BY name COLLATE NOCASE ASC")
    fun artistsByNameAsc(): Flow<List<Artist>>

    @Query("SELECT * FROM Artist WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt DESC")
    fun artistsByRowIdDesc(): Flow<List<Artist>>

    @Query("SELECT * FROM Artist WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt ASC")
    fun artistsByRowIdAsc(): Flow<List<Artist>>

    fun artists(sortBy: ArtistSortBy, sortOrder: SortOrder) = when (sortBy) {
        ArtistSortBy.Name -> when (sortOrder) {
            SortOrder.Ascending -> artistsByNameAsc()
            SortOrder.Descending -> artistsByNameDesc()
        }

        ArtistSortBy.DateAdded -> when (sortOrder) {
            SortOrder.Ascending -> artistsByRowIdAsc()
            SortOrder.Descending -> artistsByRowIdDesc()
        }
    }

    @Query("SELECT * FROM Album WHERE id = :id")
    fun album(id: String): Flow<Album?>

    @Transaction
    @Query(
        """
        SELECT * FROM Song
        JOIN SongAlbumMap ON Song.id = SongAlbumMap.songId
        WHERE SongAlbumMap.albumId = :albumId AND
        position IS NOT NULL
        ORDER BY position
        """
    )
    @RewriteQueriesToDropUnusedColumns
    fun albumSongs(albumId: String): Flow<List<Song>>

    @Query("SELECT * FROM Album WHERE bookmarkedAt IS NOT NULL ORDER BY title COLLATE NOCASE ASC")
    fun albumsByTitleAsc(): Flow<List<Album>>

    @Query("SELECT * FROM Album WHERE bookmarkedAt IS NOT NULL ORDER BY year ASC, authorsText COLLATE NOCASE ASC")
    fun albumsByYearAsc(): Flow<List<Album>>

    @Query("SELECT * FROM Album WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt ASC")
    fun albumsByRowIdAsc(): Flow<List<Album>>

    @Query("SELECT * FROM Album WHERE bookmarkedAt IS NOT NULL ORDER BY title COLLATE NOCASE DESC")
    fun albumsByTitleDesc(): Flow<List<Album>>

    @Query("SELECT * FROM Album WHERE bookmarkedAt IS NOT NULL ORDER BY year DESC, authorsText COLLATE NOCASE DESC")
    fun albumsByYearDesc(): Flow<List<Album>>

    @Query("SELECT * FROM Album WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt DESC")
    fun albumsByRowIdDesc(): Flow<List<Album>>

    fun albums(sortBy: AlbumSortBy, sortOrder: SortOrder) = when (sortBy) {
        AlbumSortBy.Title -> when (sortOrder) {
            SortOrder.Ascending -> albumsByTitleAsc()
            SortOrder.Descending -> albumsByTitleDesc()
        }

        AlbumSortBy.Year -> when (sortOrder) {
            SortOrder.Ascending -> albumsByYearAsc()
            SortOrder.Descending -> albumsByYearDesc()
        }

        AlbumSortBy.DateAdded -> when (sortOrder) {
            SortOrder.Ascending -> albumsByRowIdAsc()
            SortOrder.Descending -> albumsByRowIdDesc()
        }
    }

    @Query("UPDATE Song SET totalPlayTimeMs = totalPlayTimeMs + :addition WHERE id = :id")
    fun incrementTotalPlayTimeMs(id: String, addition: Long)

    @Query("SELECT * FROM Playlist WHERE id = :id")
    fun playlist(id: Long): Flow<Playlist?>

    fun playlistSongs(id: Long, sortBy: SongSortBy, sortOrder: SortOrder): Flow<List<Song>?> {
        return when (sortBy) {
            SongSortBy.Position -> when (sortOrder) {
                SortOrder.Ascending -> _playlistSongsByPositionAsc(id)
                SortOrder.Descending -> _playlistSongsByPositionDesc(id)
            }
            SongSortBy.DateAdded -> when (sortOrder) {
                SortOrder.Ascending -> _playlistSongsByDateAddedAsc(id)
                SortOrder.Descending -> _playlistSongsByDateAddedDesc(id)
            }
            SongSortBy.Title -> when (sortOrder) {
                SortOrder.Ascending -> _playlistSongsByTitleAsc(id)
                SortOrder.Descending -> _playlistSongsByTitleDesc(id)
            }
            SongSortBy.PlayTime -> when (sortOrder) {
                SortOrder.Ascending -> _playlistSongsByPlayTimeAsc(id)
                SortOrder.Descending -> _playlistSongsByPlayTimeDesc(id)
            }
        }
    }

    @Transaction @Query("SELECT Song.* FROM SongPlaylistMap INNER JOIN Song on Song.id = SongPlaylistMap.songId WHERE playlistId = :id ORDER BY SongPlaylistMap.position ASC")
    fun _playlistSongsByPositionAsc(id: Long): Flow<List<Song>?>

    @Transaction @Query("SELECT Song.* FROM SongPlaylistMap INNER JOIN Song on Song.id = SongPlaylistMap.songId WHERE playlistId = :id ORDER BY SongPlaylistMap.position DESC")
    fun _playlistSongsByPositionDesc(id: Long): Flow<List<Song>?>

    @Transaction @Query("SELECT Song.* FROM SongPlaylistMap INNER JOIN Song on Song.id = SongPlaylistMap.songId WHERE playlistId = :id ORDER BY SongPlaylistMap.ROWID ASC")
    fun _playlistSongsByDateAddedAsc(id: Long): Flow<List<Song>?>

    @Transaction @Query("SELECT Song.* FROM SongPlaylistMap INNER JOIN Song on Song.id = SongPlaylistMap.songId WHERE playlistId = :id ORDER BY SongPlaylistMap.ROWID DESC")
    fun _playlistSongsByDateAddedDesc(id: Long): Flow<List<Song>?>

    @Transaction @Query("SELECT Song.* FROM SongPlaylistMap INNER JOIN Song on Song.id = SongPlaylistMap.songId WHERE playlistId = :id ORDER BY Song.title COLLATE NOCASE ASC")
    fun _playlistSongsByTitleAsc(id: Long): Flow<List<Song>?>

    @Transaction @Query("SELECT Song.* FROM SongPlaylistMap INNER JOIN Song on Song.id = SongPlaylistMap.songId WHERE playlistId = :id ORDER BY Song.title COLLATE NOCASE DESC")
    fun _playlistSongsByTitleDesc(id: Long): Flow<List<Song>?>

    @Transaction @Query("SELECT Song.* FROM SongPlaylistMap INNER JOIN Song on Song.id = SongPlaylistMap.songId WHERE playlistId = :id ORDER BY Song.totalPlayTimeMs ASC")
    fun _playlistSongsByPlayTimeAsc(id: Long): Flow<List<Song>?>

    @Transaction @Query("SELECT Song.* FROM SongPlaylistMap INNER JOIN Song on Song.id = SongPlaylistMap.songId WHERE playlistId = :id ORDER BY Song.totalPlayTimeMs DESC")
    fun _playlistSongsByPlayTimeDesc(id: Long): Flow<List<Song>?>

    @Transaction
    @Query("SELECT * FROM Playlist WHERE id = :id")
    fun playlistWithSongs(id: Long): Flow<PlaylistWithSongs?>

    @Transaction
    @Query(
        """
        SELECT Playlist.id, Playlist.name, COUNT(SongPlaylistMap.songId) as songCount, Playlist.thumbnail
        FROM Playlist
        LEFT JOIN SongPlaylistMap ON Playlist.id = SongPlaylistMap.playlistId
        GROUP BY Playlist.id
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    fun playlistPreviewsByNameAsc(): Flow<List<PlaylistPreview>>

    @Transaction
    @Query(
        """
        SELECT Playlist.id, Playlist.name, COUNT(SongPlaylistMap.songId) as songCount, Playlist.thumbnail
        FROM Playlist
        LEFT JOIN SongPlaylistMap ON Playlist.id = SongPlaylistMap.playlistId
        GROUP BY Playlist.id
        ORDER BY Playlist.ROWID ASC
        """
    )
    fun playlistPreviewsByDateAddedAsc(): Flow<List<PlaylistPreview>>

    @Transaction
    @Query(
        """
        SELECT Playlist.id, Playlist.name, COUNT(SongPlaylistMap.songId) as songCount, Playlist.thumbnail
        FROM Playlist
        LEFT JOIN SongPlaylistMap ON Playlist.id = SongPlaylistMap.playlistId
        GROUP BY Playlist.id
        ORDER BY songCount ASC
        """
    )
    fun playlistPreviewsByDateSongCountAsc(): Flow<List<PlaylistPreview>>

    @Transaction
    @Query(
        """
        SELECT Playlist.id, Playlist.name, COUNT(SongPlaylistMap.songId) as songCount, Playlist.thumbnail
        FROM Playlist
        LEFT JOIN SongPlaylistMap ON Playlist.id = SongPlaylistMap.playlistId
        GROUP BY Playlist.id
        ORDER BY name COLLATE NOCASE DESC
        """
    )
    fun playlistPreviewsByNameDesc(): Flow<List<PlaylistPreview>>

    @Transaction
    @Query(
        """
        SELECT Playlist.id, Playlist.name, COUNT(SongPlaylistMap.songId) as songCount, Playlist.thumbnail
        FROM Playlist
        LEFT JOIN SongPlaylistMap ON Playlist.id = SongPlaylistMap.playlistId
        GROUP BY Playlist.id
        ORDER BY Playlist.ROWID DESC
        """
    )
    fun playlistPreviewsByDateAddedDesc(): Flow<List<PlaylistPreview>>

    @Transaction
    @Query(
        """
        SELECT Playlist.id, Playlist.name, COUNT(SongPlaylistMap.songId) as songCount, Playlist.thumbnail
        FROM Playlist
        LEFT JOIN SongPlaylistMap ON Playlist.id = SongPlaylistMap.playlistId
        GROUP BY Playlist.id
        ORDER BY songCount DESC
        """
    )
    fun playlistPreviewsByDateSongCountDesc(): Flow<List<PlaylistPreview>>

    fun playlistPreviews(
        sortBy: PlaylistSortBy,
        sortOrder: SortOrder
    ) = when (sortBy) {
        PlaylistSortBy.Name -> when (sortOrder) {
            SortOrder.Ascending -> playlistPreviewsByNameAsc()
            SortOrder.Descending -> playlistPreviewsByNameDesc()
        }

        PlaylistSortBy.SongCount -> when (sortOrder) {
            SortOrder.Ascending -> playlistPreviewsByDateSongCountAsc()
            SortOrder.Descending -> playlistPreviewsByDateSongCountDesc()
        }

        PlaylistSortBy.DateAdded -> when (sortOrder) {
            SortOrder.Ascending -> playlistPreviewsByDateAddedAsc()
            SortOrder.Descending -> playlistPreviewsByDateAddedDesc()
        }
    }

    @Query(
        """
        SELECT thumbnailUrl FROM Song
        JOIN SongPlaylistMap ON Song.id = SongPlaylistMap.songId
        WHERE playlistId = :id
        ORDER BY position
        LIMIT 4
        """
    )
    fun playlistThumbnailUrls(id: Long): Flow<List<String?>>

    @Transaction
    @Query(
        """
        SELECT * FROM Song
        JOIN SongArtistMap ON Song.id = SongArtistMap.songId
        WHERE SongArtistMap.artistId = :artistId AND
        totalPlayTimeMs > 0
        ORDER BY Song.ROWID DESC
        """
    )
    @RewriteQueriesToDropUnusedColumns
    fun artistSongs(artistId: String): Flow<List<Song>>

    @Query("SELECT * FROM Format WHERE songId = :songId")
    fun format(songId: String): Flow<Format?>

    @Transaction
    @Query(
        """
        SELECT Song.*, contentLength FROM Song
        JOIN Format ON id = songId
        WHERE contentLength IS NOT NULL
        ORDER BY Song.totalPlayTimeMs ASC
        """
    )
    fun songsWithContentLengthByPlayTimeAsc(): Flow<List<SongWithContentLength>>

    @Transaction
    @Query(
        """
        SELECT Song.*, contentLength FROM Song
        JOIN Format ON id = songId
        WHERE contentLength IS NOT NULL
        ORDER BY Song.totalPlayTimeMs DESC
        """
    )
    fun songsWithContentLengthByPlayTimeDesc(): Flow<List<SongWithContentLength>>

    @Transaction
    @Query(
        """
        SELECT Song.*, contentLength FROM Song
        JOIN Format ON id = songId
        WHERE contentLength IS NOT NULL
        ORDER BY Song.ROWID ASC
        """
    )
    fun songsWithContentLengthByRowIdAsc(): Flow<List<SongWithContentLength>>

    @Transaction
    @Query(
        """
        SELECT Song.*, contentLength FROM Song
        JOIN Format ON id = songId
        WHERE contentLength IS NOT NULL
        ORDER BY Song.ROWID DESC
        """
    )
    fun songsWithContentLengthByRowIdDesc(): Flow<List<SongWithContentLength>>

    @Transaction
    @Query(
        """
        SELECT Song.*, contentLength FROM Song
        JOIN Format ON id = songId
        WHERE contentLength IS NOT NULL
        ORDER BY Song.title COLLATE NOCASE ASC
        """
    )
    fun songsWithContentLengthByTitleAsc(): Flow<List<SongWithContentLength>>

    @Transaction
    @Query(
        """
        SELECT Song.*, contentLength FROM Song
        JOIN Format ON id = songId
        WHERE contentLength IS NOT NULL
        ORDER BY Song.title COLLATE NOCASE DESC
        """
    )
    fun songsWithContentLengthByTitleDesc(): Flow<List<SongWithContentLength>>

    fun songsWithContentLength(
        sortBy: SongSortBy = SongSortBy.DateAdded,
        sortOrder: SortOrder = SortOrder.Descending
    ) = when (sortBy) {
        SongSortBy.PlayTime -> when (sortOrder) {
            SortOrder.Ascending -> songsWithContentLengthByPlayTimeAsc()
            SortOrder.Descending -> songsWithContentLengthByPlayTimeDesc()
        }

        SongSortBy.Title -> when (sortOrder) {
            SortOrder.Ascending -> songsWithContentLengthByTitleAsc()
            SortOrder.Descending -> songsWithContentLengthByTitleDesc()
        }

        SongSortBy.Position,
        SongSortBy.DateAdded -> when (sortOrder) {
            SortOrder.Ascending -> songsWithContentLengthByRowIdAsc()
            SortOrder.Descending -> songsWithContentLengthByRowIdDesc()
        }
    }

    @Query("SELECT id FROM Song WHERE blacklisted")
    suspend fun blacklistedIds(): List<String>

    @Query("SELECT blacklisted FROM Song WHERE id = :songId")
    fun blacklisted(songId: String): Flow<Boolean>

    @Query("SELECT COUNT (*) FROM Song where blacklisted")
    fun blacklistLength(): Flow<Int>

    @Transaction
    @Query("UPDATE Song SET blacklisted = NOT blacklisted WHERE blacklisted")
    fun resetBlacklist()

    @Transaction
    @Query("UPDATE Song SET blacklisted = NOT blacklisted WHERE id = :songId")
    fun toggleBlacklist(songId: String)

    suspend fun filterBlacklistedSongs(songs: List<MediaItem>): List<MediaItem> {
        val blacklistedIds = blacklistedIds()
        return songs.filter { it.mediaId !in blacklistedIds }
    }

    @Transaction
    @Query(
        """
        UPDATE SongPlaylistMap SET position =
          CASE
            WHEN position < :fromPosition THEN position + 1
            WHEN position > :fromPosition THEN position - 1
            ELSE :toPosition
          END
        WHERE playlistId = :playlistId AND position BETWEEN MIN(:fromPosition,:toPosition) and MAX(:fromPosition,:toPosition)
        """
    )
    fun move(playlistId: Long, fromPosition: Int, toPosition: Int)

    @Transaction
    fun removeFromPlaylist(playlistId: Long, positionInPlaylist: Int) {
        deleteFromPlaylistAtPosition(playlistId, positionInPlaylist)
        updatePositionsAfterDelete(playlistId, positionInPlaylist)
    }

    @Query("SELECT position FROM SongPlaylistMap WHERE songId = :songId AND playlistId = :playlistId LIMIT 1")
    fun getPositionInPlaylist(songId: String, playlistId: Long): Int?

    @Query("DELETE FROM SongPlaylistMap WHERE playlistId = :playlistId AND position = :position")
    fun deleteFromPlaylistAtPosition(playlistId: Long, position: Int)

    @Query("UPDATE SongPlaylistMap SET position = position - 1 WHERE playlistId = :playlistId AND position > :position")
    fun updatePositionsAfterDelete(playlistId: Long, position: Int)

    @Query("DELETE FROM SongPlaylistMap WHERE playlistId = :id")
    fun clearPlaylist(id: Long)

    @Query("DELETE FROM SongAlbumMap WHERE albumId = :id")
    fun clearAlbum(id: String)

    @Query("SELECT loudnessDb FROM Format WHERE songId = :songId")
    fun loudnessDb(songId: String): Flow<Float?>

    @Query("SELECT Song.loudnessBoost FROM Song WHERE id = :songId")
    fun loudnessBoost(songId: String): Flow<Float?>

    @Query("UPDATE Song SET loudnessBoost = :loudnessBoost WHERE id = :songId")
    fun setLoudnessBoost(songId: String, loudnessBoost: Float?)

    @Query("SELECT * FROM Song WHERE title LIKE :query OR artistsText LIKE :query")
    fun search(query: String): Flow<List<Song>>

    @Query("SELECT albumId AS id, NULL AS name FROM SongAlbumMap WHERE songId = :songId")
    suspend fun songAlbumInfo(songId: String): Info?

    @Query("SELECT id, name FROM Artist LEFT JOIN SongArtistMap ON id = artistId WHERE songId = :songId")
    suspend fun songArtistInfo(songId: String): List<Info>

    @Transaction
    @Query(
        """
        SELECT Song.* FROM Event
        JOIN Song ON Song.id = songId
        WHERE Song.id NOT LIKE '$LOCAL_KEY_PREFIX%'
        GROUP BY songId
        ORDER BY SUM(playTime)
        DESC LIMIT :limit
        """
    )
    @RewriteQueriesToDropUnusedColumns
    fun trending(limit: Int = 3): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT Song.* FROM Event
        JOIN Song ON Song.id = songId
        WHERE (:now - Event.timestamp) <= :period AND
        Song.id NOT LIKE '$LOCAL_KEY_PREFIX%'
        GROUP BY songId
        ORDER BY SUM(playTime) DESC
        LIMIT :limit
        """
    )
    @RewriteQueriesToDropUnusedColumns
    fun trending(
        limit: Int = 3,
        now: Long = System.currentTimeMillis(),
        period: Long
    ): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM Event ORDER BY timestamp DESC")
    fun events(): Flow<List<EventWithSong>>

    @Query("SELECT COUNT (*) FROM Event")
    fun eventsCount(): Flow<Int>

    @Query("DELETE FROM Event")
    fun clearEvents()

    @Query("DELETE FROM Event WHERE songId = :songId")
    fun clearEventsFor(songId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Throws(SQLException::class)
    fun insert(event: Event)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(format: Format)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(searchQuery: SearchQuery)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(playlist: Playlist): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(songPlaylistMap: SongPlaylistMap): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(queuedMediaItems: List<QueuedMediaItem>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSongPlaylistMaps(songPlaylistMaps: List<SongPlaylistMap>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(album: Album, songAlbumMap: SongAlbumMap)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(artists: List<Artist>, songArtistMaps: List<SongArtistMap>)

    @Transaction
    fun insert(mediaItem: MediaItem, block: (Song) -> Song = { it }) {
        val existingLikedAt = getLikedAtSync(mediaItem.mediaId)

        val extras = mediaItem.mediaMetadata.extras?.songBundle
        val song = Song(
            id = mediaItem.mediaId,
            title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
            artistsText = mediaItem.mediaMetadata.artist?.toString(),
            durationText = extras?.durationText,
            thumbnailUrl = mediaItem.mediaMetadata.artworkUri?.toString(),
            explicit = extras?.explicit == true,
            likedAt = existingLikedAt
        ).let(block)

        upsert(song)

        extras?.albumId?.let { albumId ->
            insert(
                Album(id = albumId, title = mediaItem.mediaMetadata.albumTitle?.toString()),
                SongAlbumMap(songId = song.id, albumId = albumId, position = null)
            )
        }

        extras?.artistNames?.let { artistNames ->
            extras.artistIds?.let { artistIds ->
                if (artistNames.size == artistIds.size) insert(
                    artistNames.mapIndexed { index, artistName ->
                        Artist(
                            id = artistIds[index],
                            name = artistName
                        )
                    },
                    artistIds.map { artistId ->
                        SongArtistMap(
                            songId = song.id,
                            artistId = artistId
                        )
                    }
                )
            }
        }
    }

    @Update
    fun update(artist: Artist)

    @Update
    fun update(album: Album)

    @Update
    fun update(playlist: Playlist)

    @Upsert
    fun upsert(lyrics: Lyrics)

    @Upsert
    fun upsert(album: Album, songAlbumMaps: List<SongAlbumMap>)

    @Upsert
    fun upsert(artist: Artist)

    @Upsert
    fun upsert(song: Song)

    @Upsert
    fun upsert(songArtistMap: SongArtistMap)

    @Delete
    fun delete(song: Song)

    @Delete
    fun delete(searchQuery: SearchQuery)

    @Delete
    fun delete(playlist: Playlist)

    @Delete
    fun delete(songPlaylistMap: SongPlaylistMap)

    @RawQuery
    fun raw(supportSQLiteQuery: SupportSQLiteQuery): Int

    fun checkpoint() {
        raw(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))
    }
}

@androidx.room.Database(
    entities = [
        Song::class,
        SongPlaylistMap::class,
        Playlist::class,
        Artist::class,
        SongArtistMap::class,
        Album::class,
        SongAlbumMap::class,
        SearchQuery::class,
        QueuedMediaItem::class,
        Format::class,
        Event::class,
        Lyrics::class
    ],
    views = [SortedSongPlaylistMap::class],
    version = 1,
    exportSchema = true,
    autoMigrations = []
)
@TypeConverters(Converters::class)
abstract class DatabaseInitializer protected constructor() : RoomDatabase() {
    abstract val database: Database

    companion object {
        @Volatile
        lateinit var instance: DatabaseInitializer

        private fun buildDatabase() = Room
            .databaseBuilder(
                context = Dependencies.application.applicationContext,
                klass = DatabaseInitializer::class.java,
                name = "data.db"
            )
            .build()

        operator fun invoke() {
            if (!::instance.isInitialized) reload()
        }

        fun reload() = synchronized(this) {
            instance = buildDatabase()
        }
    }

}

@Suppress("unused")
@TypeConverters
object Converters {
    @TypeConverter
    @OptIn(UnstableApi::class)
    fun mediaItemFromByteArray(value: ByteArray?): MediaItem? = value?.let { byteArray ->
        runCatching {
            val parcel = Parcel.obtain()
            parcel.unmarshall(byteArray, 0, byteArray.size)
            parcel.setDataPosition(0)
            val bundle = parcel.readBundle(MediaItem::class.java.classLoader)
            parcel.recycle()

            bundle?.let(MediaItem::fromBundle)
        }.getOrNull()
    }

    @TypeConverter
    @OptIn(UnstableApi::class)
    fun mediaItemToByteArray(mediaItem: MediaItem?): ByteArray? = mediaItem?.toBundle()?.let {
        val parcel = Parcel.obtain()
        parcel.writeBundle(it)
        val bytes = parcel.marshall()
        parcel.recycle()

        bytes
    }

    @TypeConverter
    fun urlToString(url: Url) = url.toString()

    @TypeConverter
    fun stringToUrl(string: String) = Url(string)
}

@Suppress("UnusedReceiverParameter")
val Database.internal: RoomDatabase
    get() = DatabaseInitializer.instance

fun query(block: () -> Unit) = DatabaseInitializer.instance.queryExecutor.execute(block)

fun transaction(block: () -> Unit) = with(DatabaseInitializer.instance) {
    transactionExecutor.execute {
        runInTransaction(block)
    }
}

val RoomDatabase.path: String?
    get() = openHelper.writableDatabase.path
