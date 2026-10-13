package com.musplay.app.ui.player.styles

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.media3.common.Player
import com.musplay.app.LocalArtworkColor
import com.musplay.app.LocalPlayerConnection
import com.musplay.app.R
import com.musplay.app.constants.PlayerStyle
import com.musplay.app.constants.playerStyleRotatedKey
import com.musplay.app.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Whether a style takes touches: only on the open player. Collapsed into the mini pill the style
 * is still composed under it, and its gestures would swallow the swipe and tap that open the player.
 */
val LocalStyleInteractive = androidx.compose.runtime.compositionLocalOf { true }

/**
 * The two tones every alternate style is built from, taken from the cover: [played] is the part of
 * the song that has played, [remaining] the darker part still to come, and the line between them
 * is the playhead. Both are the cover's hue held quiet (low saturation), so big flat areas of it
 * stay easy on the eye while still belonging to the song.
 */
@Immutable
data class StyleTones(
    val played: Color,
    val remaining: Color,
    val ink: Color,
    val inkSoft: Color,
    val chip: Color,
)

@Composable
fun rememberStyleTones(): StyleTones {
    val seed = LocalArtworkColor.current
    return remember(seed) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seed.toArgb(), hsl)
        val saturation = (hsl[1] * 0.42f).coerceIn(0.06f, 0.24f)
        fun tone(lightness: Float) = Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, lightness)))
        StyleTones(
            played = tone(0.45f),
            remaining = tone(0.30f),
            ink = Color(0xFFF1ECEC),
            inkSoft = Color(0xFFF1ECEC).copy(alpha = 0.62f),
            chip = Color.White.copy(alpha = 0.22f),
        )
    }
}

/**
 * Soft light over a flat tone: brighter toward [light], a little darker at the far edge. Drawn
 * behind content in one pass, so the large colour fields read as lit surfaces rather than paint.
 */
fun Modifier.shadedTone(color: Color, light: Offset = Offset(0.15f, 0.05f)): Modifier = drawBehind {
    drawRect(color)
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * light.x, size.height * light.y),
            radius = size.maxDimension * 0.9f,
        )
    )
    drawRect(
        Brush.verticalGradient(
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.16f),
        )
    )
}

/**
 * The playback position on every frame, for the parts of a style that move with it (the playhead
 * boundary and the timestamps riding on it). Read it inside draw, layout or graphicsLayer blocks
 * only: those re-run each frame without recomposing anything.
 */
@Stable
class LivePosition internal constructor(private val read: () -> Long, private val tick: () -> Long) {
    private var readAt = Long.MIN_VALUE
    private var value = 0L

    /**
     * ms into the song. reading it subscribes the caller's phase to the frame tick. it's read from
     * the player once per tick, so every part drawn in one frame moves from the same moment
     */
    fun positionMs(): Long {
        val now = tick()
        if (now != readAt) {
            readAt = now
            value = read()
        }
        return value
    }
}

/** [animated] false (a selector card's preview): it follows the player's own updates, not every frame */
@Composable
fun rememberLivePosition(animated: Boolean = true): LivePosition {
    val connection = LocalPlayerConnection.current
    val tick = remember { mutableLongStateOf(0L) }
    val transport = connection?.uiState?.transportState?.collectAsState()
    val progress = connection?.uiState?.progressState?.collectAsState()
    val playing = transport?.value?.isPlaying == true
    LaunchedEffect(playing, progress?.value?.position, animated) {
        // a seek or a pause still moves the boundary once; playing, it moves every frame
        tick.longValue++
        while (playing && animated) withFrameMillis { tick.longValue = it }
    }
    return remember(connection) {
        LivePosition(read = { connection?.player?.currentPosition ?: 0L }, tick = { tick.longValue })
    }
}

/** the song's length in ms, never 0 */
@Composable
fun rememberDurationMs(): Long {
    val connection = LocalPlayerConnection.current ?: return 1L
    val progress by connection.uiState.progressState.collectAsState()
    return progress.duration.takeIf { it > 0L } ?: 1L
}

