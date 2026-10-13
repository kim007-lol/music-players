// LibraryPlaylistsScreen.kt

package com.musplay.app.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.musplay.app.ui.component.LocalMenuState
import com.musplay.app.ui.menu.PlaylistMenu
import com.musplay.app.viewmodels.LibraryHomeViewModel

/**
 * The library's playlists, or with [localOnly] the Local page's: only the playlists that have a
 * local song in them.
 */
@Composable
fun LibraryPlaylistsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    localOnly: Boolean = false,
) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val playlists by (if (localOnly) viewModel.localPlaylists else viewModel.playlists).collectAsState()
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()

    LibraryEntityListScreen(
        title = "Playlist",
        subtitle = if (localOnly) "Playlists with music on this device." else "All playlist on your library listed here.",
        searchPlaceholder = "Find a playlist by its name",
        items = playlists,
        idOf = { it.id },
        nameOf = { it.playlist.name },
        subtitleOf = { "${it.songCount} songs" },
        thumbnailOf = { it.thumbnails.firstOrNull() },
        // the same key Home's cards use, so the playlist opens growing out of its cover
        sharedElementKeyOf = { "playlist-${it.id}" },
        onClick = { playlist -> navController.navigate("local_playlist/${playlist.id}") },
        onLongClick = { playlist ->
            menuState.show {
                PlaylistMenu(
                    playlist = playlist,
                    coroutineScope = coroutineScope,
                    onDismiss = menuState::dismiss,
                )
            }
        },
        modifier = modifier,
        pureBlack = pureBlack,
    )
}
