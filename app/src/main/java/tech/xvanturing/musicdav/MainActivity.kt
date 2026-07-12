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
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
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
    data class Edit(val album: tech.xvanturing.musicdav.data.Album?) : AppScreen
    data object Favorites : AppScreen
    data object Search : AppScreen
}

/**
 * Whether this screen should be presented as a "sheet" over the home tabs —
 * sliding up from the bottom on enter (and down on exit) instead of the
 * regular horizontal push/pop used for depth navigation within a stack.
 *
 * Album detail is no longer part of [AppScreen] — it is rendered as its own
 * finger-following drag sheet driven by a single sheetProgress value (see
 * [MusicPlayerApp]) layered above this root router instead of going through
 * [AnimatedContent].
 */
private fun AppScreen.isSheet(): Boolean =
    this is AppScreen.Favorites || this is AppScreen.Search

/** Navigation depth used to pick a transition direction in [MusicPlayerApp]. */
private fun AppScreen.depth(): Int = when (this) {
    AppScreen.Tabs -> 0
    is AppScreen.Favorites -> 1
    is AppScreen.Search -> 1
    is AppScreen.Edit -> 2
}

/**
 * 专辑详情 sheet 的开合，全部由**唯一一个进度值** `sheetProgress` ∈ [0,1] 驱动
 * （见 [MusicPlayerApp]）：0 = 完全关闭（列表藏在底栏下方），1 = 完全打开（铺满整屏）。
 * sheet 位移、导航栏收起、是否挂载 都只**读**这个值推算；改它只有两条路：程序动画
 * （点击打开 / 返回关闭 / 松手吸附）与手指拖拽（轮播上拉 / 详情列表下拉），且任何拖拽都会
 * 取消正在跑的动画，保证同一时刻只有一个驱动者——彻底原子，不再有多输入抢一个 offset 的竞态。
 */

/** 程序动画（点击打开 / 返回关闭 / 松手吸附）用的弹簧：临界阻尼、不回弹。 */
private val SheetAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

/** 松手速度（px/s）超过它就只按方向吸附，忽略当前进度是否过阈值。 */
private const val SheetVelocityThreshold = 1000f