/** the whole seconds played, recomposing once a second, for the big timestamp */
@Composable
fun rememberPlayedSeconds(position: LivePosition): Long {
    val seconds by remember(position) { derivedStateOf { position.positionMs() / 1_000L } }
    return seconds
}

/**
 * The played and remaining tones, split at the playhead: across the screen top to bottom when
 * [vertical] (played on top), left to right otherwise. [boundary] maps the playhead fraction to
 * where the line is drawn, so a style can keep it clear of its header and its controls.
 */
fun Modifier.progressSplit(
    tones: StyleTones,
    vertical: Boolean,
    boundary: () -> Float,
): Modifier = drawBehind { drawSplit(tones, vertical, boundary()) }

/** a small round button: the alternate styles' only controls */
@Composable
fun StyleCircle(
    @DrawableRes icon: Int,
    tones: StyleTones,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    enabled: Boolean = true,
) {
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val interactive = LocalStyleInteractive.current
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
                alpha = if (enabled) 1f else 0.4f
            }
            .size(size)
            .clip(CircleShape)
            .background(tones.chip)
            .pointerInput(enabled, interactive) {
                if (!enabled || !interactive) return@pointerInput
                detectTapGestures(
                    onPress = {
                        scope.launch { press.animateTo(0.86f, spring(dampingRatio = 0.6f, stiffness = 900f)) }
                        tryAwaitRelease()
                        scope.launch { press.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
                    },
                    onTap = { onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tones.ink,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** previous, play/pause and next as three [StyleCircle]s, laid out by the caller's [Row] or [Column] */
@Composable
fun TransportCircles(tones: StyleTones, size: Dp = 30.dp, vertical: Boolean = false, spacing: Dp = 8.dp) {
    val connection = LocalPlayerConnection.current ?: return
    val transport by connection.uiState.transportState.collectAsState()
    val content: @Composable () -> Unit = {
        StyleCircle(
            icon = R.drawable.skip_previous,
            tones = tones,
            size = size,
            enabled = transport.canSkipPrevious,
            onClick = connection::seekToPrevious,
        )
        StyleCircle(
            icon = if (transport.isPlaying) R.drawable.pause else R.drawable.play,
            tones = tones,
            size = size,
            onClick = {
                if (transport.playbackState == Player.STATE_ENDED) {
                    connection.player.seekTo(0, 0)
                    connection.player.playWhenReady = true
                } else {
                    connection.togglePlayPause()
                }
            },
        )
        StyleCircle(
            icon = R.drawable.skip_next,
            tones = tones,
            size = size,
            enabled = transport.canSkipNext,
            onClick = connection::seekToNext,
        )
    }
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) { content() }
    }
}

/** what a style's ••• menu can do, wired by the player */
@Immutable
class StyleActions(
    val onOtherMenu: () -> Unit,
    val onEditPlayer: () -> Unit,
)

/** whether [style] is turned sideways, and the switch for it */
@Composable
fun rememberStyleRotation(style: PlayerStyle): Pair<Boolean, (Boolean) -> Unit> {
    val (rotated, onRotated) = rememberPreference(playerStyleRotatedKey(style), defaultValue = false)
    return rotated to onRotated
}

/**
 * Double tap plays or pauses; a horizontal swipe skips (right) or goes back (left). The alternate
 * styles have no big transport buttons, so the whole screen is the transport.
 */
fun Modifier.styleGestures(enabled: Boolean, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit): Modifier =
    if (!enabled) this else this
        .pointerInput(enabled) { detectTapGestures(onDoubleTap = { onToggle() }) }
        .pointerInput(enabled) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    when {
                        total < -SwipeSkipPx -> onNext()
                        total > SwipeSkipPx -> onPrevious()
                    }
                },
                onHorizontalDrag = { _, amount -> total += amount },
            )
        }

private const val SwipeSkipPx = 120f

