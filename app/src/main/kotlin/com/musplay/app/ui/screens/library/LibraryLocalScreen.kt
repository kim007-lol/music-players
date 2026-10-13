// LibraryLocalScreen.kt
//
// Local: everything that plays from this device - songs downloaded for offline and audio files
// imported from storage. Built like the Library home page (same collapsing header, category cards
// and wide cards) with "Add local music" at the top, but every card here counts only what is
// local: the albums, artists and playlists a local song belongs to, the liked songs that are
// local, and the local songs by when they arrived. Nothing that only streams shows up here.

package com.musplay.app.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.musplay.app.LocalPlayerAwareWindowInsets
import com.musplay.app.LocalPlayerConnection
import com.musplay.app.constants.ProfilePicUriKey
import com.musplay.app.db.entities.Artist
import com.musplay.app.db.entities.Playlist
import com.musplay.app.db.entities.Song
import com.musplay.app.extensions.toMediaItem
import com.musplay.app.playback.queues.ListQueue
import com.musplay.app.ui.component.GlassState
import com.musplay.app.ui.component.LikedCover
import com.musplay.app.ui.component.navigateToTab
import com.musplay.app.ui.screens.search.SearchAvatar
import com.musplay.app.ui.screens.search.SearchHorizontalPadding
import com.musplay.app.ui.screens.search.rememberCollapseProgress
import com.musplay.app.utils.rememberPreference
import com.musplay.app.viewmodels.LibraryHomeViewModel
import com.musplay.app.viewmodels.LocalAlbum
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem

@Composable
fun LibraryLocalScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val playerConnection = LocalPlayerConnection.current

    val localSongs by viewModel.localSongs.collectAsState()
    val likedSongs by viewModel.localLikedSongs.collectAsState()
    val albums by viewModel.localAlbums.collectAsState()
    val artists by viewModel.localArtists.collectAsState()
    val playlists by viewModel.localPlaylists.collectAsState()

    val profilePicStr by rememberPreference(ProfilePicUriKey, defaultValue = "")
    val snackbarHostState = remember { SnackbarHostState() }

    var query by remember { mutableStateOf(TextFieldValue()) }
    val results = remember(query.text, localSongs, artists, albums, playlists) {
        val q = query.text.trim()
        if (q.isBlank()) emptyList() else searchLocal(q, localSongs, artists, albums, playlists)
    }

    val listState = rememberLazyListState()
    val glassState = remember { GlassState() }
    val collapse = rememberCollapseProgress(listState)
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    Box(modifier = modifier.fillMaxSize()) {
        LibraryScaffold(
            title = "Local",
            subtitle = null,
            searchPlaceholder = "Search your local music",
            query = query,
            onQueryChange = { query = it },
            listState = listState,
            glassState = glassState,
            collapseProvider = { collapse.value },
            pureBlack = pureBlack,
            bottomInset = bottomInset,
            trailing = {
                SearchAvatar(
                    imageUrl = profilePicStr.ifBlank { null },
                    onClick = { navController.navigateToTab("settings") },
                )
            },
        ) {
            if (query.text.isNotBlank()) {
                librarySearchResults(
                    results = results,
                    query = query.text,
                    onOpen = { item ->
                        // A song found here plays among the local songs, from the files on this
                        // device, rather than as a radio that needs a connection.
                        val index = if (item is SongItem) localSongs.indexOfFirst { it.id == item.id } else -1
                        if (index >= 0) {
                            playerConnection?.playQueue(
                                ListQueue(
                                    title = "Local",
                                    items = localSongs.map { it.toMediaItem() },
                                    startIndex = index,
                                ),
                            )
                        } else {
                            openLibraryItem(item, navController, playerConnection, localSongs)
                        }
                    },
                )
                return@LibraryScaffold
            }

            item(key = "rule_top") {
                LibraryRule()
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "add_local") {
                LibraryAddLocalMusicCard(
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.padding(horizontal = SearchHorizontalPadding),
                )
                Spacer(modifier = Modifier.height(16.dp))
                LibraryRule()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Nothing downloaded and nothing imported means every card below would be an empty
            // shell linking to an empty page. One centred message says the same thing honestly.
            if (localSongs.isEmpty()) {
                item(key = "empty") { LibraryEmptyState() }
                return@LibraryScaffold
            }

            item(key = "categories") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SearchHorizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LibraryCategoryCard(
                            title = "Songs",
                            count = localSongs.size,
                            covers = localSongs.map { it.song.thumbnailUrl },
                            onClick = { navController.navigate("auto_playlist/local") },
                            modifier = Modifier.weight(1f),
                        )
                        LibraryCategoryCard(
                            title = "Albums",
                            count = albums.size,
                            covers = albums.map { it.thumbnailUrl },
                            onClick = { navController.navigate("library/local/albums") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LibraryCategoryCard(
                            title = "Artist",
                            count = artists.size,
                            covers = artists.map { it.artist.thumbnailUrl },
                            onClick = { navController.navigate("library/local/artists") },
                            roundCovers = true,
                            modifier = Modifier.weight(1f),
                        )
                        LibraryCategoryCard(
                            title = "Playlist",
                            count = playlists.size,
                            covers = playlists.flatMap { it.thumbnails },
                            onClick = { navController.navigate("library/local/playlists") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            item(key = "recently_added") {
                LibraryWideCard(
                    title = "Recently added",
                    label = localSongs.size.toString(),
                    covers = localSongs.map { it.song.thumbnailUrl },
                    onClick = { navController.navigate("library/local/added") },
                    modifier = Modifier.padding(horizontal = SearchHorizontalPadding),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // only the liked songs that are local; every liked song is on the Library home
            if (likedSongs.isNotEmpty()) {
                item(key = "liked_songs") {
                    LibraryWideCard(
                        title = "Liked songs",
                        label = likedSongs.size.toString(),
                        covers = likedSongs.map { it.song.thumbnailUrl },
                        onClick = { navController.navigate("auto_playlist/local_liked") },
                        sharedElementKey = "playlist-local_liked",
                        art = { LikedCover(it) },
                        modifier = Modifier.padding(horizontal = SearchHorizontalPadding),
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset),
        )
    }
}

/**
 * The library search, over local things only. An album whose only trace is a file's tag has no
 * page to open, so it isn't a result of its own - its songs are.
 */
private fun searchLocal(
    query: String,
    songs: List<Song>,
    artists: List<Artist>,
    albums: List<LocalAlbum>,
    playlists: List<Playlist>,
): List<YTItem> {
    val q = query.lowercase()
    val albumHits = albums
        .filter { it.albumId != null && it.title.lowercase().contains(q) }
        .take(6)
        .map {
            AlbumItem(
                browseId = it.albumId!!,
                playlistId = "",
                title = it.title,
                artists = null,
                thumbnail = it.thumbnailUrl ?: "",
            )
        }
    // the same order as the Library's search: songs, artists, albums, playlists
    return searchLibrary(query, songs, artists, emptyList(), emptyList()) +
        albumHits +
        searchLibrary(query, emptyList(), emptyList(), emptyList(), playlists)
}
