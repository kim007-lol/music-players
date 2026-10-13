// AudioQualityScreen.kt
//
// Audio Lab's streaming quality, by provider: YouTube Music's tiers, then each source module as its
// own category (Apple Music, Monochrome) with its switch and qualities, then the provider order
// songs are looked for in. The picture is the music as the first provider plays it: the lower the
// quality, the coarser the waveform, until Low is pixels; lossless is smooth.

package com.musplay.app.ui.screens.audiolab

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.musplay.app.module.AccountState
import com.musplay.app.module.applemusic.AppleMusicModule
import com.musplay.app.module.monochrome.MonochromeModule
import com.musplay.app.modules.SourceModules
import com.musplay.app.viewmodels.ModulesViewModel
import com.musplay.app.R
import com.musplay.app.constants.AudioQuality
import com.musplay.app.constants.AudioQualityKey
import com.musplay.app.eq.audio.AudioTap
import com.musplay.app.ui.component.SubSettingsScaffold
import com.musplay.app.ui.theme.InterFontFamily
import com.musplay.app.utils.rememberEnumPreference
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

private class QualityOption(val quality: AudioQuality, val title: String, val description: String, val format: String)

private val YouTubeOptions = listOf(
    QualityOption(AudioQuality.AUTO, "Auto", "High on Wi-Fi, Medium on mobile data", "Adaptive"),
    QualityOption(AudioQuality.HIGH, "High", "The best the streamer has: less compression, more data", "~160 kbps · Opus"),
    QualityOption(AudioQuality.MEDIUM, "Medium", "Around 128 kbps, easy on data", "~128 kbps · AAC"),
    QualityOption(AudioQuality.LOW, "Low", "The smallest streams, for slow connections", "~50–70 kbps · Opus"),
)

/** what the picture shows: the quality of whichever provider plays first */
private class Effective(val quality: AudioQuality, val title: String, val format: String)

