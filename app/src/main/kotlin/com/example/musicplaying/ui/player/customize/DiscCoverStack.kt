// DiscCoverStack.kt

package com.example.musicplaying.ui.player.customize

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.rememberTextMeasurer
import com.example.musicplaying.constants.DiscLabelContent
import com.example.musicplaying.constants.DiscNameKey
import com.example.musicplaying.constants.DiscRealisticModeKey
import com.example.musicplaying.constants.DiscRotatingAnimationKey
import com.example.musicplaying.constants.PlayerCoverStyle
import com.example.musicplaying.utils.rememberPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

private const val SpinDegreesPerSecond = 26f

@Composable
fun DiscCoverStack(
    style: PlayerCoverStyle,
    artworkUrl: String?,
    mediaId: String,
    isPlaying: Boolean,
    spinActive: Boolean,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    /**
     * the row of records the player's swipe and skips run along (see DiscCarousel). Without one -
     * the editor, the previews - there's just the playing song's record, still.
     */
    carousel: DiscCarousel? = null,
) {
    val (rotatingEnabled) = rememberPreference(DiscRotatingAnimationKey, defaultValue = true)
    val (realistic) = rememberPreference(DiscRealisticModeKey, defaultValue = true)
    val (customName) = rememberPreference(DiscNameKey, defaultValue = "")
    val labelContent by com.example.musicplaying.utils.rememberEnumPreference(
        com.example.musicplaying.constants.DiscLabelContentKey,
        DiscLabelContent.CUSTOM,
    )
    val connection = com.example.musicplaying.LocalPlayerConnection.current
    val track = connection?.uiState?.trackInfo?.collectAsState()?.value

    // what a record's label says, as chosen in the cover editor
    fun nameFor(slot: DiscSlot): String = when (labelContent) {
        DiscLabelContent.CUSTOM -> customName
        DiscLabelContent.MUSIC_NAME -> slot.title
        DiscLabelContent.ARTIST_NAME -> slot.artist
        DiscLabelContent.ALBUM_NAME -> slot.album
        DiscLabelContent.MUSIC_AND_ARTIST -> listOf(slot.title, slot.artist).filter { it.isNotBlank() }.joinToString(" · ")
        DiscLabelContent.NONE -> ""
    }

    val playing = DiscSlot(
        uid = 0,
        mediaId = mediaId,
        artworkUrl = artworkUrl,
        title = track?.title.orEmpty(),
        artist = track?.artist.orEmpty(),
        album = track?.album.orEmpty(),
        step = 0,
        current = true,
    )

    // The row: the songs either side of the playing one, from the queue in play order. One
    // source for all three, so the record coming in is exactly the one that then plays.
    val queue = if (carousel != null && !editMode) connection?.queueSnapshot?.collectAsState()?.value else null
    val candidates: List<DiscSlot> = run {
        val items = queue?.items
        val index = queue?.currentIndex ?: -1
        if (carousel == null || items == null || index !in items.indices) return@run listOf(playing)
        carousel.style = style
        fun slotOf(i: Int, step: Int, current: Boolean): DiscSlot? = items.getOrNull(i)?.let { item ->
            DiscSlot(
                uid = item.uid,
                mediaId = item.mediaId,
                // the playing song's own picture when the queue has none for it
                artworkUrl = item.artworkUri?.toString() ?: artworkUrl.takeIf { current && item.mediaId == mediaId },
                title = item.title,
                artist = item.artist,
                album = if (item.mediaId == track?.mediaId) track?.album.orEmpty() else "",
                step = step,
                current = current,
            )
        }
        val now = slotOf(index, 0, true)!!
        carousel.onQueue(now, index, items.getOrNull(index - 1)?.uid, items.getOrNull(index + 1)?.uid)
        val step = carousel.step
        buildList {
            slotOf(index - 1, step - 1, false)?.let(::add)
            add(now.copy(step = step))
            slotOf(index + 1, step + 1, false)?.let(::add)
            carousel.outgoing?.let { gone -> if (none { it.uid == gone.uid }) add(gone) }
        }
    }

    // A record new to the row (the next one along, after a skip) joins it once the row has come to
    // rest, not while it slides: building it and drawing its surface into its layer is real work,
    // and done at rest - off screen - nothing on screen waits for it.
    val shownBefore = remember { HashSet<Int>() }
    var restingStep by remember { mutableIntStateOf(carousel?.step ?: 0) }
    val slots = candidates.filter { it.current || it.uid in shownBefore || restingStep == (carousel?.step ?: 0) }
    shownBefore.clear()
    slots.forEach { shownBefore += it.uid }

    if (carousel != null) {
        // the row settles on the playing song: a skip from anywhere slides it there, and a swipe's
        // own slide toward it carries on with the speed it has
        LaunchedEffect(carousel, carousel.step) {
            carousel.position.animateTo(carousel.step.toFloat(), DiscSettleSpring)
            carousel.outgoing = null
            restingStep = carousel.step
        }
    }

    val angle = remember { mutableFloatStateOf(0f) }
    val lastFrameNanos = remember { mutableLongStateOf(0L) }
    val spinning = rotatingEnabled && isPlaying && spinActive
    LaunchedEffect(spinning) {
        if (!spinning) {
            lastFrameNanos.longValue = 0L
            return@LaunchedEffect
        }
        while (isActive) {
            withInfiniteAnimationFrameNanos { frameTimeNanos ->
                val previous = lastFrameNanos.longValue
                lastFrameNanos.longValue = frameTimeNanos
                if (previous != 0L) {
                    val deltaSeconds = (frameTimeNanos - previous) / 1_000_000_000f
                    angle.floatValue = (angle.floatValue + SpinDegreesPerSecond * deltaSeconds).mod(360f)
                }
            }
        }
    }

    val measurer = rememberTextMeasurer(cacheSize = 96)

    Box(modifier = modifier) {
        // the Turntable's arm stands on a slab the record sits over
        if (style == PlayerCoverStyle.TURNTABLE) TurntableBase(Modifier.fillMaxSize())

        for (slot in slots) {
            key(slot.uid) {
                // a record keeps the picture it came in with, so it never reloads as it takes the middle
                val art = remember { arrayOfNulls<String>(1) }
                if (art[0] == null) art[0] = slot.artworkUrl
                val shownArt = art[0] ?: slot.artworkUrl
                // the album card wants the album's cover, which isn't always the song's own picture
                val albumArtwork = if (style.hasAlbumCard) rememberAlbumArtwork(slot.mediaId, shownArt) else shownArt
                DiscCover(
                    style = style,
                    artworkUrl = shownArt,
                    discName = nameFor(slot),
                    albumArtworkUrl = albumArtwork,
                    realistic = realistic,
                    editMode = editMode && slot.current,
                    rotationProvider = { angle.floatValue },
                    textMeasurer = measurer,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (carousel == null) Modifier else Modifier.graphicsLayer {
                                translationX = (slot.step - carousel.position.value) * discSpacingPx(style, size)
                            }
                        ),
                )
            }
        }

        if (style == PlayerCoverStyle.TURNTABLE) {
            TurntableArm(
                active = spinActive,
                interactive = !editMode,
                awayProvider = { carousel?.away ?: 0f },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (style.hasTonearm) {
            DiscTonearm(
                liftProvider = { ((carousel?.away ?: 0f) * 3f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val PlayerCoverStyle.hasAlbumCard: Boolean get() = discGeometryFor(this).albumCard != null

/** album covers already looked up, by song */
private val albumArtworkCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * The cover of the album [mediaId] is on: from the library first (the song's album, then the album
 * itself), then YouTube's album page. Falls back to [fallback], the song's own artwork.
 */
@Composable
internal fun rememberAlbumArtwork(mediaId: String, fallback: String?): String? {
    val database = com.example.musicplaying.LocalDatabase.current
    val found by androidx.compose.runtime.produceState(albumArtworkCache[mediaId], mediaId) {
        if (mediaId.isBlank() || albumArtworkCache.containsKey(mediaId)) {
            value = albumArtworkCache[mediaId]
            return@produceState
        }
        val url = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val song = database.song(mediaId).first()
                song?.album?.thumbnailUrl
                    ?: song?.song?.albumId?.let { albumId ->
                        database.album(albumId).first()?.album?.thumbnailUrl
                            ?: com.music.innertube.YouTube.album(albumId, withSongs = false).getOrNull()?.album?.thumbnail
                    }
            }.getOrNull()
        }
        if (url != null) albumArtworkCache[mediaId] = url
        value = url
    }
    return found ?: fallback
}
