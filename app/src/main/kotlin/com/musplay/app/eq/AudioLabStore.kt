// AudioLabStore.kt

package com.musplay.app.eq

import android.content.Context
import androidx.core.content.edit
import com.musplay.app.eq.audio.Biquad
import com.musplay.app.eq.audio.LoudnessParams
import com.musplay.app.eq.audio.ReplayGainMode
import com.musplay.app.eq.audio.ReplayGainParams
import com.musplay.app.eq.audio.ResponseCurve
import com.musplay.app.eq.audio.ReverbParams
import com.musplay.app.eq.audio.SpatialMode
import com.musplay.app.eq.audio.SpatialParams
import com.musplay.app.eq.audio.VolumeCurve
import com.musplay.app.eq.autoeq.AutoEqTuning
import com.musplay.app.eq.autoeq.HeadphoneForm
import com.musplay.app.eq.data.EQProfileRepository
import com.musplay.app.eq.data.FilterType
import com.musplay.app.eq.data.ParametricEQ
import com.musplay.app.eq.data.ParametricEQBand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.pow

/** what Sonic, the player's speed stage, can do */
const val MinSpeed = 0.1f
const val MaxSpeed = 8f

/** A headphone correction picked from the AutoEq database, with the filters it came to. */
@Serializable
data class AutoEqCorrection(
    val model: String,
    val source: String,
    val rig: String? = null,
    val path: String,
    val form: HeadphoneForm = HeadphoneForm.OverEar,
    val tuning: AutoEqTuning = AutoEqTuning(),
    val filters: List<ParametricEQBand>,
    val preamp: Double,
)

@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val nodes: List<ParametricEQBand>,
    val preamp: Double = 0.0,
)

@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    /** the user's own curve: the graph's nodes and the bands' sliders are the same list */
    val nodes: List<ParametricEQBand> = EqPresets.flatNodes(),
    val preamp: Double = 0.0,
    /** pulls the level down by however much the curve boosts, so nothing clips */
    val autoPreamp: Boolean = true,
    /** the preset the curve still matches; null once it's been edited */
    val presetId: String? = EqPresets.FlatId,
    val correction: AutoEqCorrection? = null,
    val correctionEnabled: Boolean = true,
)

@Serializable
data class ReverbSettings(
    val enabled: Boolean = false,
    val mix: Float = 0.5f,
    val roomSize: Float = 0.55f,
    val damping: Float = 0.45f,
    val wet: Float = 0.6f,
    val dry: Float = 1f,
    val width: Float = 1f,
    val preDelayMs: Float = 12f,
    val lowCutHz: Float = 180f,
    val presetId: String? = ReverbPresets.Default.id,
) {
    fun toParams() = ReverbParams(enabled, mix, roomSize, damping, wet, dry, width, preDelayMs, lowCutHz)

    fun withPreset(preset: ReverbPresets.Preset) = copy(
        mix = preset.mix,
        roomSize = preset.roomSize,
        damping = preset.damping,
        wet = preset.wet,
        dry = 1f,
        width = preset.width,
        preDelayMs = preset.preDelayMs,
        lowCutHz = preset.lowCutHz,
        presetId = preset.id,
    )
}

@Serializable
data class LoudnessSettings(
    val enabled: Boolean = false,
    val boostDb: Float = 4f,
    val compression: Float = 0.3f,
) {
    fun toParams() = LoudnessParams(enabled, boostDb, compression)
}

@Serializable
data class SpatialSettings(
    val enabled: Boolean = false,
    val mode: SpatialMode = SpatialMode.Crossfeed,
    val cutoffHz: Float = 700f,
    val feedDb: Float = 4.5f,
    val speakerAngle: Float = 30f,
    val amount: Float = 1f,
    /** Dolby Atmos / surround rendered binaurally by MusicPlaying rather than by the device */
    val surround: Boolean = false,
    /** both ears hear the whole mix */
    val mono: Boolean = false,
) {
    fun toParams() = SpatialParams(enabled, mode, cutoffHz, feedDb, speakerAngle, amount, surround, mono)
}

/** "Even out songs" for files that carry ReplayGain (Monochrome's FLACs, many local files) */
@Serializable
data class NormalizeSettings(
    val replayGainMode: ReplayGainMode = ReplayGainMode.Track,
    val preampDb: Float = 3f,
) {
    fun toParams() = ReplayGainParams(replayGainMode, preampDb)
}

@Serializable
data class PlaybackSettings(
    val speed: Float = 1f,
    /** off: pitch follows speed, like a record played fast or slow */
    val preservePitch: Boolean = true,
    /** semitones, while pitch is preserved */
    val pitchSemitones: Float = 0f,
) {
    val pitch: Float
        get() = if (preservePitch) 2f.pow(pitchSemitones / 12f) else speed

    val isNormal: Boolean get() = speed == 1f && pitch == 1f
}

