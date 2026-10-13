// AudioLabScreen.kt
//
// Audio Lab: the home of MusicPlaying's sound, apart from the plain Audio settings. Big cards for the
// equalizer and the effects, then quality, transitions and reverb. Each card's drawing shows its
// state: the equalizer's knobs sit where your curve is, the FX lights when an effect is on, the
// quality waveform is as fine as what's streaming.

package com.musplay.app.ui.screens.audiolab

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.musplay.app.constants.AudioQuality
import com.musplay.app.constants.AudioQualityKey
import com.musplay.app.constants.CrossmixMode
import com.musplay.app.constants.CrossmixModeKey
import com.musplay.app.eq.EqPresets
import com.musplay.app.eq.EqSettings
import com.musplay.app.ui.component.SubSettingsScaffold
import com.musplay.app.ui.component.damageBoundary
import com.musplay.app.ui.component.pressBounce
import com.musplay.app.ui.theme.InterFontFamily
import com.musplay.app.utils.rememberEnumPreference
import com.musplay.app.utils.rememberPreference
import com.musplay.app.viewmodels.AudioLabViewModel
import kotlin.math.roundToInt
import kotlin.math.sin

private val HubCard = Color(0xFF222225)
private val Sketch = Color(0xFF6A6A70)

@Composable
fun AudioLabScreen(
    navController: NavController,
    viewModel: AudioLabViewModel = hiltViewModel(),
) {
    val eq by viewModel.eq.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val reverb by viewModel.reverb.collectAsState()
    val loudness by viewModel.loudness.collectAsState()
    val spatial by viewModel.spatial.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val (quality) = rememberEnumPreference(AudioQualityKey, defaultValue = AudioQuality.AUTO)
    val order by viewModel.sources.order.collectAsState()
    val modules by viewModel.sources.entries.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.sources.refresh() }
    // whichever provider plays first sets what "quality" means
    val firstProvider = order.firstOrNull { id ->
        id == com.musplay.app.modules.SourceModules.YouTubeId ||
            modules.any { it.module.info.id == id && it.enabled && it.account is com.musplay.app.module.AccountState.SignedIn }
    }
    val (mode) = rememberEnumPreference(CrossmixModeKey, defaultValue = CrossmixMode.AUTO_CROSSFADE)

    val activeFx = buildList {
        if (reverb.enabled) add("Reverb")
        if (loudness.enabled) add("Loudness")
        if (spatial.enabled) add("Spatial")
        if (!playback.isNormal) add(String.format("%.2f×", playback.speed))
        if (volume.enabled) add("Volume curve")
    }

    SubSettingsScaffold(
        title = "Audio Lab",
        onBack = { navController.navigateUp() },
    ) {
        Text(
            "Let's customize your music experience!",
            fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White,
        )
        Spacer(Modifier.height(18.dp))

        HubTile(
            title = "Equalizer",
            status = equalizerStatus(eq, presets.associate { it.id to it.name }),
            height = 112.dp,
            big = true,
            onClick = { navController.navigate("equalizer") },
        ) { EqualizerSketch(eq) }
        Spacer(Modifier.height(10.dp))
        HubTile(
            title = "Audio Effect",
            status = if (activeFx.isEmpty()) "All off" else activeFx.joinToString(" · "),
            height = 112.dp,
            big = true,
            onClick = { navController.navigate("audio_fx") },
        ) { FxSketch(active = activeFx.isNotEmpty()) }
        Spacer(Modifier.height(10.dp))
        HubTile(
            title = "Audio Quality",
            status = qualityName(quality, firstProvider),
            height = 58.dp,
            onClick = { navController.navigate("audio_quality") },
        ) { QualitySketch(quality, firstProvider) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubTile(
                title = "Transition",
                status = when (mode) {
                    CrossmixMode.OFF -> "Off"
                    CrossmixMode.AUTO_CROSSFADE -> "Auto"
                    CrossmixMode.MANUAL_CROSSFADE -> "Manual"
                    CrossmixMode.CROSSMIX -> "CrossMix"
                },
                height = 58.dp,
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate("crossmix_settings") },
            ) { TransitionSketch(active = mode != CrossmixMode.OFF) }
            HubTile(
                title = "Reverb",
                status = if (reverb.enabled) "On" else "Off",
                height = 58.dp,
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate("audio_fx") },
            ) { ReverbSketch(active = reverb.enabled) }
        }
        Spacer(Modifier.height(120.dp))
    }
}