@Composable
fun AudioQualityScreen(navController: NavController, modules: ModulesViewModel = hiltViewModel()) {
    val (stored, onQuality) = rememberEnumPreference(AudioQualityKey, defaultValue = AudioQuality.AUTO)
    // the tiers Monochrome used to serve from here belong to its module now
    val quality = if (stored.usesMonochrome) AudioQuality.HIGH else stored
    val entries by modules.entries.collectAsState()
    val order by modules.order.collectAsState()
    val context = LocalContext.current
    val metered = remember { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == true }

    // the first provider in the order that's on plays everything it has
    val first = order.firstOrNull { id -> id == SourceModules.YouTubeId || entries.any { it.module.info.id == id && it.enabled && it.account is AccountState.SignedIn } }
    val effective = when (first) {
        MonochromeModule.Id -> Effective(AudioQuality.LOSSLESS, "Monochrome", "FLAC · lossless")
        AppleMusicModule.Id -> {
            val pick = entries.firstOrNull { it.module.info.id == first }?.picks?.get(AppleMusicModule.ChoiceQuality)
            if (pick == AppleMusicModule.OptionHe64) Effective(AudioQuality.MEDIUM, "Apple Music", "HE-AAC 64")
            else Effective(AudioQuality.HIGH, "Apple Music", "AAC 256")
        }
        else -> {
            val tier = if (quality == AudioQuality.AUTO) (if (metered) AudioQuality.MEDIUM else AudioQuality.HIGH) else quality
            val option = YouTubeOptions.first { it.quality == tier }
            Effective(tier, "YouTube · ${option.title}", option.format)
        }
    }

    ListenToAudio()
    SubSettingsScaffold(
        title = "Audio Quality",
        backLabel = "Audio Lab",
        onBack = { navController.navigateUp() },
    ) {
        LabCard(padding = 14.dp) {
            LabStage(height = 132.dp) {
                QualityPicture(effective.quality)
                Text(
                    effective.title.uppercase(),
                    fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.2.sp,
                    color = LabColors.Secondary, modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
                Text(
                    effective.format,
                    fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                    color = LabColors.Secondary, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            ProviderHeader(logo = R.drawable.provider_youtube_music, name = "YouTube Music", status = "Always on, plays what the others don't have")
            Spacer(Modifier.height(8.dp))
            YouTubeOptions.forEach { option ->
                QualityRow(option.title, option.description, selected = quality == option.quality, enabled = true) { onQuality(option.quality) }
                Spacer(Modifier.height(8.dp))
            }
        }

        // each module is its own category: its switch, then its qualities
        entries.forEach { entry ->
            val id = entry.module.info.id
            val signedIn = entry.account is AccountState.SignedIn
            val active = entry.enabled && signedIn
            Spacer(Modifier.height(14.dp))
            LabCard(padding = 14.dp) {
                ProviderHeader(
                    logo = providerLogo(id),
                    name = entry.module.info.name,
                    status = when {
                        entry.account is AccountState.SignedOut -> "Sign in to play from it"
                        entry.account is AccountState.Expired -> "Sign in again"
                        active -> "On"
                        else -> "Off"
                    },
                    checked = active,
                    onCheckedChange = { on ->
                        if (!signedIn) navController.navigate(com.musplay.app.ui.screens.settings.modules.moduleRoute(id))
                        else modules.setEnabled(id, on)
                    },
                )
                Spacer(Modifier.height(8.dp))
                val qualityChoice = entry.module.choices.firstOrNull { it.id == "quality" }
                if (qualityChoice != null) {
                    val picked = entry.picks[qualityChoice.id]?.takeIf { p -> qualityChoice.options.any { it.id == p } }
                        ?: qualityChoice.options.first().id
                    qualityChoice.options.forEach { option ->
                        QualityRow(option.label, option.description, selected = picked == option.id, enabled = active) {
                            modules.setPick(id, qualityChoice.id, option.id)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                } else if (id == MonochromeModule.Id) {
                    QualityRow("Lossless", "FLAC, as Monochrome has it: CD quality or better", selected = true, enabled = active) {}
                    Spacer(Modifier.height(8.dp))
                }
                LabTextAction("${entry.module.info.name} settings", R.drawable.navigate_next) {
                    navController.navigate(com.musplay.app.ui.screens.settings.modules.moduleRoute(id))
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        LabSectionLabel("Provider order")
        ProviderOrder(
            order = order,
            name = modules::nameOf,
            logo = ::providerLogo,
            usable = { id -> id == SourceModules.YouTubeId || entries.any { it.module.info.id == id && it.enabled && it.account is AccountState.SignedIn } },
            onReorder = modules::setOrder,
        )
        Text(
            "Each song plays from the first provider here that's on and has it. Drag to reorder.",
            fontFamily = InterFontFamily, fontSize = 12.sp, lineHeight = 16.sp, color = LabColors.Secondary,
            modifier = Modifier.padding(top = 10.dp, start = 6.dp),
        )
        Spacer(Modifier.height(120.dp))
    }
}

internal fun providerLogo(id: String): Int? = when (id) {
    AppleMusicModule.Id -> R.drawable.provider_apple_music
    MonochromeModule.Id -> R.drawable.provider_monochrome
    SourceModules.YouTubeId -> R.drawable.provider_youtube_music
    else -> null
}

@Composable
private fun ProviderHeader(
    logo: Int?,
    name: String,
    status: String,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        ProviderLogo(logo, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            Text(status, fontFamily = InterFontFamily, fontSize = 12.sp, color = LabColors.Secondary)
        }
        if (checked != null) com.musplay.app.ui.component.AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun ProviderLogo(logo: Int?, size: Int) {
    Box(
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.26f).dp)).background(Color(0xFFD9D9D9)),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            androidx.compose.foundation.Image(
                painterResource(logo), null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(size.dp),
            )
        }
    }
}

@Composable
private fun QualityRow(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) LabColors.RowSelected else LabColors.Row, tween(200), label = "row")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, tween(250), label = "enabled")
    val check by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "check")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, if (selected) Color.White.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
            Text(description, fontFamily = InterFontFamily, fontSize = 12.sp, lineHeight = 15.sp, color = LabColors.Secondary)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier.size(22.dp).graphicsLayer { scaleX = check; scaleY = check; this.alpha = check },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.check), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** how fine the picture is drawn for a quality: 0 is pixels, 1 is Hi-Res */
private fun resolutionOf(q: AudioQuality): Float = when (q) {
    AudioQuality.LOW -> 0f
    AudioQuality.MEDIUM -> 0.3f
    AudioQuality.HIGH, AudioQuality.AUTO -> 0.58f
    AudioQuality.LOSSLESS, AudioQuality.DOLBY_ATMOS -> 0.85f
    AudioQuality.HI_RES_LOSSLESS -> 1f
}

@Composable
private fun QualityPicture(quality: AudioQuality) {
    val resolution by animateFloatAsState(resolutionOf(quality), spring(dampingRatio = 0.9f, stiffness = 90f), label = "res")
    val depth by animateFloatAsState(if (quality == AudioQuality.DOLBY_ATMOS) 1f else 0f, tween(600), label = "depth")
    val onScreen = rememberOnScreen()
    val held = rememberPicturesHeld(onScreen)
    val time = rememberLabTime(held)
    val level = remember { LevelFollower() }
    val last = remember { floatArrayOf(-1f) }

    Canvas(Modifier.fillMaxSize().trackOnScreen(onScreen)) {
        val t = time.value
        if (t != last[0]) {
            level.update(if (last[0] < 0) 0f else (t - last[0]).coerceIn(0f, 0.05f))
            last[0] = t
        }
        val w = size.width
        val h = size.height
        val cy = h * 0.55f
        val live = AudioTap.isLive
        val energy = if (live) 0.55f + level.level * 1.6f else 1f

        // sampled in steps as wide as the quality is coarse, in as few levels
        val pixel = (9f - 8f * resolution).dp.toPx()
        val levels = (4f + 60f * resolution * resolution).roundToInt()
        val step = if (resolution > 0.7f) 2.4.dp.toPx() * (1.3f - resolution * 0.5f) else pixel
        val left = 18.dp.toPx()
        val right = w - 18.dp.toPx()
        val maxHalf = h * 0.3f

        fun amplitude(x: Float, phase: Float): Float {
            val p = (x - left) / (right - left)
            // loud through the middle, dying into a dotted tail on the right, like the design
            val envelope = exp(-((p - 0.38f) * (p - 0.38f)) / 0.07f) * (if (p > 0.72f) 0.25f else 1f)
            val wave = 0.55f + 0.25f * sin(p * 37f + phase * 2.3f) + 0.2f * sin(p * 91f - phase * 3.1f)
            val detail = if (resolution > 0.9f) 0.12f * sin(p * 260f + phase * 5f) * (resolution - 0.9f) * 10f else 0f
            return (envelope * (wave + detail) * energy).coerceIn(0f, 1f)
        }

        // Atmos: two more layers behind, further away
        if (depth > 0.01f) {
            for (layer in 2 downTo 1) {
                val shift = layer * 9.dp.toPx() * depth
                val alpha = 0.18f * depth / layer
                var x = left
                while (x <= right) {
                    val a = amplitude(x + layer * 40f, t + layer)
                    val half = a * maxHalf * (1f - 0.18f * layer)
                    drawLine(Color.White.copy(alpha = alpha), Offset(x + shift, cy - shift - half), Offset(x + shift, cy - shift + half),
                        1.2.dp.toPx())
                    x += step * 1.4f
                }
            }
        }

        var x = left
        while (x <= right) {
            val a = amplitude(x, t)
            val q = (a * levels).roundToInt() / levels.toFloat()
            val p = (x - left) / (right - left)
            if (p > 0.72f && q < 0.05f) {
                // the dotted tail
                drawCircle(Color.White.copy(alpha = 0.45f), 0.9.dp.toPx(), Offset(x, cy))
            } else if (resolution < 0.7f) {
                // pixels: stacked squares, mirrored, snapped to the grid
                val n = (q * maxHalf / pixel).roundToInt().coerceAtLeast(1)
                val cell = pixel * 0.84f
                for (k in 0 until n) {
                    val alpha = 0.95f - k * 0.06f
                    drawRect(Color.White.copy(alpha = alpha.coerceAtLeast(0.35f)), Offset(x, cy - (k + 1) * pixel), Size(cell, cell))
                    drawRect(Color.White.copy(alpha = alpha.coerceAtLeast(0.35f)), Offset(x, cy + k * pixel), Size(cell, cell))
                }
            } else {
                val half = (q * maxHalf).coerceAtLeast(1.dp.toPx())
                val width = (1.6f - 0.6f * resolution).dp.toPx()
                drawRoundRect(Color.White.copy(alpha = 0.92f), Offset(x - width / 2, cy - half), Size(width, half * 2), CornerRadius(width / 2))
            }
            x += step
        }
    }
}
