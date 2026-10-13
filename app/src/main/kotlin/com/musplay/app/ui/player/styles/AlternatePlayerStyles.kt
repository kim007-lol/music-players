package com.musplay.app.ui.player.styles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.musplay.app.LocalPlayerConnection
import com.musplay.app.constants.PlayerStyle
import com.musplay.app.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.musplay.app.lyrics.LyricsEntry
import com.musplay.app.lyrics.LyricsUtils
import com.musplay.app.utils.makeTimeString

/** the player's alternate styles, drawn as they look on the player or, [preview], on a selector card */
@Composable
fun AlternatePlayerStyle(
    style: PlayerStyle,
    actions: StyleActions,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    when (style) {
        PlayerStyle.POSTER -> PosterPlayerStyle(actions, modifier, preview)
        PlayerStyle.TIMELINE -> TimelinePlayerStyle(actions, modifier, preview)
        PlayerStyle.LYRICS -> LyricsPlayerStyle(actions, modifier, preview)
        PlayerStyle.DEFAULT, PlayerStyle.TURNTABLE -> Unit
    }
}

/** what every alternate style reads from the player */
private class StyleTrack(val title: String, val artist: String, val album: String, val artwork: String?)

@Composable
private fun rememberStyleTrack(): StyleTrack {
    val connection = LocalPlayerConnection.current ?: return StyleTrack("", "", "", null)
    val track by connection.uiState.trackInfo.collectAsState()
    return remember(track) { StyleTrack(track.title, track.artist, track.album, track.thumbnailUrl) }
}

/** the frame every style is drawn in: tones, gestures, rotation, the ••• menu's state */
@Composable
private fun StyleFrame(
    style: PlayerStyle,
    preview: Boolean,
    modifier: Modifier,
    tones: StyleTones,
    /** what lies under the style and holds still when it turns; the playhead split when null */
    background: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.(landscape: Boolean, menuOpen: Boolean, setMenuOpen: (Boolean) -> Unit, rotated: Boolean, setRotated: (Boolean) -> Unit) -> Unit,
) {
    val connection = LocalPlayerConnection.current
    val (rotated, setRotated) = rememberStyleRotation(style)
    var menuOpen by remember { mutableStateOf(false) }
    RotatingFrame(
        rotated = rotated,
        tones = tones,
        background = background,
        modifier = modifier
            .then(
                if (preview) {
                    // a card shows the style, it doesn't play it
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                } else Modifier
            ),
    ) { landscape ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .styleGestures(
                    enabled = !preview && connection != null && LocalStyleInteractive.current,
                    onToggle = { connection?.togglePlayPause() },
                    onNext = { connection?.seekToNext() },
                    onPrevious = { connection?.seekToPrevious() },
                ),
        ) {
            // Inter and nothing else, in every style
            androidx.compose.material3.ProvideTextStyle(
                androidx.compose.ui.text.TextStyle(fontFamily = com.musplay.app.ui.theme.InterFontFamily)
            ) {
                content(landscape, menuOpen, { menuOpen = it }, rotated, setRotated)
            }
        }
    }
}

/**
 * Insets the way they fall on a style: upright, the status bar is at its top and the navigation
 * bar at its bottom; turned sideways, those same bars run down its left and right edges.
 */
