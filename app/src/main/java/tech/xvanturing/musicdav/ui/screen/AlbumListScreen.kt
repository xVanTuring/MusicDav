package tech.xvanturing.musicdav.ui.screen

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.edit
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.Album
import tech.xvanturing.musicdav.data.AlbumsRepository
import tech.xvanturing.musicdav.data.ConfigExportManager
import tech.xvanturing.musicdav.data.FavoritesCoverCache
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.ImportResult
import tech.xvanturing.musicdav.data.ImportStrategy
import tech.xvanturing.musicdav.data.PlaylistCache
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.favoritesCoverPath
import tech.xvanturing.musicdav.data.getWebDavConfig
import tech.xvanturing.musicdav.data.resolveFavoritesCover
import tech.xvanturing.musicdav.data.resolveAlbumUrl
import tech.xvanturing.musicdav.ui.components.AppTopBar
import tech.xvanturing.musicdav.ui.theme.MotionSpec
import tech.xvanturing.musicdav.ui.theme.VinylEasingStandard
import tech.xvanturing.musicdav.ui.theme.fadeThrough
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

// Home view-mode toggle, persisted so it sticks across app launches.
private const val UI_PREFS_NAME = "ui_prefs"
private const val KEY_HOME_VIEW_MODE = "home_view_mode"

private enum class HomeViewMode { GRID, CAROUSEL }

private fun loadHomeViewMode(context: Context): HomeViewMode {
    val prefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
    return if (prefs.getString(KEY_HOME_VIEW_MODE, null) == HomeViewMode.CAROUSEL.name) {
        HomeViewMode.CAROUSEL
    } else {
        HomeViewMode.GRID
    }
}

private fun saveHomeViewMode(context: Context, mode: HomeViewMode) {
    context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        .edit { putString(KEY_HOME_VIEW_MODE, mode.name) }
}

// Unified list of "pages"/cells shown on the home screen: Favorites first, then every album.
private sealed interface HomeEntry {
    data object Favorites : HomeEntry
    data class AlbumEntry(val album: Album) : HomeEntry
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumListScreen(
    albums: List<Album>,
    onSelect: (Album) -> Unit,
    onCreate: (Album, String?) -> Unit,
    onDelete: (Album) -> Unit,
    modifier: Modifier = Modifier,
    onAddButtonClick: (() -> Unit)? = null,
    onImportSuccess: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    bottomInset: Dp = 0.dp,
    playingAlbumId: String? = null,
    isPlaying: Boolean = false,
    onPlayAlbum: (Album) -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    carouselPage: Int = -1,
    onCarouselPageChange: (Int) -> Unit = {},
    onAlbumSheetDragStart: (Album) -> Unit = {},
    onFavoritesSheetDragStart: () -> Unit = {},
    onSheetDrag: (Float) -> Unit = {},
    onSheetDragEnd: (Float) -> Unit = {},

) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Warm the in-memory playlist cache in the background so opening an album detail
    // screen (or recomposing the "N songs" counts here) hits memory instead of disk.
    LaunchedEffect(albums) {
        withContext(Dispatchers.IO) {
            PlaylistCache.preloadAll(context, albums.map { it.id })
        }
    }

