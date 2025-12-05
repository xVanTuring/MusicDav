package com.spotify.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.DownloadDone
import com.spotify.music.cache.MusicCacheManager
import com.spotify.music.data.MusicFile
import com.spotify.music.data.WebDavConfig
import com.spotify.music.webdav.WebDavClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicListScreen(
    webDavConfig: WebDavConfig,
    directoryPath: String? = null,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    currentPlayingSong: MusicFile? = null,
    onSongSelected: (Int, MusicFile) -> Unit,
    onPlaylistLoaded: (List<MusicFile>) -> Unit = {},
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
    externalRefreshTrigger: (() -> Unit)? = null,
    cacheManager: MusicCacheManager? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var musicFiles by remember { mutableStateOf<List<MusicFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    
    val scope = rememberCoroutineScope()
    val webDavClient = remember { WebDavClient() }
    
    // 如果有 directoryPath，拼接完整路径；否则使用原始 URL
    val effectiveConfig = remember(webDavConfig, directoryPath) {
        if (directoryPath != null) {
            webDavConfig.copy(url = directoryPath)
        } else {
            webDavConfig
        }
    }
    
    // 响应外部刷新触发器
    LaunchedEffect(externalRefreshTrigger) {
        externalRefreshTrigger?.invoke()
        refreshKey++
    }

    // 响应刷新键变化
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            isRefreshing = true
            errorMessage = null
            scope.launch {
                webDavClient.fetchMusicFiles(effectiveConfig)
                    .onSuccess { files ->
                        musicFiles = files
                        com.spotify.music.data.PlaylistCache.save(context, directoryPath, files)
                        onPlaylistLoaded(files)
                        isRefreshing = false
                    }
                    .onFailure { e ->
                        errorMessage = "Failed to load music: ${e.message}"
                        isRefreshing = false
                    }
            }
        }
    }

    // 首次加载：先显示缓存，然后后台更新
    LaunchedEffect(effectiveConfig.url) {
        // 先加载缓存并立即显示
        val cachedFiles = com.spotify.music.data.PlaylistCache.load(context, directoryPath)
        if (cachedFiles.isNotEmpty()) {
            musicFiles = cachedFiles
            // 通知播放列表已加载（使用缓存）
            onPlaylistLoaded(cachedFiles)
            // 有缓存时，后台刷新不显示加载状态
            isRefreshing = true
        } else {
            // 没有缓存时，显示加载状态
            isLoading = true
        }
        
        errorMessage = null
        scope.launch {
            webDavClient.fetchMusicFiles(effectiveConfig)
                .onSuccess { files ->
                    // 检查数据是否不同
                    val cachedUrls = cachedFiles.map { it.url }.toSet()
                    val newUrls = files.map { it.url }.toSet()
                    
                    if (cachedUrls != newUrls) {
                        // 数据不同，更新UI和缓存
                        musicFiles = files
                        com.spotify.music.data.PlaylistCache.save(context, directoryPath, files)
                        // 通知播放列表已更新（但不影响当前播放）
                        onPlaylistLoaded(files)
                    } else {
                        // URL列表相同，但元数据可能有变化，更新UI和缓存
                        musicFiles = files
                        com.spotify.music.data.PlaylistCache.save(context, directoryPath, files)
                        // 通知播放列表已更新（但不影响当前播放，因为URL相同）
                        onPlaylistLoaded(files)
                    }
                    isLoading = false
                    isRefreshing = false
                }
                .onFailure { e ->
                    // 如果加载失败，保持缓存数据，只显示错误（如果有缓存）
                    if (cachedFiles.isEmpty()) {
                        errorMessage = "Failed to load music: ${e.message}"
                    }
                    isLoading = false
                    isRefreshing = false
                }
        }
    }
    
    fun loadMusicFiles(showLoading: Boolean = false) {
        if (showLoading) {
            isLoading = true
        } else {
            isRefreshing = true
        }
        errorMessage = null
        scope.launch {
            webDavClient.fetchMusicFiles(effectiveConfig)
                .onSuccess { files ->
                    musicFiles = files
                    com.spotify.music.data.PlaylistCache.save(context, directoryPath, files)
                    onPlaylistLoaded(files)
                    isLoading = false
                    isRefreshing = false
                }
                .onFailure { e ->
                    errorMessage = "Failed to load music: ${e.message}"
                    isLoading = false
                    isRefreshing = false
                }
        }
    }
    
      if (showTopBar) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Music Library") },
                    navigationIcon = if (showBack) {
                        {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    } else {
                        {}
                    },
                    actions = {
                        if (isRefreshing) {
                            Box(
                                modifier = Modifier.padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        IconButton(onClick = {
                            refreshKey++
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            },
            bottomBar = bottomBar,
            modifier = modifier
        ) { paddingValues ->
            Content(
                paddingValues = paddingValues,
                isLoading = isLoading,
                errorMessage = errorMessage,
                musicFiles = musicFiles,
                currentPlayingSong = currentPlayingSong,
                onSongSelected = onSongSelected,
                cacheManager = cacheManager
            )
        }
    } else {
        Scaffold(
            bottomBar = bottomBar,
            modifier = modifier
        ) { paddingValues ->
            Content(
                paddingValues = paddingValues,
                isLoading = isLoading,
                errorMessage = errorMessage,
                musicFiles = musicFiles,
                currentPlayingSong = currentPlayingSong,
                onSongSelected = onSongSelected,
                cacheManager = cacheManager
            )
        }
    }
}

@Composable
private fun Content(
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    isLoading: Boolean,
    errorMessage: String?,
    musicFiles: List<MusicFile>,
    currentPlayingSong: MusicFile?,
    onSongSelected: (Int, MusicFile) -> Unit,
    cacheManager: MusicCacheManager?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            !musicFiles.isEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(musicFiles) { index, musicFile ->
                        MusicListItem(
                            musicFile = musicFile,
                            isPlaying = currentPlayingSong != null && musicFile.url == currentPlayingSong.url,
                            isCached = cacheManager?.getCachedFile(musicFile.url) != null,
                            onClick = { onSongSelected(index, musicFile) }
                        )
                    }
                }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            musicFiles.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No music files found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MusicListItem(
    musicFile: MusicFile,
    isPlaying: Boolean,
    isCached: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = {
            Text(
                text = musicFile.name.substringBeforeLast('.'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = formatFileSize(musicFile.size),
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = if (isPlaying)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = if (isCached) {
            {
                Icon(
                    imageVector = Icons.Default.DownloadDone,
                    contentDescription = "Cached",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isPlaying)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.clickable(onClick = onClick)
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
