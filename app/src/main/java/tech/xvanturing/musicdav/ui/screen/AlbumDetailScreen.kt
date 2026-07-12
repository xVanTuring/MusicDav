package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import android.widget.Toast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableIntStateOf
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.Album
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.ServerAddressResolver
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.resolveAlbumUrl
import tech.xvanturing.musicdav.player.PlaylistStateController
import tech.xvanturing.musicdav.player.CacheManager
import tech.xvanturing.musicdav.ui.MusicListScreen
import tech.xvanturing.musicdav.ui.components.AppTopBar
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.PlaylistCache
import tech.xvanturing.musicdav.data.MusicMetadataCache
import tech.xvanturing.musicdav.data.cacheKey
import tech.xvanturing.musicdav.webdav.WebDavClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    onBack: () -> Unit,
    playlistController: PlaylistStateController,
    modifier: Modifier = Modifier,
    onEdit: (Album) -> Unit = {},
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    // 是否"真正打开"：只有 true 时才发起网络拉取最新数据。缓存列表不受此限、挂载即显示。用来避免
    // sheet 上拉一点又松开的瞬时挂载也发起请求、失败反复弹"无法获取最新数据"toast。
    active: Boolean = true,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 存储当前专辑的歌曲列表
    var currentAlbumSongs by remember {
        mutableStateOf<List<MusicFile>>(
            emptyList()
        )
    }
    var isExtractingMetadata by remember { mutableStateOf(false) }
    var metadataExtractionProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var hasTriedInitialRefresh by remember { mutableStateOf(false) }
    var hasLoadedCache by remember { mutableStateOf(false) }
    var forceRefreshKey by remember { mutableIntStateOf(0) }

    // 收藏状态
    var favoriteKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 收藏状态在后台线程加载，避免首次组合时阻塞主线程（进场滑动动画期间尤其明显）
    LaunchedEffect(Unit) {
        favoriteKeys = withContext(Dispatchers.IO) {
            FavoritesRepository.load(context).map { it.musicFile.cacheKey }.toSet()
        }
    }

    // 缓存状态
    val cacheManager = remember { CacheManager(context) }
    val albumCacheProgress = cacheManager.state.albumCachingProgress[album.id]

    // 拦截返回键，返回到专辑列表页面
    BackHandler {
        onBack()
    }

    // 强制刷新函数：清除缓存并重新加载数据
    fun forceRefresh() {
        coroutineScope.launch {
            // 清除播放列表缓存
            PlaylistCache.clear(context, album.id)
            // 清除该专辑下所有歌曲的元数据缓存
            currentAlbumSongs.forEach { musicFile ->
                MusicMetadataCache.remove(context, musicFile.cacheKey)
            }
            // 清除当前歌曲列表
            currentAlbumSongs = emptyList()
            // 重置初始加载标志，触发重新加载
            hasTriedInitialRefresh = false
            forceRefreshKey++
        }
    }

    // 关联的服务器配置（若有），directoryUrl/coverImageUrl 在这种情况下存的是相对路径，
    // 完整地址需要用服务器当前解析出的可用地址实时拼出来，这样服务器地址调整了也不会影响专辑
    val serverConfig = remember(album.serverConfigId) {
        album.serverConfigId?.let { id -> ServerConfigRepository.load(context).find { it.id == id } }
    }
    var webDavConfig by remember(album.serverConfigId) {
        mutableStateOf(serverConfig?.toWebDavConfig() ?: album.config)
    }

    // 解析服务器当前可用地址（按候选地址顺序探测）
    LaunchedEffect(album.serverConfigId) {
        if (serverConfig != null) {
            val resolvedUrl = ServerAddressResolver.resolve(serverConfig)
            webDavConfig = serverConfig.toWebDavConfig(resolvedUrl)
        }
        playlistController.setCredentials(webDavConfig)
        cacheManager.bind()
    }

    val effectiveDirectoryUrl = resolveAlbumUrl(album.directoryUrl, webDavConfig.url)
    val effectiveCoverImageUrl = resolveAlbumUrl(album.coverImageUrl, webDavConfig.url)

    DisposableEffect(Unit) {
        onDispose {
            cacheManager.unbind()
        }
    }

    // 1) 加载缓存列表：挂载即做（本地读取、无网络、不弹 toast）——sheet 上拉过程中就能立刻看到内容。
    LaunchedEffect(album.id, forceRefreshKey) {
        if (!hasLoadedCache) {
            hasLoadedCache = true
            val cachedFiles = withContext(Dispatchers.IO) { PlaylistCache.load(context, album.id) }
            if (cachedFiles.isNotEmpty()) {
                currentAlbumSongs = cachedFiles
                playlistController.setSongAlbumCovers(cachedFiles, effectiveCoverImageUrl)
                playlistController.loadCachedCovers(context, cachedFiles)
            }
        }
    }

    // 2) 拉取最新数据：只有 sheet 真正打开(active)后才做一次。避免"上拉一点又松开"的瞬时挂载也发起
    //    网络请求、失败反复弹"无法获取最新数据"toast。onFailure/catch 里用 currentAlbumSongs 判断
    //    是否已有缓存(由上面的效果填好)。
    LaunchedEffect(album.id, forceRefreshKey, active) {
        if (active && !hasTriedInitialRefresh) {
            hasTriedInitialRefresh = true
            try {
                // 尝试获取最新数据并提取元数据
                val webDavClient = WebDavClient()

                val fetchResult = if (serverConfig != null) {
                    // 按候选地址顺序尝试，某个地址请求失败时自动换下一个候选地址重试
                    ServerAddressResolver.withReachableAddress(serverConfig) { baseUrl ->
                        val directoryUrl = resolveAlbumUrl(album.directoryUrl, baseUrl) ?: baseUrl
                        webDavClient.fetchMusicFiles(
                            serverConfig.toWebDavConfig(baseUrl).copy(url = directoryUrl),
                            context,
                            serverConfig.id
                        ) { current, total ->
                            metadataExtractionProgress = current to total
                            isExtractingMetadata = true
                        }
                    }
                } else {
                    val effectiveConfig = if (album.directoryUrl != null) {
                        webDavConfig.copy(url = album.directoryUrl)
                    } else {
                        webDavConfig
                    }
                    webDavClient.fetchMusicFiles(effectiveConfig, context) { current, total ->
                        metadataExtractionProgress = current to total
                        isExtractingMetadata = true
                    }
                }

                fetchResult
                    .onSuccess { files ->
                        currentAlbumSongs = files
                        PlaylistCache.save(context, album.id, files)
                        // 设置专辑封面映射
                        playlistController.setSongAlbumCovers(files, effectiveCoverImageUrl)
                        // 设置 WebDAV 配置
                        playlistController.setCurrentWebDavConfig(webDavConfig)
                        // 加载缓存封面
                        playlistController.loadCachedCovers(context, files)
                        isExtractingMetadata = false
                        metadataExtractionProgress = null
                    }
                    .onFailure { e ->
                        // 如果已经有缓存数据，显示提示
                        if (currentAlbumSongs.isNotEmpty()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.album_toast_fetch_failed_use_cache),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        isExtractingMetadata = false
                        metadataExtractionProgress = null
                    }
            } catch (e: Exception) {
                // 如果已经有缓存数据，显示提示
                if (currentAlbumSongs.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.album_toast_fetch_failed_use_cache),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isExtractingMetadata = false
                metadataExtractionProgress = null
            }
        }
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier.pointerInput(Unit) {
                    var dragY = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragY > 120f) onBack(); dragY = 0f },
                        onVerticalDrag = { change, amount -> dragY += amount; change.consume() }
                    )
                }
            ) {
            AppTopBar(
                title = album.name,
                onBack = onBack,
                navigationIcon = Icons.Default.KeyboardArrowDown,
                actions = {
                    IconButton(onClick = { forceRefresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (currentAlbumSongs.isNotEmpty() && albumCacheProgress == null) {
                                cacheManager.cacheAlbum(
                                    scope = coroutineScope,
                                    context = context,
                                    musicFiles = currentAlbumSongs,
                                    config = webDavConfig,
                                    albumId = album.id,
                                    onSuccess = { count ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.album_toast_caching_album, count),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onFailure = { error ->
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.album_toast_cache_album_failed,
                                                error.message ?: ""
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        },
                        enabled = currentAlbumSongs.isNotEmpty() && albumCacheProgress == null
                    ) {
                        if (albumCacheProgress != null) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.album_cache_album_desc)
                            )
                        }
                    }
                    IconButton(onClick = { onEdit(album) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.album_edit_desc)
                        )
                    }
                }
            )
            }
        }
    ) { paddingValues ->
        Box(modifier = modifier.padding(paddingValues)) {
            if (isExtractingMetadata) {
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            stringResource(
                                R.string.album_extracting_metadata,
                                metadataExtractionProgress?.first ?: 0,
                                metadataExtractionProgress?.second ?: 0
                            )
                        )
                    },
                    leadingIcon = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                MusicListScreen(
                    musicFiles = currentAlbumSongs,
                    isLoading = false,
                    errorMessage = null,
                    currentPlayingSong = playlistController.state.currentSong,
                    onSongSelected = { index, _ ->
                        coroutineScope.launch {
                            playlistController.loadPlaylist(currentAlbumSongs)
                            playlistController.setSongAlbumCovers(
                                currentAlbumSongs,
                                effectiveCoverImageUrl
                            )
                            playlistController.setCurrentWebDavConfig(webDavConfig)
                            playlistController.setCurrentAlbumId(album.id)
                            playlistController.loadCachedCovers(context, currentAlbumSongs)
                            playlistController.setPlaylistAndPlay(index)
                        }
                    },
                    enableFavorite = true,
                    isFavorite = { musicFile -> favoriteKeys.contains(musicFile.cacheKey) },
                    onToggleFavorite = { musicFile ->
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                FavoritesRepository.toggle(
                                    context,
                                    musicFile,
                                    if (musicFile.serverConfigId == null) webDavConfig else null
                                )
                            }
                            favoriteKeys = withContext(Dispatchers.IO) {
                                FavoritesRepository.load(context).map { it.musicFile.cacheKey }.toSet()
                            }
                        }
                    },
                    enableCache = true,
                    onCacheRequest = { musicFile ->
                        cacheManager.cacheSong(
                            scope = coroutineScope,
                            context = context,
                            musicFile = musicFile,
                            config = webDavConfig,
                            onSuccess = { path ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_cached_song, musicFile.name),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_cache_song_failed, error.message ?: ""),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    bottomInset = bottomInset,
                    cacheManager = cacheManager,
                    playlistController = playlistController
                )
            }
        }
    }
}
