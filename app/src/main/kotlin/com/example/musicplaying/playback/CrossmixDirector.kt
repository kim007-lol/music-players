package com.example.musicplaying.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicplaying.crossmix.CrossmixAnalysisRepository
import com.example.musicplaying.crossmix.CrossmixPlan
import com.example.musicplaying.crossmix.CrossmixPlanner
import com.example.musicplaying.crossmix.CrossmixRole
import com.example.musicplaying.crossmix.CrossmixStyle
import com.example.musicplaying.crossmix.CrossmixTrackInput
import com.example.musicplaying.playback.audio.CrossmixTransitionAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Runs Crossmix transitions: analyses the song and the next one, plans the hand-over, then plays
 * it on two players.
 *
 * The rule throughout is that a transition either happens properly or not at all. The next song
 * is prepared silently well ahead; only once it's actually ready is the outgoing side touched, and
 * if it isn't ready in time the song simply ends and the next one starts as it always did. The
 * next player starts slightly early by a measured amount and its beat is then locked to the
 * outgoing one by a small delay inside its renderer, mostly while it's still silent. Tempo is set
 * once before it starts and eased back after the overlap, never moved while both are playing,
 * because Media3 drains and resets the whole audio chain on every speed change.
 */
@UnstableApi
class CrossmixDirector(
    private val scope: CoroutineScope,
    private val host: Host,
    private val analyses: CrossmixAnalysisRepository,
) {
    interface Host {
        val currentPlayer: ExoPlayer
        fun processorFor(player: Player): CrossmixTransitionAudioProcessor?
        /** a new player holding the queue, at [index] / [positionMs], paused, at the matched speed, not yet prepared */
        fun createIncoming(index: Int, positionMs: Long, speedRatio: Float): ExoPlayer
        /** [incoming] becomes the session's player; [outgoing] plays on as the fading side */
        fun promote(incoming: ExoPlayer, outgoing: ExoPlayer)
        /** the outgoing side has finished: let its player go */
        fun release(outgoing: ExoPlayer)
        /** a prepared next player that won't be used after all */
        fun discard(incoming: ExoPlayer)
        /** why Crossmix can't run right now (it's off, casting, a song-end sleep timer...), or null */
        fun crossmixBlocker(): String?
        fun nextIndex(): Int
        /** the song's lyrics, fetched if they haven't been yet; synced ones say where its voice is */
        suspend fun lyrics(item: MediaItem): String?
        /** the listener's own speed and pitch, which the transition returns to */
        fun baseParameters(): PlaybackParameters
        /** two songs are playing together (true) or the outgoing one has gone (false) */
        fun onMixingChanged(mixing: Boolean)
        /** what Crossmix is doing now, in a line, for the settings screen */
        fun onStatus(status: String)
        /** the album of the song at [index], for telling continuous albums apart */
        fun albumOf(index: Int): String?
    }

    private var planningJob: Job? = null
    private var planningKey: String? = null
    private var armed: ExoPlayer? = null
    private var running: Running? = null
    private var lastStatus = ""
    // the test tool's jump to just before the next planned hand-over
    private var testJump = false
    private var selfSeeking = false

    // how long play() takes to be heard, learnt from each transition's first beat-lock reading
    private var startLatencyMs = 70.0

    val isTransitioning: Boolean get() = running != null

    /**
     * every decision, as a line for the settings screen and for logcat. android.util.Log at info
     * level is kept in release builds (Timber is debug-only), so it can be read off a real phone.
     */
    private fun report(status: String) {
        if (status == lastStatus) return
        lastStatus = status
        host.onStatus(status)
        android.util.Log.i(Tag, status)
    }

    private fun voiceSource(lyrics: String?, analysis: com.example.musicplaying.crossmix.TrackAnalysis?): String = when {
        lyrics != null && Regex("""\[\d{1,2}:\d{2}""").containsMatchIn(lyrics) -> "synced lyrics"
        analysis?.vocal?.isNotEmpty() == true -> "the sound"
        else -> "a guess"
    }

    private fun clock(ms: Double): String {
        val total = (ms / 1_000).toLong().coerceAtLeast(0L)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private class Running(
        val plan: CrossmixPlan,
        val outgoing: ExoPlayer,
        val incoming: ExoPlayer,
        val outProcessor: CrossmixTransitionAudioProcessor,
        val inProcessor: CrossmixTransitionAudioProcessor,
        val base: PlaybackParameters,
    ) {
        val jobs = ArrayList<Job>()
        var released = false
        var tempoSettled = false
        var lastSetSpeed = 0f
    }

    /** called whenever the playing song, its position or its state may have changed */
    fun schedule() {
        if (running != null) return
        val player = host.currentPlayer
        val item = player.currentMediaItem ?: return cancelPending()
        host.crossmixBlocker()?.let { reason ->
            report(reason)
            return cancelPending()
        }
        val duration = player.duration
        if (duration == C.TIME_UNSET) return cancelPending()
        if (duration < MinTrackMs) {
            report("This song is too short to mix")
            return cancelPending()
        }
        val nextIndex = host.nextIndex()
        if (nextIndex == C.INDEX_UNSET) {
            report("Nothing queued after this song")
            return cancelPending()
        }
        val next = player.getMediaItemAt(nextIndex)
        val key = "${item.mediaId}→${next.mediaId}@$nextIndex"
        // decided already (planned, armed, or found it can't be mixed): only a seek or a new song
        // asks again, not every pause or buffering blip
        if (key == planningKey) return
        cancelPending()
        planningKey = key
        val jump = testJump
        testJump = false
        planningJob = scope.launch { prepareTransition(player, item, next, nextIndex, duration, jump) }
    }

    /** the listener moved the playhead: a planned transition is re-timed, a running one ends cleanly */
    fun onUserSeek() {
        if (selfSeeking) return
        if (running != null) abortRunning("seek") else { cancelPending(); schedule() }
    }

    /**
     * for trying a mix out without sitting through the song: plans this song's hand-over as
     * usual, then jumps to a few seconds before the next song gets ready for it
     */
    fun testMix() {
        if (running != null) abortRunning("test")
        cancelPending()
        testJump = true
        schedule()
        testJump = false
    }

    /** the song changed under us (a skip, a queue edit) */
    fun onItemChanged() {
        if (running != null) abortRunning("item changed") else cancelPending()
    }

    fun release() {
        cancelPending()
        running?.let { abortRunning("service released") }
    }

    private fun cancelPending() {
        planningJob?.cancel()
        planningJob = null
        planningKey = null
        armed?.let { incoming ->
            host.processorFor(incoming)?.clear()
            host.discard(incoming)
        }
        armed = null
        // the outgoing side is only ever armed right before the hand-over starts; if a transition
        // was called off after that, the song must play on untouched
        host.processorFor(host.currentPlayer)?.let { if (running == null) it.clear() }
    }

    private suspend fun prepareTransition(
        outgoing: ExoPlayer,
        item: MediaItem,
        next: MediaItem,
        nextIndex: Int,
        durationMs: Long,
        testJump: Boolean,
    ) {
        report("Listening to this song and the next one…")
        val startedAt = System.currentTimeMillis()
        // lyrics come over the network while the audio is analysed
        val outLyrics = scope.async { host.lyrics(item) }
        val inLyrics = scope.async { host.lyrics(next) }
        val outAnalysis = analyses.analyze(item, wholeTrack = true)
        val inAnalysis = analyses.analyze(next, wholeTrack = false)
        val took = (System.currentTimeMillis() - startedAt) / 1_000f
        android.util.Log.i(
            Tag,
            "analysed in %.1f s: this %s bpm (%.2f), next %s bpm (%.2f)".format(
                took, outAnalysis?.bpm?.let { "%.1f".format(it) } ?: "?", outAnalysis?.bpmConfidence ?: 0f,
                inAnalysis?.bpm?.let { "%.1f".format(it) } ?: "?", inAnalysis?.bpmConfidence ?: 0f,
            ) + (analyses.lastFailure(item.mediaId)?.let { " · this failed: $it" } ?: "") +
                (analyses.lastFailure(next.mediaId)?.let { " · next failed: $it" } ?: ""),
        )

        // an album that runs straight from one track into the next is already continuous: any
        // mix would only break it, so it plays gapless as recorded
        val album = host.albumOf(outgoing.currentMediaItemIndex)
        if (album != null && album == host.albumOf(nextIndex) && outAnalysis != null && inAnalysis != null &&
            outAnalysis.complete && outAnalysis.trailingSilenceStartMs >= durationMs - SeamlessMs &&
            inAnalysis.leadingSilenceEndMs <= SeamlessMs
        ) {
            report("Continuous album: playing straight through")
            return
        }

        val outText = outLyrics.await()
        val inText = inLyrics.await()
        val plan = CrossmixPlanner.plan(
            outgoing = CrossmixTrackInput(item.mediaId, durationMs, outAnalysis, outText),
            incoming = CrossmixTrackInput(next.mediaId, 0L, inAnalysis, inText),
        )
        android.util.Log.i(
            Tag,
            "voices from: this ${voiceSource(outText, outAnalysis)}, next ${voiceSource(inText, inAnalysis)}",
        )
        report(
            "${plan.style.label} at ${clock(plan.outStartMs.toDouble())}" +
                (if (plan.beatMatched) ", beat-matched" else "") +
                (if (plan.yieldVocals) ", voices kept apart" else "") +
                (if (plan.inCueMs > 1_000) ", into the next at ${clock(plan.inCueMs.toDouble())}" else "")
        )
        android.util.Log.i(
            Tag,
            "plan: %s %d beats from %d ms, beat %.1f ms, cue %d ms at beat %.1f, start beat %.1f, speed %.4f".format(
                plan.style.name, plan.lengthBeats, plan.outStartMs, plan.outBeatMs, plan.inCueMs,
                plan.entryBeat, plan.inStartBeat, plan.inSpeed,
            ),
        )

        // the earliest moment either side does anything: the outgoing effects start at beat 0, the
        // next player may start a little before that on a silent run-up
        val firstMoveMs = plan.outMediaAt(min(plan.inStartBeat, 0f))
        if (testJump) {
            if (!stillCurrent(outgoing, item)) return
            val target = (firstMoveMs - ArmLeadMs - TestRunUpMs).toLong().coerceAtLeast(0L)
            selfSeeking = true
            try {
                outgoing.seekTo(target)
            } finally {
                selfSeeking = false
            }
            if (!outgoing.playWhenReady) outgoing.play()
            android.util.Log.i(Tag, "test: jumped to ${clock(target.toDouble())}, the mix starts at ${clock(firstMoveMs)}")
        }
        if (outgoing.currentPosition > firstMoveMs - MinArmLeadMs) {
            report("Too close to the end to mix this one")
            return
        }
        if (!waitForOutgoing(outgoing, item, firstMoveMs - ArmLeadMs)) return
        host.crossmixBlocker()?.let { report(it); return }
        if (outgoing.currentPosition > firstMoveMs - MinArmLeadMs) {
            report("Too close to the end to mix this one")
            return
        }
        report("Getting the next song ready for ${plan.style.label}…")

        val outProcessor = host.processorFor(outgoing)
        if (outProcessor == null || !outProcessor.canRender) {
            report("This song's audio (${outProcessor?.format ?: "no renderer"}) can't be mixed: played through")
            return
        }
        val base = host.baseParameters()

        // the incoming side starts on a delay with room to move both ways, plus room for any drift
        // the delay will have to absorb when the tempos are a hair apart but not stretched
        val overlapWallMs = (plan.lengthBeats + 8) * plan.outBeatMs / base.speed
        val inRate = plan.inMediaPerBeat / plan.outBeatMs
        val drift = (inRate - plan.inSpeed) * overlapWallMs
        val initialDelayMs = (BaseDelayMs + max(0.0, drift)).coerceAtMost(400.0)

        val incoming = runCatching { host.createIncoming(nextIndex, plan.inStartMs, plan.inSpeed) }.getOrNull()
        if (incoming == null) {
            report("Couldn't open a player for the next song: played through")
            return
        }
        armed = incoming
        val inProcessor = host.processorFor(incoming) ?: return giveUp(incoming)
        // armed before it has decoded a thing: a paused player still fills its audio buffer, and
        // that audio is the first heard on play(), so it must already be the transition's (silent
        // before the next song's entry), never a blip of the next song at full volume
        inProcessor.arm(script(plan, CrossmixRole.Incoming, plan.inStartMs.toDouble(), 0L))
        inProcessor.setBeatLockDelay(initialDelayMs)
        var failed = false
        val errorListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        incoming.addListener(errorListener)
        incoming.prepare()

        // go or no-go, decided before the outgoing side is touched. READY isn't enough: a paused
        // player can be READY before its renderer has decoded anything and been set up for it
        val goDeadline = firstMoveMs - GoMarginMs
        while (incoming.playbackState != Player.STATE_READY || !inProcessor.configured) {
            if (failed || !stillCurrent(outgoing, item) || outgoing.currentPosition > goDeadline) {
                incoming.removeListener(errorListener)
                if (stillCurrent(outgoing, item)) {
                    report(
                        when {
                            failed -> "The next song failed to load: played through"
                            incoming.playbackState == Player.STATE_READY -> "The next song's audio didn't start in time: played through"
                            else -> "The next song didn't load in time: played through"
                        }
                    )
                }
                return giveUp(incoming)
            }
            delay(40)
        }
        incoming.removeListener(errorListener)
        // both sides must be able to render the transition, or neither is touched
        if (inProcessor.unsupported) {
            report("The next song's audio (${inProcessor.format}) can't be mixed: played through")
            return giveUp(incoming)
        }
        android.util.Log.i(Tag, "next song ready: ${inProcessor.format}, this song ${outProcessor.format}")

        outProcessor.arm(script(plan, CrossmixRole.Outgoing, outgoing.currentPosition.toDouble(), System.nanoTime()))

        // start the next player early by its start latency and by the delay it begins with
        val playAt = plan.outMediaAt(plan.inStartBeat) - initialDelayMs / plan.inSpeed - startLatencyMs * base.speed
        if (!waitForOutgoing(outgoing, item, playAt, precise = true)) {
            // the song changed in the last moment: put everything back
            outProcessor.clear()
            return giveUp(incoming)
        }

        armed = null
        val session = Running(plan, outgoing, incoming, outProcessor, inProcessor, base)
        running = session
        planningJob = null
        planningKey = null

        host.promote(incoming, outgoing)
        incoming.play()
        host.onMixingChanged(true)
        report("Mixing · ${plan.style.label}")
        session.lastSetSpeed = incoming.playbackParameters.speed

        session.jobs += scope.launch { lockBeat(session) }
        session.jobs += scope.launch { releaseOutgoingWhenDone(session) }
    }

    private fun giveUp(incoming: ExoPlayer) {
        armed = null
        host.processorFor(incoming)?.clear()
        host.discard(incoming)
    }

    /**
     * keeps the next song's beat on the outgoing one for as long as they overlap: reads both
     * positions a few times a second and moves the incoming side's delay by the median error.
     * the first readings come while the next song is still silent, so the lock is in before it's heard.
     */
    private suspend fun lockBeat(session: Running) {
        val plan = session.plan
        val errors = ArrayList<Double>()
        var firstCorrection = true
        delay(350)
        while (scope.isActive && running === session) {
            val outgoing = session.outgoing
            val incoming = session.incoming
            if (session.released) break
            if (outgoing.isPlaying && incoming.isPlaying) {
                val outPos = outgoing.currentPosition
                val inPos = incoming.currentPosition
                val beat = ((outPos - plan.outStartMs) / plan.outBeatMs).toFloat()
                if (beat > plan.lengthBeats + 2f) break
                val expected = plan.inMediaAt(beat)
                val heard = inPos - session.inProcessor.currentDelayMs
                errors += heard - expected
                if (errors.size >= 5) {
                    val median = errors.sorted()[errors.size / 2]
                    errors.clear()
                    if (abs(median) < 600.0) {
                        if (firstCorrection) {
                            // a positive error means it was heard early: next time, start it later
                            startLatencyMs = (startLatencyMs - median * 0.5).coerceIn(20.0, 250.0)
                            firstCorrection = false
                        }
                        if (abs(median) > 2.0) {
                            session.inProcessor.setBeatLockDelay(session.inProcessor.currentDelayMs + median)
                        }
                    }
                }
            } else {
                errors.clear()
            }
            delay(LockIntervalMs)
        }
        // the overlap is over: ease the delay away (slowly enough to be inaudible), then the tempo
        session.inProcessor.setBeatLockDelay(0.0)
        relaxTempo(session)
    }

    /** back to the listener's own tempo in steps too small to hear, once the renderer is idle */
    private suspend fun relaxTempo(session: Running) {
        val incoming = session.incoming
        val base = session.base
        // the renderer goes idle once its delay has eased back to nothing; it can't take longer
        // than this, but if it somehow does, step it out rather than hold up every later transition
        val deadline = System.currentTimeMillis() + 90_000L
        while (scope.isActive && !session.inProcessor.idle) {
            if (incoming.currentMediaItemIndex == C.INDEX_UNSET) return
            if (System.currentTimeMillis() > deadline) {
                session.inProcessor.finish()
                delay(1_000)
                break
            }
            delay(500)
        }
        while (scope.isActive) {
            val current = incoming.playbackParameters
            // the listener changed the speed themselves: theirs wins
            if (abs(current.speed - session.lastSetSpeed) > 0.0005f) break
            val gap = base.speed - current.speed
            if (abs(gap) < 0.0005f) {
                if (current.speed != base.speed || current.pitch != base.pitch) incoming.playbackParameters = base
                break
            }
            val step = gap.coerceIn(-SpeedStep, SpeedStep)
            val nextSpeed = current.speed + step
            incoming.playbackParameters = PlaybackParameters(nextSpeed, base.pitch)
            session.lastSetSpeed = nextSpeed
            // two bars between steps
            delay((8 * session.plan.inMediaPerBeat / max(0.25f, nextSpeed)).toLong().coerceIn(1_000L, 8_000L))
        }
        session.tempoSettled = true
        finishSession(session)
    }

    private suspend fun releaseOutgoingWhenDone(session: Running) {
        val plan = session.plan
        val outgoing = session.outgoing
        while (scope.isActive && running === session && !session.released) {
            val ended = outgoing.playbackState == Player.STATE_ENDED ||
                outgoing.playbackState == Player.STATE_IDLE ||
                (!outgoing.playWhenReady && outgoing.currentPosition >= plan.outStartMs + plan.lengthBeats * plan.outBeatMs)
            if (ended || outgoing.currentPosition >= plan.outReleaseMs) break
            delay(200)
        }
        if (session.released) return
        session.outProcessor.fadeOutNow()
        delay(90)
        session.released = true
        host.release(outgoing)
        host.onMixingChanged(false)
        report("Mixed with ${plan.style.label}")
        finishSession(session)
    }

    /** a session is over once the outgoing player is gone and the tempo is the listener's again */
    private fun finishSession(session: Running) {
        if (running !== session) return
        if (!session.released || !session.tempoSettled) return
        running = null
        // the incoming song is the current one now; plan its own hand-over
        schedule()
    }

    private fun abortRunning(reason: String) {
        val session = running ?: return
        report("Mix stopped early ($reason)")
        running = null
        session.jobs.forEach { it.cancel() }
        host.onMixingChanged(false)
        session.inProcessor.finish()
        if (!session.released) {
            session.outProcessor.fadeOutNow()
            session.released = true
            scope.launch {
                delay(90)
                host.release(session.outgoing)
            }
        }
        // whatever plays next plays at the listener's own tempo
        val current = session.incoming.playbackParameters
        if (current.speed != session.base.speed) session.incoming.playbackParameters = session.base
        scope.launch { delay(150); schedule() }
    }

    private fun script(plan: CrossmixPlan, role: CrossmixRole, calibrationMs: Double, calibrationNanos: Long) =
        CrossmixTransitionAudioProcessor.Script(
            role = role,
            style = plan.style,
            variation = plan.variation,
            lengthBeats = plan.lengthBeats.toFloat(),
            anchorMs = if (role == CrossmixRole.Outgoing) plan.outStartMs.toDouble() else plan.inMediaAt(0f),
            mediaPerBeatMs = if (role == CrossmixRole.Outgoing) plan.outBeatMs else plan.inMediaPerBeat,
            musicalBeatMs = if (role == CrossmixRole.Outgoing) plan.outBeatMs else plan.inMediaPerBeat,
            endBeat = if (role == CrossmixRole.Outgoing) plan.lengthBeats + plan.style.tailBeats + 2f else plan.inSettledBeat,
            calibrationMs = calibrationMs,
            calibrationNanos = calibrationNanos,
            yieldVocals = plan.yieldVocals,
        )

    private fun stillCurrent(player: ExoPlayer, item: MediaItem): Boolean =
        host.currentPlayer === player && player.currentMediaItem?.mediaId == item.mediaId

    /**
     * waits until the outgoing song reaches [targetMs] (its own clock, so pauses just wait longer).
     * false if the song changed or moved away meanwhile. [precise]: the last stretch is polled
     * every couple of ms, for the moment the next player starts.
     */
    private suspend fun waitForOutgoing(player: ExoPlayer, item: MediaItem, targetMs: Double, precise: Boolean = false): Boolean {
        while (scope.isActive) {
            if (!stillCurrent(player, item)) return false
            val remaining = targetMs - player.currentPosition
            if (remaining <= 0) return true
            val speed = player.playbackParameters.speed.coerceAtLeast(0.25f)
            val wall = remaining / speed
            delay(
                when {
                    !player.isPlaying -> 250L
                    wall > 3_000 -> min(1_000L, (wall - 2_000).toLong())
                    wall > 120 -> (wall / 3).toLong().coerceAtLeast(10L)
                    precise -> 2L
                    else -> wall.toLong().coerceAtLeast(1L)
                }
            )
        }
        return false
    }

    private companion object {
        const val Tag = "Crossmix"
        const val MinTrackMs = 45_000L
        /** the next song is prepared this far ahead of its first move */
        const val ArmLeadMs = 9_000.0
        /** too close to plan properly: let this one play through */
        const val MinArmLeadMs = 2_500.0
        /** the next song must be ready by this long before anything happens, or it's called off */
        const val GoMarginMs = 1_200.0
        const val BaseDelayMs = 40.0
        const val LockIntervalMs = 120L
        const val SpeedStep = 0.004f
        /** this little silence at a track boundary or less: the two run into each other */
        const val SeamlessMs = 400L
        /** the test jump lands this long before the next song is prepared */
        const val TestRunUpMs = 4_000.0
    }
}