@Serializable
data class VolumeSettings(
    val enabled: Boolean = false,
    val exponent: Float = 2f,
    val limit: Float = 1f,
) {
    fun toCurve() = VolumeCurve(enabled, exponent, limit)
}

/**
 * Everything Audio Lab sets, in one place: the equalizer (curve, presets, headphone correction)
 * and Audio FX (reverb, loudness, spatial, speed and pitch, volume curve). Each change goes
 * straight to the players, live, and is saved a moment later, so a slider can be dragged freely.
 */
@Singleton
class AudioLabStore @Inject constructor(
    @ApplicationContext context: Context,
    private val equalizerService: EqualizerService,
    private val audioFx: AudioFxService,
    private val legacyProfiles: EQProfileRepository,
) {
    private val prefs = context.getSharedPreferences("audio_lab", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val headroomCurve = ResponseCurve(ResponseCurve.logSpaced(160))

    private val _eq = MutableStateFlow(load("eq") ?: EqSettings())
    val eq: StateFlow<EqSettings> = _eq.asStateFlow()

    private val _reverb = MutableStateFlow(load("reverb") ?: ReverbSettings())
    val reverb: StateFlow<ReverbSettings> = _reverb.asStateFlow()

    private val _presets = MutableStateFlow(load<List<EqPreset>>("presets") ?: emptyList())
    val presets: StateFlow<List<EqPreset>> = _presets.asStateFlow()

    private val _loudness = MutableStateFlow(load("loudness") ?: LoudnessSettings())
    val loudness: StateFlow<LoudnessSettings> = _loudness.asStateFlow()

    private val _spatial = MutableStateFlow(load("spatial") ?: SpatialSettings())
    val spatial: StateFlow<SpatialSettings> = _spatial.asStateFlow()

    /** MusicService applies this to whichever player is playing */
    private val _playback = MutableStateFlow(load("playback") ?: PlaybackSettings())
    val playback: StateFlow<PlaybackSettings> = _playback.asStateFlow()

    private val _volume = MutableStateFlow(load("volume") ?: VolumeSettings())
    val volume: StateFlow<VolumeSettings> = _volume.asStateFlow()

    private val _normalize = MutableStateFlow(load("normalize") ?: NormalizeSettings())
    val normalize: StateFlow<NormalizeSettings> = _normalize.asStateFlow()

    init {
        migrateLegacyEqualizer()
        pushEq()
        pushFx()
    }

    fun updateLoudness(transform: (LoudnessSettings) -> LoudnessSettings) {
        _loudness.update(transform)
        pushFx()
        scheduleSave()
    }

    fun updateSpatial(transform: (SpatialSettings) -> SpatialSettings) {
        _spatial.update(transform)
        pushFx()
        scheduleSave()
    }

    fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings) {
        _playback.update { transform(it).let { s -> s.copy(speed = s.speed.coerceIn(MinSpeed, MaxSpeed)) } }
        scheduleSave()
    }

    fun updateNormalize(transform: (NormalizeSettings) -> NormalizeSettings) {
        _normalize.update(transform)
        pushFx()
        scheduleSave()
    }

    fun updateVolume(transform: (VolumeSettings) -> VolumeSettings) {
        _volume.update(transform)
        pushFx()
        scheduleSave()
    }

    fun updateEq(transform: (EqSettings) -> EqSettings) {
        _eq.update(transform)
        pushEq()
        scheduleSave()
    }

    fun updateReverb(transform: (ReverbSettings) -> ReverbSettings) {
        _reverb.update(transform)
        pushFx()
        scheduleSave()
    }

    fun savePreset(name: String) {
        val current = _eq.value
        val preset = EqPreset("user_${System.currentTimeMillis()}", name.trim(), current.nodes, current.preamp)
        _presets.update { it + preset }
        updateEq { it.copy(presetId = preset.id) }
    }

    fun deletePreset(id: String) {
        _presets.update { list -> list.filterNot { it.id == id } }
        if (_eq.value.presetId == id) updateEq { it.copy(presetId = null) } else scheduleSave()
    }

    /** the dB the auto preamp takes off for [settings], 0 or less */
    fun autoHeadroom(settings: EqSettings = _eq.value): Double = synchronized(headroomCurve) {
        val response = headroomCurve.response(audibleBands(settings))
        -max(0.0, response.maxOrNull() ?: 0.0)
    }

    /** what the processors play for [settings]; null is a straight wire */
    fun design(settings: EqSettings): ParametricEQ? {
        if (!settings.enabled) return null
        val bands = audibleBands(settings)
        val correctionOn = settings.correctionEnabled && settings.correction != null
        val headroom = when {
            settings.autoPreamp -> autoHeadroom(settings)
            correctionOn -> settings.correction!!.preamp
            else -> 0.0
        }
        val preamp = settings.preamp + headroom
        if (bands.isEmpty() && preamp == 0.0) return null
        return ParametricEQ(preamp = preamp, bands = bands)
    }

    private fun audibleBands(settings: EqSettings): List<ParametricEQBand> {
        val own = settings.nodes.filter(Biquad::isAudible)
        val correction = if (settings.correctionEnabled) settings.correction?.filters.orEmpty() else emptyList()
        return own + correction.filter(Biquad::isAudible)
    }

    private fun pushEq() = equalizerService.apply(design(_eq.value))

    private fun pushFx() {
        audioFx.reverb = _reverb.value.toParams()
        audioFx.loudness = _loudness.value.toParams()
        audioFx.spatial = _spatial.value.toParams()
        audioFx.replayGain = _normalize.value.toParams()
        val curve = _volume.value.toCurve()
        if (curve != audioFx.volumeCurve) audioFx.volumeCurve = curve
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            prefs.edit {
                putString("eq", json.encodeToString(EqSettings.serializer(), _eq.value))
                putString("reverb", json.encodeToString(ReverbSettings.serializer(), _reverb.value))
                putString("loudness", json.encodeToString(LoudnessSettings.serializer(), _loudness.value))
                putString("spatial", json.encodeToString(SpatialSettings.serializer(), _spatial.value))
                putString("playback", json.encodeToString(PlaybackSettings.serializer(), _playback.value))
                putString("volume", json.encodeToString(VolumeSettings.serializer(), _volume.value))
                putString("normalize", json.encodeToString(NormalizeSettings.serializer(), _normalize.value))
                putString(
                    "presets",
                    json.encodeToString(kotlinx.serialization.builtins.ListSerializer(EqPreset.serializer()), _presets.value),
                )
            }
        }
    }

    private inline fun <reified T> load(key: String): T? = prefs.getString(key, null)?.let {
        runCatching { json.decodeFromString<T>(it) }
            .onFailure { e -> Timber.tag("AudioLab").w(e, "Couldn't read saved $key") }
            .getOrNull()
    }

    /** brings over the old 10-band screen's curve and any profiles saved with it, once */
    private fun migrateLegacyEqualizer() {
        if (prefs.getBoolean("migrated_legacy_eq", false)) return
        runCatching {
            val legacyActive = legacyProfiles.getActiveProfile()
            if (prefs.getString("eq", null) == null && legacyActive != null && legacyActive.bands.isNotEmpty()) {
                _eq.value = EqSettings(
                    enabled = true,
                    nodes = legacyActive.bands,
                    preamp = legacyActive.preamp,
                    presetId = null,
                )
            }
            val saved = legacyProfiles.getSortedProfiles().filter { it.id != "musicplaying_custom_eq" && it.bands.isNotEmpty() }
            if (saved.isNotEmpty()) {
                _presets.update { it + saved.map { p -> EqPreset("legacy_${p.id}", p.name, p.bands, p.preamp) } }
            }
        }.onFailure { Timber.tag("AudioLab").w(it, "Legacy equalizer migration failed") }
        prefs.edit { putBoolean("migrated_legacy_eq", true) }
        scheduleSave()
    }
}

