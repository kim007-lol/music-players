// SourceModules.kt

package com.example.musicplaying.modules

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.example.musicplaying.module.AccountState
import com.example.musicplaying.module.HttpRequest
import com.example.musicplaying.module.HttpResponse
import com.example.musicplaying.module.MODULE_API_VERSION
import com.example.musicplaying.module.ModuleHost
import com.example.musicplaying.module.ModuleStorage
import com.example.musicplaying.module.ResolveResult
import com.example.musicplaying.module.SourceModule
import com.example.musicplaying.module.SourceStream
import com.example.musicplaying.module.TrackQuery
import com.example.musicplaying.module.applemusic.AppleMusicModule
import com.example.musicplaying.module.monochrome.MonochromeModule
import com.example.musicplaying.constants.EnableMonochromeBackendKey
import com.example.musicplaying.utils.dataStore
import com.example.musicplaying.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A module as the settings show it. */
data class ModuleEntry(
    val module: SourceModule,
    /** built into MusicPlaying, or an installed module app */
    val packageName: String?,
    val enabled: Boolean,
    /** choice id to the option picked; a choice not in here is on its first option */
    val picks: Map<String, String>,
    val account: AccountState,
)

/**
 * MusicPlaying's source modules: the ones built in, and any installed as their own app.
 *
 * A module app declares a service with the [ModuleAction] intent filter and its entry class in
 * meta-data [EntryMetaKey]; it's loaded into MusicPlaying only when it's signed with MusicPlaying's own key,
 * since its code runs inside the app, and only when it was built for this [MODULE_API_VERSION].
 * An installed module replaces a built-in one with the same id, so a module can be updated on its
 * own. The module code is compiled against `:modules:api` only.
 */