private fun equalizerStatus(eq: EqSettings, userPresets: Map<String, String>): String {
    if (!eq.enabled) return "Off"
    val curve = eq.presetId?.let { id -> EqPresets.BuiltIn.firstOrNull { it.id == id }?.name ?: userPresets[id] } ?: "Custom"
    val correction = eq.correction?.takeIf { eq.correctionEnabled }?.model
    return if (correction != null) "$curve · $correction" else curve
}

private fun qualityName(q: AudioQuality, firstProvider: String?): String = when (firstProvider) {
    com.musplay.app.module.monochrome.MonochromeModule.Id -> "Monochrome · Lossless"
    com.musplay.app.module.applemusic.AppleMusicModule.Id -> "Apple Music · AAC"
    else -> "YouTube · " + when (q) {
        AudioQuality.LOW -> "Low"
        AudioQuality.MEDIUM -> "Medium"
        AudioQuality.HIGH -> "High"
        else -> "Auto"
    }
}

@Composable
private fun HubTile(
    title: String,
    status: String,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    sketch: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pressBounce(interaction, pressedScale = 0.97f)
            .clip(RoundedCornerShape(if (big) 24.dp else 18.dp))
            .background(HubCard)
            .clickable(interaction, null, onClick = onClick)
            .damageBoundary(),
    ) {
        sketch()
        Column(
            modifier = Modifier
                .align(if (big) Alignment.BottomStart else Alignment.CenterStart)
                .padding(horizontal = if (big) 22.dp else 16.dp, vertical = if (big) 18.dp else 0.dp),
        ) {
            if (big) {
                Text(status, fontFamily = InterFontFamily, fontSize = 12.sp, color = Color(0xFF9A9AA0), maxLines = 1)
                Text(title, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Color.White)
            } else {
                Text(title, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
                Text(status, fontFamily = InterFontFamily, fontSize = 11.sp, color = Color(0xFF9A9AA0), maxLines = 1)
            }
        }
    }
}

/** the sketch's own clock: still unless it's on screen and something should move */
@Composable
private fun sketchTime(moving: () -> Boolean = { true }): androidx.compose.runtime.State<Float> {
    val onScreen = rememberOnScreen()
    val held = rememberPicturesHeld(onScreen)
    return rememberLabTime(held, moving)
}

/** three sliders on a tilted panel, set to your curve's lows, mids and highs */
@Composable
private fun BoxScope.EqualizerSketch(eq: EqSettings) {
    fun band(from: Double, to: Double): Float {
        if (!eq.enabled) return 0f
        val inBand = eq.nodes.filter { it.enabled && it.frequency >= from && it.frequency < to }
        return if (inBand.isEmpty()) 0f else (inBand.sumOf { it.gain } / inBand.size).toFloat()
    }
    val low by animateFloatAsState(band(0.0, 250.0), spring(dampingRatio = 0.6f, stiffness = 140f), label = "low")
    val mid by animateFloatAsState(band(250.0, 4000.0), spring(dampingRatio = 0.6f, stiffness = 140f), label = "mid")
    val high by animateFloatAsState(band(4000.0, 30_000.0), spring(dampingRatio = 0.6f, stiffness = 140f), label = "high")
    val level = remember { LevelFollower() }
    val time = sketchTime { eq.enabled && com.musplay.app.eq.audio.AudioTap.isLive }
    val last = remember { floatArrayOf(-1f) }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.value
        if (t != last[0]) { level.update(0.016f); last[0] = t }
        val panel = size.height * 1.05f
        val origin = Offset(size.width - panel * 0.78f, size.height * 0.05f)
        rotate(-14f, Offset(origin.x + panel / 2, origin.y + panel / 2)) {
            drawRoundRect(Sketch, origin, Size(panel, panel), CornerRadius(panel * 0.2f), style = Stroke(2.dp.toPx()))
            listOf(low, mid, high).forEachIndexed { i, gain ->
                val x = origin.x + panel * (0.25f + i * 0.22f)
                val top = origin.y + panel * 0.16f
                val bottom = origin.y + panel * 0.84f
                drawLine(Sketch, Offset(x, top), Offset(x, bottom), 2.5.dp.toPx(), StrokeCap.Round)
                val bob = level.punch * 4.dp.toPx() * sin(t * 9f + i * 2f)
                val y = (top + bottom) / 2 - (gain / 12f) * (bottom - top) / 2 + bob
                drawCircle(HubCard, 9.dp.toPx(), Offset(x, y))
                drawCircle(if (eq.enabled) Color(0xFF9A9AA0) else Sketch, 9.dp.toPx(), Offset(x, y), style = Stroke(2.5.dp.toPx()))
            }
        }
    }
}