    // 收藏夹数量 + 封面：都放到后台线程解析，避免首帧主线程 IO；封面结果用内存缓存
    // (FavoritesCoverCache)回填，从别的页面返回时能立刻显示上次结果、不闪占位图，再异步刷新。
    // 本地没有已缓存封面时 resolveFavoritesCover 会后台提取首批收藏歌曲的内嵌封面写入 CoverCache，
    // 做到无需进详情就能稳定及时显示（#3）。
    var favoritesCount by remember { mutableIntStateOf(0) }
    val favoritesCover by produceState<String?>(initialValue = FavoritesCoverCache.current()) {
        val favs = withContext(Dispatchers.IO) { FavoritesRepository.load(context) }
        favoritesCount = favs.size
        val sig = favs.joinToString(",") { it.id }
        // 1) 廉价的本地已缓存封面检查(无网络)：能立刻显示，也能捡起别的流程刚缓存好的封面。
        val local = withContext(Dispatchers.IO) { favoritesCoverPath(context, favs) }
        if (local != null) {
            FavoritesCoverCache.put(sig, local)
            value = local
            return@produceState
        }
        // 2) 同一收藏集合已解析过(哪怕结果是"无封面")就不再重复联网提取，直接用缓存值。
        if (FavoritesCoverCache.isResolved(sig)) {
            value = FavoritesCoverCache.get(sig)
            return@produceState
        }
        // 3) 本地没有：后台提取内嵌封面，成功即回填显示。
        val resolved = resolveFavoritesCover(context, favs)
        FavoritesCoverCache.put(sig, resolved)
        value = resolved
    }
    var creating by remember { mutableStateOf(false) }
    var selectedAlbumForDelete by remember { mutableStateOf<Album?>(null) }
    var showImportStrategyDialog by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showMenuDialog by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(loadHomeViewMode(context)) }

    fun setViewMode(mode: HomeViewMode) {
        viewMode = mode
        saveHomeViewMode(context, mode)
    }

    // 文件选择器launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            showImportStrategyDialog = true
        }
    }

    // 文件保存launcher
    val fileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                isExporting = true
                val result = ConfigExportManager.exportConfigs(context, it)
                isExporting = false
                result.onSuccess {
                    val configs = ServerConfigRepository.load(context)
                    val exportedAlbums = AlbumsRepository.load(context)
                    exportMessage = context.getString(
                        R.string.ie_export_success,
                        configs.size,
                        exportedAlbums.size
                    )
                    showExportDialog = true
                }.onFailure { e ->
                    exportMessage = context.getString(R.string.ie_export_failed, e.message)
                    showExportDialog = true
                }
            }
        }
    }

    // 拦截返回键，如果在创建页面则返回列表页面
    BackHandler(enabled = onAddButtonClick == null && creating) {
        creating = false
    }

    if (onAddButtonClick == null && creating) {
        AlbumCreateForm(
            onCancel = { creating = false },
            onSave = { name, url, username, password, directoryUrl, coverImageUrl, serverConfigId ->
                val config = tech.xvanturing.musicdav.data.WebDavConfig(
                    url = url,
                    username = username,
                    password = password
                )
                val album = Album(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    config = config,
                    directoryUrl = directoryUrl,
                    coverImageUrl = coverImageUrl,
                    serverConfigId = serverConfigId
                )
                onCreate(album, serverConfigId)
                creating = false
            }
        )
    } else {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.nav_albums),
                    actions = {
                        IconButton(onClick = {
                            setViewMode(if (viewMode == HomeViewMode.GRID) HomeViewMode.CAROUSEL else HomeViewMode.GRID)
                        }) {
                            Icon(
                                imageVector = if (viewMode == HomeViewMode.GRID) Icons.Default.ViewCarousel else Icons.Default.GridView,
                                contentDescription = if (viewMode == HomeViewMode.GRID) {
                                    stringResource(R.string.home_view_carousel)
                                } else {
                                    stringResource(R.string.home_view_grid)
                                }
                            )
                        }
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.action_search)
                            )
                        }
                        IconButton(onClick = {
                            onAddButtonClick?.invoke() ?: run { creating = true }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.action_add)
                            )
                        }
                        IconButton(
                            onClick = { showMenuDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.home_menu_desc)
                            )
                        }
                    }
                )
            },
            modifier = modifier
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AnimatedContent(
                    targetState = viewMode,
                    transitionSpec = { fadeThrough() },
                    modifier = Modifier.fillMaxSize(),
                    label = "homeViewMode"
                ) { mode ->
                    when (mode) {
                        HomeViewMode.GRID -> GridHomeContent(
                            albums = albums,
                            favoritesCover = favoritesCover,
                            favoritesCount = favoritesCount,
                            context = context,
                            onSelect = onSelect,
                            onOpenFavorites = onOpenFavorites,
                            onDeleteRequest = { selectedAlbumForDelete = it },
                            modifier = Modifier.fillMaxSize(),
                            bottomInset = bottomInset
                        )

                        HomeViewMode.CAROUSEL -> CarouselHomeContent(
                            albums = albums,
                            favoritesCover = favoritesCover,
                            favoritesCount = favoritesCount,
                            context = context,
                            onSelect = onSelect,
                            onOpenFavorites = onOpenFavorites,
                            onDeleteRequest = { selectedAlbumForDelete = it },
                            modifier = Modifier.fillMaxSize(),
                            bottomInset = bottomInset,
                            playingAlbumId = playingAlbumId,
                            isPlaying = isPlaying,
                            onPlayAlbum = onPlayAlbum,
                            onTogglePlayPause = onTogglePlayPause,
                            carouselPage = carouselPage,
                            onCarouselPageChange = onCarouselPageChange,
                            onAlbumSheetDragStart = onAlbumSheetDragStart,
                            onFavoritesSheetDragStart = onFavoritesSheetDragStart,
                            onSheetDrag = onSheetDrag,
                            onSheetDragEnd = onSheetDragEnd
                        )
                    }
                }

                if (showMenuDialog) {
                    AlertDialog(
                        onDismissRequest = { showMenuDialog = false },
                        title = { Text(stringResource(R.string.home_menu_title)) },
                        text = {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_show_covers),
                                        modifier = Modifier.weight(1f)
                                    )
                                    androidx.compose.material3.Switch(
                                        checked = tech.xvanturing.musicdav.data.UiSettings.showListCovers,
                                        onCheckedChange = {
                                            tech.xvanturing.musicdav.data.UiSettings.setShowListCovers(
                                                context,
                                                it
                                            )
                                        }
                                    )
                                }
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                                Button(
                                    onClick = {
                                        showMenuDialog = false
                                        val timestamp = SimpleDateFormat(
                                            "yyyyMMdd_HHmmss",
                                            Locale.getDefault()
                                        ).format(Date())
                                        val fileName = "musicdav_config_$timestamp.json"
                                        fileSaveLauncher.launch(fileName)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.ie_export))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        showMenuDialog = false
                                        filePickerLauncher.launch("application/json")
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.ie_import))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showMenuDialog = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                selectedAlbumForDelete?.let { album ->
                    AlertDialog(
                        onDismissRequest = { selectedAlbumForDelete = null },
                        title = { Text(stringResource(R.string.home_delete_title)) },
                        text = { Text(stringResource(R.string.home_delete_message, album.name)) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    onDelete(album)
                                    selectedAlbumForDelete = null
                                }
                            ) {
                                Text(stringResource(R.string.action_delete))
                            }
                        },
                        dismissButton = {
                            Button(
                                onClick = { selectedAlbumForDelete = null }
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                if (showImportStrategyDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showImportStrategyDialog = false
                            selectedFileUri = null
                        },
                        title = { Text(stringResource(R.string.ie_strategy_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.ie_strategy_prompt))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.ie_strategy_overwrite_desc),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    stringResource(R.string.ie_strategy_merge_desc),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    stringResource(R.string.ie_strategy_update_desc),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        selectedFileUri?.let { uri ->
                                            coroutineScope.launch {
                                                isImporting = true
                                                val result = ConfigExportManager.importConfigs(
                                                    context,
                                                    uri,
                                                    ImportStrategy.OVERWRITE
                                                )
                                                isImporting = false
                                                result.onSuccess {
                                                    importResult = it
                                                    showImportStrategyDialog = false
                                                    onImportSuccess()
                                                    showResultDialog = true
                                                }.onFailure { e ->
                                                    exportMessage =
                                                        context.getString(R.string.ie_import_failed, e.message)
                                                    showImportStrategyDialog = false
                                                    showExportDialog = true
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.ie_overwrite))
                                }
                                Button(
                                    onClick = {
                                        selectedFileUri?.let { uri ->
                                            coroutineScope.launch {
                                                isImporting = true
                                                val result = ConfigExportManager.importConfigs(
                                                    context,
                                                    uri,
                                                    ImportStrategy.MERGE
                                                )
                                                isImporting = false
                                                result.onSuccess {
                                                    importResult = it
                                                    showImportStrategyDialog = false
                                                    onImportSuccess()
                                                    showResultDialog = true
                                                }.onFailure { e ->
                                                    exportMessage =
                                                        context.getString(R.string.ie_import_failed, e.message)
                                                    showImportStrategyDialog = false
                                                    showExportDialog = true
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.ie_merge))
                                }
                                Button(
                                    onClick = {
                                        selectedFileUri?.let { uri ->
                                            coroutineScope.launch {
                                                isImporting = true
                                                val result = ConfigExportManager.importConfigs(
                                                    context,
                                                    uri,
                                                    ImportStrategy.UPDATE
                                                )
                                                isImporting = false
                                                result.onSuccess {
                                                    importResult = it
                                                    showImportStrategyDialog = false
                                                    onImportSuccess()
                                                    showResultDialog = true
                                                }.onFailure { e ->
                                                    exportMessage =
                                                        context.getString(R.string.ie_import_failed, e.message)
                                                    showImportStrategyDialog = false
                                                    showExportDialog = true
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.ie_update))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showImportStrategyDialog = false
                                selectedFileUri = null
                            }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                if (showExportDialog) {
                    AlertDialog(
                        onDismissRequest = { showExportDialog = false },
                        title = { Text(stringResource(R.string.ie_hint_title)) },
                        text = {
                            Column {
                                if (isExporting || isImporting) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Text(exportMessage ?: "")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showExportDialog = false }) {
                                Text(stringResource(R.string.action_confirm))
                            }
                        }
                    )
                }

                if (showResultDialog) {
                    importResult?.let { result ->
                        AlertDialog(
                            onDismissRequest = {
                                showResultDialog = false
                                importResult = null
                            },
                            title = { Text(stringResource(R.string.ie_result_title)) },
                            text = {
                                Column {
                                    Text(
                                        stringResource(R.string.ie_result_done),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (result.importedServerConfigs > 0) {
                                        Text(
                                            stringResource(
                                                R.string.ie_result_imported_configs,
                                                result.importedServerConfigs
                                            )
                                        )
                                    }
                                    if (result.updatedServerConfigs > 0) {
                                        Text(
                                            stringResource(
                                                R.string.ie_result_updated_configs,
                                                result.updatedServerConfigs
                                            )
                                        )
                                    }
                                    if (result.importedAlbums > 0) {
                                        Text(
                                            stringResource(
                                                R.string.ie_result_imported_albums,
                                                result.importedAlbums
                                            )
                                        )
                                    }
                                    if (result.skippedServerConfigs > 0) {
                                        Text(
                                            stringResource(
                                                R.string.ie_result_skipped_configs,
                                                result.skippedServerConfigs
                                            )
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showResultDialog = false
                                    importResult = null
                                }) {
                                    Text(stringResource(R.string.action_confirm))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// GRID view
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridHomeContent(
    albums: List<Album>,
    favoritesCover: String?,
    favoritesCount: Int,
    context: Context,
    onSelect: (Album) -> Unit,
    onOpenFavorites: () -> Unit,
    onDeleteRequest: (Album) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentPadding = PaddingValues(bottom = bottomInset),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "favorites") {
            GridItemEntrance(modifier = Modifier.animateItem()) {
                FavoritesGridItem(
                    coverPath = favoritesCover,
                    songCount = favoritesCount,
                    onClick = onOpenFavorites
                )
            }
        }
        items(albums, key = { it.id }) { album ->
            GridItemEntrance(modifier = Modifier.animateItem()) {
                AlbumGridItem(
                    album = album,
                    context = context,
                    onClick = { onSelect(album) },
                    onLongClick = { onDeleteRequest(album) }
                )
            }
        }
    }
}

// Subtle fade + scale entrance for grid cells the first time they're composed.
@Composable
private fun GridItemEntrance(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingStandard),
        label = "gridItemAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingStandard),
        label = "gridItemScale"
    )
    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        }
    ) {
        content()
    }
}

@Composable
fun FavoritesGridItem(
    coverPath: String?,
    songCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val favoritesLabel = stringResource(R.string.common_favorites)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        VinylPeekCover {
            if (coverPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverPath)
                        .crossfade(true)
                        .build(),
                    contentDescription = favoritesLabel,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Small heart badge so a custom favorites cover still reads as "favorites".
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(13.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = favoritesLabel,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Text(
            text = favoritesLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp, end = 4.dp)
        )
        Text(
            text = stringResource(R.string.common_song_count, songCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGridItem(
    album: Album,
    context: Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 专辑封面（关联了服务器配置时 coverImageUrl 存的是相对路径，需要用当前服务器地址拼出完整URL）
    val webDavConfig = album.getWebDavConfig(context)
    val effectiveCoverImageUrl = resolveAlbumUrl(album.coverImageUrl, webDavConfig.url)
    val headers = NetworkHeaders.Builder()
        .set("Authorization", Credentials.basic(webDavConfig.username, webDavConfig.password))
        .build()
    // Cheap local read (SharedPreferences-backed cache), never a network call.
    val songCount = remember(album.id) { PlaylistCache.load(context, album.id).size }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        horizontalAlignment = Alignment.Start
    ) {
        VinylPeekCover {
            if (effectiveCoverImageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(effectiveCoverImageUrl)
                        .httpHeaders(headers)
                        .crossfade(true)
                        .listener(
                            onError = { _, throwable ->
                                Log.e("IMAGE_LOAD", "Load error: $throwable")
                            }
                        )
                        .build(),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = stringResource(R.string.home_cover_default_desc),
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 专辑名显示在封面下面，靠左对齐
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp, end = 4.dp)
        )
        if (songCount > 0) {
            Text(
                text = stringResource(R.string.common_song_count, songCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

// Dark, near-black "vinyl" behind each square cover. Only clip = false so the
// crescent poking out to the left stays inside the grid cell's own bounds.
private val VinylDiscColor = Color(0xFF181818)

// Lays out a square cover (shapes.medium, elevated) inset from the right edge of the
// cell, with a dark vinyl disc drawn first (so it sits behind/underneath) and offset so
// only a left-edge sliver of the disc peeks out - the "record half out of its sleeve" look.
@Composable
private fun VinylPeekCover(
    modifier: Modifier = Modifier,
    cover: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val cellSize = maxWidth
        val coverSize = cellSize * 0.78f
        val peek = coverSize * 0.25f
        val discOffsetX = cellSize - coverSize - peek
        val discOffsetY = (cellSize - coverSize) / 2

        VinylPeekDisc(
            size = coverSize,
            modifier = Modifier.offset(x = discOffsetX, y = discOffsetY)
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(coverSize)
                .shadow(elevation = 3.dp, shape = MaterialTheme.shapes.medium, clip = false)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
            content = cover
        )
    }
}

@Composable
private fun VinylPeekDisc(size: Dp, modifier: Modifier = Modifier) {
    val labelColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        drawCircle(color = VinylDiscColor, radius = radius)
        drawCircle(
            color = Color.White.copy(alpha = 0.06f),
            radius = radius * 0.72f,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.05f),
            radius = radius * 0.48f,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(color = labelColor, radius = radius * 0.1f)
    }
}

// ---------------------------------------------------------------------------
// CAROUSEL view ("换碟")
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarouselHomeContent(
    albums: List<Album>,
    favoritesCover: String?,
    favoritesCount: Int,
    context: Context,
    onSelect: (Album) -> Unit,
    onOpenFavorites: () -> Unit,
    onDeleteRequest: (Album) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    playingAlbumId: String? = null,
    isPlaying: Boolean = false,
    onPlayAlbum: (Album) -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    carouselPage: Int = -1,
    onCarouselPageChange: (Int) -> Unit = {},
    onAlbumSheetDragStart: (Album) -> Unit = {},
    onFavoritesSheetDragStart: () -> Unit = {},
    onSheetDrag: (Float) -> Unit = {},
    onSheetDragEnd: (Float) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val entries = remember(albums) {
        listOf<HomeEntry>(HomeEntry.Favorites) + albums.map { HomeEntry.AlbumEntry(it) }
    }
    // 竖直上拉进入详情 sheet 拖动模式期间为 true，用于屏蔽 HorizontalPager 的横向翻页手势，
    // 避免上拉同时误触横滑换碟。
    var sheetDragging by remember { mutableStateOf(false) }
    // Infinite scroll: start deep inside the virtual page range, snapped to a multiple
    // of entries.size so the initial page (mod size) is 0 — i.e. Favorites centered.
    val startPage = remember(entries.size) {
        val mid = Int.MAX_VALUE / 2
        mid - (mid % entries.size)
    }
    // Restore the page the user last had centered (hoisted to MusicPlayerApp so it survives
    // this composable being torn down when navigating to/from the album detail screen).
    val initialPage = if (carouselPage >= 0) carouselPage else startPage
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { Int.MAX_VALUE })

    // Write the current page back up to the hoisted state as the user scrolls.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onCarouselPageChange(it) }
    }

    // Rotation is driven globally (see VinylRotationClock) so it stays in sync with the
    // now-playing screen's big disc; only the disc matching playingAlbumId reads it (spin).
    val fling = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(6),
        snapPositionalThreshold = 0.75f
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            // 手势检测必须在 padding **之前**：否则 pointerInput 的命中区被 bottom inset 挡掉，
            // 底部那条(页码圆点与播放条之间的预留区)就成了上拉死区、拖不动 sheet。放到 padding 外面
            // 让手势覆盖整列；播放条/导航栏是 Scaffold 的 bottomBar 画在最上层，各自的点击照常先被它们
            // 接走，只有空白处的拖拽才落到这里。
            .pointerInput(entries) {
                // 上滑：不管居中的是专辑还是收藏夹，都从第一次越过 touch-slop 判明方向起，把之后
                // 每一帧的位移实时喂给 sheet（手指跟随，见 onAlbumSheetDragStart/onFavoritesSheetDragStart
                // + onSheetDrag），松手时把 VelocityTracker 采样到的速度交给 onSheetDragEnd 做
                // 位置+速度吸附（复用 settleSheet 现成的阈值，不重新发明一套）。
                var isSheetDrag = false
                var directionDetermined = false
                val velocityTracker = VelocityTracker()
                fun reset() {
                    isSheetDrag = false
                    directionDetermined = false
                    sheetDragging = false
                }
                detectVerticalDragGestures(
                    onDragStart = {
                        reset()
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        if (isSheetDrag) {
                            onSheetDragEnd(velocityTracker.calculateVelocity().y)
                        }
                        reset()
                    },
                    onDragCancel = {
                        if (isSheetDrag) onSheetDragEnd(0f)
                        reset()
                    },
                    onVerticalDrag = { change, amount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        if (!directionDetermined) {
                            directionDetermined = true
                            if (amount < 0f) {
                                val entry = entries[pagerState.currentPage % entries.size]
                                isSheetDrag = true
                                sheetDragging = true
                                when (entry) {
                                    is HomeEntry.AlbumEntry -> onAlbumSheetDragStart(entry.album)
                                    is HomeEntry.Favorites -> onFavoritesSheetDragStart()
                                }
                            }
                        }
                        if (isSheetDrag) {
                            onSheetDrag(amount)
                        }
                        change.consume()
                    }
                )
            }
            // padding 放到手势之后(内侧)：内容仍被 inset 顶到底栏之上，但上拉手势覆盖整列、无死区。
            .padding(bottom = bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 64.dp),
            pageSpacing = 16.dp,
            flingBehavior = fling,
            userScrollEnabled = !sheetDragging,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            val entry = entries[page % entries.size]
            val isCentered = page == pagerState.currentPage

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp)
                    .graphicsLayer {
                        val pageOffset = (
                            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        ).absoluteValue.coerceIn(0f, 1f)
                        val scale = lerp(1f, 0.82f, pageOffset)
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(1f, 0.5f, pageOffset)
                    },
                contentAlignment = Alignment.Center
            ) {
                val recordModifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (isCentered) {
                                when (entry) {
                                    is HomeEntry.Favorites -> onOpenFavorites()
                                    is HomeEntry.AlbumEntry -> {
                                        if (playingAlbumId != null && entry.album.id == playingAlbumId) {
                                            onTogglePlayPause()          // 已是当前专辑：播放/暂停切换
                                        } else {
                                            onPlayAlbum(entry.album)       // 换专辑：从头播放
                                        }
                                    }
                                }
                            } else {
                                coroutineScope.launch { pagerState.animateScrollToPage(page) }
                            }
                        },
                        onLongClick = {
                            if (isCentered && entry is HomeEntry.AlbumEntry) {
                                onDeleteRequest(entry.album)
                            }
                        }
                    )

                when (entry) {
                    is HomeEntry.Favorites -> {
                        VinylRecord(
                            coverUrl = favoritesCover,
                            headers = NetworkHeaders.EMPTY,
                            contentDescription = stringResource(R.string.common_favorites),
                            spin = false,
                            placeholderIcon = Icons.Default.Favorite,
                            placeholderTint = MaterialTheme.colorScheme.error,
                            placeholderBackground = MaterialTheme.colorScheme.errorContainer,
                            showFavoriteBadge = true,
                            modifier = recordModifier
                        )
                    }

                    is HomeEntry.AlbumEntry -> {
                        val album = entry.album
                        val webDavConfig = album.getWebDavConfig(context)
                        val effectiveCoverImageUrl = resolveAlbumUrl(album.coverImageUrl, webDavConfig.url)
                        val headers = NetworkHeaders.Builder()
                            .set("Authorization", Credentials.basic(webDavConfig.username, webDavConfig.password))
                            .build()
                        VinylRecord(
                            coverUrl = effectiveCoverImageUrl,
                            headers = headers,
                            contentDescription = album.name,
                            spin = playingAlbumId != null && album.id == playingAlbumId,
                            spinAlbumId = album.id,
                            modifier = recordModifier
                        )
                    }
                }
            }
        }

        val currentEntry = entries[pagerState.currentPage % entries.size]
        val title = when (currentEntry) {
            is HomeEntry.Favorites -> stringResource(R.string.common_favorites)
            is HomeEntry.AlbumEntry -> currentEntry.album.name
        }
        val songCount = when (currentEntry) {
            is HomeEntry.Favorites -> favoritesCount
            is HomeEntry.AlbumEntry -> remember(currentEntry.album.id) {
                PlaylistCache.load(context, currentEntry.album.id).size
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 4.dp)
        )
        Text(
            text = stringResource(R.string.common_song_count, songCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            entries.forEachIndexed { index, _ ->
                val active = index == pagerState.currentPage % entries.size
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (active) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            }
                        )
                )
            }
        }
    }
}

// Big spinning record used by the carousel: black vinyl ring with faint grooves, the
// cover cropped into the inner ~82%, and a gold center label ring + spindle hole on top.
@Composable
private fun VinylRecord(
    coverUrl: String?,
    headers: NetworkHeaders,
    contentDescription: String?,
    spin: Boolean,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    placeholderTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    placeholderBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    // 收藏碟额外显示的爱心标识：即使有封面也贴一个红心角标，表明这是"收藏"。
    showFavoriteBadge: Boolean = false,
    // 这张碟代表的专辑 id：用于和全局旋转时钟的 albumId 比对，只有确认「当前旋转角就是自己这张」
    // 时才用它旋转，避免切专辑瞬间读到上一张残留角度而闪一下（见 VinylRotationClock.albumId）。
    spinAlbumId: String? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    val isPlaceholder = coverUrl == null
    // For the placeholder (no cover) case the gold label shrinks down to a small
    // spindle hub instead of a full label ring, so it doesn't punch through the
    // centered icon the way it can on a small, non-photographic graphic.
    val labelSizeFraction = if (isPlaceholder) 0.12f else 0.2f
    val spindleSizeFraction = if (isPlaceholder) 0.035f else 0.045f
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                val clock = tech.xvanturing.musicdav.ui.components.VinylRotationClock
                // 仅当时钟的 albumId 已切到自己这张时才用其角度；切专辑那一帧时钟还停在上一张，
                // 此时按 0 画，等 driver 把角度归零并切到本张后再跟着转，杜绝残留角度闪现。
                rotationZ = if (spin && clock.albumId == spinAlbumId) clock.angle else 0f
            }
            .clip(CircleShape)
            .background(VinylDiscColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = this.size.minDimension / 2f
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = radius * 0.95f,
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = radius * 0.87f,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        if (coverUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .httpHeaders(headers)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.82f)
                    .clip(CircleShape)
            )
        } else {
            // Fill the same inner-label area a real cover would, so there's no black
            // gap. Explicitly centered on the disc's own rotation axis - the same
            // alignment the real-cover AsyncImage above uses - so the icon stays
            // perfectly concentric and merely spins in place instead of orbiting.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.82f)
                    .clip(CircleShape)
                    .background(placeholderBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = placeholderIcon,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(0.38f),
                    tint = placeholderTint
                )
            }
        }
        // Gold center label ring + spindle hole, drawn on top of the cover/placeholder,
        // centered on the same rotation axis as everything else above.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(labelSizeFraction)
                .clip(CircleShape)
                .background(primary)
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(spindleSizeFraction)
                .clip(CircleShape)
                .background(VinylDiscColor)
        )
        // 收藏碟专属：底部一枚红心角标，即使碟面有封面也能一眼看出这是"收藏"。碟不旋转
        // （favorites 的 spin 恒为 false），角标位置固定在下方靠内一点。
        if (showFavoriteBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-14).dp)
                    .fillMaxSize(0.15f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.fillMaxSize(0.62f)
                )
            }
        }
    }
}
