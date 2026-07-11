package tech.xvanturing.musicdav

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import tech.xvanturing.musicdav.player.rememberNotificationPermissionState
import tech.xvanturing.musicdav.player.rememberPlaylistStateController
import tech.xvanturing.musicdav.ui.BottomPlayerBar
import tech.xvanturing.musicdav.ui.screen.AlbumCreateForm
import tech.xvanturing.musicdav.ui.screen.AlbumDetailScreen
import tech.xvanturing.musicdav.ui.screen.AlbumListScreen
import tech.xvanturing.musicdav.ui.screen.CacheManagementScreen
import tech.xvanturing.musicdav.ui.screen.FavoritesScreen
import tech.xvanturing.musicdav.ui.screen.NowPlayingScreen
import tech.xvanturing.musicdav.ui.screen.SearchScreen
import tech.xvanturing.musicdav.ui.screen.ServerConfigCreateScreen
import tech.xvanturing.musicdav.ui.screen.ServerConfigListScreen
import tech.xvanturing.musicdav.ui.theme.MotionSpec
import tech.xvanturing.musicdav.ui.theme.MusicDavTheme
import tech.xvanturing.musicdav.ui.theme.VinylEasingDecelerate
import tech.xvanturing.musicdav.ui.theme.VinylEasingStandard
import tech.xvanturing.musicdav.ui.theme.blockPointerInput
import tech.xvanturing.musicdav.ui.theme.fadeThrough
import tech.xvanturing.musicdav.ui.theme.forwardPush
import tech.xvanturing.musicdav.ui.theme.slideDownExit
import tech.xvanturing.musicdav.ui.theme.slideUpEnter

/**
 * Top-level screens that the root router in [MusicPlayerApp] can be showing.
 * Carries whatever payload the destination needs so the outgoing screen in
 * an [AnimatedContent] exit animation never has to re-read top-level mutable
 * state (which may already have changed/gone null by the time the exit
 * animation is still composing the old screen).
 */
private sealed interface AppScreen {
    data object Tabs : AppScreen
    data class Detail(val album: tech.xvanturing.musicdav.data.Album) : AppScreen
    data class Edit(val album: tech.xvanturing.musicdav.data.Album?) : AppScreen
    data object Favorites : AppScreen
    data object Search : AppScreen
}

/** Navigation depth used to pick a transition direction in [MusicPlayerApp]. */
private fun AppScreen.depth(): Int = when (this) {
    AppScreen.Tabs -> 0
    is AppScreen.Favorites -> 1
    is AppScreen.Search -> 1
    is AppScreen.Detail -> 1
    is AppScreen.Edit -> 2
}

/**
 * Small fixed slide distance (in px), mirroring [tech.xvanturing.musicdav.ui.theme.forwardPush]'s
 * `SharedAxisSlidePx` — see that constant for rationale (shared-axis style,
 * forgiving of dropped frames on high-refresh-rate displays).
 */
private const val ReverseSharedAxisSlidePx = 90

/**
 * Mirror of [forwardPush] but sliding the opposite direction (new screen
 * enters from the left, old screen exits to the right) — used when
 * navigating back up the hierarchy so the animation reads as "going back".
 */
private fun reversePush(): ContentTransform {
    val spatialSpring = spring<IntOffset>(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMedium
    )
    val enter = slideInHorizontally(
        animationSpec = spatialSpring
    ) { -ReverseSharedAxisSlidePx } + fadeIn(
        animationSpec = tween(200)
    )
    val exit = slideOutHorizontally(
        animationSpec = spatialSpring
    ) { ReverseSharedAxisSlidePx } + fadeOut(
        animationSpec = tween(120)
    )
    return enter togetherWith exit
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()
        tech.xvanturing.musicdav.data.UiSettings.init(this)


        setContent {
            MusicDavTheme {
                MusicPlayerApp(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /**
     * Requests the display's highest available refresh rate at the current
     * physical resolution (e.g. 120Hz instead of an OEM-capped 90Hz), so
     * animations don't look janky on high-refresh-rate devices. Many OEM
     * refresh-rate policies only unlock the panel's max refresh rate when an
     * app explicitly asks for it via [android.view.WindowManager.LayoutParams.preferredDisplayModeId].
     */
    private fun requestHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay
        } ?: return

        val current = display.mode
        val supported = display.supportedModes
        // Prefer a mode with the SAME physical resolution as the current one, highest refresh rate,
        // so we don't trigger a resolution switch — only a refresh-rate bump.
        val best = supported
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: supported.maxByOrNull { it.refreshRate }
            ?: return

        if (best.refreshRate > current.refreshRate + 0.1f) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
                // Also set the softer hint in case the OEM honors this but not the mode id:
                preferredRefreshRate = best.refreshRate
            }
        }
    }
}