/**
 * The ••• button and the little menu it opens: other menu, rotate (in the middle) and edit player,
 * rising out of the button the way the flagship's chevron menu does. The button sits [edge] from
 * the top (or the bottom, [atBottom]) and [end] from the right; the menu opens away from that edge.
 * Call it last in the style's Box so the dimmed backdrop and the menu draw over the style.
 */
@Composable
fun BoxScope.StyleMenu(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    tones: StyleTones,
    actions: StyleActions,
    rotated: Boolean,
    onRotate: (Boolean) -> Unit,
    atBottom: Boolean,
    end: Dp,
    edge: Dp,
    buttonSize: Dp = 30.dp,
    showButton: Boolean = true,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(open) {
        reveal.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 420f))
    }
    if (reveal.value > 0.001f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.42f * reveal.value.coerceIn(0f, 1f))) }
                .pointerInput(Unit) { detectTapGestures { onOpenChange(false) } },
        )
        val items = listOf(
            Triple("Other menu", R.drawable.more_horiz) { onOpenChange(false); actions.onOtherMenu() },
            Triple(if (rotated) "Turn upright" else "Rotate", R.drawable.screen_rotation) { onOpenChange(false); onRotate(!rotated) },
            Triple("Edit player", R.drawable.tune) { onOpenChange(false); actions.onEditPlayer() },
        )
        val itemGap = 10.dp
        Column(
            modifier = Modifier
                .align(if (atBottom) Alignment.BottomEnd else Alignment.TopEnd)
                .padding(
                    end = end - 3.dp,
                    top = if (atBottom) 0.dp else edge + buttonSize + itemGap,
                    bottom = if (atBottom) edge + buttonSize + itemGap else 0.dp,
                ),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(itemGap),
        ) {
            items.forEachIndexed { index, (label, icon, onClick) ->
                // nearest the button first
                val order = if (atBottom) items.size - 1 - index else index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.graphicsLayer {
                        val delay = order * 0.12f
                        val p = ((reveal.value - delay) / (1f - delay)).coerceIn(0f, 1.2f)
                        alpha = p.coerceIn(0f, 1f)
                        val away = if (atBottom) 1f else -1f
                        translationY = (1f - p) * 28.dp.toPx() * away
                        val s = 0.7f + 0.3f * p
                        scaleX = s
                        scaleY = s
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    },
                ) {
                    Text(text = label, color = tones.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.05).em, fontFamily = com.musplay.app.ui.theme.InterFontFamily)
                    StyleCircle(icon = icon, tones = tones, size = buttonSize + 6.dp, onClick = onClick)
                }
            }
        }
    }
    if (showButton) {
        Box(
            modifier = Modifier
                .align(if (atBottom) Alignment.BottomEnd else Alignment.TopEnd)
                .padding(end = end, top = if (atBottom) 0.dp else edge, bottom = if (atBottom) edge else 0.dp),
        ) {
            MoreButton(open, tones, buttonSize, onOpenChange)
        }
    }
}

@Composable
private fun MoreButton(open: Boolean, tones: StyleTones, size: Dp, onOpenChange: (Boolean) -> Unit) {
    val turn = remember { Animatable(0f) }
    LaunchedEffect(open) { turn.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 380f)) }
    StyleCircle(
        icon = if (open) R.drawable.close else R.drawable.more_horiz,
        tones = tones,
        size = size,
        onClick = { onOpenChange(!open) },
        modifier = Modifier.graphicsLayer { rotationZ = turn.value * 90f },
    )
}

/**
 * Where each orientation of a style puts its playhead split, as a share of the screen from the top.
 * Upright the split runs down the screen; sideways it runs along the turned layout, which on the
 * phone is down the screen too - so one split, drawn by the frame outside the turn, serves both,
 * and the background never turns with the layout.
 */
@Stable
class StyleSplits {
    var upright by mutableStateOf<(() -> Float)?>(null)
    var sideways by mutableStateOf<(() -> Float)?>(null)
    operator fun get(landscape: Boolean) = if (landscape) sideways else upright
    operator fun set(landscape: Boolean, value: (() -> Float)?) {
        if (landscape) sideways = value else upright = value
    }
}

