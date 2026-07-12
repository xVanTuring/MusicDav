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
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
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
 * finger-following drag sheet (see [SheetAnchor] / [MusicPlayerApp]) layered
 * above this root router instead of going through [AnimatedContent].
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
 * Anchor values for the album detail drag sheet's [AnchoredDraggableState].
 * [Closed] positions the sheet fully below the visible content area (offset
 * == content height, in px); [Open] positions it at offset 0.
 */
private enum class SheetAnchor { Closed, Open }

/** Spring used for both programmatic (click-to-open / back-arrow-to-close) and
 * fling-settle sheet animations — critically damped so it never overshoots. */
private val SheetAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

/** Fling velocity (px/s) above which a release settles by direction alone,
 * ignoring how far past the 40% positional threshold the drag got. */
private const val SheetVelocityThreshold = 1000f

/** Positional threshold (fraction of the Closed<->Open distance) past which a
 * released drag with sub-threshold velocity snaps to Closed instead of Open. */
private const val SheetPositionalThreshold = 0.4f

/**
 * Decide whether a released drag on the album detail sheet should settle to
 * [SheetAnchor.Open] or [SheetAnchor.Closed], considering both how far the
 * offset has travelled from Closed towards Open (>[SheetPositionalThreshold]
 * of the total distance commits to Open) and the release velocity (a fast
 * enough fling wins regardless of position).
 */
private suspend fun AnchoredDraggableState<SheetAnchor>.settleSheet(velocity: Float) {
    val openOffset = anchors.positionOf(SheetAnchor.Open)
    val closedOffset = anchors.positionOf(SheetAnchor.Closed)
    if (openOffset.isNaN() || closedOffset.isNaN() || openOffset == closedOffset) return
    val currentOffset = if (offset.isNaN()) closedOffset else offset
    // 0f = 完全 Closed，1f = 完全 Open。
    val openedProgress =
        ((closedOffset - currentOffset) / (closedOffset - openOffset)).coerceIn(0f, 1f)
    val target = when {
        velocity <= -SheetVelocityThreshold -> SheetAnchor.Open
        velocity >= SheetVelocityThreshold -> SheetAnchor.Closed
        openedProgress > SheetPositionalThreshold -> SheetAnchor.Open
        else -> SheetAnchor.Closed
    }
    animateTo(target, SheetAnimationSpec)
}

/**
 * Standard "pull to dismiss a bottom sheet whose content is scrollable"
 * nested scroll wiring, mirroring androidx.compose.material3's internal
 * ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection (not public, so
 * reimplemented here against the public foundation.gestures API): upward
 * drags are offered to the sheet first (pre-scroll, so it can finish closing
 * the gap to fully Open before the list itself scrolls); downward drags are
 * only handed to the sheet once the list has nothing left to consume
 * (post-scroll, i.e. the list is already at its top). Flings are settled via
 * [settleSheet] on both pre- and post-fling so a released drag always snaps.
 */
