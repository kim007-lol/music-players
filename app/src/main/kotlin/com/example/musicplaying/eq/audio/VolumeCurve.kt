// VolumeCurve.kt

package com.example.musicplaying.eq.audio

import kotlin.math.pow

/**
 * How MusicPlaying's own volume maps onto gain. Linear volume crowds everything audible into the top
 * of the slider; a power curve spreads the quiet end out so small steps stay small. [limit] caps
 * the loudest the app will play.
 */
data class VolumeCurve(
    val enabled: Boolean = false,
    val exponent: Float = 2f,
    val limit: Float = 1f,
) {
    fun gain(volume: Float): Float {
        val v = volume.coerceIn(0f, 1f)
        if (!enabled) return v
        return limit.coerceIn(0.05f, 1f) * v.pow(exponent.coerceIn(1f, 5f))
    }
}