/** The built-in curves, as gains for the ten classic bands. */
object EqPresets {
    const val FlatId = "flat"

    val Frequencies = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private const val OctaveQ = 1.41

    class Preset(val id: String, val name: String, val gains: DoubleArray) {
        val nodes: List<ParametricEQBand> get() = nodesFor(gains)
    }

    fun nodesFor(gains: DoubleArray): List<ParametricEQBand> = Frequencies.mapIndexed { i, f ->
        ParametricEQBand(frequency = f, gain = gains.getOrElse(i) { 0.0 }, q = OctaveQ, filterType = FilterType.PK)
    }

    fun flatNodes(): List<ParametricEQBand> = nodesFor(DoubleArray(Frequencies.size))

    val BuiltIn = listOf(
        Preset(FlatId, "Flat", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        Preset("acoustic", "Acoustic", doubleArrayOf(4.5, 4.5, 3.5, 1.0, 1.5, 1.5, 3.0, 3.5, 3.0, 1.5)),
        Preset("bass_boost", "Bass Booster", doubleArrayOf(5.5, 4.5, 3.5, 2.5, 1.2, 0.0, 0.0, 0.0, 0.0, 0.0)),
        Preset("bass_cut", "Bass Reducer", doubleArrayOf(-5.5, -4.5, -3.5, -2.5, -1.2, 0.0, 0.0, 0.0, 0.0, 0.0)),
        Preset("classical", "Classical", doubleArrayOf(4.5, 3.5, 3.0, 2.5, -1.5, -1.5, 0.0, 2.0, 3.0, 3.5)),
        Preset("dance", "Dance", doubleArrayOf(3.5, 6.5, 5.0, 0.0, 2.0, 3.5, 5.0, 4.5, 3.5, 0.0)),
        Preset("deep", "Deep", doubleArrayOf(5.0, 3.5, 1.5, 1.0, 3.0, 2.5, 1.5, -2.0, -3.5, -4.5)),
        Preset("electronic", "Electronic", doubleArrayOf(4.5, 4.0, 1.5, 0.0, -2.0, 2.0, 1.0, 1.5, 4.5, 5.0)),
        Preset("hiphop", "Hip-Hop", doubleArrayOf(5.0, 4.0, 1.5, 3.0, -1.0, -1.0, 1.5, -0.5, 2.0, 3.0)),
        Preset("jazz", "Jazz", doubleArrayOf(4.0, 3.0, 1.5, 2.0, -1.5, -1.5, 0.0, 1.5, 3.0, 3.5)),
        Preset("loudness", "Loudness", doubleArrayOf(6.0, 4.0, 0.0, 0.0, -2.0, 0.0, -1.0, -5.0, 5.0, 1.0)),
        Preset("lounge", "Lounge", doubleArrayOf(-3.0, -1.5, -0.5, 1.5, 4.0, 2.5, 0.0, -1.5, 2.0, 1.0)),
        Preset("piano", "Piano", doubleArrayOf(3.0, 2.0, 0.0, 2.5, 3.0, 1.5, 3.5, 4.5, 3.0, 3.5)),
        Preset("pop", "Pop", doubleArrayOf(-1.5, -1.0, 0.0, 2.0, 4.0, 4.0, 2.0, 0.0, -1.0, -1.5)),
        Preset("rnb", "R&B", doubleArrayOf(2.5, 7.0, 5.5, 1.5, -2.5, -1.5, 2.5, 3.0, 3.0, 3.5)),
        Preset("rock", "Rock", doubleArrayOf(5.0, 4.0, 3.0, 1.5, -0.5, -1.0, 0.5, 2.5, 3.5, 4.5)),
        Preset("small_speakers", "Small Speakers", doubleArrayOf(5.5, 4.0, 3.5, 2.5, 1.0, 0.0, -1.0, -2.0, -3.5, -4.0)),
        Preset("spoken", "Spoken Word", doubleArrayOf(-3.5, -0.5, 0.0, 0.5, 3.5, 4.5, 5.0, 4.0, 2.5, 0.0)),
        Preset("treble_boost", "Treble Booster", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.5, 3.5, 4.5, 5.5)),
        Preset("treble_cut", "Treble Reducer", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -2.5, -3.5, -4.5, -5.5)),
        Preset("vocal", "Vocal Booster", doubleArrayOf(-1.5, -3.0, -3.0, 1.5, 3.5, 3.5, 3.0, 1.5, 0.0, -1.5)),
    )
}

