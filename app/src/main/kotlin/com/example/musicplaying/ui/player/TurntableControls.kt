// TurntableControls.kt

package com.example.musicplaying.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.example.musicplaying.LocalPlayerConnection
import com.example.musicplaying.R
import com.example.musicplaying.constants.ShowFormatBadgeKey
import com.example.musicplaying.constants.ShowFormatSampleRateKey
import com.example.musicplaying.extensions.togglePlayPause
import com.example.musicplaying.ui.theme.InterFontFamily
import com.example.musicplaying.utils.rememberPreference

// The Turntable frame's controls, at its sizes: four pills stacked on the right (like, back,
// play/pause, forward) above the title row - the chevron on the left, the title and its format
// badge running the width under them.
private val PillWidth = 59.dp
private val PillHeight = 42.dp
private val PillGap = 11.dp
private val ChevronSize = 43.dp
private val SideMargin = 30.dp
private val PillFill = Color.White.copy(alpha = 0.08f)
/** between the stack and the title row, on top of the stack's own gap */
private val TitleGap = 4.dp

@Composable
fun TurntableControls(
    isExpandMenuOpen: Boolean,
    onToggleExpandMenu: () -> Unit,
    modifier: Modifier = Modifier,
    /** hides the chevron while the lyrics page's own (the same control, moved) is up */
    hideChevron: Boolean = false,
    onTitlePositioned: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onChevronPositioned: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    likeBurst: LikeBurstState? = null,
    onSongInfoClick: (() -> Unit)? = null,
) {
    val connection = LocalPlayerConnection.current ?: return
    val track by connection.uiState.trackInfo.collectAsState()
    val transport by connection.uiState.transportState.collectAsState()

    val holdSpeed = remember(connection) { HoldSpeed(connection.player) }
    DisposableEffect(holdSpeed) { onDispose { holdSpeed.end() } }

    Column(
        verticalArrangement = Arrangement.spacedBy(PillGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = SideMargin, end = SideMargin),
    ) {
        Pill(modifier = Modifier.align(Alignment.End)) {
            val liked = track.liked
            PressScaleIcon(
                icon = if (liked) R.drawable.ic_untitled_heart else R.drawable.ic_untitled_heart_unfill,
                tint = if (liked) Color.White else Color.White.copy(alpha = 0.85f),
                onClick = {
                    if (likeBurst != null) likeBurst.likeFromButton(LikeSlot.PLAYER, liked, connection::toggleLike)
                    else connection.toggleLike()
                },
                modifier = Modifier.likeButtonTarget(likeBurst, LikeSlot.PLAYER),
            )
        }
        Pill(modifier = Modifier.align(Alignment.End)) {
            AnimatedPressScaleSkipButton(
                icon = R.drawable.avd_skip_previous,
                onClick = connection::seekToPrevious,
                enabled = transport.canSkipPrevious,
                tint = Color.White,
                iconSize = 24.dp,
                onHoldStart = { holdSpeed.start(HoldSlowFactor) },
                onHoldEnd = holdSpeed::end,
                holdLabel = "0.5×",
                holdPulseMs = 900L,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Pill(modifier = Modifier.align(Alignment.End)) {
            AnimatedPressScalePlayPauseButton(
                isPlaying = transport.isPlaying,
                playbackState = transport.playbackState,
                onClick = {
                    if (transport.playbackState == Player.STATE_ENDED) {
                        connection.player.seekTo(0, 0)
                        connection.player.playWhenReady = true
                    } else {
                        connection.togglePlayPause()
                    }
                },
                tint = Color.White,
                iconSize = 26.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Pill(modifier = Modifier.align(Alignment.End)) {
            AnimatedPressScaleSkipButton(
                icon = R.drawable.avd_skip_next,
                onClick = connection::seekToNext,
                enabled = transport.canSkipNext,
                tint = Color.White,
                iconSize = 24.dp,
                onHoldStart = { holdSpeed.start(HoldFastFactor) },
                onHoldEnd = holdSpeed::end,
                holdLabel = "2×",
                holdPulseMs = 420L,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(TitleGap))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChevronButton(
                open = isExpandMenuOpen,
                hidden = hideChevron,
                onClick = onToggleExpandMenu,
                modifier = Modifier.onGloballyPositioned { onChevronPositioned(it.boundsInRoot()) },
            )
            Spacer(Modifier.width(14.dp))
            TitleBlock(
                title = track.title,
                artist = track.artist,
                mediaId = track.mediaId,
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { onTitlePositioned(it.boundsInRoot()) }
                    .then(
                        if (onSongInfoClick != null) Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onSongInfoClick,
                        ) else Modifier
                    ),
            )
        }
    }
}

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(PillWidth, PillHeight)
            .clip(RoundedCornerShape(50))
            .background(PillFill),
    ) { content() }
}

@Composable
private fun PressScaleIcon(icon: Int, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.84f else 1f, spring(dampingRatio = 0.54f, stiffness = 720f), label = "pillPress")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = modifier
                .size(20.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

@Composable
private fun ChevronButton(open: Boolean, hidden: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.55f, stiffness = 380f), label = "ttChevron")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.54f, stiffness = 720f), label = "ttChevronPress")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(ChevronSize)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                alpha = if (open || hidden) 0f else 1f
            }
            .clip(CircleShape)
            .background(PillFill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.expand_less),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)),
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

private data class TitleLines(val mediaId: String, val title: String, val artist: String)

@Composable
private fun TitleBlock(title: String, artist: String, mediaId: String, modifier: Modifier = Modifier) {
    val connection = LocalPlayerConnection.current ?: return
    val (showBadge) = rememberPreference(ShowFormatBadgeKey, defaultValue = true)
    val (showSampleRate) = rememberPreference(ShowFormatSampleRateKey, defaultValue = false)
    AnimatedContent(
        targetState = TitleLines(mediaId, title, artist),
        transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) },
        label = "turntableTitle",
        modifier = modifier,
    ) { lines ->
        Column {
            val facts by remember(lines.mediaId) { connection.streamFacts(lines.mediaId) }.collectAsState(initial = null)
            val badge = if (showBadge) streamBadgeText(facts, showSampleRate) else null
            Row(verticalAlignment = Alignment.CenterVertically) {
                FadingOverflowText(
                    text = lines.title,
                    style = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 27.sp,
                        lineHeight = 31.sp,
                        letterSpacing = (-0.035).em,
                    ),
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    StreamFormatBadge(text = badge, height = 18.dp)
                }
            }
            if (lines.artist.isNotBlank()) {
                Text(
                    text = lines.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        lineHeight = 20.sp,
                        letterSpacing = (-0.02).em,
                        color = Color.White.copy(alpha = 0.55f),
                    ),
                )
            }
        }
    }
}