internal val LocalStyleSplits = androidx.compose.runtime.compositionLocalOf<StyleSplits?> { null }

/** a layout reporting where its split is, for the frame to draw it */
@Composable
internal fun ReportSplit(landscape: Boolean, boundary: () -> Float) {
    val splits = LocalStyleSplits.current ?: return
    val current by androidx.compose.runtime.rememberUpdatedState(boundary)
    androidx.compose.runtime.DisposableEffect(splits, landscape) {
        val read = { current() }
        splits[landscape] = read
        onDispose { if (splits[landscape] === read) splits[landscape] = null }
    }
}

/** the played/remaining split as [progressSplit] draws it, into any draw scope */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSplit(tones: StyleTones, vertical: Boolean, boundary: Float) {
    val b = boundary.coerceIn(0f, 1f)
    if (vertical) {
        val y = size.height * b
        drawRect(tones.played, size = size.copy(height = y))
        drawRect(tones.remaining, topLeft = Offset(0f, y), size = size.copy(height = size.height - y))
        // light falls on the played part from the top, the line itself catches a highlight
        drawRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), endY = y.coerceAtLeast(1f)),
            size = size.copy(height = y),
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent),
                startY = y,
                endY = y + size.height * 0.18f,
            ),
            topLeft = Offset(0f, y),
            size = size.copy(height = size.height * 0.18f),
        )
        drawRect(Color.White.copy(alpha = 0.10f), topLeft = Offset(0f, y - 1f), size = size.copy(height = 1.5f))
    } else {
        val x = size.width * b
        drawRect(tones.played, size = size.copy(width = x))
        drawRect(tones.remaining, topLeft = Offset(x, 0f), size = size.copy(width = size.width - x))
        drawRect(
            Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), endX = x.coerceAtLeast(1f)),
            size = size.copy(width = x),
        )
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent),
                startX = x,
                endX = x + size.width * 0.14f,
            ),
            topLeft = Offset(x, 0f),
            size = size.copy(width = size.width * 0.14f),
        )
        drawRect(Color.White.copy(alpha = 0.10f), topLeft = Offset(x - 1f, 0f), size = size.copy(width = 1.5f))
    }
}

/**
 * Lays [content] out sideways when [rotated]: measured with width and height swapped and turned a
 * quarter clockwise, so the style reads upright with the phone held on its side.
 *
 * Changing it is a real turn: the layout leaving and the layout arriving are both drawn, turning
 * together through the whole quarter as one piece, shrunk just enough at each angle to stay on the
 * screen, tipped back a little in depth and motion-blurred at the fastest part of the swing, the
 * one handing over to the other through it. [background] sits under the turn and holds still: the
 * style's own tone, or its playhead split, which the layouts report through [ReportSplit] and which
 * glides from where the old layout had it to where the new one does.
 */