/** Rooms to start from; any slider moved afterwards makes it Custom. */
object ReverbPresets {
    class Preset(
        val id: String,
        val name: String,
        val mix: Float,
        val roomSize: Float,
        val damping: Float,
        val wet: Float,
        val width: Float,
        val preDelayMs: Float,
        val lowCutHz: Float,
    )

    val Default = Preset("room", "Room", 0.5f, 0.55f, 0.45f, 0.6f, 1f, 12f, 180f)

    val All = listOf(
        Default,
        Preset("studio", "Studio", 0.4f, 0.3f, 0.65f, 0.45f, 0.8f, 5f, 250f),
        Preset("bathroom", "Bathroom", 0.5f, 0.42f, 0.05f, 0.7f, 0.6f, 2f, 120f),
        Preset("plate", "Plate", 0.45f, 0.65f, 0.1f, 0.55f, 1f, 0f, 300f),
        Preset("hall", "Hall", 0.5f, 0.78f, 0.35f, 0.6f, 1f, 25f, 150f),
        Preset("cathedral", "Cathedral", 0.55f, 0.95f, 0.2f, 0.7f, 1f, 40f, 120f),
        Preset("stadium", "Stadium", 0.6f, 0.9f, 0.5f, 0.65f, 1f, 60f, 200f),
        Preset("dream", "Dreamy", 0.7f, 0.93f, 0.6f, 0.85f, 1f, 30f, 300f),
    )
}
