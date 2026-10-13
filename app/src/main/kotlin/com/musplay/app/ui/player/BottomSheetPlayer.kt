// BottomSheetPlayer.kt

package com.musplay.app.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.media3.common.Player
import com.musplay.app.constants.PlayerHorizontalPadding
import com.musplay.app.extensions.toggleRepeatMode
import com.musplay.app.ui.component.BlurEffectCache
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.musplay.app.ui.player.studio.playHoldRamp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import android.os.Build
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.joinAll
import androidx.navigation.NavController
import com.musplay.app.LocalPlayerConnection
import com.musplay.app.R
import com.musplay.app.constants.PlayerBackgroundStyle
import com.musplay.app.constants.PlayerBackgroundStyleKey
import com.musplay.app.constants.PlayerCoverStyle
import com.musplay.app.constants.DefaultPlayerCoverStyle
import com.musplay.app.constants.PlayerCoverStyleKey
import com.musplay.app.constants.PlayerStyle
import com.musplay.app.constants.ShowPlayerBottomCardKey
import com.musplay.app.constants.InfiniteQueueKey
import androidx.activity.compose.BackHandler
import com.musplay.app.ui.component.BottomSheet
import com.musplay.app.ui.component.BottomSheetState
import com.musplay.app.ui.component.ExpandedSwipe
import com.musplay.app.ui.component.GlassState
import com.musplay.app.ui.player.studio.EditTiles
import com.musplay.app.ui.player.studio.PlayerSelectorOverlay
import com.musplay.app.ui.player.studio.StudioEditOverlay
import com.musplay.app.ui.player.studio.StudioSheets
import com.musplay.app.ui.player.studio.StudioStage
import com.musplay.app.ui.player.studio.studioAnchor
import com.musplay.app.ui.player.studio.studioCaptureSource
import com.musplay.app.ui.player.studio.studioCardLayer
import com.musplay.app.ui.player.studio.studioCoverLayer
import com.musplay.app.ui.player.studio.studioMiddleLayer
import com.musplay.app.ui.player.studio.studioSheetLayer
import com.musplay.app.ui.player.studio.studioTransportLayer
import com.musplay.app.ui.player.studio.studioPiece
import com.musplay.app.ui.player.studio.holdAnywhere
import com.musplay.app.ui.player.menu.PlayerActionMenu
import com.musplay.app.utils.rememberEnumPreference
import com.musplay.app.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * How far the controls are pulled up over the artwork.
 *
 * This only makes sense for an edge-to-edge cover, where the art bleeds behind the controls and
 * the overlap is the intended look. Every other cover style ends its artwork above the controls,
 * so the same 64dp lift dragged the title, timestamps and seek bar back up onto the cover - the
 * overlap that showed up once SQUARED became the default on devices without blur.
 */
private val ControlsDrawShift = 64.dp

/** Small upward nudge for the seek bar and its timestamps, so they sit off the controls below. */
private val SeekBarLift = 6.dp

/** the Turntable's controls stand this far above the card, as in its frame */
private val TurntableControlsLift = 44.dp

/** The queue page's third button (autoplay) and the gap above it, on top of the lyrics pair. */
private val QueueButtonExtraHeight = 41.dp

/** Chevron, shuffle and autoplay: three 34dp buttons, 7dp apart. */
private val QueueButtonColumnHeight = 75.dp + QueueButtonExtraHeight


/** A swipe on the strip below the seek bar commits past this travel, or past [PageSwipeFling]. */
private val PageSwipeCommit = 40.dp

/** Fling speed (per second) that commits a swipe on its own. */
private val PageSwipeFling = 600.dp

/** What the vertical swipe being handled is doing, see the handler in [BottomSheetPlayer]. */
private enum class PlayerSwipe {
    NONE,

    /** A page following the finger up out of the card. */
    PULL,

    /** Up on the strip below the seek bar with a page open: the page the card offers. */
    OPEN_OTHER,

    /** Down on that strip: back to the page before, or to the player. */
    BACK,
}

/** The old gap from the seek bar down to the buttons (24dp plus the bar's lift). */
private val TransportGapAboveRow = 24.dp + SeekBarLift

/** How far the title row rises as a page opens and its title moves into the header. */
private val TitleRowDrift = 22.dp

/**
 * A slot whose height (and a gap under it) is read at measure time, with its content pinned to the
 * slot's bottom and free to overflow upward - what `height(x).wrapContentHeight(Bottom, unbounded)`
 * did, minus the recomposition every frame the height animates.
 */
private fun Modifier.bottomAnchoredSlot(
    height: () -> androidx.compose.ui.unit.Dp,
    bottomGap: () -> androidx.compose.ui.unit.Dp = { 0.dp },
): Modifier = layout { measurable, constraints ->
    val slot = height().roundToPx().coerceAtLeast(0)
    val gap = bottomGap().roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(
        constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
    )
    val total = (slot + gap).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, total) {
        placeable.place(0, slot - placeable.height)
    }
}