private class StyleInsets(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

@Composable
private fun styleInsets(landscape: Boolean): StyleInsets {
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return if (landscape) StyleInsets(start = status, top = 0.dp, end = nav, bottom = 0.dp)
    else StyleInsets(start = 0.dp, top = status, end = 0.dp, bottom = nav)
}

// ---- Poster ------------------------------------------------------------------------------

/**
 * The cover cut into a great circle swung in from the top-left corner, over the flagship's own
 * background, and beneath it the song's words performed big ([ExpressiveLyrics]) with the artist
 * and title set small under them. A song with no synced lyrics shouts its title instead. Turned
 * sideways, the circle swings in from the left.
 */
@Composable
private fun PosterPlayerStyle(actions: StyleActions, modifier: Modifier, preview: Boolean) {
    val tones = rememberStyleTones()
    val track = rememberStyleTrack()
    val r = rememberResponsive()
    val circle = r.dp(0.078f, 28.dp, 40.dp)
    val position = rememberLivePosition(animated = !preview)
    val lines = rememberStyleLyrics(readings = !preview)
    val backgroundStyle by com.musplay.app.utils.rememberEnumPreference(
        com.musplay.app.constants.PlayerBackgroundStyleKey,
        com.musplay.app.constants.PlayerBackgroundStyle.COVER_GRADIENT,
    )
    StyleFrame(
        PlayerStyle.POSTER, preview, modifier, tones,
        background = {
            // the flagship's background, moving as it does there; it stays put when the poster turns
            BoxWithConstraints(Modifier.fillMaxSize()) {
                com.musplay.app.ui.player.customize.PlayerBackgroundPreview(
                    style = backgroundStyle,
                    thumbnailUrl = track.artwork,
                    width = maxWidth,
                    height = maxHeight,
                    animate = !preview,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    ) { landscape, menuOpen, setMenuOpen, rotated, setRotated ->
        val insets = styleInsets(landscape)
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val w = maxWidth
            val h = maxHeight
            if (!landscape) {
                // circle from the top-left: through the right edge at 0.8 W, the left edge at 1.19 W
                CircleCover(
                    url = track.artwork,
                    center = Offset(0.13f, 0.06f),
                    radius = 1.14f,
                    unit = w,
                    modifier = Modifier.fillMaxWidth().height(w * 1.22f),
                )
                val bottom = insets.bottom + 36.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = (w * 1.24f).coerceAtMost(h * 0.6f),
                            start = 28.dp,
                            end = 22.dp + circle + 18.dp,
                            bottom = bottom,
                        ),
                ) {
                    PosterText(lines, position, track, tones, r, baseSize = r.sp(0.12f, 34f, 56f), gap = 12.dp)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = bottom),
                ) {
                    TransportCircles(tones = tones, vertical = true, size = circle)
                }
                // the menu at the top, opening down, clear of the transport
                StyleMenu(
                    open = menuOpen, onOpenChange = setMenuOpen, tones = tones, actions = actions,
                    rotated = rotated, onRotate = setRotated, atBottom = false, end = 22.dp, edge = insets.top + 16.dp,
                    buttonSize = circle,
                )
            } else {
                // circle from the left edge, the words to its right
                CircleCover(
                    url = track.artwork,
                    center = Offset(0.04f, 0.30f),
                    radius = 0.953f,
                    unit = h,
                    modifier = Modifier.fillMaxHeight().width(h * 1.0f),
                )
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(
                            start = maxOf(h * 1.08f, w * 0.5f),
                            end = 24.dp + insets.end,
                            top = 18.dp + circle + 18.dp,
                            bottom = 20.dp,
                        ),
                ) {
                    PosterText(lines, position, track, tones, r, baseSize = r.sp(0.095f, 28f, 46f), gap = 8.dp)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 18.dp, end = 20.dp + insets.end + circle + 8.dp),
                ) {
                    TransportCircles(tones = tones, size = circle)
                }
                StyleMenu(
                    open = menuOpen, onOpenChange = setMenuOpen, tones = tones, actions = actions,
                    rotated = rotated, onRotate = setRotated, atBottom = false, end = 20.dp + insets.end, edge = 18.dp,
                    buttonSize = circle,
                )
            }
        }
    }
}