/** 松手速度不够时，按进度是否过这个比例决定吸附到打开(1)还是关闭(0)。 */
private const val SheetPositionalThreshold = 0.4f

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
    // 首页胶片轮播当前页号，提升到这里以免详情页显示时 Tabs 内容被销毁重建导致轮播复位。
    // -1 = 未设置，交给 CarouselHomeContent 使用其默认起始页（Favorites 居中）。
    var carouselPage by rememberSaveable { mutableIntStateOf(-1) }

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
        else -> AppScreen.Tabs
    }

    // 专辑详情不再是 AnimatedContent 里的一个 AppScreen 分支，而是叠在根内容之上的手指跟随 sheet。
    // 唯一真相源：开合进度 sheetProgress ∈ [0,1]，0=完全关闭、1=完全打开（见文件顶部 SheetAnimationSpec
    // 附近的说明）。selectedAlbum 负责挂载/卸载 sheet 内容；sheetProgress 负责它的位置与导航栏收起。
    val scope = rememberCoroutineScope()

    // 内容区高度（px，也是 sheet 完全关闭时需要下移藏起的行程基准）与常驻底栏（播放条+导航栏）整体
    // 高度（px）。底栏高度用 maxOf 保留展开时的完整值，免得导航栏收起动画中途变矮把行程算歪。
    var contentAreaHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    // 导航栏自身高度（px）：底栏随 sheet 打开整体下移的距离就是它——下移一个导航栏高度，播放条正好
    // 沉到手势条上方、导航栏滑出屏外。用 maxOf 保留展开时的完整高度。
    var navBarHeightPx by remember { mutableIntStateOf(0) }

    var sheetProgress by remember { mutableFloatStateOf(0f) }
    var sheetAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // sheet 从完全关闭到完全打开需要移动的距离（px）：关闭时 sheet 顶边正好贴在底栏顶部（被挡住），
    // 打开时到屏顶。sheetProgress=0 → 位移 = 这段行程；=1 → 位移 0。
    val sheetTravelPx: () -> Float =
        { (contentAreaHeightPx - bottomBarHeightPx).coerceAtLeast(0).toFloat() }

    // ① 程序动画到某进度。到 0（完全关闭）时把 selectedAlbum 卸载——挂载/卸载与进度落定原子绑定：
    // 只有关闭动画“真正跑完”这一行才执行，被拖拽取消则抛 CancellationException、这行不会跑，绝不会
    // 在动画途中把列表卸掉。
    // velocityPxPerSec 是松手瞬间的竖直速度（向上为负），换算成进度/秒（-v/行程，向上=进度增大=正）
    // 传给 animate 作初速度，让"甩"的动量接进吸附动画里——不传就是从静止弹起，手感发肉、不跟手。
    fun animateSheetTo(target: Float, velocityPxPerSec: Float = 0f) {
        sheetAnimJob?.cancel()
        val travel = sheetTravelPx()
        val initialVelocity = if (travel > 0f) -velocityPxPerSec / travel else 0f
        sheetAnimJob = scope.launch {
            animate(
                initialValue = sheetProgress,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = SheetAnimationSpec
            ) { value, _ -> sheetProgress = value }
            if (target <= 0f) selectedAlbum = null
        }
    }

    // ② 手指拖拽：把「上抬的像素」换算成进度增量直接赋值（上抬为正 = 更打开）。先取消动画，确保
    // 拖拽期间只有手指在驱动这个值，没有第二个驱动者。
    fun dragSheetByRisePx(risePx: Float) {
        sheetAnimJob?.cancel()
        sheetAnimJob = null
        val travel = sheetTravelPx()
        if (travel <= 0f) return
        sheetProgress = (sheetProgress + risePx / travel).coerceIn(0f, 1f)
    }

    // 松手吸附：速度够大只看方向，否则看进度是否过阈值，动画到打开(1)或关闭(0)。velocity 为 px/s，
    // 向上为负。
    fun settleSheet(velocityPxPerSec: Float) {
        val target = when {
            velocityPxPerSec <= -SheetVelocityThreshold -> 1f
            velocityPxPerSec >= SheetVelocityThreshold -> 0f
            sheetProgress > SheetPositionalThreshold -> 1f
            else -> 0f
        }
        animateSheetTo(target, velocityPxPerSec)   // 把松手速度带进吸附动画，保留甩的动量
    }

    // 详情列表的嵌套滚动：列表未到顶时上滑先喂给 sheet 继续打开；到顶后下滑的剩余量喂给 sheet 收起；
    // 松手（fling）交给 settleSheet 吸附。全部通过上面同一套函数改 sheetProgress，与拖拽/动画同源。
    val sheetNestedScroll = remember {
        object : NestedScrollConnection {
            private fun consumeToSheet(dy: Float): Float {
                val travel = sheetTravelPx()
                if (travel <= 0f) return 0f
                val before = sheetProgress
                dragSheetByRisePx(-dy)   // 上滑 dy<0 → rise>0 打开；下滑 dy>0 → rise<0 收起
                return -(sheetProgress - before) * travel   // 换回滚动坐标（向下为正）的已消费像素
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                return if (dy < 0 && source == NestedScrollSource.UserInput && sheetProgress < 1f) {
                    Offset(0f, consumeToSheet(dy))
                } else Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val dy = available.y
                return if (dy > 0 && source == NestedScrollSource.UserInput) {
                    Offset(0f, consumeToSheet(dy))
                } else Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // 上甩且未完全打开：带速度吸附（开），并吃掉这段速度，别让列表再 fling。
                return if (available.y < 0 && sheetProgress < 1f) {
                    settleSheet(available.y); available
                } else Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 只处理列表消费完后剩下的“向下”甩（列表已到顶）：带速度吸附（关）。上甩已由
                // onPreFling 处理，这里不能再无条件 settle，否则会用速度 0 覆盖掉上面带速度的吸附。
                return if (available.y > 0f) {
                    settleSheet(available.y); available
                } else Velocity.Zero
            }
        }
    }

    // 底栏「下移/收起」比例的实时读取器：0 = 完全展开，1 = 完全收起（下移一个导航栏高度）。
    // 直接等于 sheetProgress，因此**跨越整段上拉行程**、跟手同步——修用户反馈"手势没划到一半底部
    // 动画就结束了"。以 lambda 形式提供，供底栏的 offset/graphicsLayer 延迟块每帧读取，只重新放置/
    // 合成、不重排(relayout)，消除"顿挫感"。没有 sheet 挂载时返回 0（导航栏完整显示），与挂载态原子。
    val bottomBarSlideFraction: () -> Float = {
        if (selectedAlbum == null) 0f else sheetProgress
    }

    // sheet 是否已完全打开。用 derivedStateOf，只在跨过阈值那一刻通知读者、不会每帧重组。
    // 传给 AlbumDetailScreen 作 active：只有真正打开后才发起网络刷新，避免"上拉一点又松开"的
    // 瞬时挂载也请求、失败反复弹 "无法获取最新数据" toast。
    val sheetFullyOpen by remember {
        derivedStateOf { selectedAlbum != null && sheetProgress >= 0.999f }
    }

    // 常驻底栏（迷你播放条 + 3-tab 导航栏）的可见性：导航栏只在首页（Tabs 且没有打开子表单）
    // 时显示，与详情 sheet 是否打开无关——sheet 打开时底栏改由下面的整体位移移出屏幕，不再
    // 靠这里的显隐来"消失"。播放条在编辑表单页隐藏，在 Tabs 内打开子表单（新建专辑/服务器
    // 配置）时也隐藏，其余场景（包括详情 sheet 打开时）常驻，随底栏一起位移。
    val navBarVisible = screen is AppScreen.Tabs && !tabFormOpen
    val playerVisible = when (screen) {
        is AppScreen.Edit -> false
        is AppScreen.Tabs -> !tabFormOpen
        else -> true
    }

    // 底部常驻栏各部分的固定高度（不随导航栏收起动画变化，避免列表逐帧重排）
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val homeBottomInset = 72.dp + 80.dp + systemBottomInset      // 播放条 + 导航栏 + 系统手势条（首页）
    val contentBottomInset = 72.dp + systemBottomInset            // 仅播放条 + 系统手势条（收藏/搜索）

    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        androidx.compose.material3.Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // 底栏：一层持久的手势条填充(永远铺满 inset、不随 sheet 动，杜绝残影) + [播放条][导航栏]
                // 整体随 sheet 打开按 bottomBarSlideFraction **整体下移一个导航栏高度**(placement offset，
                // 不重排)：播放条下沉到手势条上方保持常驻可见，导航栏滑出屏底并淡出。整段动画只做重新
                // 放置/合成、不做逐帧 relayout，也不移动带阴影的播放条布局(阴影只随图层平移，不重绘)，
                // 消除"顿挫感"；且行程跨越整段上拉、不再半路就结束。
                Box(modifier = Modifier.fillMaxWidth()) {
                    // 持久手势条填充：永远贴最底部，surfaceContainerHigh，与播放条/导航栏同色无缝。
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(systemBottomInset)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // 整体下移：offset 是延迟放置块，随 fraction 每帧只重新放置，不触发重排。
                            .offset {
                                IntOffset(0, (bottomBarSlideFraction() * navBarHeightPx).roundToInt())
                            }
                            .padding(bottom = systemBottomInset)   // 内容坐落在手势条填充之上
                            .onSizeChanged { size ->
                                // 测到的是内容(播放条+导航栏)高度、不含 inset，作为 sheet 的 Closed 行程基准。
                                bottomBarHeightPx = maxOf(bottomBarHeightPx, size.height)
                            }
                    ) {
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
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                // 只测量导航栏自身高度作为整体下移的距离；位置下移由外层 Column 的 offset
                                // 统一处理。不做淡出——导航栏在播放条下方整体向下平移滑出屏底，不会盖到
                                // 列表，保持不透明即可。
                                .onSizeChanged { size ->
                                    navBarHeightPx = maxOf(navBarHeightPx, size.height)
                                }
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
            }
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        contentAreaHeightPx = size.height
                    }
            ) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val homeToSheet = initialState is AppScreen.Tabs && targetState.isSheet()
                    val sheetToHome = initialState.isSheet() && targetState is AppScreen.Tabs
                    val targetDepth = targetState.depth()
                    val initialDepth = initialState.depth()
                    when {
                        homeToSheet -> (slideUpEnter() togetherWith fadeOut(tween(MotionSpec.medium)))
                            .apply { targetContentZIndex = 1f }
                        sheetToHome -> (fadeIn(tween(MotionSpec.medium)) togetherWith slideDownExit())
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
                            onSelectAlbum = { album ->
                                // 网格点击 / 非居中碟点击：挂载 sheet 内容并动画到完全打开。
                                selectedAlbum = album
                                animateSheetTo(1f)
                            },
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
                            bottomInset = homeBottomInset,
                            carouselPage = carouselPage,
                            onCarouselPageChange = { carouselPage = it },
                            onSheetDragStart = { album ->
                                // 轮播上拖：挂载 sheet，随后每帧由 onSheetDrag 手指跟随驱动 sheetProgress。
                                selectedAlbum = album
                            },
                            // dy 为每帧竖直位移（向上为负）；向上 = 上抬 = 更打开，故取 -dy 作上抬像素。
                            onSheetDrag = { dy -> dragSheetByRisePx(-dy) },
                            onSheetDragEnd = { velocity -> settleSheet(velocity) }
                        )
                    }
                    }
                }
            }

            // 专辑详情：手指跟随 sheet，叠在 AnimatedContent 之上、NowPlaying 之下。selectedAlbum
            // 负责挂载/卸载这块内容；sheetProgress 驱动它的竖直位置（位移 = (1-progress)*行程）。
            // 位移写在 offset{} 延迟 lambda 里，sheetProgress 变化只重新放置、不重组本函数。
            val sheetAlbum = selectedAlbum
            if (sheetAlbum != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, ((1f - sheetProgress) * sheetTravelPx()).roundToInt()) }
                        .nestedScroll(sheetNestedScroll)
                ) {
                    // AlbumDetailScreen 内部 currentAlbumSongs/hasTriedInitialRefresh 等状态是
                    // 无 key 的 remember；sheet 在同一组合位置切换不同专辑时若不加 key，会复用
                    // 旧实例、残留上一个专辑的歌曲列表（hasTriedInitialRefresh 已为 true 导致跳过
                    // 重新加载）。按 album.id 包一层 key()，换专辑时整棵子树连同其 remember 状态
                    // 一起重建，强制重新加载。
                    androidx.compose.runtime.key(sheetAlbum.id) {
                        AlbumDetailScreen(
                            album = sheetAlbum,
                            onBack = { animateSheetTo(0f) },
                            onEdit = { album -> editingAlbum = album },
                            playlistController = playlistController,
                            modifier = Modifier.fillMaxSize(),
                            // 播放条随 sheet 打开常驻在详情底部可见（只有导航栏收起），所以详情列表要为
                            // 播放条预留高度，否则最后一首会被播放条挡住。
                            bottomInset = contentBottomInset,
                            // 只有 sheet 完全打开后才拉取最新数据——上拉一点又松开(退回)不会触发网络请求。
                            active = sheetFullyOpen
                        )
                    }
                    // sheet 顶部很细的拖拽横线（抓手）：贴在 sheet 顶边，拖拽/半开时可见，接近完全展开时
                    // 淡出，避免遮挡顶栏。alpha 在 graphicsLayer 里读 sheetProgress，逐帧只合成、不重组。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .graphicsLayer {
                                alpha = 0.5f * ((1f - sheetProgress) / 0.2f).coerceIn(0f, 1f)
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
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
    bottomInset: Dp,
    carouselPage: Int = -1,
    onCarouselPageChange: (Int) -> Unit = {},
    onSheetDragStart: (tech.xvanturing.musicdav.data.Album) -> Unit = {},
    onSheetDrag: (Float) -> Unit = {},
    onSheetDragEnd: (Float) -> Unit = {}
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
                                    },
                                    carouselPage = carouselPage,
                                    onCarouselPageChange = onCarouselPageChange,
                                    onSheetDragStart = onSheetDragStart,
                                    onSheetDrag = onSheetDrag,
                                    onSheetDragEnd = onSheetDragEnd
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