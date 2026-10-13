// LibraryRecentlyAddedScreen.kt
//
// Songs only - every library song, or with localOnly the Local page's songs. The underlying query
// orders by when each song entered the library (a local one: when it was downloaded or imported),
// which is the one timeline shared across the library; albums/artists/playlists carry no
// comparable timestamp, so a combined "recently added" feed would need new queries rather than a
// merge of what exists.

package com.musplay.app.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.musplay.app.LocalPlayerConnection
import com.musplay.app.extensions.toMediaItem
import com.musplay.app.playback.queues.ListQueue
import com.musplay.app.viewmodels.LibraryHomeViewModel

@Composable
fun LibraryRecentlyAddedScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    localOnly: Boolean = false,
) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val songs by (if (localOnly) viewModel.localSongs else viewModel.songs).collectAsState()
    val queueTitle = if (localOnly) "Local" else "Recently added"
    val playerConnection = LocalPlayerConnection.current ?: return

    val entries = remember(songs) {
        songs.map {
            LibraryGridEntry(
                id = it.id,
                title = it.title,
                subtitle = it.artists.joinToString { a -> a.name }.ifBlank { null },
                thumbnailUrl = it.song.thumbnailUrl,
            )
        }
    }

    fun play(startIndex: Int, shuffle: Boolean) {
        if (songs.isEmpty()) return
        val ordered = if (shuffle) songs.shuffled() else songs
        playerConnection.playQueue(
            ListQueue(
                title = queueTitle,
                items = ordered.map { it.toMediaItem() },
                startIndex = if (shuffle) 0 else startIndex,
            ),
        )
    }

    LibraryGridScreen(
        title = "Added",
        subtitle = "Recently",
        searchPlaceholder = "Find song listed here, by the lyrics, or the artist",
        entries = entries,
        onOpen = { entry -> play(songs.indexOfFirst { it.id == entry.id }.coerceAtLeast(0), shuffle = false) },
        onPlay = { play(0, shuffle = false) },
        onShuffle = { play(0, shuffle = true) },
        modifier = modifier,
        pureBlack = pureBlack,
    )
}
