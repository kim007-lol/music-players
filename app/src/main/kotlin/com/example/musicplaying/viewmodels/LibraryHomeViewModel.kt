// LibraryHomeViewModel.kt

package com.example.musicplaying.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplaying.constants.AlbumSortType
import com.example.musicplaying.constants.ArtistSortType
import com.example.musicplaying.constants.PlaylistSortType
import com.example.musicplaying.constants.SongSortType
import com.example.musicplaying.db.MusicDatabase
import com.example.musicplaying.db.entities.Artist
import com.example.musicplaying.db.entities.Playlist
import com.example.musicplaying.db.entities.Song
import com.example.musicplaying.db.entities.SpeedDialItem
import com.example.musicplaying.importer.ImportProgress
import com.example.musicplaying.importer.ImportSource
import com.example.musicplaying.importer.MusicImportService
import com.example.musicplaying.utils.ArtistImageResolver
import com.music.innertube.YouTube
import com.music.innertube.models.YTItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.text.Collator
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject

/**
 * Backs the Library home screen: the four category counts/covers, the pinned grid, and the
 * Recently added / Downloaded preview cards.
 *
 * Deliberately its own view model rather than reusing [HomeViewModel]'s `speedDialItems` — that
 * flow pads pinned items out to 27 with "keep listening" filler for the Home feed's speed-dial
 * carousel, which is exactly wrong here: the Library screen's Pinned section is only supposed to
 * show what was actually pinned, and hides entirely when that's empty.
 */
