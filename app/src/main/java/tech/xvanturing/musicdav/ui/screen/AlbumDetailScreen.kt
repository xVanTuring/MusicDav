package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import android.widget.Toast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableIntStateOf
import tech.xvanturing.musicdav.data.Album
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.ServerAddressResolver
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.resolveAlbumUrl
import tech.xvanturing.musicdav.player.PlaylistStateController
import tech.xvanturing.musicdav.player.CacheManager
import tech.xvanturing.musicdav.ui.BottomPlayerBar
import tech.xvanturing.musicdav.ui.MusicListScreen
import kotlinx.coroutines.launch
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
    onOpenNowPlaying: () -> Unit = {},
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
    var forceRefreshKey by remember { mutableIntStateOf(0) }

    // 收藏状态
    var favoriteKeys by remember {
        mutableStateOf(FavoritesRepository.load(context).map { it.musicFile.cacheKey }.toSet())
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

    // 初始加载时刷新专辑详情
    LaunchedEffect(album.id, forceRefreshKey) {
        if (!hasTriedInitialRefresh) {
            hasTriedInitialRefresh = true
            coroutineScope.launch {
                // 先加载缓存数据（如果有）
                val cachedFiles = PlaylistCache.load(context, album.id)
                if (cachedFiles.isNotEmpty()) {
                    currentAlbumSongs = cachedFiles
                    // 设置专辑封面映射
                    playlistController.setSongAlbumCovers(cachedFiles, effectiveCoverImageUrl)
                    // 加载缓存封面
                    playlistController.loadCachedCovers(context, cachedFiles)
                }

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
                            if (cachedFiles.isNotEmpty()) {
                                Toast.makeText(
                                    context,
                                    "无法获取最新数据，使用缓存列表",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            isExtractingMetadata = false
                            metadataExtractionProgress = null
                        }
                } catch (e: Exception) {
                    // 如果已经有缓存数据，显示提示
                    if (cachedFiles.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            "无法获取最新数据，使用缓存列表",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    isExtractingMetadata = false
                    metadataExtractionProgress = null
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = album.name,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(),
                        softWrap = false
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { forceRefresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
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
                                            "正在缓存 $count 首歌曲...",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onFailure = { error ->
                                        Toast.makeText(
                                            context,
                                            "Failed to cache album: ${error.message}",
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
                                contentDescription = "Cache Album"
                            )
                        }
                    }
                    IconButton(onClick = { onEdit(album) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Album"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = modifier.padding(paddingValues)) {
            if (isExtractingMetadata) {
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            "提取元数据: ${metadataExtractionProgress?.first ?: 0}/${metadataExtractionProgress?.second ?: 0}"
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
                            playlistController.loadCachedCovers(context, currentAlbumSongs)
                            playlistController.setPlaylistAndPlay(index)
                        }
                    },
                    enableFavorite = true,
                    isFavorite = { musicFile -> favoriteKeys.contains(musicFile.cacheKey) },
                    onToggleFavorite = { musicFile ->
                        FavoritesRepository.toggle(
                            context,
                            musicFile,
                            if (musicFile.serverConfigId == null) webDavConfig else null
                        )
                        favoriteKeys = FavoritesRepository.load(context).map { it.musicFile.cacheKey }.toSet()
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
                                    "Cached: ${musicFile.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    context,
                                    "Failed to cache: ${error.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    bottomBar = {
                        BottomPlayerBar(
                            playlistState = playlistController.state,
                            onPlayPause = {
                                if (playlistController.state.isPlaying) {
                                    playlistController.pause()
                                } else {
                                    playlistController.play()
                                }
                            },
                            onNext = {
                                playlistController.seekToNext()
                            },
                            onPrevious = {
                                playlistController.seekToPrevious()
                            },
                            onTogglePlayMode = {
                                playlistController.togglePlayMode()
                            },
                            onOpenNowPlaying = onOpenNowPlaying
                        )
                    },
                    cacheManager = cacheManager,
                    playlistController = playlistController
                )
            }
        }
    }
}