/**
 * The poster's words and its credits. With lyrics, the lyrics are performed big and the title sits
 * small under them with the artist; without, the title is the big words. When a song's lyrics are
 * found, its big title slides down and shrinks into its small place at the bottom while the lyrics
 * take the space it leaves - and back up if they go away.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PosterText(
    lines: List<LyricsEntry>,
    position: LivePosition,
    track: StyleTrack,
    tones: StyleTones,
    r: Responsive,
    baseSize: TextUnit,
    gap: Dp,
) {
    val hasLyrics = lines.isNotEmpty()
    val found = remember { androidx.compose.animation.core.Animatable(if (hasLyrics) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(hasLyrics) {
        found.animateTo(
            if (hasLyrics) 1f else 0f,
            androidx.compose.animation.core.tween(820, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.1f, 1f)),
        )
    }
    val titleShown by remember { androidx.compose.runtime.derivedStateOf { found.value < 0.999f } }
    // the column, the big title and the small title: where the slide runs from and to, measured
    // against the column so a turned poster slides along its own axes
    val places = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(3) }
    val smallTitle = r.sp(0.05f, 17f, 22f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .onPlaced { places[0] = it },
    ) {
        if (hasLyrics) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val f = found.value
                        if (f < 0.999f) alpha = ((f - 0.25f) / 0.75f).coerceIn(0f, 1f)
                    },
            ) {
                ExpressiveLyrics(
                    lines = lines,
                    position = position,
                    color = tones.ink,
                    baseSize = baseSize,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (titleShown) {
            Box(
                modifier = Modifier
                    .onPlaced { places[1] = it }
                    .graphicsLayer {
                        val f = found.value
                        val column = places[0]
                        val from = places[1]
                        val to = places[2]
                        if (f > 0.0005f && column != null && from != null && to != null &&
                            column.isAttached && from.isAttached && to.isAttached
                        ) {
                            val a = column.localPositionOf(from, Offset.Zero)
                            val b = column.localPositionOf(to, Offset.Zero)
                            translationX = (b.x - a.x) * f
                            translationY = (b.y - a.y) * f
                        }
                        val s = 1f + (smallTitle.value / baseSize.value - 1f) * f
                        scaleX = s
                        scaleY = s
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        // it becomes the small title in the last part of the slide
                        alpha = 1f - ((f - 0.6f) / 0.35f).coerceIn(0f, 1f)
                    },
            ) {
                ExpressiveTitle(text = track.title, baseSize = baseSize, color = tones.ink)
            }
        }
    }
    Spacer(Modifier.height(gap))
    if (track.artist.isNotBlank()) {
        Text(track.artist, color = tones.inkSoft, style = posterType(r.sp(0.034f, 12f, 15f), FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(3.dp))
    Text(
        track.title,
        color = tones.inkSoft,
        style = posterType(smallTitle, FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .onPlaced { places[2] = it }
            .graphicsLayer { alpha = ((found.value - 0.6f) / 0.4f).coerceIn(0f, 1f) },
    )
}

/**
 * The current song's synced lines, the background vocals left out, with their pronunciation and
 * translation filled in by the same code as the lyrics page when [readings] (the live player, not
 * a selector card). Lyrics without timings show nothing here: a style performs lines in time.
 */
@Composable
private fun rememberStyleLyrics(readings: Boolean): List<LyricsEntry> {
    val connection = LocalPlayerConnection.current
    val lyricsEntity = connection?.currentLyrics?.collectAsState(initial = null)?.value
    val all = remember(lyricsEntity?.lyrics) {
        val raw = lyricsEntity?.lyrics
        if (raw.isNullOrBlank() || raw == LYRICS_NOT_FOUND) emptyList()
        else runCatching { LyricsUtils.parseLyrics(raw) }.getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() && !LyricsUtils.isUnsynced(it) }.orEmpty()
    }
    if (readings && connection != null && all.isNotEmpty()) {
        val metadata by connection.mediaMetadata.collectAsState()
        // the whole song, background lines too, so a stored translation lines up with the lyrics page's
        com.musplay.app.ui.player.LyricsReadings(all, metadata?.id, com.musplay.app.LocalDatabase.current, lyricsEntity)
    }
    return remember(all) { all.filter { !it.isBackground && it.text.isNotBlank() } }
}

/**
 * [url] cropped into a circle of [radius] around [center], both in multiples of [unit], with a
 * soft shadow on the tone beneath and the rim shaded so the disc reads as lifted off the page.
 */
@Composable
private fun CircleCover(url: String?, center: Offset, radius: Float, unit: Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val unitPx = with(density) { unit.toPx() }
    val c = Offset(center.x * unitPx, center.y * unitPx)
    val r = radius * unitPx
    val shape = remember(c, r) {
        GenericShape { _, _ -> addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
    }
    Box(
        modifier = modifier.drawBehind {
            drawCircle(
                brush = Brush.radialGradient(
                    0.9f to Color.Black.copy(alpha = 0.22f),
                    1f to Color.Transparent,
                    center = c + Offset(0f, 10.dp.toPx()),
                    radius = r * 1.06f,
                ),
                radius = r * 1.06f,
                center = c + Offset(0f, 10.dp.toPx()),
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color.White.copy(alpha = 0.06f))
                .drawWithContent {
                    drawContent()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.82f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.28f),
                            center = c,
                            radius = r,
                        ),
                        radius = r,
                        center = c,
                    )
                },
        ) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ---- Timeline ----------------------------------------------------------------------------