private class SheetNestedScrollConnection(
    private val sheetState: AnchoredDraggableState<SheetAnchor>,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (delta < 0 && source == NestedScrollSource.UserInput) {
            Offset(0f, sheetState.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        return if (source == NestedScrollSource.UserInput) {
            Offset(0f, sheetState.dispatchRawDelta(available.y))
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val toFling = available.y
        val currentOffset = sheetState.requireOffset()
        val minAnchor = sheetState.anchors.minPosition()
        return if (toFling < 0 && currentOffset > minAnchor) {
            sheetState.settleSheet(toFling)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        sheetState.settleSheet(available.y)
        return available
    }
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

    // 专辑详情不再是 AnimatedContent 里的一个 AppScreen 分支，而是叠在根内容之上的
    // 手指跟随 drag sheet：selectedAlbum 只驱动这个 sheet 的显隐，sheetState 驱动它的
    // 开合动画/手势（见下方 Scaffold content 里的渲染，以及 CarouselHomeContent 的
    // onSheetDragStart/onSheetDrag/onSheetDragEnd 三个回调）。
    val scope = rememberCoroutineScope()
    val sheetState = remember { AnchoredDraggableState(initialValue = SheetAnchor.Closed) }
    // 点击(网格/非居中碟)打开详情时，先同步置 false 以在打开动画期间摘掉 sheet 的拖拽/嵌套滚动
    // 手势，避免真机上「按下→微移→抬起」这段还没结束的触摸被刚组合出来的 anchoredDraggable 当作
    // 拖拽接管，打断打开动画并按≈0 位移回弹(#2.5 真机 bug)。动画结束(finally)后恢复为 true。
    // 轮播上拉打开走的是手指跟随路径，全程保持 true。
    var sheetDragEnabled by remember { mutableStateOf(true) }
    val sheetNestedScrollConnection = remember(sheetState) { SheetNestedScrollConnection(sheetState) }
    val sheetFlingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = sheetState,
        positionalThreshold = { distance -> distance * SheetPositionalThreshold },
        animationSpec = SheetAnimationSpec
    )
    // Sheet 真正落到 Closed 后清空 selectedAlbum（sheet 内容随之被移出组合）。这里必须**同时**要求
    // settledValue 与 targetValue 都是 Closed：settledValue 只在动画完成的一刻才翻，而 targetValue 是
    // 当前的意图/去向。打开过程中 targetValue==Open，即使某一帧 settledValue 读到 Closed 也不会卸载，
    // 保证挂载(selectedAlbum) / 位置(sheetState) / 导航栏收起三者原子一致，绝不会中途把列表卸掉。
    LaunchedEffect(sheetState) {
        snapshotFlow {
            sheetState.settledValue == SheetAnchor.Closed && sheetState.targetValue == SheetAnchor.Closed
        }.collect { fullyClosed ->
            if (fullyClosed) selectedAlbum = null
        }
    }

    // 详情 sheet 所在内容区的高度（px，即 sheetState 的 Closed 锚点偏移量），由下方内容 Box
    // 的 onSizeChanged 写入；常驻底栏（播放条+导航栏）整体高度（px），由 bottomBar 自己的
    // onSizeChanged 写入，用 maxOf 保留最大值以免动画中途变小导致底栏跟手比例跳变。
    var contentAreaHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }

    // Sheet 的 Closed 锚点偏移 = 内容区高度 - 底部栏高度，即收起时 sheet 顶边正好贴着底部栏
    // （播放条+导航栏）顶部，被其挡住而不可见；上拉行程也因此缩短一个底部栏高度。任一高度
    // 变化都要重新计算锚点，避免底部栏高度晚于内容区测量到时 Closed 偏移仍停留在旧值。
    LaunchedEffect(contentAreaHeightPx, bottomBarHeightPx) {
        if (contentAreaHeightPx > 0) {
            sheetState.updateAnchors(
                DraggableAnchors {
                    SheetAnchor.Closed at (contentAreaHeightPx - bottomBarHeightPx).coerceAtLeast(0).toFloat()
                    SheetAnchor.Open at 0f
                }
            )
        }
    }

    // 导航栏收起比例的实时读取器：0 = 完全展开，1 = 完全收起。用来让 3-tab 导航栏随 sheet 上抬
    // 而收起（布局高度按 1-fraction 收缩 + 淡出），而播放条不加位移、始终常驻可见。以 lambda 形式
    // 提供，好让读者（导航栏的 layout/graphicsLayer 块）在 sheetState.offset 每帧变化时被直接触发
    // 重算，实现跟手同步，而不是依赖外层重组。
    //
    // 关键：收起行程用**一个底栏高度**(bottomBarHeightPx)，而不是 sheet 的整段行程(closed，≈整屏)。
    // 因为 sheet 一从 Closed 上抬就会盖住底栏所在的那块区域；若按整段行程线性收起，等 sheet 已经抬
    // 上来一大截，导航栏才收起了一点点，就会大面积残留、盖在列表上——黑胶模式慢速上拉时看到的
    // "底部 tab 恢复/残留一半"。按底栏高度收起，可让导航栏在上拉之初就迅速让位、抬过底栏即完全收起。
    // sheetState.offset 在锚点未初始化前是 NaN，此时按未收起（0f）处理，避免调用 requireOffset() 抛异常。
    val navBarCollapseFraction: () -> Float = {
        val currentOffset = sheetState.offset
        val closed = (contentAreaHeightPx - bottomBarHeightPx).coerceAtLeast(0).toFloat()
        if (bottomBarHeightPx <= 0 || closed <= 0f || currentOffset.isNaN()) {
            0f
        } else {
            // (closed - currentOffset) = sheet 从 Closed 上抬的距离；抬过一个底栏高度即完全收起。
            ((closed - currentOffset) / bottomBarHeightPx).coerceIn(0f, 1f)
        }
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
                Column(
                    modifier = Modifier
                        // 底栏背景铺满到物理屏幕底部（含手势导航条那段 inset），内容(播放条+导航栏)只上移
                        // 到手势条上方。这样不管此刻底部是导航栏(首页)还是播放条(详情/收藏)，手势条区域都
                        // 被同一底栏色填满，不再露出屏幕背景色带（即用户说的"残影"）。背景色取 surfaceContainerHigh
                        // 与播放条一致，并把导航栏容器色也统一成它，衔接处无色差。
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(bottom = systemBottomInset)
                        .onSizeChanged { size ->
                            // onSizeChanged 在 padding 内侧，测到的是内容(播放条+导航栏)高度、不含 inset，
                            // 与之前 navigationBarsPadding 时的语义一致，sheet 几何(Closed 锚点)不变。
                            // 导航栏收起时 Column 会变矮，逐帧回调；用 maxOf 保留展开时的完整高度。
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
                            // 底部 inset 由外层 Column 的 padding 统一处理，这里不再自己留 inset。
                            // 容器色与播放条/底栏填充统一，衔接处无色差。
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                // 导航栏随详情 sheet 展开而收起：淡出 + 布局高度按 (1-fraction) 收缩。
                                // 它在 Column 里位于播放条下方、Column 底部锚定，收缩其布局高度会让上方
                                // 播放条自然下沉到屏底并保持可见（播放条本身不加位移）。clipToBounds 裁掉
                                // 收缩后溢出的部分。fraction 在 layout/graphicsLayer 块里实时读取，随
                                // sheetState.offset 每帧变化重算，实现跟手同步。
                                .graphicsLayer { alpha = (1f - navBarCollapseFraction()).coerceIn(0f, 1f) }
                                .clipToBounds()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val collapsed = (placeable.height * (1f - navBarCollapseFraction()))
                                        .roundToInt().coerceAtLeast(0)
                                    layout(placeable.width, collapsed) { placeable.place(0, 0) }
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
                                // 网格点击 / 非居中碟点击：挂载 sheet 内容并直接 animateTo(Open)。
                                // 关键：**不做 snapTo(Closed)**。sheet 关闭后 selectedAlbum 才会被清空，
                                // 此时 sheetState 已停在 Closed，直接从 Closed 动到 Open 即可；多余的
                                // snapTo(Closed) 会把 settledValue 瞬时打回 Closed，被下面"落到 Closed 就
                                // 卸载"的收集器误判成关闭 → 中途清掉 selectedAlbum（列表消失）而 offset 还在
                                // 往 Open animate（底栏还在动），正是"底栏上下闪、看不到列表、最后卡成塌陷"。
                                // 打开动画期间禁用 sheet 手势（sheetDragEnabled），动画结束(finally)恢复。
                                sheetDragEnabled = false
                                selectedAlbum = album
                                scope.launch {
                                    try {
                                        sheetState.animateTo(SheetAnchor.Open, SheetAnimationSpec)
                                    } finally {
                                        sheetDragEnabled = true
                                    }
                                }
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
                                // 轮播上拖：sheet 从 Closed 起点开始，随后每帧由 onSheetDrag 手指跟随。
                                selectedAlbum = album
                            },
                            onSheetDrag = { dy -> sheetState.dispatchRawDelta(dy) },
                            onSheetDragEnd = { velocity ->
                                scope.launch { sheetState.settleSheet(velocity) }
                            }
                        )
                    }
                    }
                }
            }

            // 专辑详情：偏移量驱动的手指跟随 sheet，叠在 AnimatedContent 之上、NowPlaying 之下。
            // selectedAlbum 只负责挂载/卸载这块内容；sheetState 驱动它的位置/开合手势。
            val sheetAlbum = selectedAlbum
            if (sheetAlbum != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, sheetState.requireOffset().roundToInt()) }
                        // 点击打开动画期间摘掉拖拽/嵌套滚动，避免真机触摸竞态回弹（#2.5）；动画结束
                        // 后恢复。轮播上拉打开走手指跟随路径，sheetDragEnabled 全程为 true 不受影响。
                        .then(if (sheetDragEnabled) Modifier.nestedScroll(sheetNestedScrollConnection) else Modifier)
                        .then(
                            if (sheetDragEnabled) {
                                Modifier.anchoredDraggable(
                                    state = sheetState,
                                    orientation = Orientation.Vertical,
                                    flingBehavior = sheetFlingBehavior
                                )
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // AlbumDetailScreen 内部 currentAlbumSongs/hasTriedInitialRefresh 等状态是
                    // 无 key 的 remember；sheet 在同一组合位置切换不同专辑时若不加 key，会复用
                    // 旧实例、残留上一个专辑的歌曲列表（hasTriedInitialRefresh 已为 true 导致跳过
                    // 重新加载）。按 album.id 包一层 key()，换专辑时整棵子树连同其 remember 状态
                    // 一起重建，强制重新加载。
                    androidx.compose.runtime.key(sheetAlbum.id) {
                        AlbumDetailScreen(
                            album = sheetAlbum,
                            onBack = { scope.launch { sheetState.animateTo(SheetAnchor.Closed, SheetAnimationSpec) } },
                            onEdit = { album -> editingAlbum = album },
                            playlistController = playlistController,
                            modifier = Modifier.fillMaxSize(),
                            // 播放条随 sheet 打开常驻在详情底部可见（只有导航栏收起），所以详情列表要为
                            // 播放条预留高度，否则最后一首会被播放条挡住。
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