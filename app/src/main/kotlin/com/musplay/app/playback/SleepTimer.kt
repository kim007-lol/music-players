// SleepTimer.kt

package com.musplay.app.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class SleepTimer(
    private val scope: CoroutineScope,
    var player: Player,
) : Player.Listener {
    private var sleepTimerJob: Job? = null
    var triggerTime by mutableLongStateOf(-1L)
        private set
    var pauseWhenSongEnd by mutableStateOf(false)
        private set
    // MusPlay: end-of-queue — pause only when queue is exhausted, not mid-queue crossfade.
    var pauseWhenQueueEnd by mutableStateOf(false)
        private set
    val isActive: Boolean
        get() = triggerTime != -1L || pauseWhenSongEnd || pauseWhenQueueEnd

    fun start(minute: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (minute == -1) {
            pauseWhenSongEnd = true
            pauseWhenQueueEnd = false
            triggerTime = -1L
        } else if (minute == -2) {
            pauseWhenQueueEnd = true
            pauseWhenSongEnd = false
            triggerTime = -1L
        } else {
            pauseWhenSongEnd = false
            pauseWhenQueueEnd = false
            triggerTime = System.currentTimeMillis() + minute.minutes.inWholeMilliseconds
            sleepTimerJob =
                scope.launch {
                    delay(minute.minutes)
                    player.pause()
                    triggerTime = -1L
                }
        }
    }

    fun notifySongTransition() {
        if (pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            player.pause()
        }
    }

    /** Called when queue becomes empty and playback ends — MusPlay specific. */
    fun notifyQueueEnd() {
        if (pauseWhenQueueEnd) {
            pauseWhenQueueEnd = false
            player.pause()
        }
    }

    fun clear() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        pauseWhenSongEnd = false
        pauseWhenQueueEnd = false
        triggerTime = -1L
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            player.pause()
        }
        // pauseWhenQueueEnd intentionally NOT handled here — wait for queue exhaustion
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        if (playbackState == Player.STATE_ENDED && pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            player.pause()
        }
        if (playbackState == Player.STATE_ENDED && pauseWhenQueueEnd) {
            // Only pause if there is no next window to play (queue exhausted).
            val hasNext = player.hasNextMediaItem()
            if (!hasNext) {
                pauseWhenQueueEnd = false
                player.pause()
            }
        }
    }
}