/**
 * The song as time: the screen split into what's played and what's left, the line between them
 * the playhead, with the elapsed and total times riding either side of it in huge type.
 */
@Composable
private fun TimelinePlayerStyle(actions: StyleActions, modifier: Modifier, preview: Boolean) {
    val tones = rememberStyleTones()
    val track = rememberStyleTrack()
    val position = rememberLivePosition(animated = !preview)
    val r = rememberResponsive()
    val icon = r.dp(0.15f, 50.dp, 72.dp)
    val duration = rememberDurationMs()
    StyleFrame(PlayerStyle.TIMELINE, preview, modifier, tones) { landscape, menuOpen, setMenuOpen, rotated, setRotated ->
        val insets = styleInsets(landscape)
        RidingTimes(
            tones = tones,
            position = position,
            duration = duration,
            landscape = landscape,
            insets = insets,
            topReserve = if (landscape) 0.dp else 20.dp + icon + 14.dp,
        ) {
            // header: the cover as an icon, the title and artist beside it
            Row(
                modifier = Modifier
                    .padding(start = 22.dp + insets.start, top = insets.top + 20.dp, end = 70.dp + insets.end)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = track.artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(if (landscape) icon * 0.86f else icon)
                        .clip(RoundedCornerShape(icon * 0.26f))
                        .background(Color.White.copy(alpha = 0.08f)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(track.title, color = tones.ink, fontSize = r.sp(0.04f, 14f, 18f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = tones.inkSoft, fontSize = r.sp(0.034f, 12f, 16f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StyleControls(tones, landscape, insets, menuOpen, setMenuOpen, actions, rotated, setRotated, menuTop = insets.top + 20.dp + (icon - r.dp(0.078f, 28.dp, 40.dp)) / 2)
        }
    }
}

// ---- Lyrics ------------------------------------------------------------------------------

/**
 * The words, performed: the current line set huge and sung through letter by letter, over the
 * same played/remaining split and riding times as the timeline.
 */
@Composable
private fun LyricsPlayerStyle(actions: StyleActions, modifier: Modifier, preview: Boolean) {
    val tones = rememberStyleTones()
    val track = rememberStyleTrack()
    val position = rememberLivePosition(animated = !preview)
    val r = rememberResponsive()
    val duration = rememberDurationMs()
    val lines = rememberStyleLyrics(readings = !preview)
    StyleFrame(PlayerStyle.LYRICS, preview, modifier, tones) { landscape, menuOpen, setMenuOpen, rotated, setRotated ->
        val insets = styleInsets(landscape)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val lyricHeight = if (landscape) maxHeight * 0.52f else maxHeight * 0.3f
            RidingTimes(
                tones = tones,
                position = position,
                duration = duration,
                landscape = landscape,
                insets = insets,
                topReserve = if (landscape) 0.dp else 18.dp + lyricHeight + 8.dp,
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 24.dp + insets.start, top = insets.top + 18.dp, end = 64.dp + insets.end)
                        .fillMaxWidth()
                        .height(lyricHeight),
                ) {
                    if (lines.isEmpty()) {
                        Column {
                            ExpressiveTitle(text = track.title, baseSize = r.sp(0.112f, 32f, 56f), color = tones.ink.copy(alpha = 0.55f))
                            Spacer(Modifier.height(10.dp))
                            Text("No synced lyrics for this song", color = tones.inkSoft, fontSize = r.sp(0.034f, 12f, 15f), fontWeight = FontWeight.Medium)
                        }
                    } else {
                        ExpressiveLyrics(
                            lines = lines,
                            position = position,
                            color = tones.ink,
                            baseSize = if (landscape) r.sp(0.092f, 28f, 46f) else r.sp(0.118f, 34f, 58f),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                StyleControls(tones, landscape, insets, menuOpen, setMenuOpen, actions, rotated, setRotated, menuTop = insets.top + 20.dp)
            }
        }
    }
}

// ---- shared pieces -----------------------------------------------------------------------

/** the transport circles and the ••• menu where the timeline and lyrics styles keep them */
@Composable
private fun BoxScope.StyleControls(
    tones: StyleTones,
    landscape: Boolean,
    insets: StyleInsets,
    menuOpen: Boolean,
    setMenuOpen: (Boolean) -> Unit,
    actions: StyleActions,
    rotated: Boolean,
    setRotated: (Boolean) -> Unit,
    menuTop: Dp,
) {
    val circle = rememberResponsive().dp(0.078f, 28.dp, 40.dp)
    if (landscape) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp + insets.end, bottom = 20.dp),
        ) {
            TransportCircles(tones = tones, vertical = true, size = circle)
        }
    } else {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = insets.bottom + 36.dp),
        ) {
            TransportCircles(tones = tones, size = circle)
        }
    }
    StyleMenu(
        open = menuOpen, onOpenChange = setMenuOpen, tones = tones, actions = actions,
        rotated = rotated, onRotate = setRotated, atBottom = false,
        end = 22.dp + insets.end, edge = if (landscape) 18.dp else menuTop,
        buttonSize = circle,
    )
}