@Composable
fun RotatingFrame(
    rotated: Boolean,
    tones: StyleTones,
    modifier: Modifier = Modifier,
    background: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.(landscape: Boolean) -> Unit,
) {
    var shown by remember { mutableStateOf(rotated) }
    // the orientation turning away, only while a turn plays
    var leaving by remember { mutableStateOf<Boolean?>(null) }
    val turn = remember { Animatable(1f) }
    val splits = remember { StyleSplits() }
    LaunchedEffect(rotated) {
        if (rotated == shown) return@LaunchedEffect
        leaving = shown
        shown = rotated
        turn.snapTo(0f)
        // a little weight at the end: it settles a hair past square and comes back
        turn.animateTo(1f, spring(dampingRatio = 0.74f, stiffness = 150f))
        leaving = null
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (background != null) Modifier else Modifier.drawBehind {
                    val now = splits[shown]
                    if (now == null) {
                        drawRect(tones.played)
                        return@drawBehind
                    }
                    val from = leaving?.let { splits[it] }
                    val t = turn.value.coerceIn(0f, 1f)
                    val b = if (from != null) from() + (now() - from()) * t else now()
                    drawSplit(tones, vertical = true, boundary = b)
                }
            ),
    ) {
        if (background != null) {
            Box(modifier = Modifier.fillMaxSize(), content = background)
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalStyleSplits provides splits) {
            val layers = listOfNotNull(leaving, shown)
            layers.forEach { orientation ->
                androidx.compose.runtime.key(orientation) {
                    val arriving = orientation == shown
                    Box(
                        modifier = Modifier
                            .quarterTurn(orientation)
                            .graphicsLayer {
                                if (leaving == null) return@graphicsLayer
                                val t = turn.value
                                // toward landscape the turn is clockwise, back upright counter-clockwise
                                val direction = if (shown) 1f else -1f
                                // this layer's angle away from where it rests
                                val angle = if (arriving) -direction * 90f * (1f - t) else direction * 90f * t
                                rotationZ = angle
                                val fit = turnFit(size.width, size.height, if (orientation) 90f else 0f, angle)
                                val s = fit * (1f - 0.04f * sin01(t))
                                scaleX = s
                                scaleY = s
                                // tipped back a touch mid-swing, so it turns in space rather than on glass
                                cameraDistance = 14f * density
                                rotationX = 9f * sin01(t)
                                // the hand-over: the old fades through the first half, the new through the second
                                alpha = if (arriving) smooth((t - 0.3f) / 0.45f) else 1f - smooth((t - 0.1f) / 0.45f)
                                if (android.os.Build.VERSION.SDK_INT >= 31) {
                                    val blur = 14.dp.toPx() * sin01(t)
                                    renderEffect = if (blur > 0.5f) {
                                        androidx.compose.ui.graphics.BlurEffect(blur, blur, androidx.compose.ui.graphics.TileMode.Clamp)
                                    } else null
                                }
                            },
                    ) {
                        content(orientation)
                    }
                }
            }
        }
    }
}

/**
 * How far a layer [w] x [h] (as laid out, before its own [restAngle]) must shrink, turned a further
 * [angle] degrees, to stay inside the screen it fills at rest.
 */
private fun turnFit(w: Float, h: Float, restAngle: Float, angle: Float): Float {
    if (w <= 0f || h <= 0f) return 1f
    // the screen: the layer's own size, turned by its rest angle
    val screenW = if (restAngle == 0f) w else h
    val screenH = if (restAngle == 0f) h else w
    val a = Math.toRadians((restAngle + angle).toDouble())
    val c = abs(kotlin.math.cos(a)).toFloat()
    val s = abs(kotlin.math.sin(a)).toFloat()
    val extentW = w * c + h * s
    val extentH = w * s + h * c
    return minOf(screenW / extentW, screenH / extentH, 1f)
}

private fun sin01(t: Float): Float = kotlin.math.sin(Math.PI.toFloat() * t.coerceIn(0f, 1f))

private fun smooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** measures with width and height swapped and turns the result a quarter clockwise, about its centre */
fun Modifier.quarterTurn(enabled: Boolean): Modifier = layout { measurable, constraints ->
    if (!enabled || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val placeable = measurable.measure(Constraints.fixed(h, w))
        layout(w, h) {
            placeable.placeWithLayer((w - h) / 2, (h - w) / 2) { rotationZ = 90f }
        }
    }
}

/**
 * Sizes that follow the screen: a share of its short side, held between sensible bounds, so the
 * styles and the editor look the same on a small phone and a big one.
 */
@Immutable
class Responsive(val shortSideDp: Float) {
    fun dp(fraction: Float, min: Dp, max: Dp): Dp = (shortSideDp * fraction).dp.coerceIn(min, max)
    fun sp(fraction: Float, min: Float, max: Float): androidx.compose.ui.unit.TextUnit =
        (shortSideDp * fraction).coerceIn(min, max).sp
}

@Composable
fun rememberResponsive(): Responsive {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val short = minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    return remember(short) { Responsive(short) }
}

/** a colour between two tones, for the bits that cross the playhead */
fun toneBetween(a: Color, b: Color, t: Float): Color = lerp(a, b, t.coerceIn(0f, 1f))
