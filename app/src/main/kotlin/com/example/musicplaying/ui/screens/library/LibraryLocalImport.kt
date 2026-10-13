// LibraryLocalImport.kt
//
// "Add local music": the file picker and the import behind it. Used by the card at the top of the
// Local page, and by the Local card on the Library home while there is no local music yet. The
// import itself ([importLocalAudio]) is shared with audio files shared into MusicPlaying from other
// apps ([importSharedAudio]): permission grant, tag extraction, artwork extraction, artist dedup,
// format row.

package com.example.musicplaying.ui.screens.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.musicplaying.LocalDatabase
import com.example.musicplaying.db.entities.ArtistEntity
import com.example.musicplaying.db.entities.FormatEntity
import com.example.musicplaying.db.entities.SongArtistMap
import com.example.musicplaying.db.entities.SongEntity
import com.example.musicplaying.ui.screens.extractAudioMetadata
import com.example.musicplaying.ui.screens.search.SearchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.UUID

/** The picker and its import, as one handle: [launch] opens the picker, the rest is its progress. */
@Stable
class LocalMusicImporter internal constructor() {
    var isImporting by mutableStateOf(false)
        internal set

    /** 0..1 while [isImporting]. */
    var progress by mutableFloatStateOf(0f)
        internal set

    internal var openPicker: () -> Unit = {}

    fun launch() {
        if (!isImporting) openPicker()
    }
}

/** Audio types the picker offers: every audio type, plus the common ones named outright. */
private val LocalAudioMimeTypes = arrayOf(
    "audio/*",
    "audio/mpeg",
    "audio/mp4",
    "audio/flac",
    "audio/ogg",
    "audio/wav",
    "audio/x-wav",
    "audio/aac",
)

/**
 * The importer for one screen. The import runs in this screen's scope, and how it went is shown in
 * [snackbarHostState] once it finishes.
 */
@Composable
fun rememberLocalMusicImporter(snackbarHostState: SnackbarHostState): LocalMusicImporter {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val importer = remember { LocalMusicImporter() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            importer.isImporting = true
            importer.progress = 0f
            coroutineScope.launch {
                var successCount = 0
                var failCount = 0
                val total = uris.size
                for ((index, uri) in uris.withIndex()) {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                            importLocalAudio(context, database, uri)
                        }
                        successCount++
                    } catch (e: Exception) {
                        failCount++
                    }
                    importer.progress = (index + 1).toFloat() / total
                }
                importer.isImporting = false
                importer.progress = 0f

                val message = if (failCount == 0) {
                    "Imported $successCount song${if (successCount != 1) "s" else ""}"
                } else {
                    "Imported $successCount, failed $failCount"
                }
                snackbarHostState.showSnackbar(message)
            }
        },
    )
    SideEffect { importer.openPicker = { launcher.launch(LocalAudioMimeTypes) } }

    return importer
}

/** "Importing… 40%" while [LocalMusicImporter.isImporting]. */
internal fun LocalMusicImporter.progressLabel(): String = "Importing… ${(progress * 100).toInt()}%"

@Composable
fun LibraryAddLocalMusicCard(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val importer = rememberLocalMusicImporter(snackbarHostState)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SearchColors.Tile)
            .clickable(enabled = !importer.isImporting, onClick = importer::launch)
            .padding(18.dp),
    ) {
        Text(
            text = if (importer.isImporting) importer.progressLabel() else "Add local music",
            color = SearchColors.Primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Adds one audio file to the library as a local song: tags, artwork, artist and format row.
 * [uri] has to stay readable, so a shared file is copied into the app first (see [importSharedAudio]).
 */
suspend fun importLocalAudio(
    context: android.content.Context,
    database: com.example.musicplaying.db.MusicDatabase,
    uri: Uri,
) {
    val metadata = withContext(Dispatchers.IO) {
        extractAudioMetadata(context, uri)
    }

    val songId = "LOCAL_${UUID.randomUUID()}"
    val thumbnailUrl = withContext(Dispatchers.IO) {
        metadata.embeddedArtwork?.let { artworkData ->
            try {
                val artworkFile = java.io.File(
                    context.cacheDir,
                    "artwork_${songId.hashCode()}.jpg",
                )
                artworkFile.writeBytes(artworkData)
                Uri.fromFile(artworkFile).toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    withContext(Dispatchers.IO) {
        database.transaction {
            insert(
                SongEntity(
                    id = songId,
                    title = metadata.title,
                    duration = metadata.durationSeconds,
                    thumbnailUrl = thumbnailUrl,
                    albumName = metadata.album,
                    isLocal = true,
                    inLibrary = LocalDateTime.now(),
                    localUri = uri.toString(),
                ),
            )

            val artistName = metadata.artist
            val artistId = artistByName(artistName)?.id
                ?: ArtistEntity.generateArtistId()
            insert(
                ArtistEntity(
                    id = artistId,
                    name = artistName,
                    isLocal = true,
                ),
            )
            insert(
                SongArtistMap(
                    songId = songId,
                    artistId = artistId,
                    position = 0,
                ),
            )

            val mimeType = metadata.mimeType ?: "audio/mpeg"
            val codecs = when {
                mimeType.contains("flac") -> "flac"
                mimeType.contains("opus") || mimeType.contains("ogg") -> "opus"
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "mp4a.40.2"
                mimeType.contains("wav") -> "pcm"
                else -> "mp3"
            }
            upsert(
                FormatEntity(
                    id = songId,
                    itag = -1,
                    mimeType = mimeType,
                    codecs = codecs,
                    bitrate = metadata.bitrate ?: 0,
                    sampleRate = metadata.sampleRate,
                    contentLength = 0L,
                    loudnessDb = null,
                    perceptualLoudnessDb = null,
                    playbackUrl = null,
                ),
            )
        }
    }
}

/** Copies a file another app shared into the app's own storage, then imports it. */
suspend fun importSharedAudio(
    context: android.content.Context,
    database: com.example.musicplaying.db.MusicDatabase,
    shared: Uri,
) {
    val name = withContext(Dispatchers.IO) {
        context.contentResolver.query(shared, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "audio"
    val copy = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.filesDir, "shared_audio/${UUID.randomUUID()}").apply { mkdirs() }
        java.io.File(dir, name).also { file ->
            context.contentResolver.openInputStream(shared)!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }
    try {
        importLocalAudio(context, database, Uri.fromFile(copy))
    } catch (e: Exception) {
        copy.parentFile?.deleteRecursively()
        throw e
    }
}
