// LibraryArtistsScreen.kt

package com.example.musicplaying.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.musicplaying.ui.component.LocalMenuState
import com.example.musicplaying.ui.menu.ArtistMenu
import com.example.musicplaying.viewmodels.LibraryHomeViewModel

/**
 * The library's artists, or with [localOnly] the Local page's: only the artists of local songs,
 * each with how many of its songs are local.
 */
@Composable
fun LibraryArtistsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    localOnly: Boolean = false,
) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val artists by (if (localOnly) viewModel.localArtists else viewModel.artists).collectAsState()
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()

    LibraryEntityListScreen(
        title = "Artist",
        subtitle = if (localOnly) "Artists with music on this device." else "All artist on your library listed here.",
        searchPlaceholder = "Find an artist by name",
        items = artists,
        idOf = { it.id },
        nameOf = { it.artist.name },
        subtitleOf = {
            val count = if (it.songCount == 1) "1 song" else "${it.songCount} songs"
            if (localOnly) "$count on this device" else count
        },
        thumbnailOf = { it.artist.thumbnailUrl },
        roundThumbnails = true,
        onClick = { artist -> navController.navigate("artist/${artist.id}") },
        onLongClick = { artist ->
            menuState.show {
                ArtistMenu(
                    originalArtist = artist,
                    coroutineScope = coroutineScope,
                    onDismiss = menuState::dismiss,
                )
            }
        },
        modifier = modifier,
        pureBlack = pureBlack,
    )
}