@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val trackInfo by playerConnection.uiState.trackInfo.collectAsState()
    val transportState by playerConnection.uiState.transportState.collectAsState()

    val morphingGlassState = remember { GlassState() }
    // The like animation: hearts fly home to the like button on whichever page is showing.
    val likeBurst = remember { LikeBurstState() }
    // The lyrics and queue pages. They share the header morph (cover and title move up the same
    // way) and the slot above the seek bar; only one is ever open. Opened from the player, a page
    // grows out of the bottom card - by tap or by swiping up anywhere on the player.
    val transition = rememberPlayerPageTransition()
    val deck = remember { PlayerDeckState() }
    val morph = remember { CardMorphState() }
    val showLyrics by remember { derivedStateOf { transition.page == PlayerPage.LYRICS } }
    val showQueue by remember { derivedStateOf { transition.page == PlayerPage.QUEUE } }
    likeBurst.slotProvider = {
        when {
            showQueue -> LikeSlot.QUEUE
            showLyrics -> LikeSlot.LYRICS
            else -> LikeSlot.PLAYER
        }
    }
    val (infiniteQueue) = rememberPreference(InfiniteQueueKey, defaultValue = true)
    val (showPlayerBottomCard) = rememberPreference(ShowPlayerBottomCardKey, defaultValue = true)

    // the player studio: the selector the player shrinks into, and the flagship's in-place editor
    val studio = com.musplay.app.ui.player.studio.rememberPlayerStudioState()
    val studioAnchors = remember { com.musplay.app.ui.player.studio.StudioAnchors() }
    val studioParts = com.musplay.app.ui.player.studio.rememberStudioParts(studio, studioAnchors)
    val menuLayout = com.musplay.app.ui.player.studio.rememberPlayerMenuLayout()
    val studioFrame = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val studioCapture = remember(studioFrame) { com.musplay.app.ui.player.studio.StudioCapture(studioFrame) }
    // the middle part's pieces (side button, chevron, seek bar, end time), lifted off the player
    // while it's edited, so the editor moves the player's own drawing of each
    val studioPieces = com.musplay.app.ui.player.studio.rememberStudioPieces()
    androidx.compose.runtime.SideEffect { studioParts.pieces = studioPieces }
    // The middle part is a camera move over a still of the player: on opening it the pieces are
    // lifted and the rest of the player is taken as one picture (the camera waits for it, a frame
    // or two); the live player stops drawing under it until the camera is back out.
    LaunchedEffect(studio, studioCapture, studioPieces) {
        androidx.compose.runtime.snapshotFlow { studio.stage == StudioStage.MIDDLE || studio.stage == StudioStage.MENU }
            .collectLatest { inMiddle ->
                if (!inMiddle) {
                    androidx.compose.runtime.snapshotFlow { studio.middle.value }.first { it < 0.002f }
                    studio.sheetCovered = false
                    // the live player is back under the still for a frame before the pieces go home
                    androidx.compose.runtime.withFrameNanos { }
                    studioPieces.lifted = false
                    studioCapture.image = null
                    studioCapture.soft = null
                    return@collectLatest
                }
                if (studioCapture.image != null) return@collectLatest
                studio.middle.value = 0f
                studioPieces.lifted = true
                studioCapture.recording = true
                androidx.compose.runtime.withFrameNanos { }
                androidx.compose.runtime.withFrameNanos { }
                val frame = runCatching { studioFrame.toImageBitmap() }.getOrNull()
                studioCapture.recording = false
                studioCapture.image = frame
                if (frame != null) studio.sheetCovered = true
                studio.middle.animateTo(1f)
                if (frame != null) {
                    studioCapture.soft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching { com.musplay.app.ui.player.studio.softBackdrop(frame) }.getOrNull()
                    }
                }
            }
    }
    var showPlaybackSpeed by remember { mutableStateOf(false) }
    var showDeviceOutput by remember { mutableStateOf(false) }

    var showExpandMenu by remember { mutableStateOf(false) }
    var seeVideo by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    // The title and artist's sheet: the artists, the album, about the song
    var showSongDetails by remember { mutableStateOf(false) }

    var showActionMenu by remember { mutableStateOf(false) }
    // Which surface opened the menu. The lyrics tools only appear when it came from the lyrics
    // page, where they have something visible to act on.
    var menuFromLyrics by remember { mutableStateOf(false) }

    val menuReveal = remember { mutableFloatStateOf(0f) }

    var coverStyle by rememberEnumPreference(PlayerCoverStyleKey, DefaultPlayerCoverStyle)
    val (playerStyle, onPlayerStyleChange) = rememberEnumPreference(
        com.musplay.app.constants.PlayerStyleKey,
        com.musplay.app.constants.PlayerStyle.DEFAULT,
    )
    // the Turntable is the flagship around a record player, whatever cover the flagship is set to
    val isTurntable = playerStyle == com.musplay.app.constants.PlayerStyle.TURNTABLE
    val shownCoverStyle = if (isTurntable) PlayerCoverStyle.TURNTABLE else coverStyle
    val controlsDrawShift = if (shownCoverStyle == PlayerCoverStyle.EDGE_TO_EDGE) ControlsDrawShift else 0.dp
    var backgroundStyle by rememberEnumPreference(
        PlayerBackgroundStyleKey,
        PlayerBackgroundStyle.COVER_GRADIENT,
    )
    // a style stored by the ones these replaced lands on its successor
    val (storedPlayerStyle, onStoredPlayerStyle) = rememberPreference(com.musplay.app.constants.PlayerStyleKey, "")
    LaunchedEffect(storedPlayerStyle) {
        com.musplay.app.constants.PlayerStyle.fromStored(storedPlayerStyle)?.let { onStoredPlayerStyle(it.name) }
    }

    var coverArtRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var controlsRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var bottomCardRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    val studioEngaged by remember { derivedStateOf { studio.engaged } }
    LaunchedEffect(studioEngaged) {
        if (studioEngaged) transition.close()
    }
    val playerStyles = remember { com.musplay.app.constants.PlayerStyle.entries.toList() }
    val currentPlayerStyleForStudio by androidx.compose.runtime.rememberUpdatedState(playerStyle)
    val openStudio: () -> Unit = remember(studio) {
        {
            showActionMenu = false
            showExpandMenu = false
            studio.open(playerStyles.indexOf(currentPlayerStyleForStudio).coerceAtLeast(0))
        }
    }
    // a hold opens it with a buzz rising from soft to strong, the selector arriving on the peak
    val studioContext = androidx.compose.ui.platform.LocalContext.current
    val studioScope = androidx.compose.runtime.rememberCoroutineScope()
    val holdToStudio: () -> Unit = remember(openStudio, studioContext, studioScope) {
        var pending: kotlinx.coroutines.Job? = null
        {
            if (!studio.engaged && pending?.isActive != true) {
                studioContext.playHoldRamp()
                pending = studioScope.launch {
                    kotlinx.coroutines.delay(com.musplay.app.ui.player.studio.HoldRampMs)
                    openStudio()
                }
            }
        }
    }
    // "Customize player" in settings: the player opens, then shrinks into the selector
    val studioRequest by playerConnection.studioRequests.collectAsState()
    val handledStudioRequest = remember { androidx.compose.runtime.mutableLongStateOf(playerConnection.studioRequests.value) }
    LaunchedEffect(studioRequest) {
        if (studioRequest == handledStudioRequest.longValue) return@LaunchedEffect
        handledStudioRequest.longValue = studioRequest
        state.expandSoft()
        androidx.compose.runtime.snapshotFlow { state.isExpanded }.first { it }
        kotlinx.coroutines.delay(180)
        openStudio()
    }
    val styleActions = remember(openStudio) {
        com.musplay.app.ui.player.styles.StyleActions(
            onOtherMenu = {
                // the styles that perform the lyrics get the lyrics tools too: provider, translation
                menuFromLyrics = currentPlayerStyleForStudio == PlayerStyle.POSTER ||
                    currentPlayerStyleForStudio == PlayerStyle.LYRICS
                showActionMenu = true
            },
            onEditPlayer = openStudio,
        )
    }

    LaunchedEffect(state.isExpanded) {
        if (!state.isExpanded) {
            studio.reset()

            showActionMenu = false
            menuReveal.floatValue = 0f
            showExpandMenu = false
            likeBurst.clear()
            showSleepTimer = false
            showSongDetails = false
        }
    }

    // A page open when the player closes stays open: next time the player opens, it opens on that
    // page - the same way it opens on the player itself when that's where it was left. Closing the
    // page as the sheet started to collapse also ran the page's own shrink-into-the-card animation
    // on top of the sheet's, which is what made closing from lyrics or the queue so heavy. Only
    // dismissing the player (nothing left to play) forgets the page.
    LaunchedEffect(state.isDismissed) {
        if (state.isDismissed) transition.reset()
    }

    // Any part of the expanded player on screen. The pages and the seek bar's clock only run while
    // it is: collapsed, the pages are unmounted (still remembered) and the seek bar holds still.
    val sheetShowing by remember(state) { derivedStateOf { state.progress > 0f } }

    val isSheetInTransition by remember(state) {
        derivedStateOf { !state.isExpanded && !state.isCollapsed && !state.isDismissed }
    }

    var songInfoSourceRect by remember {
        mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    }
    var chevronButtonRect by remember {
        mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    }
    // Kept apart from chevronButtonRect: both chevrons used to write that one, so the expand
    // menu anchored to whichever laid out last - often the other, stale one - and jumped.
    var lyricsChevronRect by remember {
        mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    }
    val statusBarTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current

    var controlsInset by remember { mutableStateOf(220.dp) }

    /**
     * Distance from the top of the seek bar to the bottom of the screen.
     *
     * The lyrics list ends at the seek bar, so no line is ever drawn behind the bar or its
     * timestamps; the list's own bottom fade finishes just above it.
     */
    var lyricsBottomInset by remember { mutableStateOf(120.dp) }

    // Where the seek bar's top is on screen (root, px; NaN until measured): the vertical swipe
    // handler's line between the player above and the strip of controls below it. Held in a plain
    // array, not state - it is only read when a swipe begins.
    val seekBarTop = remember { floatArrayOf(Float.NaN) }

    var lyricsImmersive by remember { mutableStateOf(false) }
    // The queue hides the controls the same way when scrolled, each page with its own flag so one
    // page's state never hides the controls on the other.
    var queueImmersive by remember { mutableStateOf(false) }
    val controlsHidden by animateFloatAsState(
        targetValue = if (
            (lyricsImmersive && showLyrics) || (queueImmersive && showQueue)
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        label = "lyricsControlsHide",
    )

    // The header morph: cover and title moving up into the page header. Lyrics and the queue share
    // it, so switching straight from one page to the other leaves the header where it is. Clamped
    // here: the open spring's little landing bounce belongs to the page, not to the cover.
    val lyricsProgress by remember { derivedStateOf { transition.headerProgress.coerceIn(0f, 1f) } }

    // Each page's own content. Opened from the player it is the header's progress (one motion);
    // switching pages crossfades the content under a header that stays put.
    val lyricsPageProgress by remember { derivedStateOf { transition.lyricsProgress.coerceIn(0f, 1f) } }
    val queuePageProgress by remember { derivedStateOf { transition.queueProgress.coerceIn(0f, 1f) } }
    // the Turntable's pages: lyrics or queue, whichever is opening
    val turntablePageProgress by remember { derivedStateOf { maxOf(lyricsPageProgress, queuePageProgress) } }
    val turntablePageUp by remember { derivedStateOf { turntablePageProgress >= 0.5f } }
    // Also while the page is being built, just before it grows (PlayerPageTransition.preparing)
    val lyricsPageMounted by remember {
        derivedStateOf { lyricsPageProgress > 0.001f || transition.preparing == PlayerPage.LYRICS }
    }
    val queuePageMounted by remember {
        derivedStateOf { queuePageProgress > 0.001f || transition.preparing == PlayerPage.QUEUE }
    }

    val lyricsMounted by remember { derivedStateOf { lyricsProgress > 0.001f } }

    // Counted from the moment the page is being built for its open: it then mounts already in its
    // morphing state, instead of every row recomposing as the flag flips in the open's first frame
    val lyricsMorphing by remember {
        derivedStateOf {
            (lyricsProgress > 0.001f && lyricsProgress < 0.999f) || transition.preparing == PlayerPage.LYRICS
        }
    }
    val lyricsButtonMounted by remember { derivedStateOf { lyricsProgress < 0.999f } }
    // Also built just before a page closes, while the deck is still hidden (preparingClose), and
    // kept from then on: with no page open the controls are showing or on their way back
    val deckMounted by remember {
        derivedStateOf { controlsHidden < 0.99f || transition.preparingClose || transition.page == PlayerPage.NONE }
    }
    androidx.compose.runtime.SideEffect {
        transition.closeNeedsPreparing = { showPlayerBottomCard && controlsHidden >= 0.99f }
    }
    val controlsInert by remember { derivedStateOf { controlsHidden > 0.99f } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val studioStatusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        androidx.compose.runtime.SideEffect {
            studioParts.width = with(density) { screenWidth.toPx() }
            studioParts.height = with(density) { screenHeight.toPx() }
            studioParts.statusTop = with(density) { studioStatusTop.toPx() }
            studioParts.density = density
            studioParts.fullCover = shownCoverStyle == PlayerCoverStyle.EDGE_TO_EDGE
        }
        val progressProvider = remember(state) { { state.progress.coerceIn(0f, 1f) } }
        val horizontalOffsetProvider = remember(state) { { state.horizontalOffset } }

        // Vertical swipes on the expanded player, offered here before the sheet acts on them:
        //  - On the plain player, a swipe up anywhere (the card keeps its own swipe, the flip) pulls
        //    the card's page up out of it, the page following the finger. One finger-length to the
        //    top of the card is the whole way, so the card's edge stays under the finger.
        //  - From the seek bar down to the card - the timestamps, the transport row, the card - no
        //    swipe closes the player: that strip has swipes of its own. With a page open (lyrics or
        //    queue), a swipe up there opens the page the card offers and a swipe down goes back
        //    to the page before it (or to the player).
        val screenHeightPx = with(density) { screenHeight.toPx() }
        val currentPlayerStyle by androidx.compose.runtime.rememberUpdatedState(playerStyle)
        val currentCardShown by androidx.compose.runtime.rememberUpdatedState(showPlayerBottomCard)
        val swipe = remember(transition, deck, morph, screenHeightPx, density) {
            val commitPx = with(density) { PageSwipeCommit.toPx() }
            val flingPx = with(density) { PageSwipeFling.toPx() }
            object : ExpandedSwipe {
                // Summed per gesture: one move is too small to tell a vertical swipe from a
                // horizontal one, and the start tells a new gesture from the one being summed.
                private var sumStart = Offset.Unspecified
                private var sum = Offset.Zero
                private var mode = PlayerSwipe.NONE
                private var travelled = 0f

                private fun distance() = (morph.cardRect?.top ?: (screenHeightPx * 0.85f)).coerceAtLeast(1f)

                /** The alternate styles and the editor draw and handle their own screen. */
                private fun owned() = currentPlayerStyle.flagshipFamily && !studio.engaged

                /** [start] (root) is on or below the seek bar, with the controls actually showing. */
                private fun inControlStrip(start: Offset): Boolean {
                    val top = seekBarTop[0]
                    return !top.isNaN() && controlsHidden < 0.5f && start.y >= top
                }

                override fun onStart(start: Offset, drag: Offset): Boolean {
                    if (!owned()) return false
                    if (start != sumStart) {
                        sumStart = start
                        sum = Offset.Zero
                    }
                    sum += drag
                    if (abs(sum.y) <= abs(sum.x)) return false

                    val up = sum.y < 0f
                    mode = when {
                        transition.page == PlayerPage.NONE ->
                            if (up && transition.beginDrag(if (currentCardShown) deck.focusedPage else PlayerPage.LYRICS)) {
                                PlayerSwipe.PULL
                            } else {
                                PlayerSwipe.NONE
                            }
                        // down closes the player, wherever it starts (canCollapseFrom)
                        inControlStrip(start) -> if (up) PlayerSwipe.OPEN_OTHER else PlayerSwipe.NONE
                        else -> PlayerSwipe.NONE
                    }
                    if (mode == PlayerSwipe.NONE) return false
                    // What was summed before this move; the move itself arrives through onDrag.
                    travelled = sum.y - drag.y
                    return true
                }

                override fun onDrag(dy: Float) {
                    if (mode == PlayerSwipe.PULL) transition.dragBy(-dy / distance()) else travelled += dy
                }

                override fun onEnd(velocityY: Float) {
                    val finished = mode
                    mode = PlayerSwipe.NONE
                    sumStart = Offset.Unspecified
                    sum = Offset.Zero
                    when (finished) {
                        PlayerSwipe.PULL -> transition.release(-velocityY / distance())
                        // 0 is a cancelled gesture: nothing to commit.
                        PlayerSwipe.OPEN_OTHER ->
                            if (velocityY != 0f && (travelled < -commitPx || velocityY < -flingPx)) {
                                transition.open(
                                    if (transition.page == PlayerPage.LYRICS) PlayerPage.QUEUE else PlayerPage.LYRICS
                                )
                            }
                        PlayerSwipe.BACK ->
                            if (velocityY != 0f && (travelled > commitPx || velocityY > flingPx)) transition.back()
                        PlayerSwipe.NONE -> Unit
                    }
                }

                // a swipe down closes the player from anywhere on it - the cover, the controls, the
                // card - unless a page's own swipe took it first (onStart, above)
                override fun canCollapseFrom(start: Offset): Boolean = true
            }
        }

        // what the round buttons do: the side button by the chevron and the chevron's menu, each
        // set to one of these in the editor
        fun menuActionActive(action: com.musplay.app.ui.player.studio.PlayerMenuAction): Boolean = when (action) {
            com.musplay.app.ui.player.studio.PlayerMenuAction.LIKE -> trackInfo.liked
            com.musplay.app.ui.player.studio.PlayerMenuAction.SLEEP_TIMER -> playerConnection.service.sleepTimer.isActive
            com.musplay.app.ui.player.studio.PlayerMenuAction.SEE_VIDEO -> seeVideo
            com.musplay.app.ui.player.studio.PlayerMenuAction.REPEAT -> transportState.repeatMode != Player.REPEAT_MODE_OFF
            com.musplay.app.ui.player.studio.PlayerMenuAction.SHUFFLE -> transportState.shuffleModeEnabled
            else -> false
        }
        val onMenuAction: (com.musplay.app.ui.player.studio.PlayerMenuAction) -> Unit = { action ->
            when (action) {
                com.musplay.app.ui.player.studio.PlayerMenuAction.LIKE ->
                    likeBurst.likeFromButton(LikeSlot.PLAYER, trackInfo.liked, playerConnection::toggleLike)
                com.musplay.app.ui.player.studio.PlayerMenuAction.OTHER_MENU -> {
                    // The lyrics tools are in this menu now (no button in the lyrics header).
                    menuFromLyrics = showLyrics
                    showActionMenu = true
                }
                com.musplay.app.ui.player.studio.PlayerMenuAction.SLEEP_TIMER -> showSleepTimer = true
                com.musplay.app.ui.player.studio.PlayerMenuAction.SEE_VIDEO -> seeVideo = !seeVideo
                com.musplay.app.ui.player.studio.PlayerMenuAction.REPEAT -> playerConnection.player.toggleRepeatMode()
                com.musplay.app.ui.player.studio.PlayerMenuAction.SHUFFLE ->
                    playerConnection.player.shuffleModeEnabled = !playerConnection.player.shuffleModeEnabled
                com.musplay.app.ui.player.studio.PlayerMenuAction.LYRICS -> transition.open(PlayerPage.LYRICS)
                com.musplay.app.ui.player.studio.PlayerMenuAction.QUEUE -> transition.open(PlayerPage.QUEUE)
                com.musplay.app.ui.player.studio.PlayerMenuAction.SPEED -> showPlaybackSpeed = true
                com.musplay.app.ui.player.studio.PlayerMenuAction.OUTPUT -> showDeviceOutput = true
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // behind the player while it's shrunk into a card or a box, too
                .graphicsLayer { alpha = if (studio.engaged) 1f else menuReveal.floatValue }
                .background(Color.Black)
        )

        BottomSheet(
            state = state,

            modifier = modifier
                .graphicsLayer {

                    val r = menuReveal.floatValue
                    if (r > 0.001f) {
                        val scale = 1f - 0.08f * r
                        scaleX = scale
                        scaleY = scale
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp * r)
                        clip = true
                    }
                }
                .then(Modifier.studioSheetLayer(studio))
                .then(Modifier.studioCaptureSource(studioCapture))
                // holding anywhere on the open flagship opens the studio, not just the cover and card.
                // Only attached while open: this box is full screen and sits above the app, so a
                // pointer handler on it took every touch meant for the app under the mini player
                .then(
                    if (!state.isExpanded) Modifier else Modifier.holdAnywhere(
                        enabled = {
                            state.isExpanded && currentPlayerStyle.flagshipFamily && !studio.engaged &&
                                transition.page == PlayerPage.NONE && !showExpandMenu && !showActionMenu
                        },
                        onHold = holdToStudio,
                    )
                ),

            isExpandable = !studioEngaged,
            isPillTransition = true,
            expandedSwipe = swipe,
            pureBlack = pureBlack,
            background = {},
            sharedContent = {
                MorphingCover(
                    progressProvider = progressProvider,
                    horizontalOffsetProvider = horizontalOffsetProvider,
                    trackInfo = trackInfo,
                    isPlaying = transportState.isPlaying,
                    playbackState = transportState.playbackState,
                    maxWidth = screenWidth,
                    maxHeight = screenHeight,
                    collapsedBound = state.collapsedBound,
                    pureBlack = pureBlack,
                    glassState = morphingGlassState,
                    lyricsProgressProvider = { lyricsProgress },
                    forceVideoBackground = seeVideo,
                    modifier = Modifier.fillMaxSize(),
                    coverStyle = shownCoverStyle,
                    backgroundStyle = backgroundStyle,
                    editMode = studioEngaged,
                    // the editor's top bar frosts what's under it, so the glass keeps capturing there
                    glassActiveInEdit = { studio.stage >= StudioStage.EDIT && studio.selector.value < 0.01f },
                    // another style draws the open player itself: none of the flagship runs behind it
                    alternateStyle = !playerStyle.flagshipFamily,
                    onLongPressCover = holdToStudio,
                    studioArtModifier = if (playerStyle.flagshipFamily) {
                        Modifier
                            .then(Modifier.studioAnchor(studioAnchors) { it.cover })
                            .then(Modifier.studioCoverLayer(studioParts))
                    } else Modifier,
                    onDoubleTapCover = { position ->
                        if (!studioEngaged) {
                            likeBurst.likeFromDoubleTap(position, trackInfo.liked, playerConnection::toggleLike)
                        }
                    },
                    onArtBoundsChanged = { coverArtRect = it },
                )
                if (playerStyle.flagshipFamily) MorphingSongInfo(
                    trackInfo = trackInfo,
                    lyricsProgressProvider = { lyricsProgress },
                    sourceRectProvider = { songInfoSourceRect },
                    sheetProgressProvider = progressProvider,

                    targetY = statusBarTopInset + 38.dp,
                )
                // Above the title after a like, moving with it into the lyrics/queue header.
                if (playerStyle.flagshipFamily) LikedLabel(
                    likeCount = likeBurst.likeCount,
                    mixing = playerConnection.isCrossmixing.collectAsState().value,
                    sourceRectProvider = { songInfoSourceRect },
                    headerTitleX = LyricsHeaderArtX + LyricsHeaderArtSize + 18.dp,
                    headerTitleY = statusBarTopInset + 38.dp,
                    lyricsProgressProvider = { lyricsProgress },
                    sheetProgressProvider = progressProvider,
                )
            },
            onDismiss = {
                playerConnection.service.clearAutomix()
                playerConnection.player.stop()
                playerConnection.player.clearMediaItems()
            },
            collapsedContent = {},
        ) {

            // Alternate styles replace the player outright rather than drawing over it, so none
            // of the default composition - blur, morphing cover, controls - runs while one is
            // active.
            if (!playerStyle.flagshipFamily) {
                // The alternate styles draw their own full screen, which meant they also swallowed
                // the player's own gestures - there was no way to close them or reach the editor.
                // Both live here so every style gets them for free.
                // only on the open player: collapsed into the pill, these swallowed the swipe up and
                // the tap that open it
                val styleOpen = state.isExpanded
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(styleOpen) {
                            if (!styleOpen) return@pointerInput
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, amount ->
                                    // a swipe down anywhere on a style closes the player, the same
                                    // as on the flagship; the styles' own swipes are sideways
                                    if (amount > 6f) state.collapseSoft()
                                },
                            )
                        }
                        .pointerInput(styleOpen) {
                            if (!styleOpen) return@pointerInput
                            detectTapGestures(
                                onLongPress = { holdToStudio() },
                            )
                        },
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.musplay.app.ui.player.styles.LocalStyleInteractive provides styleOpen,
                    ) {
                        com.musplay.app.ui.player.styles.AlternatePlayerStyle(
                            style = playerStyle,
                            actions = styleActions,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                return@BottomSheet
            }

            // what the studio measures the flagship's parts against: the sheet itself, so the
            // sheet shrinking into a selector card doesn't move them
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .onGloballyPositioned { studioAnchors.root = it },
            )

            // the Turntable's record isn't a cover to soften into the backdrop
            if (!isTurntable) SeamBlur(
                glassState = morphingGlassState,
                progressProvider = progressProvider,
                trackInfo = trackInfo,
                maxHeight = screenHeight,

                fadeProvider = { 1f - lyricsProgress },
            )

            // The card growing into the page, under it: its glass widens to the full screen while
            // the player behind dims a touch. Only there while a page is growing or shrinking.
            ExpandingCardSurface(transition = transition, morph = morph)

            // Unmounted while the player is collapsed - even though the page itself is remembered -
            // because its lines keep per-frame clocks of their own that would otherwise tick away
            // behind Home. It mounts again with the first frame of the next open. (Keeping it
            // composed but paused measured worse: waking a page whose position had jumped cost
            // more in one frame than building it fresh.)
            if (lyricsPageMounted && sheetShowing) {
                LyricsScreen(
                    onClose = { transition.close() },
                    screenHeight = screenHeight,

                    contentBottomInset = lyricsBottomInset,
                    onImmersiveChange = { lyricsImmersive = it },
                    isSheetDragging = isSheetInTransition,
                    isMorphing = lyricsMorphing,
                    enterProgressProvider = { transition.cardMorphProgress(PlayerPage.LYRICS) },
                    morph = morph,
                    modifier = Modifier
                        .fillMaxSize()
                        .pageTransitionLayer(PlayerPage.LYRICS, transition, morph) { lyricsPageProgress },
                )
            }

            if (queuePageMounted && sheetShowing) {
                QueueScreen(
                    onClose = { transition.close() },
                    // Fades into the seek bar like the lyrics; rows run under the chevron/shuffle/
                    // autoplay column, which reaches this far above the bar.
                    contentBottomInset = lyricsBottomInset,
                    controlsClearance = QueueButtonColumnHeight + 12.dp - SeekBarLift,
                    likeBurst = likeBurst,
                    onImmersiveChange = { queueImmersive = it },
                    enterProgressProvider = { transition.cardMorphProgress(PlayerPage.QUEUE) },
                    morph = morph,
                    modifier = Modifier
                        .fillMaxSize()
                        .pageTransitionLayer(PlayerPage.QUEUE, transition, morph) { queuePageProgress },
                )
            }

            if (showPlayerBottomCard && deckMounted) {
                PlayerBottomCardStack(
                    glassState = morphingGlassState,
                    progressProvider = progressProvider,
                    deck = deck,
                    transition = transition,
                    morph = morph,
                    onOpen = { transition.open(it) },
                    onLongPress = holdToStudio,

                    modifier = Modifier
                        .align(Alignment.BottomCenter)

                        .onGloballyPositioned { bottomCardRect = it.boundsInRoot() }
                        .padding(horizontal = 26.dp)
                        .then(Modifier.studioAnchor(studioAnchors) { it.card })
                        .then(Modifier.studioCardLayer(studioParts))

                        .graphicsLayer {
                            alpha = 1f - controlsHidden
                            translationY = controlsHidden * size.height * 0.75f
                        }
                )
            }

            if (lyricsButtonMounted && !studioEngaged) {
                // Removed as per request
            }

            // The transport row sits centred between the seek bar and the card. The seek bar keeps
            // its place; only the gap is split evenly now - it used to be 30dp above the buttons
            // and about 70dp below, which put them visibly high.
            //
            // Measured against where the seek bar is DRAWN: with the edge-to-edge cover the title
            // and bar are drawn controlsDrawShift higher than they're laid out, and leaving that
            // out centred the buttons on the bar's layout spot - 64dp too low under the full cover.
            // The navigation bar is only read when there's no card to sit above: read always, it
            // recomposed the whole player through the bar's hide/show animation at the end of every
            // open and the start of every close, for a value the card layout never uses.
            val belowControls = if (showPlayerBottomCard) {
                PlayerDeckHeight
            } else {
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp
            }
            val transportGap = (screenHeight * 0.19f - 37.dp - belowControls + TransportGapAboveRow + controlsDrawShift) / 2f
            // What's left of the gap above once the bar's own lift and the draw shift are counted.
            val transportSpacer = (transportGap - SeekBarLift - controlsDrawShift).coerceAtLeast(0.dp)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = belowControls + transportGap.coerceAtLeast(0.dp))
                    .graphicsLayer {
                        // the Turntable shows its own controls; these (with the seek bar) come up
                        // only as the lyrics or the queue open, which run off the seek bar
                        alpha = (1f - controlsHidden) * (if (isTurntable) turntablePageProgress else 1f)

                        translationY = controlsHidden * size.height * 0.55f
                    }

                    .then(
                        if (controlsInert || (isTurntable && !turntablePageUp)) {
                            Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes
                                            .forEach { it.consume() }
                                    }
                                }
                            }
                        } else Modifier
                    )

                    .onGloballyPositioned { coords ->
                        val parentHeight = coords.parentLayoutCoordinates?.size?.height ?: return@onGloballyPositioned
                        val topY = coords.positionInParent().y
                        val insetPx = (parentHeight - topY).coerceAtLeast(0f)
                        val inset = with(density) { insetPx.toDp() }
                        if ((inset - controlsInset).value.absoluteValue > 0.5f) {
                            controlsInset = inset
                        }

                        val bounds = coords.boundsInRoot()
                        controlsRect = androidx.compose.ui.geometry.Rect(
                            left = bounds.left,
                            top = bounds.top - with(density) { controlsDrawShift.toPx() },
                            right = bounds.right,
                            bottom = bounds.bottom,
                        )
                    }
            ) {
                Column(modifier = Modifier.offset(y = -controlsDrawShift)) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .studioAnchor(studioAnchors) { it.songInfo }
                            .studioMiddleLayer(studioParts) { it.songInfo }
                            .graphicsLayer {
                                alpha = 1f - lyricsProgress
                                // Drifts up as it hands its title to the header.
                                translationY = -lyricsProgress * TitleRowDrift.toPx()
                            }
                    ) {
                        SongInfoRow(
                            // Only while the sheet is up: during a slide the title's root position
                            // moves every frame, and the header morph that reads it recomposed with
                            // it. Its place on the sheet itself doesn't change.
                            onTitlePositioned = { if (state.isExpanded && !isTurntable) songInfoSourceRect = it },
                            onChevronPositioned = { if (!isTurntable) chevronButtonRect = it },
                            isExpandMenuOpen = showExpandMenu,
                            hideChevron = lyricsMounted,
                            onToggleExpandMenu = { showExpandMenu = !showExpandMenu },
                            likeBurst = likeBurst,
                            sideAction = menuLayout.side,
                            sideActive = menuActionActive(menuLayout.side),
                            onSideAction = onMenuAction,
                            // measured only on the plain player: in the editor they're moving with it
                            onSidePlaced = { if (!studio.editing) studioAnchors.capture(studioAnchors.sideButton, it) },
                            onChevronPlaced = { if (!studio.editing) studioAnchors.capture(studioAnchors.chevron, it) },
                            sideModifier = Modifier.studioPiece(studioPieces, com.musplay.app.ui.player.studio.StudioPieces.SIDE),
                            chevronModifier = Modifier.studioPiece(studioPieces, com.musplay.app.ui.player.studio.StudioPieces.CHEVRON),
                            onSongInfoClick = { if (!studioEngaged) showSongDetails = true },
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Chevron + like live here while the lyrics are up: the song info row they
                    // normally sit in is faded out, so they stack above the progress bar instead.
                    // The queue page swaps like (moved to its header) for shuffle and autoplay.
                    // Height tracks the transitions so the controls don't jump when a page opens.
                    if (lyricsMounted) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PlayerHorizontalPadding)
                                // Read while measuring, not composing: the slot opens with the
                                // transition without recomposing the player on every frame.
                                .bottomAnchoredSlot(
                                    height = { (75.dp + QueueButtonExtraHeight * queuePageProgress) * lyricsProgress },
                                    bottomGap = { 12.dp * lyricsProgress },
                                ),
                            horizontalAlignment = Alignment.End,
                        ) {
                            // The same chevron as in the song info row, not a second one fading
                            // in: it glides from that chevron's spot to this one with the lyrics
                            // transition (the row's own chevron is hidden while this is mounted,
                            // so there's never two of them on screen). Before, this one sat still
                            // at its final spot while the other faded out, which read as a jump.
                            PressScaleActionButton(
                                icon = R.drawable.expand_less,
                                tint = Color.White.copy(alpha = 0.90f),
                                containerColor = Color.White.copy(alpha = 0.15f),
                                onClick = { showExpandMenu = !showExpandMenu },
                                hasShadow = false,
                                modifier = Modifier
                                    .onGloballyPositioned { coords ->
                                        lyricsChevronRect = coords.boundsInRoot()
                                    }
                                    .graphicsLayer {
                                        val from = chevronButtonRect
                                        val to = lyricsChevronRect
                                        if (from != null && to != null) {
                                            val k = 1f - lyricsProgress
                                            translationX = (from.center.x - to.center.x) * k
                                            translationY = (from.center.y - to.center.y) * k
                                        } else {
                                            alpha = lyricsProgress
                                        }
                                        // The overlay draws its own (rotated) chevron here while open.
                                        if (showExpandMenu) alpha = 0f
                                    }
                            )
                            Spacer(modifier = Modifier.height(7.dp))
                            // Like (lyrics) and shuffle (queue) share a slot, crossfading when the
                            // page switches. Each is only composed while its page is, so an
                            // invisible one never takes the other's taps.
                            Box {
                                if (lyricsPageMounted) {
                                    PressScaleActionButton(
                                        icon = if (trackInfo.liked) R.drawable.ic_untitled_heart else R.drawable.ic_untitled_heart_unfill,
                                        tint = if (trackInfo.liked) Color.White else Color.White.copy(alpha = 0.85f),
                                        containerColor = if (trackInfo.liked) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.15f),
                                        onClick = {
                                            likeBurst.likeFromButton(LikeSlot.LYRICS, trackInfo.liked, playerConnection::toggleLike)
                                        },
                                        hasShadow = false,
                                        modifier = Modifier
                                            .likeButtonTarget(likeBurst, LikeSlot.LYRICS)
                                            .graphicsLayer { alpha = lyricsPageProgress },
                                    )
                                }
                                if (queuePageMounted) {
                                    val shuffleOn = transportState.shuffleModeEnabled
                                    PressScaleActionButton(
                                        icon = R.drawable.shuffle,
                                        tint = if (shuffleOn) Color.White else Color.White.copy(alpha = 0.85f),
                                        containerColor = if (shuffleOn) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.15f),
                                        onClick = { playerConnection.player.shuffleModeEnabled = !shuffleOn },
                                        hasShadow = false,
                                        modifier = Modifier.graphicsLayer { alpha = queuePageProgress },
                                    )
                                }
                            }
                            // Autoplay grows in underneath: the slot opens from nothing, so shuffle
                            // rises off it rather than everything jumping when it mounts.
                            if (queuePageMounted) {
                                Box(
                                    contentAlignment = Alignment.BottomEnd,
                                    modifier = Modifier.bottomAnchoredSlot(
                                        height = { QueueButtonExtraHeight * queuePageProgress },
                                    ),
                                ) {
                                    PressScaleActionButton(
                                        icon = R.drawable.all_inclusive,
                                        tint = if (infiniteQueue) Color.White else Color.White.copy(alpha = 0.85f),
                                        containerColor = if (infiniteQueue) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.15f),
                                        onClick = { playerConnection.setInfiniteQueueEnabled(!infiniteQueue) },
                                        hasShadow = false,
                                        modifier = Modifier.graphicsLayer { alpha = queuePageProgress },
                                    )
                                }
                            }
                        }
                    }

                    // Column, not Box: PlayerProgressSlider emits the track and the timestamp
                    // row as two siblings, so a Box would stack the timestamps on top of the
                    // seek bar instead of below it.
                    Column(
                        modifier = Modifier
                            .offset(y = -SeekBarLift)
                            .onGloballyPositioned { coords ->
                                // Measured to the TOP of the seek bar: the lyrics list ends there and
                                // its fade finishes above it. Measured to the bottom, lines kept
                                // scrolling behind the bar and the timestamps.
                                //
                                // Only with the sheet up and the controls in place. In root
                                // coordinates this moved on every frame of the sheet's slide (and of
                                // the controls hiding), and the pages that read it recomposed - and
                                // re-laid out their lists - frame by frame for it.
                                if (!state.isExpanded || controlsHidden > 0.001f) return@onGloballyPositioned
                                val rootHeight = coords.findRootCoordinates().size.height
                                val topY = coords.positionInRoot().y
                                seekBarTop[0] = topY
                                val insetPx = (rootHeight - topY).coerceAtLeast(0f)
                                val inset = with(density) { insetPx.toDp() }
                                if ((inset - lyricsBottomInset).value.absoluteValue > 0.5f) {
                                    lyricsBottomInset = inset
                                }
                            }
                            .studioAnchor(studioAnchors) { it.slider }
                            .studioMiddleLayer(studioParts) { it.slider }
                    ) {
                        PlayerProgressSlider(
                            active = sheetShowing,
                            onSeekBarPlaced = { if (!studio.editing) studioAnchors.capture(studioAnchors.seekBar, it) },
                            onEndTimePlaced = { if (!studio.editing) studioAnchors.capture(studioAnchors.endTime, it) },
                            seekBarModifier = Modifier.studioPiece(studioPieces, com.musplay.app.ui.player.studio.StudioPieces.SEEK_BAR),
                            endTimeModifier = Modifier.studioPiece(studioPieces, com.musplay.app.ui.player.studio.StudioPieces.END_TIME),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(transportSpacer))

                PlayerTransportRow(
                    modifier = Modifier
                        .studioAnchor(studioAnchors) { it.transport }
                        .studioTransportLayer(studioParts)
                )
            }

            // The Turntable's own controls, as its frame lays them out, above the card. They give
            // way to the flagship's (with the seek bar) while a page is up.
            if (isTurntable) {
                TurntableControls(
                    isExpandMenuOpen = showExpandMenu,
                    onToggleExpandMenu = { showExpandMenu = !showExpandMenu },
                    hideChevron = lyricsMounted,
                    onTitlePositioned = { if (state.isExpanded) songInfoSourceRect = it },
                    onChevronPositioned = { chevronButtonRect = it },
                    likeBurst = likeBurst,
                    onSongInfoClick = { if (!studioEngaged) showSongDetails = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = belowControls + TurntableControlsLift)
                        .graphicsLayer {
                            alpha = (1f - controlsHidden) * (1f - turntablePageProgress)
                            translationY = controlsHidden * size.height * 0.55f
                        }
                        .then(
                            if (controlsInert || turntablePageUp) Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            } else Modifier
                        ),
                )
            }

            // the editor's tiles: inside the sheet, so they shrink and grow with it
            EditTiles(studioParts)
        }

        // Above the player and its controls, so a heart can fly over everything on its way home.
        LikeBurstOverlay(state = likeBurst)

        ExpandMenuOverlay(
            visible = showExpandMenu,
            onDismiss = { showExpandMenu = false },
            slots = menuLayout.slots,
            isActive = ::menuActionActive,
            onAction = onMenuAction,
            repeatMode = transportState.repeatMode,
            chevronRect = if (showLyrics || showQueue) lyricsChevronRect ?: chevronButtonRect else chevronButtonRect,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            controlsDrawShift = controlsDrawShift,
        )

        // Pulling down at the top of the queue list stretches the list instead of closing the
        // player. (Lyrics keep the pull-to-close.)
        androidx.compose.runtime.DisposableEffect(state, showQueue) {
            state.collapseOnNestedOverscroll = !showQueue
            onDispose { state.collapseOnNestedOverscroll = true }
        }

        if ((showQueue || showLyrics) && state.isExpanded) {
            // Composed only while a page is open on the expanded player, so it registers after -
            // and wins over - the sheet's own back handler: back shrinks the page into its card
            // first, then collapses the player. Not while collapsed: the page is remembered then,
            // and back belongs to the app.
            BackHandler { transition.close() }
        }

        if (showSleepTimer) {
            com.musplay.app.ui.player.menu.SleepTimerSheet(
                onDismiss = { showSleepTimer = false }
            )
        }

        if (showSongDetails) {
            // Opening an artist or the album leaves the player: it closes on its way to the page,
            // the way the song menus' "view artist" does
            com.musplay.app.ui.player.menu.SongDetailsSheet(
                onDismiss = { showSongDetails = false },
                onOpenArtist = { artistId ->
                    showSongDetails = false
                    navController.navigate("artist/$artistId")
                    state.collapseSoft()
                },
                onOpenAlbum = { albumId ->
                    showSongDetails = false
                    navController.navigate("album/$albumId")
                    state.collapseSoft()
                },
            )
        }

        if (showActionMenu) {
            PlayerActionMenu(
                fromLyrics = menuFromLyrics,
                onDismiss = {
                    showActionMenu = false
                    menuReveal.floatValue = 0f
                },
                onEditPlayer = {
                    // Same destination as a long press: the selector, the player shrinking into
                    // its card. Jumping straight into the editor skipped the choice of style.
                    openStudio()
                },
                onReveal = { menuReveal.floatValue = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showPlaybackSpeed) {
            com.musplay.app.ui.player.menu.PlaybackSpeedSheet(onDismiss = { showPlaybackSpeed = false })
        }
        if (showDeviceOutput) {
            com.musplay.app.ui.player.menu.DeviceOutputSheet(onDismiss = { showDeviceOutput = false })
        }

        // the player studio, over the player: the selector, the editor's header and editors, its sheets
        PlayerSelectorOverlay(
            studio = studio,
            styles = playerStyles,
            onSet = { index ->
                val style = playerStyles[index]
                if (style != playerStyle) onPlayerStyleChange(style)
                studio.livePage = index
                studio.go(StudioStage.PLAYER)
            },
            onEdit = { focused ->
                if (playerStyles.getOrNull(focused) == PlayerStyle.TURNTABLE) {
                    // its one edit is the disc label: back to the player, with the label's sheet
                    if (playerStyle != PlayerStyle.TURNTABLE) onPlayerStyleChange(PlayerStyle.TURNTABLE)
                    studio.livePage = focused
                    studio.go(StudioStage.PLAYER)
                    studio.openSheet(com.musplay.app.ui.player.studio.StudioSheet.DISC_LABEL)
                } else {
                    val index = playerStyles.indexOf(PlayerStyle.DEFAULT)
                    if (playerStyle != PlayerStyle.DEFAULT) onPlayerStyleChange(PlayerStyle.DEFAULT)
                    studio.livePage = index
                    studio.go(StudioStage.EDIT)
                }
            },
        )
        if (playerStyle.flagshipFamily) {
            val (showBigDiscStyles) = rememberPreference(com.musplay.app.constants.ShowBigDiscStylesKey, defaultValue = false)
            val coverStyles = remember(showBigDiscStyles, coverStyle) {
                PlayerCoverStyle.entries.filter { style ->
                    style != PlayerCoverStyle.TURNTABLE
                }.filter { style ->
                    showBigDiscStyles || !(style == PlayerCoverStyle.DISC_BIG_FULL || style == PlayerCoverStyle.DISC_BIG_LABEL) || style == coverStyle
                }
            }
            val backgroundStyles = remember { PlayerBackgroundStyle.entries.toList() }
            var seekBarStyle by rememberEnumPreference(
                com.musplay.app.constants.SeekBarStyleKey,
                com.musplay.app.ui.component.SeekBarStyle.DEFAULT,
            )
            var buttonStyle by rememberEnumPreference(
                com.musplay.app.constants.ButtonStyleKey,
                com.musplay.app.ui.component.ButtonStyle.DEFAULT,
            )
            val endTimestamp by rememberEnumPreference(
                com.musplay.app.constants.PlayerEndTimestampKey,
                com.musplay.app.constants.PlayerEndTimestamp.END_TIME,
            )
            val artworkPalette = com.musplay.app.ui.theme.ArtworkPalette.from(com.musplay.app.LocalArtworkColor.current)
            if (playerStyle == PlayerStyle.DEFAULT) StudioEditOverlay(
                parts = studioParts,
                glassState = morphingGlassState,
                capture = studioCapture,
                coverStyles = coverStyles,
                coverStyle = coverStyle,
                onCoverStyle = { coverStyle = it },
                backgroundStyles = backgroundStyles,
                backgroundStyle = backgroundStyle,
                onBackgroundStyle = { backgroundStyle = it },
                menuLayout = menuLayout,
                seekBarStyle = seekBarStyle,
                endTimestamp = endTimestamp,
                isMenuActionActive = ::menuActionActive,
            )
            StudioSheets(
                studio = studio,
                coverStyle = shownCoverStyle,
                buttonStyle = buttonStyle,
                onButtonStyle = { buttonStyle = it },
                seekBarStyle = seekBarStyle,
                onSeekBarStyle = { seekBarStyle = it },
                menuLayout = menuLayout,
                transportColors = com.musplay.app.ui.component.TransportColors(
                    accent = artworkPalette.accent,
                    onAccent = artworkPalette.onAccent,
                    container = artworkPalette.container,
                    onContainer = artworkPalette.onContainer,
                ),
            )
        }
    }
}

