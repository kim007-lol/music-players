// LibraryImportedScreen.kt
//
// "Imported from Spotify": every song one service brought in, in the same A-Z list as Songs, with the
// service's own icon where the Library home has the avatar.

package com.musplay.app.ui.screens.library

import com.musplay.app.ui.screens.search.AvatarSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.musplay.app.LocalPlayerConnection
import com.musplay.app.extensions.toMediaItem
import com.musplay.app.playback.queues.ListQueue
import com.musplay.app.ui.component.LocalMenuState
import com.musplay.app.ui.component.SwipeActionsBox
import com.musplay.app.ui.component.librarySwipeAction
import com.musplay.app.ui.component.queueSwipeAction
import com.musplay.app.ui.menu.SongMenu
import com.musplay.app.viewmodels.LibraryImportedViewModel

@Composable
fun LibraryImportedScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
) {
    val viewModel: LibraryImportedViewModel = hiltViewModel()
    val songs by viewModel.songs.collectAsState()
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val source = viewModel.source

    val label = source?.label ?: "an unknown source"
    val sourceIcon: (@Composable () -> Unit)? = if (source != null) {
        { ImportSourceIcon(source, size = AvatarSize) }
    } else {
        null
    }

    LibraryEntityListScreen(
        title = source?.label ?: "Imported",
        subtitle = when (songs.size) {
            1 -> "1 song imported from $label."
            else -> "${songs.size} songs imported from $label."
        },
        searchPlaceholder = "Find a song imported from $label",
        items = songs,
        idOf = { it.id },
        nameOf = { it.song.title },
        subtitleOf = { it.artists.joinToString { a -> a.name }.ifBlank { null } },
        thumbnailOf = { it.song.thumbnailUrl },
        playingIdOf = { it.id },
        onClick = { song ->
            // The order on screen, so the queue starts where the user tapped and runs down the list.
            val ordered = sortForLibraryIndex(songs) { it.song.title }
            playerConnection.playQueue(
                ListQueue(
                    title = "Imported from $label",
                    items = ordered.map { it.toMediaItem() },
                    startIndex = ordered.indexOf(song).coerceAtLeast(0),
                ),
            )
        },
        onLongClick = { song ->
            menuState.show {
                SongMenu(
                    originalSong = song,
                    navController = navController,
                    onDismiss = menuState::dismiss,
                )
            }
        },
        modifier = modifier,
        pureBlack = pureBlack,
        rowWrapper = { song, row ->
            SwipeActionsBox(
                slab = false,
                start = { librarySwipeAction(song.song) },
                end = { queueSwipeAction { song.toMediaItem() } },
            ) {
                row()
            }
        },
        trailing = sourceIcon,
    )
}
