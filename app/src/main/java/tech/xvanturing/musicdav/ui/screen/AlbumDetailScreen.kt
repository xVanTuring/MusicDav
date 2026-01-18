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
import tech.xvanturing.musicdav.data.Album
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.player.PlaylistStateController
import tech.xvanturing.musicdav.player.CacheManager
import tech.xvanturing.musicdav.player.MusicCache
import tech.xvanturing.musicdav.ui.BottomPlayerBar
import tech.xvanturing.musicdav.ui.MusicListScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    onBack: () -> Unit,
    playlistController: PlaylistStateController,
    modifier: Modifier = Modifier,
    onEdit: (Album) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 存储当前专辑的歌曲列表
    var currentAlbumSongs by remember {
        mutableStateOf<List<tech.xvanturing.musicdav.data.MusicFile>>(
            emptyList()
        )
    }
    var isExtractingMetadata by remember { mutableStateOf(false) }
    var metadataExtractionProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var hasTriedInitialRefresh by remember { mutableStateOf(false) }

    // 缓存状态
    val cacheManager = remember { CacheManager(context) }
    val albumCacheProgress = cacheManager.state.albumCachingProgress[album.id]

    // 拦截返回键，返回到专辑列表页面
    BackHandler {
        onBack()
    }

    val webDavConfig = if (album.serverConfigId != null) {
        ServerConfigRepository.load(context)
            .find { it.id == album.serverConfigId }
            ?.toWebDavConfig() ?: album.config
    } else {
        album.config
    }

    // 设置 WebDAV 凭据
    LaunchedEffect(webDavConfig) {
        playlistController.setCredentials(webDavConfig)
        cacheManager.bind()
    }

    DisposableEffect(Unit) {
        onDispose {
            cacheManager.unbind()
        }
    }

    // 初始加载时刷新专辑详情
    LaunchedEffect(album.name + album.directoryUrl) {
        if (!hasTriedInitialRefresh) {
            hasTriedInitialRefresh = true
            coroutineScope.launch {
                try {
                    // 先加载缓存数据（如果有）
                    val cachedFiles = tech.xvanturing.musicdav.data.PlaylistCache.load(
                        context,
                        album.directoryUrl
                    )
                    if (cachedFiles.isNotEmpty()) {
                        currentAlbumSongs = cachedFiles
                    }

                    // 尝试获取最新数据并提取元数据
                    val webDavClient = tech.xvanturing.musicdav.webdav.WebDavClient()
                    val effectiveConfig = if (album.directoryUrl != null) {
                        webDavConfig.copy(url = album.directoryUrl)
                    } else {
                        webDavConfig
                    }

                    webDavClient.fetchMusicFiles(effectiveConfig, context) { current, total ->
                        metadataExtractionProgress = current to total
                        isExtractingMetadata = true
                    }
                        .onSuccess { files ->
                            currentAlbumSongs = files
                            tech.xvanturing.musicdav.data.PlaylistCache.save(
                                context,
                                album.directoryUrl,
                                files
                            )
                            isExtractingMetadata = false
                            metadataExtractionProgress = null
                            Toast.makeText(
                                context,
                                "已加载 ${files.size} 首歌曲",
                                Toast.LENGTH_SHORT
                            ).show()
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
                    val cachedFiles = tech.xvanturing.musicdav.data.PlaylistCache.load(
                        context,
                        album.directoryUrl
                    )
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
                                album.coverImageUrl
                            )
                            playlistController.setCurrentWebDavConfig(webDavConfig)
                            playlistController.loadCachedCovers(context, currentAlbumSongs)
                            playlistController.setPlaylistAndPlay(index)
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
                            }
                        )
                    },
                    cacheManager = cacheManager
                )
            }
        }
    }
}