/**
 * The played/remaining split with the two times riding on the playhead: upright, the elapsed time
 * sits just above the line and the total just below it, the line running down the screen as the
 * song plays (between [topReserve] under the top and the controls); sideways, the line runs across
 * and the times sit either side of it along the bottom. [content] draws over the split.
 */
@Composable
private fun RidingTimes(
    tones: StyleTones,
    position: LivePosition,
    duration: Long,
    landscape: Boolean,
    insets: StyleInsets,
    topReserve: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val seconds = rememberPlayedSeconds(position)
    val elapsed = makeTimeString(seconds * 1_000L)
    val total = makeTimeString(duration)
    val circle = rememberResponsive().dp(0.078f, 28.dp, 40.dp)
    val timeStyle = posterType(rememberResponsive().sp(0.185f, 56f, 96f), FontWeight.Bold)
    val timeWidthPx = remember(total, timeStyle) { measurer.measure(total, timeStyle).size.width.toFloat() }
    val timeHeightPx = remember(timeStyle) { measurer.measure("0:00", timeStyle).size.height.toFloat() }
    val fraction = { (position.positionMs().toFloat() / duration).coerceIn(0f, 1f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val (lo, hi) = with(density) {
            if (!landscape) {
                val top = (insets.top + topReserve).toPx() + timeHeightPx
                val bottom = hPx - (insets.bottom + 36.dp + circle + 18.dp).toPx() - timeHeightPx
                top to bottom.coerceAtLeast(top)
            } else {
                val left = (insets.start + 22.dp).toPx() + timeWidthPx + 10.dp.toPx()
                val right = wPx - (insets.end + 20.dp + circle + 22.dp).toPx() - timeWidthPx - 10.dp.toPx()
                left to right.coerceAtLeast(left)
            }
        }
        val linePx = { lo + (hi - lo) * fraction() }
        // the frame draws the split itself, under the turn, so it holds still when the style rotates
        ReportSplit(landscape) { linePx() / (if (landscape) wPx else hPx) }
        Box(modifier = Modifier.fillMaxSize()) {
            val gapPx = with(density) { 6.dp.toPx() }
            val startPx = with(density) { (insets.start + 24.dp).toPx() }
            val bottomPx = with(density) { 16.dp.toPx() }
            // the elapsed time: above the line, or left of it
            Text(
                text = elapsed,
                color = tones.ink,
                style = timeStyle,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        if (!landscape) {
                            p.place(startPx.toInt(), (linePx() - p.height - gapPx * 0.3f).toInt())
                        } else {
                            p.place((linePx() - p.width - gapPx * 1.6f).toInt(), (hPx - bottomPx - p.height).toInt())
                        }
                    }
                },
            )
            // the total: below the line, or right of it
            Text(
                text = total,
                color = tones.ink.copy(alpha = 0.78f),
                style = timeStyle,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        if (!landscape) {
                            p.place(startPx.toInt(), (linePx() + gapPx * 0.6f).toInt())
                        } else {
                            p.place((linePx() + gapPx * 1.6f).toInt(), (hPx - bottomPx - p.height).toInt())
                        }
                    }
                },
            )
            content()
        }
    }
}
