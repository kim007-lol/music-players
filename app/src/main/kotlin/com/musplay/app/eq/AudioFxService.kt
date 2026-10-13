// AudioFxService.kt

package com.musplay.app.eq

import com.musplay.app.eq.audio.LoudnessParams
import com.musplay.app.eq.audio.ReplayGainParams
import com.musplay.app.eq.audio.ReverbParams
import com.musplay.app.eq.audio.SpatialParams
import com.musplay.app.eq.audio.VolumeCurve
import javax.inject.Inject
import javax.inject.Singleton

/** The Audio FX settings every player's effects read at the start of each buffer. */
@Singleton
class AudioFxService @Inject constructor() {
    @Volatile
    var reverb: ReverbParams = ReverbParams()

    @Volatile
    var loudness: LoudnessParams = LoudnessParams()

    @Volatile
    var spatial: SpatialParams = SpatialParams()

    /** replaced, never mutated: the sink notices a new curve by identity */
    @Volatile
    var volumeCurve: VolumeCurve = VolumeCurve()

    /** "Even out songs": YouTube's loudness numbers, or a file's own ReplayGain */
    @Volatile
    var normalize: Boolean = true

    @Volatile
    var replayGain: ReplayGainParams = ReplayGainParams()

    /** how much the loudness stage is pulling peaks down right now, in dB (0 or less), for the meter */
    @Volatile
    var loudnessReductionDb: Float = 0f
}
