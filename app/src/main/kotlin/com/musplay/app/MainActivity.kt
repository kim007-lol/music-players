// MainActivity.kt

package com.musplay.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.musplay.app.ui.component.LocalSharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.musplay.app.R
import com.musplay.app.constants.AppBarHeight
import com.musplay.app.constants.AppLanguageKey
import com.musplay.app.constants.DarkModeKey
import com.musplay.app.ui.theme.ForceDarkTheme
import com.musplay.app.constants.DefaultOpenTabKey
import com.musplay.app.constants.CropAlbumArtKey
import com.musplay.app.constants.GridItemsSizeKey
import com.musplay.app.constants.GridItemSize
import com.musplay.app.constants.SwipeToSongKey
import com.musplay.app.constants.DisableScreenshotKey
import com.musplay.app.constants.DynamicThemeKey
import com.musplay.app.constants.EnableHighRefreshRateKey
import com.musplay.app.constants.MiniPlayerBottomSpacing
import com.musplay.app.constants.MiniPlayerHeight
import com.musplay.app.constants.NavigationBarAnimationSpec
import com.musplay.app.constants.NavigationBarHeight
import com.musplay.app.constants.SlimNavBarHeight
import com.musplay.app.constants.SlimNavBarKey
import com.musplay.app.constants.StopMusicOnTaskClearKey

import com.musplay.app.core.updater.getAutoUpdateCheckSetting
import com.musplay.app.core.updater.isNewerVersion
import com.musplay.app.core.updater.saveUpdateAvailableState
import com.musplay.app.core.updater.getUpdateNotificationsSetting
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import com.musplay.app.ui.component.GlassState
import com.musplay.app.ui.component.glassRoot
import com.musplay.app.constants.PauseListenHistoryKey
import com.musplay.app.constants.PauseSearchHistoryKey
import com.musplay.app.constants.PureBlackKey
import com.musplay.app.constants.SYSTEM_DEFAULT
import com.musplay.app.constants.SelectedThemeColorKey
import com.musplay.app.db.MusicDatabase
import com.musplay.app.db.entities.SearchHistory
import com.musplay.app.extensions.toEnum
import com.musplay.app.models.toMediaMetadata
import com.musplay.app.playback.DownloadUtil
import com.musplay.app.playback.MusicService
import com.musplay.app.playback.MusicService.MusicBinder
import com.musplay.app.playback.PlayerConnection
import com.musplay.app.playback.queues.YouTubeQueue
import com.musplay.app.ui.component.AppNavigationBar
import com.musplay.app.ui.component.onTabTapped
import com.musplay.app.ui.component.AppNavigationRail
import com.musplay.app.ui.component.LocalBottomSheetPageState
import com.musplay.app.ui.component.LocalMenuState
import com.musplay.app.ui.component.rememberBottomSheetState
import com.musplay.app.ui.component.shimmer.ShimmerTheme
import com.musplay.app.ui.menu.YouTubeSongMenu
import com.musplay.app.ui.player.BottomSheetPlayer
import com.musplay.app.ui.screens.Screens
import com.musplay.app.ui.screens.navigationBuilder
import com.musplay.app.ui.screens.DarkMode
import com.musplay.app.ui.screens.NavigationTab
import com.musplay.app.ui.theme.ColorSaver
import com.musplay.app.ui.theme.DefaultThemeColor
import com.musplay.app.ui.theme.MusicPlayingTheme
import com.musplay.app.ui.theme.extractThemeColor
import com.musplay.app.ui.utils.appBarScrollBehavior
import com.musplay.app.ui.utils.resetHeightOffset
import com.musplay.app.ui.utils.handOffToLaunchIntro
import com.musplay.app.ui.launch.LaunchGate
import com.musplay.app.ui.launch.LaunchIntroOverlay
import com.musplay.app.ui.launch.LaunchIntroState
import com.musplay.app.ui.launch.LocalLaunchIntro
import com.musplay.app.ui.launch.launchRise
import com.musplay.app.LocalDatabase
import com.musplay.app.LocalDownloadUtil
import com.musplay.app.utils.SyncUtils
import com.musplay.app.utils.dataStore
import com.musplay.app.utils.get
import com.musplay.app.utils.rememberEnumPreference
import com.musplay.app.utils.rememberPreference
import com.musplay.app.utils.reportException
import com.musplay.app.utils.setAppLocale
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import com.musplay.app.ui.screens.setup.SetupWizardContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject

private const val BackgroundZoomOnExpand = true

@Suppress("DEPRECATION", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")

private const val SETUP_MARKER_FILE = "setup_wizard_completed"

private fun hasOnboardedOnThisInstall(context: android.content.Context): Boolean {
    if (runCatching { java.io.File(context.noBackupFilesDir, SETUP_MARKER_FILE).exists() }.getOrDefault(false)) {
        return true
    }

    if (isUpgradedInstall(context)) {
        markOnboardedOnThisInstall(context)
        return true
    }
    return false
}

/** True when this package has been updated in place at least once, i.e. it is not a fresh install. */
private fun isUpgradedInstall(context: android.content.Context): Boolean =
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)

