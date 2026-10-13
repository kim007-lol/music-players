package com.example.musicplaying.ui.player.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** 0..1 eased in and out, for the stretches of a move that run off one spring */
internal fun ease(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/**
 * The editor's hold on the live flagship's parts. In the tiles they stay exactly where they are on
 * the player; opening a tile moves them: the cover editor slides the rest away, and the middle part
 * is a camera move - a dolly in on the middle part, everything else moving by its depth (the cover
 * behind drifts up and away slower, the transport in front flies out faster).
 */
@Stable
class StudioParts(
    val studio: PlayerStudioState,
    val anchors: StudioAnchors,
) {
    internal var width = 1f
    internal var height = 1f
    internal var statusTop = 0f
    internal var density: Density = Density(1f)
    internal var fullCover = false

    fun layout(): TileLayout = tileLayout(width, height, density, anchors, fullCover)

    /** the middle part's pieces, lifted off the player while it's being edited */
    internal var pieces: StudioPieces? = null

    /**
     * The middle part is a camera move over a still of the player (the editor draws it), so the
     * live parts don't move for it; only the cover editor moves them.
     */
    internal fun zoom(): Float = 0f

    /** where the middle part is brought to, as the camera closes in */
    private fun zoomTarget() = Offset(width / 2f, height * 0.5f)

    /**
     * the middle part as a whole: the camera only closes in a little, so it keeps its place and
     * comes slightly forward (and toward the middle). slid away for the cover editor
     */
    internal fun middleTransform(): PartTransform {
        val z = zoom()
        val g = anchors.middle
        val target = zoomTarget()
        val dx = (target.x - g.center.x) * z * 0.35f
        val dy = (target.y - g.center.y) * z * 0.35f + studio.cover.value * (height - g.top + offPx())
        return PartTransform(1f + 0.06f * z, dx, dy)
    }

    /** the cover, further back: it grows a touch less and drifts a little up */
    internal fun coverTransform(): PartTransform {
        val z = zoom()
        val u = studio.menu.value
        return PartTransform(1f + 0.035f * z + 0.025f * u, 0f, -height * (0.035f * z + 0.03f * u))
    }

    /** the transport, nearest the camera: it grows a touch more and drifts a little down */
    internal fun transportTransform(): PartTransform {
        val z = zoom()
        val away = studio.cover.value * (height - anchors.transport.value.top + offPx())
        return PartTransform(1f + 0.05f * z, 0f, height * 0.03f * z + away)
    }

    private fun offPx() = with(density) { 60.dp.toPx() }

    /** where [rect] (a piece of the middle part) is drawn, the middle part moved as a whole about its centre */
    internal fun inMiddle(rect: Rect): Rect {
        val t = middleTransform()
        val g = anchors.middle
        val cx = g.center.x + t.dx + t.scale * (rect.center.x - g.center.x)
        val cy = g.center.y + t.dy + t.scale * (rect.center.y - g.center.y)
        val hw = rect.width * t.scale / 2f
        val hh = rect.height * t.scale / 2f
        return Rect(cx - hw, cy - hh, cx + hw, cy + hh)
    }
}

@Composable
fun rememberStudioParts(studio: PlayerStudioState, anchors: StudioAnchors): StudioParts =
    remember(studio, anchors) { StudioParts(studio, anchors) }

/**
 * The layer on the cover art. In the tiles it stays put; in the cover editor it slides aside as
 * the styles are swiped through; for the middle part it drifts back with the camera.
 */
fun Modifier.studioCoverLayer(parts: StudioParts?): Modifier {
    if (parts == null) return this
    return graphicsLayer {
        val studio = parts.studio
        if (!studio.editing) return@graphicsLayer
        val t = parts.coverTransform()
        scaleX = t.scale
        scaleY = t.scale
        translationX = (studio.coverCommitted - studio.coverPage.value) * parts.width * studio.cover.value
        translationY = t.dy
    }
}

/** the layer on one of the middle part's two pieces (the title row, the seek bar) */
fun Modifier.studioMiddleLayer(parts: StudioParts?, slot: (StudioAnchors) -> MutableState<Rect>): Modifier {
    if (parts == null) return this
    return graphicsLayer {
        val studio = parts.studio
        if (!studio.editing) return@graphicsLayer
        val rect = slot(parts.anchors).value
        if (rect.width <= 0f) return@graphicsLayer
        val to = parts.inMiddle(rect)
        scaleX = to.width / rect.width
        scaleY = scaleX
        translationX = to.center.x - rect.center.x
        translationY = to.center.y - rect.center.y
    }
}

fun Modifier.studioTransportLayer(parts: StudioParts?): Modifier {
    if (parts == null) return this
    return graphicsLayer {
        if (!parts.studio.editing) return@graphicsLayer
        val t = parts.transportTransform()
        scaleX = t.scale
        scaleY = t.scale
        translationY = t.dy
    }
}

/** the bottom card leaves while the editor is up: it isn't edited here */
fun Modifier.studioCardLayer(parts: StudioParts?): Modifier {
    if (parts == null) return this
    return graphicsLayer {
        val e = parts.studio.edit.value
        if (e <= 0.0005f) return@graphicsLayer
        alpha = 1f - ease(e / 0.6f)
        translationY = ease(e) * size.height * 0.8f
    }
}

/**
 * The tiles' outlines and their tap targets, drawn inside the player sheet so they shrink and grow
 * with it on the way between the selector and the editor. While the editor is up nothing else on
 * the player takes a touch.
 */
@Composable
fun EditTiles(parts: StudioParts, modifier: Modifier = Modifier) {
    val studio = parts.studio
    val editing by remember(studio) { derivedStateOf { studio.editing } }
    if (!editing) return
    val density = LocalDensity.current
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                    }
                },
        )
        // while the middle part's pieces are lifted the editor draws the tiles itself, over its still
        Canvas(modifier = Modifier.fillMaxSize()) { if (parts.pieces?.lifted != true) drawTiles(parts) }
        if (studio.stage == StudioStage.EDIT) {
            val l = parts.layout()
            TileTarget(l.coverTile, density) { studio.go(StudioStage.COVER) }
            TileTarget(l.middleTile, density) { studio.go(StudioStage.MIDDLE) }
            TileTarget(l.controlsTile, density) { studio.openSheet(StudioSheet.CONTROLS) }
        }
    }
}