@Composable
fun MusicPlayerApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var albums by remember {
        mutableStateOf(
            tech.xvanturing.musicdav.data.AlbumsRepository.load(
                context
            )
        )
    }
    var selectedAlbum by remember { mutableStateOf<tech.xvanturing.musicdav.data.Album?>(null) }
    var editingAlbum by remember { mutableStateOf<tech.xvanturing.musicdav.data.Album?>(null) }
    var showFavorites by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    val playlistController = rememberPlaylistStateController()
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var tabFormOpen by remember { mutableStateOf(false) }

    // 播放进度每 100ms 刷新一次 state，用 derivedStateOf 只在这两个派生值真正
    // 变化时才通知读者，避免全局旋转驱动跟着一起 100ms 重组一次。
    val playingAlbumIdRoot by remember { derivedStateOf { playlistController.state.currentAlbumId } }
    val isPlayingRoot by remember { derivedStateOf { playlistController.state.isPlaying } }

    // 处理通知权限
    rememberNotificationPermissionState()

    // 在主页面的返回键处理：双击退出
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = selectedAlbum == null && editingAlbum == null && !showFavorites && !showSearch && !showNowPlaying) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            // 两次点击间隔小于2秒，退出应用
            (context as? android.app.Activity)?.finish()
        } else {
            // 第一次点击，显示提示
            lastBackPressTime = currentTime
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.toast_press_again_exit),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    val screen: AppScreen = when {
        editingAlbum != null -> AppScreen.Edit(editingAlbum)
        showFavorites -> AppScreen.Favorites
        showSearch -> AppScreen.Search
        selectedAlbum == null -> AppScreen.Tabs
        else -> AppScreen.Detail(selectedAlbum!!)
    }

    // 常驻底栏（迷你播放条 + 3-tab 导航栏）的可见性：导航栏只在首页（Tabs 且没有打开子表单）显示；
    // 播放条在编辑表单页隐藏，在 Tabs 内打开子表单（新建专辑/服务器配置）时也隐藏，其余场景常驻。
    val navBarVisible = screen is AppScreen.Tabs && !tabFormOpen
    val playerVisible = when (screen) {
        is AppScreen.Edit -> false
        is AppScreen.Tabs -> !tabFormOpen
        else -> true
    }

    // 底部常驻栏各部分的固定高度（不随导航栏收起动画变化，避免列表逐帧重排）
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val homeBottomInset = 72.dp + 80.dp + systemBottomInset      // 播放条 + 导航栏 + 系统手势条（首页）
    val contentBottomInset = 72.dp + systemBottomInset            // 仅播放条 + 系统手势条（详情/收藏/搜索）

    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        androidx.compose.material3.Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    AnimatedVisibility(
                        visible = playerVisible,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        BottomPlayerBar(
                            playlistState = playlistController.state,
                            onPlayPause = {
                                if (playlistController.state.isPlaying) {
                                    playlistController.pause()
                                } else {
                                    playlistController.play()
                                }
                            },
                            onNext = { playlistController.seekToNext() },
                            onPrevious = { playlistController.seekToPrevious() },
                            onTogglePlayMode = { playlistController.togglePlayMode() },
                            onOpenNowPlaying = { showNowPlaying = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    AnimatedVisibility(
                        visible = navBarVisible,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        NavigationBar(
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            NavigationBarItem(
                                selected = selectedTabIndex == 0,
                                onClick = { selectedTabIndex = 0 },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Album,
                                        contentDescription = stringResource(R.string.nav_albums)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_albums)) }
                            )
                            NavigationBarItem(
                                selected = selectedTabIndex == 1,
                                onClick = { selectedTabIndex = 1 },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = stringResource(R.string.nav_servers)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_servers)) }
                            )
                            NavigationBarItem(
                                selected = selectedTabIndex == 2,
                                onClick = { selectedTabIndex = 2 },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = stringResource(R.string.nav_cache)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_cache)) }
                            )
                        }
                    }
                }
            }
        ) { _ ->
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val homeToDetail = initialState is AppScreen.Tabs && targetState is AppScreen.Detail
                    val detailToHome = initialState is AppScreen.Detail && targetState is AppScreen.Tabs
                    val targetDepth = targetState.depth()
                    val initialDepth = initialState.depth()
                    when {
                        homeToDetail -> (slideUpEnter() togetherWith fadeOut(tween(MotionSpec.medium)))
                            .apply { targetContentZIndex = 1f }
                        detailToHome -> (fadeIn(tween(MotionSpec.medium)) togetherWith slideDownExit())
                            .apply { targetContentZIndex = 0f }
                        targetDepth > initialDepth -> forwardPush()
                        targetDepth < initialDepth -> reversePush()
                        else -> fadeThrough()
                    }
                },
                label = "rootNav",
                modifier = Modifier.fillMaxSize()
            ) { target ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (transition.targetState == EnterExitState.PostExit) Modifier.blockPointerInput() else Modifier)
                ) {
                    when (target) {
                    is AppScreen.Edit -> {
                        // Show edit form
                        AlbumCreateForm(
                            onCancel = { editingAlbum = null },
                            onSave = { name, url, username, password, directoryUrl, coverImageUrl, serverConfigId ->
                                val config = tech.xvanturing.musicdav.data.WebDavConfig(
                                    url = url,
                                    username = username,
                                    password = password
                                )
                                val updatedAlbum = target.album!!.copy(
                                    name = name,
                                    config = config,
                                    directoryUrl = directoryUrl,
                                    coverImageUrl = coverImageUrl,
                                    serverConfigId = serverConfigId
                                )
                                // Update the album in the list
                                val updatedAlbums =
                                    albums.map { if (it == target.album) updatedAlbum else it }
                                albums = updatedAlbums
                                tech.xvanturing.musicdav.data.AlbumsRepository.save(
                                    context,
                                    updatedAlbums
                                )
                                // Update selected album if it's the same one
                                if (selectedAlbum == target.album) {
                                    selectedAlbum = updatedAlbum
                                }
                                editingAlbum = null
                            },
                            editingAlbum = target.album
                        )
                    }

                    AppScreen.Favorites -> {
                        FavoritesScreen(
                            onBack = { showFavorites = false },
                            playlistController = playlistController,
                            modifier = Modifier.fillMaxSize(),
                            bottomInset = contentBottomInset
                        )
                    }

                    AppScreen.Search -> {
                        SearchScreen(
                            onBack = { showSearch = false },
                            playlistController = playlistController,
                            modifier = Modifier.fillMaxSize(),
                            bottomInset = contentBottomInset
                        )
                    }

                    AppScreen.Tabs -> {
                        MainTabScreen(
                            albums = albums,
                            onRefreshAlbums = {
                                albums = tech.xvanturing.musicdav.data.AlbumsRepository.load(context)
                            },
                            onSelectAlbum = { selectedAlbum = it },
                            onCreateAlbum = { album, serverConfigId ->
                                val updated = albums + album
                                albums = updated
                                tech.xvanturing.musicdav.data.AlbumsRepository.save(context, updated)
                                // 不自动导航到专辑详情，保持在列表页面
                            },
                            onDeleteAlbum = { album ->
                                val updated = albums.filterNot { it.id == album.id }
                                albums = updated
                                tech.xvanturing.musicdav.data.AlbumsRepository.save(context, updated)
                            },
                            onOpenFavorites = { showFavorites = true },
                            onOpenSearch = { showSearch = true },
                            playlistController = playlistController,
                            modifier = Modifier.fillMaxSize(),
                            selectedTabIndex = selectedTabIndex,
                            onFormOpenChange = { tabFormOpen = it },
                            bottomInset = homeBottomInset
                        )
                    }

                    is AppScreen.Detail -> {
                        AlbumDetailScreen(
                            album = target.album,
                            onBack = { selectedAlbum = null },
                            onEdit = { album -> editingAlbum = album },
                            playlistController = playlistController,
                            modifier = Modifier.fillMaxSize(),
                            bottomInset = contentBottomInset
                        )
                    }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showNowPlaying,
            enter = slideUpEnter(),
            exit = slideDownExit()
        ) {
            NowPlayingScreen(
                playlistController = playlistController,
                onBack = { showNowPlaying = false },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 全局黑胶旋转时钟：首页轮播的当前碟与播放页大碟共用同一个角度，只在两者之一
        // 可能可见、且正在播放时才推进。
        val vinylOnScreen = showNowPlaying || (screen is AppScreen.Tabs && selectedTabIndex == 0)
        tech.xvanturing.musicdav.ui.components.VinylRotationDriver(
            playingAlbumId = playingAlbumIdRoot,
            active = isPlayingRoot && vinylOnScreen
        )
    }
}

/**
 * Screens that [MainTabScreen] itself can be showing, layered above the tab
 * scaffold. Carries whatever payload the destination needs so the outgoing
 * screen in an [AnimatedContent] exit animation never has to re-read this
 * composable's mutable state (which may already have changed/gone null by
 * the time the exit animation is still composing the old screen).
 */
private sealed interface TabRoute {
    data object Main : TabRoute
    data object CreateAlbum : TabRoute
    data class EditServerConfig(val editing: tech.xvanturing.musicdav.data.ServerConfig?) :
        TabRoute
}

/** Navigation depth used to pick a transition direction in [MainTabScreen]. */
private fun TabRoute.depth(): Int = when (this) {
    TabRoute.Main -> 0
    TabRoute.CreateAlbum -> 1
    is TabRoute.EditServerConfig -> 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabScreen(
    albums: List<tech.xvanturing.musicdav.data.Album>,
    onRefreshAlbums: () -> Unit,
    onSelectAlbum: (tech.xvanturing.musicdav.data.Album) -> Unit,
    onCreateAlbum: (tech.xvanturing.musicdav.data.Album, String?) -> Unit,
    onDeleteAlbum: (tech.xvanturing.musicdav.data.Album) -> Unit,
    onOpenFavorites: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    playlistController: tech.xvanturing.musicdav.player.PlaylistStateController,
    modifier: Modifier = Modifier,
    selectedTabIndex: Int,
    onFormOpenChange: (Boolean) -> Unit,
    bottomInset: Dp
) {
    var creatingAlbum by remember { mutableStateOf(false) }
    var creatingServerConfig by remember { mutableStateOf(false) }
    var editingServerConfig by remember {
        mutableStateOf<tech.xvanturing.musicdav.data.ServerConfig?>(
            null
        )
    }
    var serverConfigsRefreshKey by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 播放进度每 100ms 刷新一次 state，用 derivedStateOf 只在这两个派生值真正
    // 变化时才通知读者，避免首页胶片轮播每 100ms 重组一次。
    val playingAlbumId by remember { derivedStateOf { playlistController.state.currentAlbumId } }
    val isPlayingNow by remember { derivedStateOf { playlistController.state.isPlaying } }

    // 拦截返回键处理创建状态
    androidx.activity.compose.BackHandler(enabled = creatingAlbum || creatingServerConfig || editingServerConfig != null) {
        if (creatingAlbum) {
            creatingAlbum = false
        } else if (creatingServerConfig) {
            creatingServerConfig = false
        } else if (editingServerConfig != null) {
            editingServerConfig = null
        }
    }

    // 如果正在创建专辑或服务器配置，显示相应的创建屏幕
    val tabRoute: TabRoute = when {
        creatingAlbum -> TabRoute.CreateAlbum
        creatingServerConfig || editingServerConfig != null ->
            TabRoute.EditServerConfig(editingServerConfig)

        else -> TabRoute.Main
    }

    // 通知根部：是否正打开新建专辑/服务器配置表单，据此收起常驻底栏
    LaunchedEffect(tabRoute) {
        onFormOpenChange(tabRoute != TabRoute.Main)
    }

    AnimatedContent(
        targetState = tabRoute,
        transitionSpec = {
            val targetDepth = targetState.depth()
            val initialDepth = initialState.depth()
            when {
                targetDepth > initialDepth -> forwardPush()
                targetDepth < initialDepth -> reversePush()
                else -> fadeThrough()
            }
        },
        label = "tabRoute",
        modifier = modifier
    ) { route ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (transition.targetState == EnterExitState.PostExit) Modifier.blockPointerInput() else Modifier)
        ) {
        when (route) {
            TabRoute.CreateAlbum -> {
                AlbumCreateForm(
                    onCancel = { creatingAlbum = false },
                    onSave = { name, url, username, password, directoryUrl, coverImageUrl, serverConfigId ->
                        val config = tech.xvanturing.musicdav.data.WebDavConfig(
                            url = url,
                            username = username,
                            password = password
                        )
                        val album = tech.xvanturing.musicdav.data.Album(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name,
                            config = config,
                            directoryUrl = directoryUrl,
                            coverImageUrl = coverImageUrl,
                            serverConfigId = serverConfigId
                        )
                        onCreateAlbum(album, serverConfigId)
                        creatingAlbum = false
                    },
                    onCreateServerConfig = {
                        creatingAlbum = false
                        creatingServerConfig = true
                    }
                )
            }

            is TabRoute.EditServerConfig -> {
                ServerConfigCreateScreen(
                    editingConfig = route.editing,
                    onCancel = {
                        creatingServerConfig = false
                        editingServerConfig = null
                    },
                    onSave = { config ->
                        // 服务器配置已保存
                        creatingServerConfig = false
                        editingServerConfig = null
                        serverConfigsRefreshKey++
                    }
                )
            }

            TabRoute.Main -> {
                // 主标签页布局：常驻底栏（播放条+导航栏）已上提到根 Scaffold，这里只放 tab 内容
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = selectedTabIndex,
                        transitionSpec = { fadeThrough() },
                        label = "tabNav"
                    ) { tab ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (transition.targetState == EnterExitState.PostExit) Modifier.blockPointerInput() else Modifier)
                        ) {
                        when (tab) {
                            0 -> {
                                AlbumListScreen(
                                    albums = albums,
                                    onSelect = onSelectAlbum,
                                    onCreate = { album, serverConfigId ->
                                        onCreateAlbum(album, serverConfigId)
                                    },
                                    onDelete = onDeleteAlbum,
                                    onAddButtonClick = { creatingAlbum = true },
                                    onImportSuccess = { onRefreshAlbums() },
                                    onOpenFavorites = onOpenFavorites,
                                    onOpenSearch = onOpenSearch,
                                    modifier = Modifier.fillMaxSize(),
                                    bottomInset = bottomInset,
                                    playingAlbumId = playingAlbumId,
                                    isPlaying = isPlayingNow,
                                    onPlayAlbum = { album ->
                                        scope.launch {
                                            tech.xvanturing.musicdav.player.playAlbumFromStart(
                                                context = context,
                                                album = album,
                                                playlistController = playlistController,
                                                onNeedsDetail = { onSelectAlbum(album) }
                                            )
                                        }
                                    },
                                    onTogglePlayPause = {
                                        if (playlistController.state.isPlaying) {
                                            playlistController.pause()
                                        } else {
                                            playlistController.play()
                                        }
                                    }
                                )
                            }

                            1 -> {
                                ServerConfigListScreen(
                                    onCreate = { creatingServerConfig = true },
                                    onEdit = { config -> editingServerConfig = config },
                                    refreshKey = serverConfigsRefreshKey,
                                    onImportSuccess = { onRefreshAlbums() },
                                    modifier = Modifier.fillMaxSize(),
                                    bottomInset = bottomInset
                                )
                            }

                            2 -> {
                                CacheManagementScreen(
                                    bottomInset = bottomInset,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
        }
    }
}