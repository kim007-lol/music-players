package com.example.musicplaying.ui.player.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale

/**
 * The player, frozen: as the middle part opens, the player is captured once (its middle pieces
 * lifted out first) and blurred once (small, on a background thread); the editor's camera moves
 * over that still. Nothing of the live player is drawn behind it after that.
 */
@Stable
class StudioCapture internal constructor(internal val layer: GraphicsLayer) {
    /** the sheet records itself into [layer] while this is set */
    internal var recording by mutableStateOf(false)
    /** the player as it was when the middle part opened, its middle pieces lifted out */
    var image by mutableStateOf<ImageBitmap?>(null)
        internal set
    /** [image], softened: what the camera settles on behind the pieces */
    var soft by mutableStateOf<ImageBitmap?>(null)
        internal set
}

/**
 * The middle part's pieces - the side button, the chevron, the seek bar and the end time - lifted
 * off the player for the editor. While [lifted], each one stops drawing in its place and records
 * itself into its own layer instead, and the editor draws those layers where the pieces are going:
 * the player's own drawing of each piece, so nothing is swapped for a lookalike.
 */
@Stable
class StudioPieces internal constructor(internal val layers: List<GraphicsLayer>) {
    var lifted by mutableStateOf(false)
        internal set

    companion object {
        const val SIDE = 0
        const val CHEVRON = 1
        const val SEEK_BAR = 2
        const val END_TIME = 3
    }
}

@androidx.compose.runtime.Composable
fun rememberStudioPieces(): StudioPieces {
    val side = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val chevron = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val seek = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val end = androidx.compose.ui.graphics.rememberGraphicsLayer()
    return androidx.compose.runtime.remember(side, chevron, seek, end) { StudioPieces(listOf(side, chevron, seek, end)) }
}

/** on one of the middle part's pieces: drawn in place normally, recorded for the editor while lifted */
fun Modifier.studioPiece(pieces: StudioPieces?, which: Int): Modifier {
    if (pieces == null) return this
    return drawWithContent {
        if (pieces.lifted) {
            pieces.layers[which].record { this@drawWithContent.drawContent() }
        } else {
            drawContent()
        }
    }
}

/** on the player sheet: records it into the capture's layer, while a capture is being taken */
fun Modifier.studioCaptureSource(capture: StudioCapture?): Modifier {
    if (capture == null) return this
    return drawWithContent {
        if (capture.recording) {
            capture.layer.record { this@drawWithContent.drawContent() }
            drawLayer(capture.layer)
        } else {
            drawContent()
        }
    }
}

/** a soft copy of [image]: an eighth of the size, box-blurred three times */
internal fun softBackdrop(image: ImageBitmap): ImageBitmap {
    val source = image.asAndroidBitmap().let {
        if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
    }
    val w = (source.width / 8).coerceAtLeast(1)
    val h = (source.height / 8).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(source, w, h, true)
    val a = IntArray(w * h)
    small.getPixels(a, 0, w, 0, 0, w, h)
    val b = IntArray(w * h)
    repeat(3) {
        boxBlur(a, b, w, h, 5, horizontal = true)
        boxBlur(b, a, w, h, 5, horizontal = false)
    }
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val window = r * 2 + 1
    for (line in 0 until lines) {
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        fun at(i: Int): Int {
            val c = i.coerceIn(0, length - 1)
            return if (horizontal) src[line * w + c] else src[c * w + line]
        }
        for (i in -r..r) {
            val p = at(i)
            sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
        }
        for (i in 0 until length) {
            val out = ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
            if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            val add = at(i + r + 1)
            val remove = at(i - r)
            sa += (add ushr 24) - (remove ushr 24)
            sr += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
            sb += (add and 0xFF) - (remove and 0xFF)
        }
    }
}