@HiltViewModel
class LibraryHomeViewModel
@Inject
constructor(
    private val database: MusicDatabase,
    private val importService: MusicImportService,
) : ViewModel() {

    val pinnedItems = database.speedDialDao.getAll()
        .map { pinned -> pinned.map { it.toYTItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val songs = database.songs(SongSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists = database.artists(ArtistSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums = database.albums(AlbumSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists = database.playlists(PlaylistSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val likedSongs = database.likedSongs(SongSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The import under way, if any - the banner at the top of the Library turns into its progress bar. */
    val importProgress: StateFlow<ImportProgress> = importService.progress

    /**
     * One card per service that has brought music in, each with its song count and newest covers.
     * In the order the services are listed in [ImportSource]. A source that only
     * stands for "some file" ([ImportSource.OTHER]) has no card: its songs are in the library and
     * that is all there is to say about them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val importedSources: StateFlow<List<ImportedSourceCard>> = database.importedSourceCounts()
        .flatMapLatest { counts ->
            val listed = counts
                .mapNotNull { row ->
                    ImportSource.fromKey(row.source)?.takeIf { it.isListed }?.let { it to row.songCount }
                }
                .sortedBy { (source, _) -> source.ordinal }
            if (listed.isEmpty()) {
                flowOf(emptyList<ImportedSourceCard>())
            } else {
                combine(
                    listed.map { (source, count) ->
                        database.importedCovers(source.key).map { covers -> ImportedSourceCard(source, count, covers) }
                    }
                ) { cards -> cards.toList() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Everything that plays from this device: songs downloaded for offline and audio files
     * imported from storage, newest first. This is what "Local" means everywhere in the Library -
     * the Local card on the home page and every card on the Local page count from this list and
     * nothing else, so a song that is only in the library (or only liked) never shows up there.
     */
    val localSongs = database.onDeviceSongs(SongSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The liked songs that are local - not every liked song, which is what Liked songs is for. */
    val localLikedSongs = localSongs
        .map { list -> list.filter { it.song.liked } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The albums local songs belong to, built from the songs themselves rather than the album
     * table: a downloaded song that was never added to the library has no album in the Library's
     * list, and an imported file only has its album as a tag. See [LocalAlbum].
     */
    val localAlbums = localSongs
        .map { groupLocalAlbums(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The artists of local songs, each with how many of its songs are local, the artist with the
     * newest local song first. Same rule as the Library's own artist list: only artists that have a
     * page to open (a YouTube channel, or one made for an imported file).
     */
    val localArtists = localSongs
        .map { list ->
            list.flatMap { it.artists }
                .filter { it.isYouTubeArtist || it.isLocal }
                .groupBy { it.id }
                .map { (_, entries) -> Artist(artist = entries.first(), songCount = entries.size) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The library's playlists that have at least one local song in them. Re-checked whenever the
     * local songs change, and whenever a playlist does - [playlists] re-emits when a song is added
     * to or taken out of one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val localPlaylists = combine(
        playlists,
        localSongs.map { list -> list.map { it.id } }.distinctUntilChanged(),
    ) { lists, ids -> lists to ids }
        .mapLatest { (lists, ids) ->
            if (lists.isEmpty() || ids.isEmpty()) return@mapLatest emptyList<Playlist>()
            // under the 999 bound parameters older SQLite builds allow in one statement
            val containing = ids.chunked(900)
                .flatMap { database.playlistIdsContainingSongs(it) }
                .toSet()
            lists.filter { it.id in containing }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Imports made before library songs had their own place left them in a playlist, or out of
        // the Library entirely; put them where they belong (once - it finds nothing to do after).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { importService.repairEarlierImports() }
                .onFailure { timber.log.Timber.w(it, "Repairing earlier imports failed") }
        }

        // Artist rows have no picture until something goes and fetches one: the artist table only
        // stores a thumbnail once it has been resolved, and nothing resolves it as a side effect of
        // reading the table. LibraryArtistsViewModel already did this, but the rebuilt Library
        // screens read from here instead, so without this block every artist in the library
        // rendered as a placeholder icon forever.
        //
        // Refreshes anything missing a picture, or whose picture is over ten days old — artists
        // change their images and a permanently cached one goes stale.
        //
        // Local artists are included: a downloaded song that never went into the library has
        // artists the Library's list doesn't know about, and the Local page shows them too.
        viewModelScope.launch(Dispatchers.IO) {
            combine(artists, localArtists) { library, local -> (library + local).distinctBy { it.id } }.collect { list ->
                list.map { it.artist }
                    .filter {
                        it.thumbnailUrl == null ||
                            Duration.between(it.lastUpdateTime, LocalDateTime.now()) > Duration.ofDays(10)
                    }
                    .forEach { artist ->
                        val preferred = ArtistImageResolver.resolveThumbnail(artist)
                        YouTube.artist(artist.id)
                            .onSuccess { page ->
                                database.query { update(artist, page, preferred) }
                            }
                            .onFailure {
                                // The lookup failing doesn't mean we have nothing — the resolver
                                // may still have found an image locally, and dropping it here
                                // would leave the row blank for another ten days.
                                if (preferred != null) {
                                    database.query {
                                        update(
                                            artist.copy(
                                                thumbnailUrl = preferred,
                                                lastUpdateTime = LocalDateTime.now(),
                                            )
                                        )
                                    }
                                }
                            }
                    }
            }
        }
    }

    fun unpin(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            database.speedDialDao.delete(id)
        }
    }

    fun pin(item: YTItem) {
        viewModelScope.launch(Dispatchers.IO) {
            database.pinToSpeedDial(SpeedDialItem.fromYTItem(item))
        }
    }
}

/** What a service's card in the Library shows. */
@androidx.compose.runtime.Immutable
data class ImportedSourceCard(
    val source: ImportSource,
    val songCount: Int,
    val covers: List<String>,
)

/**
 * An album on the Local page. [albumId] is null for an imported file whose album is only a tag -
 * there is no album page to open for it, so opening it plays its [songs] instead.
 */
@androidx.compose.runtime.Immutable
data class LocalAlbum(
    val key: String,
    val albumId: String?,
    val title: String,
    val artists: String?,
    val thumbnailUrl: String?,
    val songs: List<Song>,
)

private fun groupLocalAlbums(songs: List<Song>): List<LocalAlbum> =
    songs
        .groupBy { song ->
            song.album?.id
                ?: song.song.albumId
                ?: song.song.albumName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "tag:$it" }
        }
        .mapNotNull { (key, group) ->
            if (key == null) return@mapNotNull null
            val first = group.first()
            LocalAlbum(
                key = key,
                albumId = first.album?.id ?: first.song.albumId,
                title = first.album?.title ?: first.song.albumName?.trim() ?: return@mapNotNull null,
                artists = group.flatMap { it.artists }.map { it.name }.distinct().joinToString().ifBlank { null },
                thumbnailUrl = first.album?.thumbnailUrl ?: group.firstNotNullOfOrNull { it.song.thumbnailUrl },
                songs = group,
            )
        }

/**
 * Downloads and imported files as one list, sorted as [sortType] says. They come from two queries
 * because they are two columns ([isDownloaded][com.example.musicplaying.db.entities.SongEntity.isDownloaded]
 * and [isLocal][com.example.musicplaying.db.entities.SongEntity.isLocal]); a download is dated by when
 * it finished downloading, an imported file by when it was imported.
 */
internal fun MusicDatabase.onDeviceSongs(sortType: SongSortType, descending: Boolean): Flow<List<Song>> =
    combine(
        downloadedSongs(SongSortType.CREATE_DATE, false),
        localSongs(SongSortType.CREATE_DATE, false),
    ) { downloaded, imported ->
        val merged = (downloaded + imported).distinctBy { it.id }
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val sorted = when (sortType) {
            SongSortType.CREATE_DATE -> merged.sortedBy { it.song.dateDownload ?: it.song.inLibrary }
            SongSortType.NAME -> merged.sortedWith(compareBy(collator) { it.song.title })
            SongSortType.ARTIST -> merged.sortedWith(compareBy(collator) { song -> song.artists.joinToString("") { it.name } })
            SongSortType.PLAY_TIME -> merged.sortedBy { it.song.totalPlayTime }
        }
        if (descending) sorted.asReversed() else sorted
    }.flowOn(Dispatchers.Default)