private fun markOnboardedOnThisInstall(context: android.content.Context) {
    runCatching { java.io.File(context.noBackupFilesDir, SETUP_MARKER_FILE).createNewFile() }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val ACTION_SEARCH = "com.musplay.app.action.SEARCH"
        private const val ACTION_LIBRARY = "com.musplay.app.action.LIBRARY"
    }

    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var syncUtils: SyncUtils

    private lateinit var navController: NavHostController
    private var pendingIntent: Intent? = null

    private var playerConnection by mutableStateOf<PlayerConnection?>(null)

    /** Bumped when launched from an update notification; the composition opens the version sheet. */
    private var openUpdateRequests by mutableStateOf(0)

    /** A fresh launch from the launcher plays the launch intro (ui/launch/LaunchIntro.kt). */
    private var introRequested = false
    private var introPlays = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is MusicBinder) {
                try {
                    playerConnection = PlayerConnection(this@MainActivity, service, database, lifecycleScope)
                    Timber.tag("MainActivity").d("PlayerConnection created successfully")
                } catch (e: Exception) {
                    Timber.tag("MainActivity").e(e, "Failed to create PlayerConnection")

                    lifecycleScope.launch {
                        delay(500)
                        try {
                            playerConnection = PlayerConnection(this@MainActivity, service, database, lifecycleScope)
                        } catch (e2: Exception) {
                            Timber.tag("MainActivity").e(e2, "Failed to create PlayerConnection on retry")
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerConnection?.dispose()
            playerConnection = null
        }
    }

    override fun onStart() {
        super.onStart()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1000)
            }
        }

        bindService(
            Intent(this, MusicService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        unbindService(serviceConnection)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (dataStore.get(StopMusicOnTaskClearKey, false) &&
            playerConnection?.isPlaying?.value == true &&
            isFinishing
        ) {
            stopService(Intent(this, MusicService::class.java))
            unbindService(serviceConnection)
            playerConnection = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == com.musplay.app.ui.screens.update.ActionOpenUpdate) {
            openUpdateRequests++
            return
        }
        if (::navController.isInitialized) {
            handleDeepLinkIntent(intent, navController)
        } else {
            pendingIntent = intent
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().handOffToLaunchIntro { introPlays }
        super.onCreate(savedInstanceState)
        introRequested = savedInstanceState == null && intent?.action == Intent.ACTION_MAIN
        if (introRequested) LaunchGate.begin() else LaunchGate.skip()
        if (savedInstanceState == null && intent?.action == com.musplay.app.ui.screens.update.ActionOpenUpdate) {
            openUpdateRequests++
        }

        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val locale = dataStore[AppLanguageKey]
                ?.takeUnless { it == SYSTEM_DEFAULT }
                ?.let { Locale.forLanguageTag(it) }
                ?: Locale.getDefault()
            setAppLocale(this, locale)
        }

        lifecycleScope.launch {
            dataStore.data
                .map { it[DisableScreenshotKey] ?: false }
                .distinctUntilChanged()
                .collectLatest {
                    if (it) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
        }

        setContent {
            musicplayingApp(
                playerConnection = playerConnection,
                database = database,
                downloadUtil = downloadUtil,
                syncUtils = syncUtils,
            )
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    @Composable
    private fun musicplayingApp(
        playerConnection: PlayerConnection?,
        database: MusicDatabase,
        downloadUtil: DownloadUtil,
        syncUtils: SyncUtils,
    ) {
        val enableDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
        val enableHighRefreshRate by rememberPreference(EnableHighRefreshRateKey, defaultValue = true)
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            if (getAutoUpdateCheckSetting(context)) {

                delay(2000L)
                // Same cached lookup the version sheet and home prompt use (this app's own
                // releases - the old checker here queried another project's repo). Launch-time
                // only: nothing about updates runs in the background.
                val release = com.musplay.app.core.updater.getLatestRelease().getOrNull()
                val isAvailable = release != null &&
                    com.musplay.app.core.updater.isNewerThanInstalled(release.version)
                saveUpdateAvailableState(context, isAvailable)

                if (release != null && isAvailable && getUpdateNotificationsSetting(context)) {
                    // At most three reminders per release (now, +2 weeks, +3 months).
                    com.musplay.app.ui.screens.update.maybeNotifyUpdate(context, release)
                }
            }
        }

        LaunchedEffect(enableHighRefreshRate) {
            val window = this@MainActivity.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val layoutParams = window.attributes
                if (enableHighRefreshRate) {
                    layoutParams.preferredDisplayModeId = 0
                } else {
                    val modes = window.windowManager.defaultDisplay.supportedModes
                    val mode60 = modes.firstOrNull { kotlin.math.abs(it.refreshRate - 60f) < 1f }
                        ?: modes.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }

                    if (mode60 != null) {
                        layoutParams.preferredDisplayModeId = mode60.modeId
                    }
                }
                window.attributes = layoutParams
            } else {
                val params = window.attributes
                if (enableHighRefreshRate) {
                    params.preferredRefreshRate = 0f
                } else {
                    params.preferredRefreshRate = 60f
                }
                window.attributes = params
            }
        }

        val useDarkTheme = ForceDarkTheme

        LaunchedEffect(useDarkTheme) {
            setSystemBarAppearance(useDarkTheme)
        }

        val pureBlackEnabled by rememberPreference(PureBlackKey, defaultValue = false)
        // Pure black only makes sense on top of the dark scheme; forcing it in light mode
        // paints a black background under light-mode colours and the app looks dark on a
        // phone set to light.
        val pureBlack = pureBlackEnabled && useDarkTheme

        val (selectedThemeColorInt) = rememberPreference(SelectedThemeColorKey, defaultValue = DefaultThemeColor.toArgb())
        val selectedThemeColor = Color(selectedThemeColorInt)

        var themeColor by rememberSaveable(stateSaver = ColorSaver) {
            mutableStateOf(selectedThemeColor)
        }

        LaunchedEffect(selectedThemeColor) {
            if (!enableDynamicTheme) {
                themeColor = selectedThemeColor
            }
        }

        // the launch intro waits for this, so the whole app doesn't recompose for a new colour
        // scheme halfway through it
        var themeSettled by remember { mutableStateOf(false) }

        LaunchedEffect(playerConnection, enableDynamicTheme, selectedThemeColor) {
            val playerConnection = playerConnection
            if (!enableDynamicTheme || playerConnection == null) {
                themeColor = selectedThemeColor
                if (!enableDynamicTheme) themeSettled = true
                return@LaunchedEffect
            }

            playerConnection.service.currentMediaMetadata.collectLatest { song ->
                if (song?.thumbnailUrl != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val result = imageLoader.execute(
                                ImageRequest.Builder(this@MainActivity)
                                    .data(song.thumbnailUrl)
                                    .allowHardware(false)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .networkCachePolicy(CachePolicy.ENABLED)
                                    .crossfade(false)
                                    .build()
                            )
                            themeColor = result.image?.toBitmap()?.extractThemeColor() ?: selectedThemeColor
                        } catch (e: Exception) {

                            themeColor = selectedThemeColor
                        }
                    }
                } else {
                    themeColor = selectedThemeColor
                }
                themeSettled = true
            }
        }

        MusicPlayingTheme(
            darkTheme = useDarkTheme,
            pureBlack = pureBlack,
            themeColor = themeColor,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface)
            ) {
                val glassState = remember { GlassState() }
                val hideAppChrome = remember { mutableStateOf(false) }
                val detailAccentColor = remember { mutableStateOf<Color?>(null) }
                val focusManager = LocalFocusManager.current
                val density = LocalDensity.current
                val configuration = LocalWindowInfo.current
                val cutoutInsets = WindowInsets.displayCutout

                @OptIn(ExperimentalLayoutApi::class)
                val windowsInsets = WindowInsets.systemBarsIgnoringVisibility
                val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val (previousTab, setPreviousTab) = rememberSaveable { mutableStateOf("home") }

                val navigationItems = remember {
                        Screens.MainScreens
                }
                val (slimNav) = rememberPreference(SlimNavBarKey, defaultValue = false)

                val defaultOpenTab = remember {
                    dataStore[DefaultOpenTabKey].toEnum<NavigationTab>(defaultValue = NavigationTab.HOME)
                }
                val tabOpenedFromShortcut = remember {
                    when (intent?.action) {
                        ACTION_SEARCH -> NavigationTab.SEARCH
                        ACTION_LIBRARY -> NavigationTab.SEARCH
                        else -> null
                    }
                }

                // the intro flies the splash mark into Home's logo, so it plays only when Home is first
                val launchIntro = remember {
                    val plays = introRequested && (tabOpenedFromShortcut ?: defaultOpenTab) == NavigationTab.HOME
                    introPlays = plays
                    if (introRequested && !plays) LaunchGate.skip()
                    LaunchIntroState(plays)
                }

                val topLevelScreens = remember {
                    listOf(
                        Screens.Home.route,
                        "settings",
                    )
                }

                val (query, onQueryChange) = rememberSaveable(stateSaver = TextFieldValue.Saver) {
                    mutableStateOf(TextFieldValue())
                }

                val onSearch: (String) -> Unit = remember {
                    { searchQuery ->
                        if (searchQuery.isNotEmpty()) {
                            navController.navigate("search/${URLEncoder.encode(searchQuery, "UTF-8")}")

                            if (dataStore[PauseSearchHistoryKey] != true) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    database.query {
                                        insert(SearchHistory(query = searchQuery))
                                    }
                                }
                            }
                        }
                    }
                }

                val currentRoute by remember {
                    derivedStateOf { navBackStackEntry?.destination?.route }
                }

                // the tab lit in the bar is read off the back stack every time, never remembered:
                // a page belongs to the tab page under it, however it was reached
                val backStack by navController.currentBackStack.collectAsState()
                val selectedTabRoute by remember(navigationItems) {
                    derivedStateOf { com.musplay.app.ui.component.owningTab(backStack, navigationItems) }
                }
                val tabOwnership = remember(navigationItems) {
                    com.musplay.app.ui.component.TabOwnership(navigationItems)
                }
                // every page's tab is noted while it's on the stack, so the slide still knows it
                // after a tab switch has saved the page away
                SideEffect { tabOwnership.update(backStack) }

                val inSearchScreen by remember {
                    derivedStateOf {
                        currentRoute?.startsWith("search/") == true
                    }
                }
                val navigationItemRoutes = remember(navigationItems) {
                    navigationItems.map { it.route }.toSet()
                }

                val shouldShowNavigationBar = remember(currentRoute, navigationItemRoutes) {
                    currentRoute == null ||
                        navigationItemRoutes.contains(currentRoute) ||
                        currentRoute!!.startsWith("search/") ||

                        currentRoute!!.startsWith("genre/") ||
                        currentRoute!!.startsWith("local_playlist/") ||
                        currentRoute!!.startsWith("online_playlist/") ||
                        currentRoute!!.startsWith("auto_playlist/") ||
                        // the bar stays while a page from the Library, an album or an artist is open
                        currentRoute!!.startsWith("library/") ||
                        currentRoute!!.startsWith("album/") ||
                        currentRoute!!.startsWith("artist/") ||

                        currentRoute in SubSettingsRoutes
                }

                val isLandscape = configuration.containerDpSize.width > configuration.containerDpSize.height

                val showRail = isLandscape && !inSearchScreen

                val navPadding = if (shouldShowNavigationBar && !showRail) {
                    if (slimNav) SlimNavBarHeight else NavigationBarHeight
                } else {
                    0.dp
                }

                val navigationBarHeight by animateDpAsState(
                    targetValue = if (shouldShowNavigationBar && !showRail) NavigationBarHeight else 0.dp,
                    animationSpec = NavigationBarAnimationSpec,
                    label = "navBarHeight",
                )

                val playerBottomSheetState = rememberBottomSheetState(
                    dismissedBound = 0.dp,
                    collapsedBound = bottomInset +
                        (if (!showRail && shouldShowNavigationBar) navPadding else 0.dp) +
                        MiniPlayerHeight,
                    expandedBound = maxHeight,
                )

                LaunchedEffect(playerBottomSheetState.isExpanded) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView.rootView)
                    if (playerBottomSheetState.isExpanded) {
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        controller.hide(WindowInsetsCompat.Type.navigationBars())
                    } else {
                        controller.show(WindowInsetsCompat.Type.navigationBars())
                    }
                }

                val playerAwareWindowInsets = remember(
                    bottomInset,
                    shouldShowNavigationBar,
                    playerBottomSheetState.isDismissed,
                    showRail,
                ) {
                    var bottom = bottomInset
                    if (shouldShowNavigationBar && !showRail) {
                        bottom += NavigationBarHeight
                    }
                    if (!playerBottomSheetState.isDismissed) bottom += MiniPlayerHeight
                    windowsInsets
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                        .add(WindowInsets(top = AppBarHeight, bottom = bottom))
                }
                appBarScrollBehavior(
                    canScroll = {
                        !inSearchScreen &&
                            (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                    }
                )

                val topAppBarScrollBehavior = appBarScrollBehavior(
                    canScroll = {
                        !inSearchScreen &&
                            (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                    },
                )

                LaunchedEffect(navBackStackEntry) {
                    if (inSearchScreen) {
                        val searchQuery = withContext(Dispatchers.IO) {
                            val rawQuery = navBackStackEntry?.arguments?.getString("query")
                            if (!rawQuery.isNullOrEmpty()) {
                                try {
                                    URLDecoder.decode(rawQuery, "UTF-8")
                                } catch (e: IllegalArgumentException) {
                                    rawQuery
                                }
                            } else {
                                ""
                            }
                        }
                        onQueryChange(
                            TextFieldValue(
                                searchQuery,
                                TextRange(searchQuery.length)
                            )
                        )
                    } else if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                        onQueryChange(TextFieldValue())
                    }

                    if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                        if (navigationItems.fastAny { it.route == previousTab }) {
                            topAppBarScrollBehavior.state.resetHeightOffset()
                        }
                    }

                    topAppBarScrollBehavior.state.resetHeightOffset()

                    navController.currentBackStackEntry?.destination?.route?.let {
                        setPreviousTab(it)
                    }
                }

                LaunchedEffect(playerConnection) {
                    val player = playerConnection?.player ?: return@LaunchedEffect
                    if (player.currentMediaItem == null) {
                        if (!playerBottomSheetState.isDismissed) {
                            playerBottomSheetState.dismiss()
                        }
                    } else {
                        if (playerBottomSheetState.isDismissed) {
                            playerBottomSheetState.collapseSoft()
                        }
                    }
                }

                DisposableEffect(playerConnection, playerBottomSheetState) {
                    val player = playerConnection?.player ?: return@DisposableEffect onDispose { }
                    val listener = object : Player.Listener {
                        override fun onMediaItemTransition(
                            mediaItem: MediaItem?,
                            reason: Int,
                        ) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                                mediaItem != null &&
                                playerBottomSheetState.isDismissed
                            ) {
                                playerBottomSheetState.collapseSoft()
                            }
                        }
                    }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                    }
                }

                var shouldShowTopBar by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(navBackStackEntry) {
                    val currentRoute = navBackStackEntry?.destination?.route
                    shouldShowTopBar = currentRoute in topLevelScreens &&
                        currentRoute != "settings" &&

                        currentRoute != Screens.Search.route &&
                        currentRoute != Screens.Home.route
                }

                val coroutineScope = rememberCoroutineScope()
                var sharedSong: SongItem? by remember {
                    mutableStateOf(null)
                }
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    if (pendingIntent != null) {
                        handleDeepLinkIntent(pendingIntent!!, navController)
                        pendingIntent = null
                    } else {
                        handleDeepLinkIntent(intent, navController)
                    }
                }

                DisposableEffect(Unit) {
                    val listener = Consumer<Intent> { intent ->
                        handleDeepLinkIntent(intent, navController)
                    }

                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                val currentTitleRes = remember(navBackStackEntry) {
                    when (navBackStackEntry?.destination?.route) {
                        Screens.Home.route -> R.string.music
                        Screens.Search.route -> R.string.search
                        else -> null
                    }
                }

                val pauseListenHistory by rememberPreference(PauseListenHistoryKey, defaultValue = false)
                val eventCount by database.eventCount().collectAsState(initial = 0)
                val showHistoryButton = remember(pauseListenHistory, eventCount) {
                    !(pauseListenHistory && eventCount == 0)
                }

                val baseBg = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer

                val cropAlbumArt by rememberPreference(CropAlbumArtKey, false)
                val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)
                val swipeToSong by rememberPreference(SwipeToSongKey, false)

                val setupCompleted by rememberPreference(com.musplay.app.constants.SetupCompletedKey, false)
                var forceShowSetup by remember { mutableStateOf(false) }
                // Seeded once per process: a restored backup can say setup is done on an
                // install that has never actually shown the wizard. Kept as state because
                // finishing the wizard writes the marker and must dismiss it right away.
                var onboardedHere by remember { mutableStateOf(hasOnboardedOnThisInstall(context)) }


                CompositionLocalProvider(
                    LocalDatabase provides database,
                    LocalContentColor provides if (pureBlack) Color.White else contentColorFor(MaterialTheme.colorScheme.surface),
                    com.musplay.app.ui.component.LocalPlayerBottomSheetState provides playerBottomSheetState,

                    LocalPlayerConnection provides playerConnection,
                    LocalGlassState provides glassState,
                    LocalHideAppChrome provides hideAppChrome,
                    LocalDetailAccentColor provides detailAccentColor,
                    LocalArtworkColor provides themeColor,
                    LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                    LocalDownloadUtil provides downloadUtil,
                    LocalShimmerTheme provides ShimmerTheme,
                    LocalSyncUtils provides syncUtils,
                    LocalCropAlbumArt provides cropAlbumArt,
                    LocalGridItemSize provides gridItemSize,
                    LocalSwipeToSong provides swipeToSong,
                    LocalLaunchIntro provides launchIntro,
                ) {
                    com.musplay.app.ui.component.PopupSheetHost(
                        states = listOf(
                            com.musplay.app.ui.component.LocalMenuState.current,
                            com.musplay.app.ui.component.LocalBottomSheetPageState.current,
                            com.musplay.app.ui.component.LocalZoomOutOverlayState.current,
                        ),
                    ) {
                    com.musplay.app.ui.screens.update.UpdateNotificationOpener(
                        requests = openUpdateRequests,
                    )
                    com.musplay.app.ui.screens.donate.DonatePromptScheduler(
                        enabled = setupCompleted && onboardedHere && !forceShowSetup,
                    )
                        SetupWizardContainer(
                            isVisible = !setupCompleted || !onboardedHere || forceShowSetup,
                            onSetupCompleted = { username, uri ->
                                markOnboardedOnThisInstall(context)
                                onboardedHere = true
                                coroutineScope.launch(Dispatchers.IO) {

                                    var savedUriStr = ""
                                    if (uri != null) {
                                        try {
                                            val file = java.io.File(context.filesDir, "profile_pic_${System.currentTimeMillis()}.jpg")
                                            if (uri.scheme == "file") {
                                                java.io.File(uri.path!!).copyTo(file, overwrite = true)
                                            } else {
                                                val inputStream = context.contentResolver.openInputStream(uri)
                                                val outputStream = java.io.FileOutputStream(file)
                                                inputStream?.copyTo(outputStream)
                                                inputStream?.close()
                                                outputStream.close()
                                            }
                                            savedUriStr = file.absolutePath
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }

                                    dataStore.updateData { prefs ->
                                        prefs.toMutablePreferences().apply {
                                            set(com.musplay.app.constants.SetupCompletedKey, true)
                                            set(com.musplay.app.constants.UsernameKey, username)
                                            if (savedUriStr.isNotEmpty()) {
                                                set(com.musplay.app.constants.ProfilePicUriKey, savedUriStr)
                                            }
                                        }
                                    }
                                }
                                forceShowSetup = false
                            }
                        ) {
                            Scaffold(
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            topBar = {
                                AnimatedVisibility(
                                    visible = shouldShowTopBar,
                                    enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                                    exit = fadeOut(animationSpec = tween(durationMillis = 200))
                                ) {
                                    Row {
                                        val profilePicStr by rememberPreference(com.musplay.app.constants.ProfilePicUriKey, "")
                                        TopAppBar(
                                            navigationIcon = {},
                                            title = {
                                                Text(
                                                    text = currentTitleRes?.let { stringResource(it) } ?: "",
                                                    style = MaterialTheme.typography.titleLarge.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 24.sp
                                                    ),
                                                )
                                            },
                                            actions = {
                                                if (showHistoryButton) {
                                                    IconButton(onClick = { navController.navigate("history") }) {
                                                        if (profilePicStr.isNotEmpty()) {
                                                            coil3.compose.AsyncImage(
                                                                model = if (profilePicStr.startsWith("file://")) profilePicStr else "file://$profilePicStr",
                                                                contentDescription = "Profile",
                                                                modifier = Modifier
                                                                    .size(32.dp)
                                                                    .clip(CircleShape),
                                                                contentScale = ContentScale.Crop
                                                            )
                                                        } else {
                                                            Icon(
                                                                painter = painterResource(R.drawable.music_history),
                                                                contentDescription = stringResource(R.string.history)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            scrollBehavior = topAppBarScrollBehavior,
                                            colors = TopAppBarDefaults.topAppBarColors(
                                                containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
                                                scrolledContainerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
                                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            modifier = Modifier.windowInsetsPadding(
                                                if (showRail) {
                                                    WindowInsets(left = NavigationBarHeight)
                                                        .add(cutoutInsets.only(WindowInsetsSides.Start))
                                                } else {
                                                    cutoutInsets.only(WindowInsetsSides.Start + WindowInsetsSides.End)
                                                }
                                            )
                                        )
                                    }
                                }
                            },
                            bottomBar = {
                                val onNavItemClick: (Screens, Boolean) -> Unit = remember(navController, coroutineScope, topAppBarScrollBehavior, playerBottomSheetState) {
                                    { screen: Screens, isSelected: Boolean ->
                                        if (playerBottomSheetState.isExpanded) {
                                            playerBottomSheetState.collapseSoft()
                                        }
                                        navController.onTabTapped(screen, isSelected) {
                                            coroutineScope.launch {
                                                topAppBarScrollBehavior.state.resetHeightOffset()
                                            }
                                        }
                                    }
                                }

                                val onSearchLongClick: () -> Unit = remember(navController) {
                                    {
                                        navController.navigate("recognition") {
                                            launchSingleTop = true
                                        }
                                    }
                                }

                                val navBarTotalHeight = bottomInset + NavigationBarHeight

                                if (!showRail && currentRoute != "wrapped" && currentRoute != "update") {

                                    val detailAccent = LocalDetailAccentColor.current.value
                                    val navScrimTint by androidx.compose.animation.animateColorAsState(
                                        targetValue = detailAccent ?: Color.Black,
                                        animationSpec = tween(500),
                                        label = "navScrimTint"
                                    )

                                    Box {

                                        if (!hideAppChrome.value) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .launchRise(order = 3)
                                                .fillMaxWidth()
                                                .height(bottomInset + navPadding + 40.dp)
                                                .background(
                                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color.Transparent,
                                                            navScrimTint.copy(alpha = 0.5f),
                                                            navScrimTint.copy(alpha = 0.9f),
                                                            navScrimTint
                                                        )
                                                    )
                                                )
                                                .graphicsLayer {
                                                    val navBarHeightPx = navigationBarHeight.toPx()
                                                    val totalHeightPx = navBarTotalHeight.toPx()

                                                    translationY = if (navBarHeightPx == 0f) {
                                                        totalHeightPx
                                                    } else {
                                                        val progress = playerBottomSheetState.progress.coerceIn(0f, 1f)
                                                        val slideOffset = totalHeightPx * progress
                                                        val hideOffset = totalHeightPx * (1 - navBarHeightPx / NavigationBarHeight.toPx())
                                                        slideOffset + hideOffset
                                                    }
                                                }
                                        )

                                        }

                                        if (!hideAppChrome.value) {
                                        BottomSheetPlayer(
                                            state = playerBottomSheetState,
                                            navController = navController,
                                            pureBlack = pureBlack,
                                            modifier = Modifier.launchRise(order = 3),
                                        )

                                        AppNavigationBar(
                                            navigationItems = navigationItems,
                                            currentRoute = currentRoute,
                                            selectedTabRoute = selectedTabRoute,
                                            onItemClick = onNavItemClick,
                                            pureBlack = pureBlack,
                                            slimNav = slimNav,
                                            onSearchLongClick = onSearchLongClick,
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .launchRise(order = 3)
                                                .height(bottomInset + navPadding)

                                                .graphicsLayer {
                                                    val navBarHeightPx = navigationBarHeight.toPx()
                                                    val totalHeightPx = navBarTotalHeight.toPx()

                                                    val progress = playerBottomSheetState.progress.coerceIn(0f, 1f)

                                                    translationY = if (navBarHeightPx == 0f) {
                                                        totalHeightPx
                                                    } else {
                                                        val slideOffset = totalHeightPx * progress
                                                        val hideOffset = totalHeightPx * (1 - navBarHeightPx / NavigationBarHeight.toPx())
                                                        slideOffset + hideOffset
                                                    }

                                                    alpha = (1f - progress / 0.6f).coerceIn(0f, 1f)
                                                }
                                        )
                                        }
                                    }
                                } else {
                                    if (currentRoute != "wrapped" && currentRoute != "update") {
                                        BottomSheetPlayer(
                                            state = playerBottomSheetState,
                                            navController = navController,
                                            pureBlack = pureBlack
                                        )
                                    }

                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                        ) {
                            Row(Modifier.fillMaxSize()) {
                                val onRailItemClick: (Screens, Boolean) -> Unit = remember(navController, coroutineScope, topAppBarScrollBehavior, playerBottomSheetState) {
                                    { screen: Screens, isSelected: Boolean ->
                                        if (playerBottomSheetState.isExpanded) {
                                            playerBottomSheetState.collapseSoft()
                                        }
                                        navController.onTabTapped(screen, isSelected) {
                                            coroutineScope.launch {
                                                topAppBarScrollBehavior.state.resetHeightOffset()
                                            }
                                        }
                                    }
                                }

                                val onRailSearchLongClick: () -> Unit = remember(navController) {
                                    {
                                        navController.navigate("recognition") {
                                            launchSingleTop = true
                                        }
                                    }
                                }

                                if (showRail && currentRoute != "wrapped" && currentRoute != "update") {
                                    AppNavigationRail(
                                        navigationItems = navigationItems,
                                        currentRoute = currentRoute,
                                        selectedTabRoute = selectedTabRoute,
                                        onItemClick = onRailItemClick,
                                        pureBlack = pureBlack,
                                        onSearchLongClick = onRailSearchLongClick
                                    )
                                }
                                Box(
                                    Modifier
                                        .weight(1f)

                                        .graphicsLayer {
                                            val p = if (BackgroundZoomOnExpand) {
                                                playerBottomSheetState.progress.coerceIn(0f, 1f)
                                            } else 0f
                                            if (p > 0.001f) {
                                                val scale = 1f - 0.06f * p
                                                scaleX = scale
                                                scaleY = scale

                                                alpha = ((1f - p) / 0.25f).coerceIn(0f, 1f)
                                                // While the player slides over it the app behind
                                                // doesn't change, but the zoom made it redraw in
                                                // full every frame - every image, every text glyph
                                                // re-rasterised at the new scale, its own glass
                                                // blurs. Kept in a layer, it is drawn once and the
                                                // zoom and fade move that. Not once the player is
                                                // fully open: at alpha 0 it isn't drawn at all.
                                                if (p < 0.999f) {
                                                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                                }
                                            }

                                        }
                                ) {

                                    // from the moment the player starts to open, not only once it's
                                    // all the way up. While the sheet moves, the app behind is kept
                                    // in one cached layer (above); anything still animating in it -
                                    // the hero's video, the gacha's drift, the playing bars - made
                                    // that whole layer render again on each of its frames, all
                                    // through the open and the close
                                    val appContentObscured = remember(playerBottomSheetState) {
                                        derivedStateOf { playerBottomSheetState.progress > 0.001f }
                                    }
                                    SharedTransitionLayout {
                                    CompositionLocalProvider(
                                        LocalSharedTransitionScope provides this,
                                        LocalAppContentObscured provides appContentObscured,
                                    ) {
                                    NavHost(
                                        navController = navController,
                                        startDestination = when (tabOpenedFromShortcut ?: defaultOpenTab) {
                                            NavigationTab.HOME -> Screens.Home
                                            NavigationTab.SEARCH -> Screens.Search
                                            else -> Screens.Home
                                        }.route,

                                        // each page slides by the tab it belongs to, not by its own route: an
                                        // album open in the Library moves like the Library when you switch tabs
                                        enterTransition = {
                                            val stack = navController.currentBackStack.value
                                            val currentRouteIndex = tabOwnership.indexOf(targetState, stack)
                                            val previousRouteIndex = tabOwnership.indexOf(initialState, stack)

                                            if (currentRouteIndex == -1 || currentRouteIndex >= previousRouteIndex)
                                                slideInHorizontally { it / 8 } + fadeIn(tween(200))
                                            else
                                                slideInHorizontally { -it / 8 } + fadeIn(tween(200))
                                        },

                                        exitTransition = {
                                            val stack = navController.currentBackStack.value
                                            val currentRouteIndex = tabOwnership.indexOf(initialState, stack)
                                            val targetRouteIndex = tabOwnership.indexOf(targetState, stack)

                                            if (targetRouteIndex == -1 || targetRouteIndex >= currentRouteIndex)
                                                slideOutHorizontally { -it / 8 } + fadeOut(tween(200))
                                            else
                                                slideOutHorizontally { it / 8 } + fadeOut(tween(200))
                                        },

                                        popEnterTransition = {
                                            val stack = navController.currentBackStack.value
                                            val currentRouteIndex = tabOwnership.indexOf(targetState, stack)
                                            val previousRouteIndex = tabOwnership.indexOf(initialState, stack)

                                            if (previousRouteIndex != -1 && previousRouteIndex < currentRouteIndex)
                                                slideInHorizontally { it / 8 } + fadeIn(tween(200))
                                            else
                                                slideInHorizontally { -it / 8 } + fadeIn(tween(200))
                                        },

                                        popExitTransition = {
                                            val stack = navController.currentBackStack.value
                                            val currentRouteIndex = tabOwnership.indexOf(initialState, stack)
                                            val targetRouteIndex = tabOwnership.indexOf(targetState, stack)

                                            if (currentRouteIndex != -1 && currentRouteIndex < targetRouteIndex)
                                                slideOutHorizontally { -it / 8 } + fadeOut(tween(200))
                                            else
                                                slideOutHorizontally { it / 8 } + fadeOut(tween(200))
                                        },
                                        modifier = Modifier
                                            .glassRoot(glassState, isActive = { true })
                                            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                                    ) {
                                        navigationBuilder(
                                            navController = navController,
                                            scrollBehavior = topAppBarScrollBehavior,
                                            activity = this@MainActivity,
                                            snackbarHostState = snackbarHostState
                                        )
                                    }
                                    }
                                    }
                                }
                            }
                        }
                    }
                    }

                    // action feedback ("Added to Queue", "Undo"...) drops in over the sheets too
                    com.musplay.app.ui.component.TopToastHost()

                    HomeUpdatePrompt(currentRoute = currentRoute)

                    sharedSong?.let { song ->
                        playerConnection?.let {
                            Dialog(
                                onDismissRequest = { sharedSong = null },
                                properties = DialogProperties(usePlatformDefaultWidth = false),
                            ) {
                                Surface(
                                    modifier = Modifier.padding(24.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = AlertDialogDefaults.containerColor,
                                    tonalElevation = AlertDialogDefaults.TonalElevation,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        YouTubeSongMenu(
                                            song = song,
                                            navController = navController,
                                            onDismiss = { sharedSong = null },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    LaunchIntroOverlay(
                        state = launchIntro,
                        essentialsReady = playerConnection != null && themeSettled,
                    )

                }
            }
        }
    }

    /** Audio files shared to MusicPlaying from another app or a file manager are imported as local songs. */
    private fun importSharedAudio(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return false
        val uris = if (intent.action == Intent.ACTION_SEND) {
            listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java))
        } else {
            androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
        }
        if (uris.isEmpty()) return false
        // handled once: the activity being recreated would otherwise import them again
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        lifecycle.coroutineScope.launch {
            var done = 0
            for (uri in uris) {
                try {
                    com.musplay.app.ui.screens.library.importSharedAudio(applicationContext, database, uri)
                    done++
                } catch (e: Exception) {
                    reportException(e)
                }
            }
            com.musplay.app.ui.component.TopToaster.show(
                if (done == 0) "Couldn't import that" else "Imported $done song${if (done != 1) "s" else ""} to MusicPlaying",
            )
        }
        return true
    }

    private fun handleDeepLinkIntent(intent: Intent, navController: NavHostController) {
        if (importSharedAudio(intent)) return
        val uri = intent.data ?: intent.extras?.getString(Intent.EXTRA_TEXT)?.toUri() ?: return
        intent.data = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        val coroutineScope = lifecycle.coroutineScope

        when (val path = uri.pathSegments.firstOrNull()) {
            "playlist" -> uri.getQueryParameter("list")?.let { playlistId ->
                if (playlistId.startsWith("OLAK5uy_")) {
                    coroutineScope.launch(Dispatchers.IO) {
                        YouTube.albumSongs(playlistId).onSuccess { songs ->
                            songs.firstOrNull()?.album?.id?.let { browseId ->
                                withContext(Dispatchers.Main) {
                                    navController.navigate("album/$browseId")
                                }
                            }
                        }.onFailure { reportException(it) }
                    }
                } else {
                    navController.navigate("online_playlist/$playlistId")
                }
            }

            "browse" -> uri.lastPathSegment?.let { browseId ->
                navController.navigate("album/$browseId")
            }

            "channel", "c" -> uri.lastPathSegment?.let { artistId ->
                navController.navigate("artist/$artistId")
            }

            "search" -> {
                uri.getQueryParameter("q")?.let {
                    navController.navigate("search/${URLEncoder.encode(it, "UTF-8")}")
                }
            }

            else -> {
                val videoId = when {
                    path == "watch" -> uri.getQueryParameter("v")
                    uri.host == "youtu.be" -> uri.pathSegments.firstOrNull()
                    else -> null
                }

                val playlistId = uri.getQueryParameter("list")

                if (videoId != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        YouTube.queue(listOf(videoId), playlistId).onSuccess { queue ->
                            withContext(Dispatchers.Main) {
                                playerConnection?.playQueue(
                                    YouTubeQueue(
                                        WatchEndpoint(videoId = queue.firstOrNull()?.id, playlistId = playlistId),
                                        queue.firstOrNull()?.toMediaMetadata()
                                    )
                                )
                            }
                        }.onFailure {
                            reportException(it)
                        }
                    }
                } else if (playlistId != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        YouTube.queue(null, playlistId).onSuccess { queue ->
                            val firstItem = queue.firstOrNull()
                            withContext(Dispatchers.Main) {
                                playerConnection?.playQueue(
                                    YouTubeQueue(
                                        WatchEndpoint(videoId = firstItem?.id, playlistId = playlistId),
                                        firstItem?.toMediaMetadata()
                                    )
                                )
                            }
                        }.onFailure {
                            reportException(it)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            window.statusBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }
}

val SubSettingsRoutes = setOf(
    "appearance_settings",
    "playback_settings",
    "experimental_settings",
    "cipher_settings",
    "playback_diagnostics",
    "musicplaying_settings",
    "import_sync",
    "import_providers",
    "import_tunemymusic",
    "import_progress",
    "youtube_sync",
    "stats",
    "developer_space",
    // Audio Lab keeps the bar, as in its designs
    "audio_lab",
    "equalizer",
    "audio_fx",
    "audio_quality",
    "crossmix_settings",
    "modules",
    "module/{id}",
)

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalGlassState = staticCompositionLocalOf<GlassState?> { null }

/**
 * True while the app's screens are entirely covered (the player fully open). Animations that run
 * on their own forever - a playing indicator's bars, Home's hero video and gacha drift - wait it
 * out instead of drawing frames nobody can see. Only provided around the app's screens; the player
 * itself never sees it set.
 */
val LocalAppContentObscured = staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> { AppContentNeverObscured }

private object AppContentNeverObscured : androidx.compose.runtime.State<Boolean> {
    override val value: Boolean get() = false
}

val LocalHideAppChrome = staticCompositionLocalOf { mutableStateOf(false) }

private const val UpdateSnoozeWindowMillis = 24L * 60L * 60L * 1000L

@Composable
private fun HomeUpdatePrompt(currentRoute: String?) {
    if (currentRoute != Screens.Home.route) return

    val updateState by com.musplay.app.ui.screens.update.rememberUpdateState()
    val (snoozedAt, setSnoozedAt) = rememberPreference(
        com.musplay.app.constants.UpdatePromptSnoozedAtKey,
        defaultValue = 0L,
    )
    val (userName) = rememberPreference(com.musplay.app.constants.UsernameKey, "")

    var dismissed by rememberSaveable { mutableStateOf(false) }

    val release = (updateState as? com.musplay.app.core.updater.UpdateState.Available)?.release
    val snoozeExpired = System.currentTimeMillis() - snoozedAt > UpdateSnoozeWindowMillis

    if (release != null && snoozeExpired && !dismissed) {
        com.musplay.app.ui.screens.update.UpdatePromptSheet(
            release = release,
            userName = userName,
            onSnooze = {
                setSnoozedAt(System.currentTimeMillis())
                dismissed = true
            },
            onDismiss = { dismissed = true },
        )
    }
}
val LocalPlayerAwareWindowInsets = staticCompositionLocalOf<WindowInsets> { error("No WindowInsets provided") }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }
val LocalSyncUtils = staticCompositionLocalOf<SyncUtils> { error("No SyncUtils provided") }
val LocalCropAlbumArt = compositionLocalOf { false }
val LocalGridItemSize = compositionLocalOf { GridItemSize.BIG }
val LocalSwipeToSong = compositionLocalOf { false }
val LocalIsPlayerExpanded = compositionLocalOf { false }

/**
 * Colour extracted from the current track's artwork.
 *
 * Exposed so Material 3 surfaces in the player can be seeded from the music rather than the device
 * wallpaper - which is the whole point of the M3 player styles.
 */
val LocalArtworkColor = androidx.compose.runtime.compositionLocalOf { DefaultThemeColor }

val LocalDetailAccentColor = staticCompositionLocalOf<androidx.compose.runtime.MutableState<Color?>> {
    androidx.compose.runtime.mutableStateOf(null)
}
