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
import androidx.compose.runtime.snapshotFlow
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
 *
 * Favorites/Search/album-detail are no longer part of [AppScreen] — they are
 * rendered as [SheetContent], a single finger-following drag sheet driven by
 * a single sheetProgress value (see [MusicPlayerApp]) layered above this root
 * router instead of going through [AnimatedContent].
 */
private sealed interface AppScreen {
    data object Tabs : AppScreen
    data class Edit(val album: tech.xvanturing.musicdav.data.Album?) : AppScreen
}

/** Navigation depth used to pick a transition direction in [MusicPlayerApp]. */
private fun AppScreen.depth(): Int = when (this) {
    AppScreen.Tabs -> 0
    is AppScreen.Edit -> 1
}

/**
 * Whatever is currently mounted in the drag sheet layered above the root
 * router — album detail, favorites, or search. Null means the sheet is
 * unmounted (fully closed). Unifies what used to be three mutually-exclusive
 * booleans/nullables (`selectedAlbum`/`showFavorites`/`showSearch`) into one
 * state, since only one of them could ever be open at a time anyway.
 */
private sealed interface SheetContent {
    data class AlbumDetail(val album: tech.xvanturing.musicdav.data.Album) : SheetContent
    data object Favorites : SheetContent
    data object Search : SheetContent
}

/**
 * sheet 的开合，全部由**唯一一个进度值** `sheetProgress` ∈ [0,1] 驱动
 * （见 [MusicPlayerApp]）：0 = 完全关闭（列表藏在底栏下方），1 = 完全打开（铺满整屏）。
 * sheet 位移、导航栏收起、是否挂载 都只**读**这个值推算；改它只有两条路：程序动画
 * （点击打开 / 返回关闭 / 松手吸附）与手指拖拽（轮播上拉 / 详情列表下拉），且任何拖拽都会
 * 取消正在跑的动画，保证同一时刻只有一个驱动者——彻底原子，不再有多输入抢一个 offset 的竞态。
 * 挂载哪块内容（专辑详情/收藏夹/搜索）由 [SheetContent] 决定，与这套开合机制完全解耦。
 */

/**
 * 程序动画（点击打开 / 返回关闭 / 松手吸附）用的弹簧：临界阻尼、不回弹。
 * 刚度用 MediumLow(400)：之前的 Medium(1500) 全屏行程 ~120ms 就收完，松手瞬间像被"吸走"、
 * 显得急促；400 约 250-300ms 滑行更顺。快甩时松手速度会注入动画初速度（见 animateSheetTo），
 * 起步依旧跟手不发肉，软刚度只影响后段减速的从容程度。
 */
private val SheetAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/**
 * 松手速度（px/s）超过它就只按方向吸附，忽略当前进度是否过阈值。
 * 从 1000 降到 600：整段行程是接近整屏的高度，"轻轻一甩就开"才符合状态栏下拉那种手感，
 * 1000 要求甩得相当猛，日常上拉经常达不到 → 反复上滑打不开。
 */
private const val SheetVelocityThreshold = 600f

/**
 * 松手速度不够时的位置判定，**相对本次拖拽的起点**而不是绝对进度：从关着拉开，拉过这一比例的
 * 行程就算开；从开着往下拖，同样拖过这一比例就算关。
 *
 * 旧写法是一个绝对阈值 0.4：从关着要拖过全屏 40% 才开（"很容易不展开"），而从开着往下拖到 39%
 * 才关（下拉一大截仍弹回）。同一个数字对两个方向都别扭。改成相对起点后两边都只要 1/4 行程，
 * 和状态栏下拉的手感一致。
 */
private const val SheetFlipThreshold = 0.25f