@Singleton
class SourceModules @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("source_modules", Context.MODE_PRIVATE)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val modules: List<Pair<SourceModule, String?>> by lazy {
        val builtIn = listOf<SourceModule>(AppleMusicModule(), MonochromeModule()).map { it to null as String? }
        val installed = discover()
        val ids = installed.map { it.first.info.id }.toSet()
        migrateMonochromeSwitch()
        (builtIn.filter { it.first.info.id !in ids } + installed).onEach { (module, _) -> module.attach(hostFor(module)) }
    }

    /** Monochrome used to be built into playback with its own switch; it carries over, once */
    private fun migrateMonochromeSwitch() {
        if (prefs.getBoolean("migrated_monochrome", false)) return
        val wasOn = runCatching { context.dataStore.get(EnableMonochromeBackendKey, false) }.getOrDefault(false)
        prefs.edit {
            if (wasOn) putBoolean("enabled_${MonochromeModule.Id}", true)
            putBoolean("migrated_monochrome", true)
        }
    }

    // ---- provider order -----------------------------------------------------------------------
    //
    // The order songs are looked for in: the modules and YouTube Music, which is always there and
    // always last to give up. Each song plays from the first switched-on provider that has it.

    private val _order = MutableStateFlow(readOrder())

    val order: StateFlow<List<String>> = _order.asStateFlow()

    private fun readOrder(): List<String> {
        var saved = prefs.getString("provider_order", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        // Monochrome is preferred when it has the song (lossless): first by default, and moved to
        // the top once for orders saved before it was
        if (!prefs.getBoolean("order_monochrome_first", false)) {
            saved = listOf(MonochromeModule.Id) + saved.filter { it != MonochromeModule.Id }
            prefs.edit {
                putString("provider_order", (saved + KnownOrder).distinct().joinToString(","))
                putBoolean("order_monochrome_first", true)
            }
        }
        return (saved + KnownOrder).distinct()
    }

    fun setOrder(ids: List<String>) {
        val full = (ids + _order.value).distinct()
        prefs.edit { putString("provider_order", full.joinToString(",")) }
        _order.value = full
    }

    /** whether [id] can play anything now: YouTube always, a module when on and signed in */
    fun isUsable(id: String): Boolean = id == YouTubeId ||
        (isEnabled(id) && module(id)?.accountState() is AccountState.SignedIn)

    fun nameOf(id: String): String = if (id == YouTubeId) "YouTube Music" else module(id)?.info?.name ?: id

    /** where a song plays from */
    sealed interface Route {
        data class Module(val module: SourceModule, val stream: SourceStream) : Route
        /** hand it to YouTube, by its video id */
        data object YouTube : Route
        /** every provider in [skip] or without it */
        data object Exhausted : Route
    }

    /**
     * Walks the provider order for [track], passing over the providers in [skip] (the ones that
     * already failed on this song). YouTube in the order means "play it from YouTube now".
     */
    suspend fun route(track: TrackQuery?, skip: Set<String>): Route = withContext(Dispatchers.IO) {
        for (id in _order.value) {
            if (id in skip || !isUsable(id)) continue
            if (id == YouTubeId) return@withContext Route.YouTube
            val module = module(id) ?: continue
            if (track == null) continue
            val result = runCatching { module.resolve(track, picks(id)) }
                .onFailure { android.util.Log.w(Tag, "$id failed for ${track.title}", it) }
                .getOrNull()
            when (result) {
                is ResolveResult.Found -> {
                    android.util.Log.i(Tag, "$id: ${track.title} -> ${result.stream.matched.title} (${result.stream.qualityLabel})")
                    return@withContext Route.Module(module, result.stream)
                }
                is ResolveResult.NotFound -> android.util.Log.i(Tag, "$id: ${track.title}: ${result.reason}")
                is ResolveResult.Unavailable -> android.util.Log.i(Tag, "$id unavailable for ${track.title}: ${result.reason}")
                null -> Unit
            }
        }
        Route.Exhausted
    }

    private val _entries = MutableStateFlow<List<ModuleEntry>>(emptyList())

    /** filled by [refresh]; the settings call it when they open */
    val entries: StateFlow<List<ModuleEntry>> = _entries.asStateFlow()

    fun module(id: String): SourceModule? = modules.firstOrNull { it.first.info.id == id }?.first

    fun isEnabled(id: String): Boolean = prefs.getBoolean("enabled_$id", false)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit { putBoolean("enabled_$id", enabled) }
        refresh()
    }

    fun picks(id: String): Map<String, String> {
        val module = module(id) ?: return emptyMap()
        return module.choices.mapNotNull { choice ->
            prefs.getString("pick_${id}_${choice.id}", null)?.let { choice.id to it }
        }.toMap()
    }

    fun setPick(id: String, choiceId: String, optionId: String) {
        prefs.edit { putString("pick_${id}_$choiceId", optionId) }
        refresh()
    }

    fun refresh() {
        // a module installed since: into the order just ahead of YouTube
        val missing = modules.map { it.first.info.id }.filter { it !in _order.value }
        if (missing.isNotEmpty()) {
            val current = _order.value
            val at = current.indexOf(YouTubeId).let { if (it < 0) current.size else it }
            setOrder(current.take(at) + missing + current.drop(at))
        }
        _entries.value = modules.map { (module, pkg) ->
            ModuleEntry(module, pkg, isEnabled(module.info.id), picks(module.info.id), module.accountState())
        }
    }

    private fun hostFor(module: SourceModule) = object : ModuleHost {
        private val store = context.getSharedPreferences("module_${module.info.id}", Context.MODE_PRIVATE)

        override val storage = object : ModuleStorage {
            override fun get(key: String): String? = store.getString(key, null)
            override fun set(key: String, value: String?) = store.edit { if (value == null) remove(key) else putString(key, value) }
        }

        override fun http(request: HttpRequest): HttpResponse {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            val body = request.body?.toRequestBody(request.contentType?.toMediaTypeOrNull())
            builder.method(request.method, if (request.method == "GET") null else body ?: ByteArray(0).toRequestBody())
            client.newCall(builder.build()).execute().use { response ->
                return HttpResponse(response.code, response.body.bytes())
            }
        }

        override fun log(message: String) {
            Timber.tag("Module:${module.info.id}").d(message)
        }
    }

    /** module apps installed on the phone, signed like MusicPlaying and built for this API */
    @SuppressLint("QueryPermissionsNeeded")
    private fun discover(): List<Pair<SourceModule, String?>> = runCatching {
        val pm = context.packageManager
        pm.queryIntentServices(Intent(ModuleAction), PackageManager.GET_META_DATA).mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            val pkg = service.packageName
            val entry = service.metaData?.getString(EntryMetaKey) ?: return@mapNotNull null
            if (pm.checkSignatures(context.packageName, pkg) != PackageManager.SIGNATURE_MATCH) {
                Timber.tag(Tag).w("Skipping module $pkg: not signed like MusicPlaying")
                return@mapNotNull null
            }
            runCatching {
                val app = service.applicationInfo
                val loader = PathClassLoader(app.sourceDir, app.nativeLibraryDir, javaClass.classLoader)
                val module = loader.loadClass(entry).getDeclaredConstructor().newInstance() as SourceModule
                if (module.info.apiVersion != MODULE_API_VERSION) {
                    Timber.tag(Tag).w("Skipping module $pkg: built for API ${module.info.apiVersion}, MusicPlaying has $MODULE_API_VERSION")
                    null
                } else module to pkg
            }.onFailure { Timber.tag(Tag).w(it, "Couldn't load module $pkg") }.getOrNull()
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val Tag = "SourceModules"
        const val ModuleAction = "com.example.musicplaying.SOURCE_MODULE"
        /** YouTube Music in the provider order: not a module, always usable */
        const val YouTubeId = "youtube"
        private val KnownOrder = listOf(MonochromeModule.Id, AppleMusicModule.Id, YouTubeId)
        const val EntryMetaKey = "com.example.musicplaying.module.entry"
    }
}