/** Transparent margin around each expand-menu item's blur layer - roughly the max blur radius. */
private val MenuItemBlurRoom = 20.dp

/**
 * Grows the layer that follows by [room] on every side without changing the layout footprint:
 * pair with `.padding(room)` after the layer. Lets a blur render past the content's edges
 * instead of being cut off at them.
 */
private fun Modifier.blurBleedRoom(room: androidx.compose.ui.unit.Dp): Modifier =
    layout { measurable, constraints ->
        val pad = room.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = 2 * pad, vertical = 2 * pad))
        val width = constraints.constrainWidth(placeable.width - 2 * pad)
        val height = constraints.constrainHeight(placeable.height - 2 * pad)
        layout(width, height) { placeable.place(-pad, -pad) }
    }


@Composable
private fun ExpandMenuOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    /** the four actions, top to bottom, as set in the editor */
    slots: List<com.musplay.app.ui.player.studio.PlayerMenuAction>,
    isActive: (com.musplay.app.ui.player.studio.PlayerMenuAction) -> Boolean,
    onAction: (com.musplay.app.ui.player.studio.PlayerMenuAction) -> Unit,
    repeatMode: Int,
    chevronRect: androidx.compose.ui.geometry.Rect?,
    screenWidth: androidx.compose.ui.unit.Dp,
    screenHeight: androidx.compose.ui.unit.Dp,
    controlsDrawShift: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val menuItems = remember(slots) { slots.map { it.label to it.icon } }

    val density = LocalDensity.current
    val itemProgress = remember { List(menuItems.size) { androidx.compose.animation.core.Animatable(0f) } }
    val bgAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    var isRendered by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            isRendered = true
            launch {
                bgAlpha.animateTo(
                    targetValue = 0.62f,
                    animationSpec = tween(durationMillis = 350, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
                )
            }
            itemProgress.forEachIndexed { index, anim ->
                launch {
                    kotlinx.coroutines.delay((menuItems.size - 1 - index) * 40L)
                    anim.animateTo(
                        targetValue = 1f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 0.54f,
                            stiffness = 320f
                        )
                    )
                }
            }
        } else if (isRendered) {
            val bgJob = launch {
                bgAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 260, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
                )
            }
            val itemsJob = launch {
                val exitJobs = itemProgress.mapIndexed { index, anim ->
                    launch {
                        kotlinx.coroutines.delay(index * 25L)
                        anim.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 180, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                        )
                    }
                }
                exitJobs.forEach { it.join() }
            }
            bgJob.join()
            itemsJob.join()
            isRendered = false
        }
    }

    if (isRendered) {
        val chevronBottomDp = if (chevronRect != null) {
            val screenHeightPx = with(density) { screenHeight.toPx() }
            val bottomPx = (screenHeightPx - chevronRect.bottom).coerceAtLeast(0f)
            with(density) { bottomPx.toDp() }
        } else {
            (screenHeight * 0.19f) + 93.dp - controlsDrawShift
        }

        val menuBottomDp = if (chevronRect != null) {
            val screenHeightPx = with(density) { screenHeight.toPx() }
            val topPx = (screenHeightPx - chevronRect.top).coerceAtLeast(0f)
            with(density) { (topPx + 14.dp.toPx()).toDp() }
        } else {
            chevronBottomDp + 48.dp
        }

        // A chevron on the screen's left (the Turntable's) opens the menu the other way round:
        // the buttons up from it on its own line, their names to their right.
        val fromLeft = chevronRect != null && chevronRect.center.x < with(density) { screenWidth.toPx() } / 2f
        val chevronStartDp = with(density) { (chevronRect?.left ?: 0f).toDp() }
        val chevronSizeDp = with(density) { (chevronRect?.width ?: 0f).toDp() }.coerceAtLeast(34.dp)

        // Right edge of the chevron actually tapped, not an assumed padding - the lyrics-page
        // chevron and the song-info one don't share an exact x.
        val chevronEndDp = if (chevronRect != null) {
            val screenWidthPx = with(density) { screenWidth.toPx() }
            with(density) { (screenWidthPx - chevronRect.right).coerceAtLeast(0f).toDp() }
        } else {
            PlayerHorizontalPadding
        }

        val chevronRotation by animateFloatAsState(
            targetValue = if (visible) 180f else 0f,
            label = "overlayChevronRot",
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 380f)
        )
        val chevronScale by animateFloatAsState(
            targetValue = if (visible) 0.86f else 1f,
            label = "overlayChevronScale",
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 380f)
        )
        val overlayChevronBgColor by animateColorAsState(
            targetValue = if (visible) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.15f),
            label = "overlayChevronBgColor",
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        )
        val overlayChevronTint by animateColorAsState(
            targetValue = if (visible) Color.White else Color.White.copy(alpha = 0.90f),
            label = "overlayChevronTint",
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = bgAlpha.value))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                }
        ) {
            // Menu items placed directly ON TOP of the chevron:
            Column(
                modifier = modifier
                    .then(
                        if (fromLeft) {
                            // the 34dp buttons centred over the chevron
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = chevronStartDp + (chevronSizeDp - 34.dp) / 2, bottom = menuBottomDp)
                        } else {
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = chevronEndDp, bottom = menuBottomDp)
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = if (fromLeft) Alignment.Start else Alignment.End
            ) {
                menuItems.forEachIndexed { index, item ->
                    val p = itemProgress[index].value
                    if (p > 0.005f) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                // The blur's layer used to end exactly at the row's edges, with
                                // the button circle and text shadow touching them, and CLAMP
                                // tiling smeared those edge pixels outward - the stray lines at
                                // the sides. Give it room to fade out, and let it fade to clear.
                                .blurBleedRoom(MenuItemBlurRoom)
                                .graphicsLayer {
                                    val progress = p.coerceIn(0f, 1.25f)
                                    alpha = progress.coerceIn(0f, 1f)
                                    translationY = (1f - progress) * 55f
                                    val scale = (0.7f + 0.3f * progress).coerceAtLeast(0.01f)
                                    scaleX = scale
                                    scaleY = scale
                                    val blurPx = (1f - progress.coerceIn(0f, 1f)) * 20f
                                    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        // DECAL only exists on API 31+; keep the reference inside
                                        // the check so older devices never resolve it.
                                        BlurEffectCache.get(blurPx, android.graphics.Shader.TileMode.DECAL)
                                    } else {
                                        null
                                    }
                                }
                                .padding(MenuItemBlurRoom)
                        ) {
                            val label: @Composable () -> Unit = {
                            androidx.compose.material3.Text(
                                text = item.first,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black.copy(alpha = 0.75f),
                                        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                        blurRadius = 8f
                                    )
                                ),
                                modifier = Modifier.padding(start = if (fromLeft) 4.dp else 0.dp, end = if (fromLeft) 0.dp else 4.dp)
                            )
                            }

                            val action = slots[index]
                            val isToggled = isActive(action)
                            val iconTint = if (isToggled) Color.White else Color.White.copy(alpha = 0.85f)
                            val containerColor = if (isToggled) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.16f)

                            if (!fromLeft) label()
                            PressScaleActionButton(
                                icon = item.second,
                                tint = iconTint,
                                containerColor = containerColor,
                                hasShadow = false,
                                badgeText = if (action == com.musplay.app.ui.player.studio.PlayerMenuAction.REPEAT &&
                                    repeatMode == Player.REPEAT_MODE_ONE
                                ) "1" else null,
                                onClick = {
                                    when (action) {
                                        // toggles that stay open, so the change shows on the button
                                        com.musplay.app.ui.player.studio.PlayerMenuAction.REPEAT,
                                        com.musplay.app.ui.player.studio.PlayerMenuAction.SHUFFLE,
                                        com.musplay.app.ui.player.studio.PlayerMenuAction.LIKE -> onAction(action)
                                        else -> { onDismiss(); onAction(action) }
                                    }
                                }
                            )
                            if (fromLeft) label()
                        }
                    }
                }
            }

            // Chevron rendered ON TOP of the dim background, directly tappable, zoomed out & rotated 180°:
            Box(
                modifier = Modifier
                    .then(
                        if (fromLeft) {
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = chevronStartDp, bottom = chevronBottomDp)
                        } else {
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = chevronEndDp, bottom = chevronBottomDp)
                        }
                    )
            ) {
                PressScaleActionButton(
                    icon = R.drawable.expand_less,
                    tint = overlayChevronTint,
                    containerColor = overlayChevronBgColor,
                    onClick = onDismiss,
                    hasShadow = false,
                    // over the Turntable's chevron exactly, its own size
                    size = if (fromLeft) chevronSizeDp else 34.dp,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = chevronRotation
                        scaleX = chevronScale
                        scaleY = chevronScale
                    }
                )
            }
        }
    }
}
