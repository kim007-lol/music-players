// PlaybackSettingsScreen.kt

package com.example.musicplaying.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.musicplaying.R
import com.example.musicplaying.constants.PersistentQueueKey
import com.example.musicplaying.constants.StopPlaybackOnTaskRemovedKey
import com.example.musicplaying.ui.component.AppSwitch
import com.example.musicplaying.ui.component.SettingsGroup
import com.example.musicplaying.ui.component.SettingsGroupStyle
import com.example.musicplaying.ui.component.SettingsItem
import com.example.musicplaying.ui.component.SubSettingsScaffold
import com.example.musicplaying.ui.component.SubSettingsSearchBar
import com.example.musicplaying.utils.rememberPreference

@Composable
fun PlaybackSettingsScreen(navController: NavController) {
    var query by remember { mutableStateOf("") }

    val (stopPlaybackOnTaskRemoved, onStopPlaybackOnTaskRemovedChange) = rememberPreference(
        StopPlaybackOnTaskRemovedKey,
        defaultValue = false
    )
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(
        PersistentQueueKey,
        defaultValue = true
    )

    val keepPlaying = !stopPlaybackOnTaskRemoved

    SubSettingsScaffold(
        title = "Playback",
        onBack = { navController.navigateUp() },
        searchBar = { SubSettingsSearchBar(query = query, onQueryChange = { query = it }) },
    ) {
        val items = listOf(
            SettingsItem(
                title = { Text("Keep playing even when closed") },
                highlightKey = "Keep playing even when closed",
                descriptionText = "Keep playing after the app is cleared",
                detail = "Continue playback in background when app is cleared from recents",
                icon = painterResource(R.drawable.clear_all),
                iconShape = CircleShape,
                onClick = { onStopPlaybackOnTaskRemovedChange(!stopPlaybackOnTaskRemoved) },
                trailingContent = {
                    AppSwitch(
                        checked = keepPlaying,
                        onCheckedChange = { onStopPlaybackOnTaskRemovedChange(!it) },
                    )
                },
            ),
            SettingsItem(
                title = { Text("Save Player's last state") },
                highlightKey = "Save Player's last state",
                descriptionText = "Off clears played tracks on close",
                detail = "disabling this will removing previously played tracks when closing the app",
                icon = painterResource(R.drawable.cached),
                iconShape = CircleShape,
                onClick = { onPersistentQueueChange(!persistentQueue) },
                trailingContent = {
                    AppSwitch(
                        checked = persistentQueue,
                        onCheckedChange = onPersistentQueueChange,
                    )
                },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (items.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = items,
            )
        }

        Spacer(Modifier.height(140.dp))
    }
}
