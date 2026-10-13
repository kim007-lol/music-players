// AudioSettingsScreen.kt

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
import com.example.musicplaying.constants.AudioNormalizationKey
import com.example.musicplaying.constants.AudioQuality
import com.example.musicplaying.constants.AudioQualityKey
import com.example.musicplaying.constants.SkipSilenceKey
import com.example.musicplaying.ui.component.AppSwitch
import com.example.musicplaying.ui.component.SettingsGroup
import com.example.musicplaying.ui.component.SettingsGroupStyle
import com.example.musicplaying.ui.component.SettingsItem
import com.example.musicplaying.ui.component.SubSettingsScaffold
import com.example.musicplaying.ui.component.SubSettingsSearchBar
import com.example.musicplaying.utils.rememberEnumPreference
import com.example.musicplaying.utils.rememberPreference

@Composable
fun AudioSettingsScreen(navController: NavController) {
    var query by remember { mutableStateOf("") }

    val (audioQuality) = rememberEnumPreference(
        AudioQualityKey,
        defaultValue = AudioQuality.AUTO
    )
    val (skipSilence, onSkipSilenceChange) = rememberPreference(
        SkipSilenceKey,
        defaultValue = false
    )
    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        AudioNormalizationKey,
        defaultValue = true
    )

    fun qualityLabel(q: AudioQuality) = when (q) {
        AudioQuality.AUTO -> "Auto"
        AudioQuality.LOW -> "Low"
        AudioQuality.MEDIUM -> "Medium"
        AudioQuality.HIGH -> "High"
        AudioQuality.LOSSLESS -> "Lossless"
        AudioQuality.HI_RES_LOSSLESS -> "Hi-Res Lossless"
        AudioQuality.DOLBY_ATMOS -> "Dolby Atmos"
    }

    SubSettingsScaffold(
        title = "Audio",
        onBack = { navController.navigateUp() },
        searchBar = { SubSettingsSearchBar(query = query, onQueryChange = { query = it }) },
    ) {
        val pageItems = listOf(
            SettingsItem(
                title = { Text("Audio Lab") },
                highlightKey = "Audio Lab",
                descriptionText = "Equalizer, effects, crossfade and quality",
                icon = painterResource(R.drawable.equalizer),
                iconShape = CircleShape,
                onClick = { navController.navigate("audio_lab") },
            ),
            SettingsItem(
                title = { Text("Audio Quality") },
                highlightKey = "Audio quality",
                descriptionText = qualityLabel(audioQuality),
                icon = painterResource(R.drawable.graphic_eq),
                iconShape = CircleShape,
                onClick = { navController.navigate("audio_quality") },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (pageItems.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = pageItems,
            )
            Spacer(Modifier.height(14.dp))
        }

        val toggleItems = listOf(
            SettingsItem(
                title = { Text("Skip Silence") },
                highlightKey = "Skip silence",
                descriptionText = "Skip silent intervals in tracks",
                icon = painterResource(R.drawable.skip_next),
                iconShape = CircleShape,
                onClick = { onSkipSilenceChange(!skipSilence) },
                trailingContent = {
                    AppSwitch(
                        checked = skipSilence,
                        onCheckedChange = onSkipSilenceChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text("Audio Normalization") },
                highlightKey = "Audio normalization",
                descriptionText = "Balance loudness across all tracks",
                icon = painterResource(R.drawable.volume_up),
                iconShape = CircleShape,
                onClick = { onAudioNormalizationChange(!audioNormalization) },
                trailingContent = {
                    AppSwitch(
                        checked = audioNormalization,
                        onCheckedChange = onAudioNormalizationChange,
                    )
                },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (toggleItems.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = toggleItems,
            )
        }

        Spacer(Modifier.height(140.dp))
    }
}