/** 吸附动画注入的初速度上限（进度/秒）。5 ≈ 0.2 秒跑完整段行程，再快就只是视觉噪音了。 */
private const val MaxSettleVelocity = 5f

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
    var sheetContent by remember { mutableStateOf<SheetContent?>(null) }
    var editingAlbum by remember { mutableStateOf<tech.xvanturing.musicdav.data.Album?>(null) }
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
    androidx.activity.compose.BackHandler(enabled = sheetContent == null && editingAlbum == null && !showNowPlaying) {
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
        else -> AppScreen.Tabs
    }

    // 专辑详情/收藏夹/搜索都不是 AnimatedContent 里的 AppScreen 分支，而是叠在根内容之上的手指
    // 跟随 sheet。唯一真相源：开合进度 sheetProgress ∈ [0,1]，0=完全关闭、1=完全打开（见文件顶部
    // SheetAnimationSpec 附近的说明）。sheetContent 负责挂载/卸载 sheet 内容；sheetProgress 负责它的
    // 位置与导航栏收起。
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

    // 开合「意图」：true = 目标是打开。它和 sheetProgress 一起构成状态机的完整状态——
    // 进度说"现在在哪"，意图说"要去哪"。卸载判定只认这两个（外加手指在不在），不再依赖
    // "关闭动画有没有跑完"。旧写法把 sheetContent = null 挂在关闭动画的最后一行：动画一旦被
    // 拖拽/嵌套滚动取消（反复上拉下拉时常发生），那行就永远不执行，于是 sheet 明明进度已经是 0、
    // 内容却还挂着，屏幕外躺着一个不可见但活着的详情页——正是"多次操作后状态异常"的来源。
    var sheetOpenTarget by remember { mutableStateOf(false) }

    // 手指是否正按着驱动 sheet（轮播上拉 / 顶栏下拉 / 列表嵌套滚动）。拖拽途中即使进度归零也
    // 不能卸载，否则手还没松内容就没了。
    var sheetDragging by remember { mutableStateOf(false) }

    // 本次拖拽起手时的进度。松手判定用"相对起点走了多远"而不是绝对进度，见 SheetFlipThreshold。
    var sheetDragStartProgress by remember { mutableFloatStateOf(0f) }

    // 手指驱动期间自己估算的开合速度（进度/秒，正 = 正在打开）。
    // 为什么不用各手势回调传上来的 VelocityTracker 结果：顶栏下拉时，装着手势的节点自己也在跟着
    // 手指位移，局部坐标系里"手指几乎没动"，算出来的速度恒为 0（实测日志里 endDrag v=0.0），
    // 于是快速下滑甩不掉 sheet、只能靠位置判定。直接对 sheetProgress 求导最可靠——所有输入
    // （轮播拖拽/顶栏下拉/列表嵌套滚动）最后都汇到这一个值上。
    var sheetDragVelocity by remember { mutableFloatStateOf(0f) }
    var sheetDragSampleTimeMs by remember { mutableLongStateOf(0L) }
    var sheetDragSampleProgress by remember { mutableFloatStateOf(0f) }

    // sheet 从完全关闭到完全打开需要移动的距离（px）：关闭时 sheet 顶边正好贴在底栏顶部（被挡住），
    // 打开时到屏顶。sheetProgress=0 → 位移 = 这段行程；=1 → 位移 0。
    val sheetTravelPx: () -> Float =
        { (contentAreaHeightPx - bottomBarHeightPx).coerceAtLeast(0).toFloat() }

    // ① 程序动画到某进度（点击打开 / 返回关闭 / 松手吸附）。同时更新意图，卸载由下面统一的
    // LaunchedEffect 判定，这里不再负责。
    // velocityPxPerSec 是松手瞬间的竖直速度（向上为负），换算成进度/秒（-v/行程，向上=进度增大=正）
    // 传给 animate 作初速度，让"甩"的动量接进吸附动画里——不传就是从静止弹起，手感发肉、不跟手。
    fun animateSheetTo(target: Float, velocityPxPerSec: Float = 0f) {
        sheetOpenTarget = target > 0.5f
        sheetAnimJob?.cancel()
        val travel = sheetTravelPx()
        // 初速度限幅：甩得再猛也不让弹簧冲过头（进度算出界会把 sheet 顶到屏幕上方之外）
        val initialVelocity = if (travel > 0f) {
            (-velocityPxPerSec / travel).coerceIn(-MaxSettleVelocity, MaxSettleVelocity)
        } else 0f
        sheetAnimJob = scope.launch {
            animate(
                initialValue = sheetProgress,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = SheetAnimationSpec
            ) { value, _ -> sheetProgress = value.coerceIn(0f, 1f) }
        }
    }

    // ② 手指拖拽：把「上抬的像素」换算成进度增量直接赋值（上抬为正 = 更打开）。先取消动画，确保
    // 拖拽期间只有手指在驱动这个值，没有第二个驱动者；顺便按时间采样出开合速度。
    fun dragSheetByRisePx(risePx: Float) {
        sheetAnimJob?.cancel()
        sheetAnimJob = null
        val travel = sheetTravelPx()
        if (travel <= 0f) return
        val next = (sheetProgress + risePx / travel).coerceIn(0f, 1f)

        val now = android.os.SystemClock.uptimeMillis()
        val dt = now - sheetDragSampleTimeMs
        sheetDragVelocity = when {
            // 首帧或隔了太久（上一段手势的残留）：重新起算，不要拿旧样本外推
            sheetDragSampleTimeMs == 0L || dt !in 1..120 -> 0f
            // 轻度平滑，抹掉单帧抖动但保留甩出去的那一下
            else -> sheetDragVelocity * 0.35f + ((next - sheetDragSampleProgress) / (dt / 1000f)) * 0.65f
        }
        sheetDragSampleTimeMs = now
        sheetDragSampleProgress = next
        sheetProgress = next
    }

    /**
     * 标记"手指开始驱动 sheet"。三条拖拽通道（轮播上拉 / 顶栏下拉 / 列表嵌套滚动）起手都要走它，
     * 记下起点进度并清空测速状态。
     */
    fun markSheetDragging() {
        if (sheetDragging) return
        sheetDragging = true
        sheetDragStartProgress = sheetProgress
        sheetDragVelocity = 0f
        sheetDragSampleTimeMs = 0L
        sheetDragSampleProgress = sheetProgress
    }

    // 松手吸附：速度够大只看方向；否则看**相对起点**走了多远（见 SheetFlipThreshold）。
    // velocity 为 px/s，向上为负。
    fun settleSheet(velocityPxPerSec: Float) {
        val startedOpen = sheetDragStartProgress > 0.5f
        val target = when {
            velocityPxPerSec <= -SheetVelocityThreshold -> 1f
            velocityPxPerSec >= SheetVelocityThreshold -> 0f
            startedOpen -> if (sheetDragStartProgress - sheetProgress > SheetFlipThreshold) 0f else 1f
            else -> if (sheetProgress - sheetDragStartProgress > SheetFlipThreshold) 1f else 0f
        }
        animateSheetTo(target, velocityPxPerSec)   // 把松手速度带进吸附动画，保留甩的动量
    }

    // ③ 三个入口，所有改 sheet 的地方都只走它们，杜绝散落各处的"挂载 + 动画"两步写法漏掉一步。
    /** 点击打开：挂载内容并动画到全开。 */
    fun openSheet(content: SheetContent) {
        sheetContent = content
        animateSheetTo(1f)
    }

    /** 手指开始拖：挂载内容、把意图置为打开，之后每帧由 dragSheetByRisePx 跟手驱动。 */
    fun beginSheetDrag(content: SheetContent) {
        sheetAnimJob?.cancel()
        sheetAnimJob = null
        sheetContent = content
        sheetOpenTarget = true
        markSheetDragging()
    }

    /**
     * 手指松开：先落手指标记再吸附，两个状态在同一帧内更新完，卸载判定看到的是最终值。
     * 手势自带的速度不可靠时（顶栏下拉恒为 0，原因见 sheetDragVelocity），退回用自测的进度速率。
     */
    fun endSheetDrag(velocityPxPerSec: Float) {
        val measuredPxPerSec = -sheetDragVelocity * sheetTravelPx()
        val effective =
            if (kotlin.math.abs(velocityPxPerSec) >= kotlin.math.abs(measuredPxPerSec)) {
                velocityPxPerSec
            } else {
                measuredPxPerSec
            }
        sheetDragging = false
        settleSheet(effective)
    }

    /** 卸载的唯一入口：目标是关 + 进度已经到底 + 手指不在上面。与"动画有没有跑完"无关。 */
    LaunchedEffect(Unit) {
        snapshotFlow { !sheetOpenTarget && sheetProgress <= 0f && !sheetDragging }
            .collect { shouldUnmount ->
                if (shouldUnmount && sheetContent != null) sheetContent = null
            }
    }

    // 详情列表的嵌套滚动：列表未到顶时上滑先喂给 sheet 继续打开；到顶后下滑的剩余量喂给 sheet 收起；
    // 松手（fling）交给 settleSheet 吸附。全部通过上面同一套函数改 sheetProgress，与拖拽/动画同源。
    val sheetNestedScroll = remember {
        object : NestedScrollConnection {
            // onPreFling 已经带速度吸附过了就别让 onPostFling 再用 0 速度覆盖一次
            private var settledInPreFling = false

            private fun consumeToSheet(dy: Float): Float {
                val travel = sheetTravelPx()
                if (travel <= 0f) return 0f
                // 列表带着 sheet 走的这段也算"手指在驱动"，中途进度归零时不能把内容卸掉
                markSheetDragging()
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
                settledInPreFling = false
                // scrollable 在每次拖拽结束时都会走到这里（哪怕速度为 0），拿它当"手指松开"的信号
                sheetDragging = false
                // 上甩且未完全打开：带速度吸附（开），并吃掉这段速度，别让列表再 fling。
                return if (available.y < 0 && sheetProgress < 1f) {
                    settledInPreFling = true
                    endSheetDrag(available.y); available
                } else Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                sheetDragging = false
                if (settledInPreFling) {
                    settledInPreFling = false
                    return Velocity.Zero
                }
                // 只要 sheet 停在中间就必须吸附，不能只在"向下甩"时才处理：慢慢往下拖再轻放手，
                // 松手速度是 0，旧写法两个 fling 回调都不满足条件，sheet 就卡在半开位置不动了。
                return if (sheetProgress > 0f && sheetProgress < 1f) {
                    endSheetDrag(available.y); available
                } else Velocity.Zero
            }
        }
    }

    // 底栏「下移/收起」比例的实时读取器：0 = 完全展开，1 = 完全收起（下移一个导航栏高度）。
    // 直接等于 sheetProgress，因此**跨越整段上拉行程**、跟手同步——修用户反馈"手势没划到一半底部
    // 动画就结束了"。以 lambda 形式提供，供底栏的 offset/graphicsLayer 延迟块每帧读取，只重新放置/
    // 合成、不重排(relayout)，消除"顿挫感"。没有 sheet 挂载时返回 0（导航栏完整显示），与挂载态原子。
    val bottomBarSlideFraction: () -> Float = {
        if (sheetContent == null) 0f else sheetProgress
    }

    // sheet 是否已完全打开。用 derivedStateOf，只在跨过阈值那一刻通知读者、不会每帧重组。
    // 传给 AlbumDetailScreen/FavoritesScreen/SearchScreen 作 active：只有真正打开后才发起网络
    // 刷新，避免"上拉一点又松开"的瞬时挂载也请求、失败反复弹 "无法获取最新数据" toast。
    val sheetFullyOpen by remember {
        derivedStateOf { sheetContent != null && sheetProgress >= 0.999f }
    }

    // 常驻底栏（迷你播放条 + 3-tab 导航栏）的可见性：导航栏只在首页（Tabs 且没有打开子表单）
    // 时显示，与详情 sheet 是否打开无关——sheet 打开时底栏改由下面的整体位移移出屏幕，不再
    // 靠这里的显隐来"消失"。播放条在编辑表单页隐藏，在 Tabs 内打开子表单（新建专辑/服务器
    // 配置）时也隐藏，其余场景（包括详情 sheet 打开时）常驻，随底栏一起位移。
    val navBarVisible = screen is AppScreen.Tabs && !tabFormOpen
    val playerVisible = when (screen) {
        is AppScreen.Edit -> false
        is AppScreen.Tabs -> !tabFormOpen
    }

    // 底部常驻栏各部分的固定高度（不随导航栏收起动画变化，避免列表逐帧重排）
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val homeBottomInset = 72.dp + 80.dp + systemBottomInset      // 播放条 + 导航栏 + 系统手势条（首页）
    val contentBottomInset = 72.dp + systemBottomInset            // 仅播放条 + 系统手势条（收藏/搜索）

    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        androidx.compose.material3.Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // 底栏：[播放条][导航栏]整体随 sheet 打开按 bottomBarSlideFraction **整体下移一个
                // 导航栏高度**(placement offset，不重排)：播放条下沉到手势条上方保持常驻可见，导航栏
                // 滑出内容区。整段动画只做重新放置/合成、不做逐帧 relayout，也不移动带阴影的播放条
                // 布局(阴影只随图层平移，不重绘)，消除"顿挫感"；且行程跨越整段上拉、不再半路就结束。
                // 最上层再画持久手势条填充（见 Column 之后）：下移距离恰好是导航栏高度，导航栏顶部
                // 会正好停在手势条区域里，填充画在其上才能把这条盖掉——导航栏是"滑进手势条底下"消失。
                Box(modifier = Modifier.fillMaxWidth()) {
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
                    // 持久手势条填充：永远铺满 inset、不随 sheet 动，杜绝残影。画在滑动列**之上**：
                    // sheet 全开时导航栏顶部正好滑到这块区域里，填充在上层才能盖住它（同色无缝，
                    // 视觉上导航栏滑进手势条底下消失）；画在下层会露出一条 tab 图标的上沿。
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(systemBottomInset)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
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
                    val targetDepth = targetState.depth()
                    val initialDepth = initialState.depth()
                    when {
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
                                // Update the sheet's mounted content too, if it's showing this same album.
                                val currentSheet = sheetContent
                                if (currentSheet is SheetContent.AlbumDetail && currentSheet.album == target.album) {
                                    sheetContent = SheetContent.AlbumDetail(updatedAlbum)
                                }
                                editingAlbum = null
                            },
                            editingAlbum = target.album
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
                                openSheet(SheetContent.AlbumDetail(album))
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
                            onOpenFavorites = { openSheet(SheetContent.Favorites) },
                            onOpenSearch = { openSheet(SheetContent.Search) },
                            playlistController = playlistController,
                            modifier = Modifier.fillMaxSize(),
                            selectedTabIndex = selectedTabIndex,
                            onFormOpenChange = { tabFormOpen = it },
                            bottomInset = homeBottomInset,
                            carouselPage = carouselPage,
                            onCarouselPageChange = { carouselPage = it },
                            onAlbumSheetDragStart = { album ->
                                // 轮播上拖专辑：挂载 sheet，随后每帧由 onSheetDrag 手指跟随驱动 sheetProgress。
                                beginSheetDrag(SheetContent.AlbumDetail(album))
                            },
                            onFavoritesSheetDragStart = {
                                // 轮播上拖收藏夹：与专辑走同一条手指跟随通道。
                                beginSheetDrag(SheetContent.Favorites)
                            },
                            // dy 为每帧竖直位移（向上为负）；向上 = 上抬 = 更打开，故取 -dy 作上抬像素。
                            onSheetDrag = { dy -> dragSheetByRisePx(-dy) },
                            onSheetDragEnd = { velocity -> endSheetDrag(velocity) }
                        )
                    }
                    }
                }
            }

            // 专辑详情/收藏夹/搜索：手指跟随 sheet，叠在 AnimatedContent 之上、NowPlaying 之下。
            // sheetContent 负责挂载/卸载这块内容（三种内容互斥、共用同一套开合机制）；sheetProgress
            // 驱动它的竖直位置（位移 = (1-progress)*行程）。位移写在 offset{} 延迟 lambda 里，
            // sheetProgress 变化只重新放置、不重组本函数。
            // 编辑表单(AppScreen.Edit)在 AnimatedContent 里、层级在本 sheet 之下——从详情点编辑时
            // 必须把 sheet 淡出让开，否则表单被 sheet 盖住看不见；编辑退出后淡回，详情还在原地。
            val currentSheetContent = sheetContent
            if (currentSheetContent != null) {
                AnimatedVisibility(
                    visible = screen is AppScreen.Tabs,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(120))
                ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, ((1f - sheetProgress) * sheetTravelPx()).roundToInt()) }
                        .nestedScroll(sheetNestedScroll)
                ) {
                    when (currentSheetContent) {
                        is SheetContent.AlbumDetail -> {
                            // AlbumDetailScreen 内部 currentAlbumSongs/hasTriedInitialRefresh 等状态是
                            // 无 key 的 remember；sheet 在同一组合位置切换不同专辑时若不加 key，会复用
                            // 旧实例、残留上一个专辑的歌曲列表（hasTriedInitialRefresh 已为 true 导致跳过
                            // 重新加载）。按 album.id 包一层 key()，换专辑时整棵子树连同其 remember 状态
                            // 一起重建，强制重新加载。
                            androidx.compose.runtime.key(currentSheetContent.album.id) {
                                AlbumDetailScreen(
                                    album = currentSheetContent.album,
                                    onBack = { animateSheetTo(0f) },
                                    onEdit = { album -> editingAlbum = album },
                                    playlistController = playlistController,
                                    modifier = Modifier.fillMaxSize(),
                                    // 播放条随 sheet 打开常驻在详情底部可见（只有导航栏收起），所以详情
                                    // 列表要为播放条预留高度，否则最后一首会被播放条挡住。
                                    bottomInset = contentBottomInset,
                                    // 只有 sheet 完全打开后才拉取最新数据——上拉一点又松开(退回)不会触发
                                    // 网络请求。
                                    active = sheetFullyOpen,
                                    // 顶栏下拉走手指跟随：与轮播上拉/列表下拉同一套进度驱动与吸附
                                    onSheetDrag = { dy -> markSheetDragging(); dragSheetByRisePx(-dy) },
                                    onSheetDragEnd = { velocity -> endSheetDrag(velocity) }
                                )
                            }
                        }

                        SheetContent.Favorites -> {
                            FavoritesScreen(
                                onBack = { animateSheetTo(0f) },
                                playlistController = playlistController,
                                modifier = Modifier.fillMaxSize(),
                                bottomInset = contentBottomInset,
                                active = sheetFullyOpen,
                                onSheetDrag = { dy -> markSheetDragging(); dragSheetByRisePx(-dy) },
                                onSheetDragEnd = { velocity -> endSheetDrag(velocity) }
                            )
                        }

                        SheetContent.Search -> {
                            SearchScreen(
                                onBack = { animateSheetTo(0f) },
                                playlistController = playlistController,
                                modifier = Modifier.fillMaxSize(),
                                bottomInset = contentBottomInset,
                                active = sheetFullyOpen,
                                onSheetDrag = { dy -> markSheetDragging(); dragSheetByRisePx(-dy) },
                                onSheetDragEnd = { velocity -> endSheetDrag(velocity) }
                            )
                        }
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
    onAlbumSheetDragStart: (tech.xvanturing.musicdav.data.Album) -> Unit = {},
    onFavoritesSheetDragStart: () -> Unit = {},
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
                                    onAlbumSheetDragStart = onAlbumSheetDragStart,
                                    onFavoritesSheetDragStart = onFavoritesSheetDragStart,
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