@Composable
private fun TileTarget(rect: Rect, density: Density, onClick: () -> Unit) {
    with(density) {
        Box(
            modifier = Modifier
                .offset(rect.left.toDp(), rect.top.toDp())
                .size(rect.width.toDp(), rect.height.toDp())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        )
    }
}

internal fun DrawScope.drawTiles(parts: StudioParts) {
    val studio = parts.studio
    val e = studio.edit.value
    if (e <= 0.01f) return
    val l = parts.layout()
    val c = studio.cover.value
    val m = studio.middle.value
    val stroke = TileStroke.toPx()
    val corner = TileCorner.toPx()
    val fade = (1f - c) * (1f - ease(m / 0.4f))
    val a = e * fade
    if (a <= 0.01f) return

    // the cover's tile always fades out toward its bottom; the full-bleed one has no top edge at all
    val r = l.coverTile
    val brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.62f * a),
        0.62f to Color.White.copy(alpha = 0.36f * a),
        1f to Color.Transparent,
        startY = r.top,
        endY = r.bottom,
    )
    if (l.fullCover) {
        val path = Path().apply {
            moveTo(r.left, r.top)
            lineTo(r.left, r.bottom)
            moveTo(r.right, r.top)
            lineTo(r.right, r.bottom)
        }
        drawPath(path, brush, style = Stroke(width = stroke, cap = StrokeCap.Round))
    } else {
        drawRoundRect(brush, r.topLeft, r.size, CornerRadius(corner), style = Stroke(width = stroke))
    }
    drawTileOutline(l.middleTile, a)
    drawTileOutline(l.controlsTile, a)
}

/** a tile's outline: bold, a touch brighter along the top */
internal fun DrawScope.drawTileOutline(rect: Rect, alpha: Float) {
    if (alpha <= 0.01f) return
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.6f * alpha),
            1f to Color.White.copy(alpha = 0.38f * alpha),
            startY = rect.top,
            endY = rect.bottom,
        ),
        rect.topLeft,
        rect.size,
        CornerRadius(TileCorner.toPx().coerceAtMost(rect.height / 2f)),
        style = Stroke(width = TileStroke.toPx()),
    )
}

internal fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