/** big outlined FX letters off the edge; a light runs through them while an effect is on */
@Composable
private fun BoxScope.FxSketch(active: Boolean) {
    val measurer = rememberTextMeasurer()
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(500), label = "fx")
    val time = sketchTime { active }
    Canvas(Modifier.fillMaxSize()) {
        val style = TextStyle(
            fontFamily = InterFontFamily, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic,
            fontSize = (size.height / density * 1.05f).sp,
            drawStyle = Stroke(width = 1.6.dp.toPx()),
        )
        val layout = measurer.measure("FX", style)
        val topLeft = Offset(size.width - layout.size.width * 0.86f, (size.height - layout.size.height) / 2f + size.height * 0.08f)
        drawText(layout, color = Sketch, topLeft = topLeft)
        if (glow > 0.01f) {
            val sweep = (time.value * 0.35f) % 1f
            val x = topLeft.x + layout.size.width * (sweep * 1.6f - 0.3f)
            drawText(
                layout,
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.85f * glow), Color.Transparent),
                    startX = x - 60.dp.toPx(), endX = x + 60.dp.toPx(),
                ),
                topLeft = topLeft,
            )
        }
    }
}

/** a little waveform, as coarse as what's streaming */
@Composable
private fun BoxScope.QualitySketch(quality: AudioQuality, firstProvider: String?) {
    val fine = when {
        firstProvider == com.musplay.app.module.monochrome.MonochromeModule.Id -> 1f
        firstProvider == com.musplay.app.module.applemusic.AppleMusicModule.Id -> 0.65f
        quality == AudioQuality.LOW -> 0f
        quality == AudioQuality.MEDIUM -> 0.35f
        else -> 0.65f
    }
    val resolution by animateFloatAsState(fine, tween(600), label = "res")
    Canvas(Modifier.fillMaxHeight().width(120.dp).align(Alignment.CenterEnd).padding(end = 14.dp)) {
        val step = (5f - 3.6f * resolution).dp.toPx()
        val levels = (3 + 12 * resolution).roundToInt()
        var x = 0f
        while (x < size.width) {
            val p = x / size.width
            val a = (0.35f + 0.35f * sin(p * 23f) + 0.3f * sin(p * 57f + 1f)).coerceIn(0f, 1f) * (1f - p * 0.6f)
            val q = (a * levels).roundToInt() / levels.toFloat()
            val half = q * size.height * 0.32f
            drawLine(Sketch, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), (step * 0.55f).coerceAtLeast(1.dp.toPx()))
            x += step
        }
    }
}

/** two songs' volumes crossing */
@Composable
private fun BoxScope.TransitionSketch(active: Boolean) {
    val on by animateFloatAsState(if (active) 1f else 0.4f, tween(400), label = "t")
    Canvas(Modifier.fillMaxHeight().width(56.dp).align(Alignment.CenterEnd).padding(end = 14.dp, top = 18.dp, bottom = 18.dp)) {
        val w = size.width
        val h = size.height
        val out = Path().apply { moveTo(0f, 0f); cubicTo(w * 0.5f, 0f, w * 0.5f, h, w, h) }
        val inn = Path().apply { moveTo(0f, h); cubicTo(w * 0.5f, h, w * 0.5f, 0f, w, 0f) }
        drawPath(out, Sketch.copy(alpha = on), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(inn, Sketch.copy(alpha = on), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** echoes running into a wall */
@Composable
private fun BoxScope.ReverbSketch(active: Boolean) {
    val time = sketchTime { active }
    Canvas(Modifier.fillMaxHeight().width(44.dp).align(Alignment.CenterEnd).padding(end = 14.dp, top = 16.dp, bottom = 16.dp)) {
        val w = size.width
        val h = size.height
        val wall = w - 3.dp.toPx()
        drawLine(Sketch, Offset(wall, 0f), Offset(wall, h), 3.dp.toPx(), StrokeCap.Round)
        for (k in 0 until 2) {
            val drift = if (active) ((time.value * 0.8f + k * 0.5f) % 1f) else 0.5f
            val x = w * (0.1f + 0.45f * drift) + k * 7.dp.toPx() * (1f - drift)
            val alpha = if (active) 1f - drift * 0.7f else 0.7f
            val chevron = Path().apply {
                moveTo(x, h * 0.2f)
                lineTo(x + h * 0.3f, h / 2)
                lineTo(x, h * 0.8f)
            }
            drawPath(chevron, Sketch.copy(alpha = alpha), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
