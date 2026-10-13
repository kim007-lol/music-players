// DiscCarousel.kt

package com.example.musicplaying.ui.player.customize

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.media3.common.Player
import com.example.musicplaying.constants.PlayerCoverStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/*
 * The records in a row: the one playing, the song before's to its left and the song after's to its
 * right, each just off screen. A swipe slides the row under the finger - the next record comes in
 * as this one goes - and a skip from anywhere (the buttons, the song ending, the queue) runs it
 * along the same way.
 *
 * Where the row is counts in steps, one per song moved through, and never resets. So a song
 * changing in the middle of a slide changes nothing on screen: the record that was coming in is
 * the same record (the same composition, picture already loaded) now in the middle, and the row
 * just carries on to it. Nothing is built, loaded or swapped in the frame a song changes.
 */

/** a song's record in the row, [step] its place */
@Immutable
internal data class DiscSlot(
    val uid: Int,
    val mediaId: String,
    val artworkUrl: String?,
    val title: String,
    val artist: String,
    val album: String,
    val step: Int,
    val current: Boolean = false,
)

/** how the row settles: no bounce, quick to start, a flick's speed carried in */
internal val DiscSettleSpring = spring<Float>(dampingRatio = 0.92f, stiffness = 190f)

@Stable
class DiscCarousel {
    /** where the row is, in steps; at rest it's [step] */
    internal val position = Animatable(0f)

    /** the playing song's step: one more for each song forward, one less for each back */
    internal var step by mutableIntStateOf(0)
        private set

    /** a song left for one that wasn't beside it (a jump in the queue): it slides out on its own */
    internal var outgoing by mutableStateOf<DiscSlot?>(null)

    /** set by the stack, for how far apart the records sit */
    internal var style: PlayerCoverStyle = PlayerCoverStyle.DISC_SMALL_FULL

    private var currentSlot: DiscSlot? = null
    private var previousUid: Int? = null
    private var nextUid: Int? = null
    private var queueIndex = -1

    /** 0 at rest, 1 a whole record along: lifts the arm while the records move under it */
    val away: Float get() = abs(position.value - step)

    /**
     * The queue as composition sees it: when the playing song has changed, the step moves - by
     * one toward wherever the new song was in the row, or by one either way for a jump.
     * Idempotent, so composing twice changes nothing.
     */
    internal fun onQueue(current: DiscSlot, index: Int, previous: Int?, next: Int?) {
        val last = currentSlot
        if (last != null && last.uid != current.uid) {
            val delta = when (current.uid) {
                nextUid -> 1
                previousUid -> -1
                else -> if (index < queueIndex) -1 else 1
            }
            // a jump: the record that was playing isn't beside the new one, so it goes on its own
            outgoing = if (current.uid != nextUid && current.uid != previousUid) last.copy(current = false) else null
            step += delta
        }
        currentSlot = current.copy(step = step)
        queueIndex = index
        previousUid = previous
        nextUid = next
    }

    /* ---- the swipe, from the cover's gesture ---- */

    private var dragFrom = 0f

    internal fun startDrag(scope: CoroutineScope) {
        dragFrom = position.value
        scope.launch { position.stop() }
    }

    /** the finger [travelledPx] from where it went down; past the queue's ends the row only gives a little */
    internal fun dragTo(scope: CoroutineScope, travelledPx: Float, size: Size, hasPrevious: Boolean, hasNext: Boolean) {
        val spacing = discSpacingPx(style, size)
        val raw = dragFrom - travelledPx / spacing
        val low = step - (if (hasPrevious) 1f else 0f)
        val high = step + (if (hasNext) 1f else 0f)
        val target = when {
            raw < low -> low - (low - raw) * 0.3f
            raw > high -> high + (raw - high) * 0.3f
            else -> raw
        }
        scope.launch { position.snapTo(target) }
    }

    /**
     * The finger lifted, [travelledPx] from where it went down and moving at [velocityPx]: far or
     * fast enough, on to the next record (or back to the last) and the song with it; otherwise
     * back to this one.
     */
    internal fun release(
        scope: CoroutineScope,
        travelledPx: Float,
        velocityPx: Float,
        size: Size,
        flingPx: Float,
        player: Player?,
    ) {
        val spacing = discSpacingPx(style, size)
        val commit = spacing * 0.22f
        val forward = (travelledPx < -commit || velocityPx < -flingPx) && player?.hasNextMediaItem() == true
        val back = !forward && (travelledPx > commit || velocityPx > flingPx) && player?.hasPreviousMediaItem() == true
        val from = step
        val target = from + when {
            forward -> 1
            back -> -1
            else -> 0
        }
        scope.launch {
            position.animateTo(target.toFloat(), DiscSettleSpring, initialVelocity = -velocityPx / spacing)
            // the song didn't change after all: back to the one playing
            if (step != target) position.animateTo(step.toFloat(), DiscSettleSpring)
        }
        if (forward) player?.seekToNext() else if (back) player?.seekToPreviousMediaItem()
    }
}

/**
 * How far apart the records in [style]'s row sit, for a box [size]: enough that, at rest, the ones
 * either side are just past the screen's edges (the box can be inset from them a little).
 */
internal fun discSpacingPx(style: PlayerCoverStyle, size: Size): Float {
    val (center, radius) = discCircleIn(style, size)
    val margin = size.width * 0.08f
    return max(size.width - center.x + radius, center.x + radius) + margin
}
