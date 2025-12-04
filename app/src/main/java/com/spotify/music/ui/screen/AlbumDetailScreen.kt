package com.spotify.music.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spotify.music.data.Album
import com.spotify.music.data.ServerConfigRepository
import com.spotify.music.player.PlaylistStateController
import com.spotify.music.ui.BottomPlayerBar
import com.spotify.music.ui.MusicListScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    onBack: () -> Unit,
    playlistController: PlaylistStateController,
    onEdit: (Album) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 存储当前专辑的歌曲列表
    var currentAlbumSongs by remember { mutableStateOf<List<com.spotify.music.data.MusicFile>>(emptyList()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // 拦截返回键，返回到专辑列表页面
    BackHandler {
        onBack()
    }

    // 触发刷新函数
    fun triggerRefresh() {
        refreshTrigger++
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
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = album.name,
                        maxLines = 1,
                        modifier = modifier
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
                    IconButton(onClick = { triggerRefresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
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
        MusicListScreen(
            webDavConfig = webDavConfig,
            directoryPath = album.directoryUrl,
            showBack = false, // We now have a top bar with back button
            onBack = onBack,
            currentPlayingSong = playlistController.state.currentSong,
            onPlaylistLoaded = { songs ->
                // 存储当前专辑的歌曲列表，但不自动加载到播放器
                currentAlbumSongs = songs
            },
            onSongSelected = { index, _ ->
                coroutineScope.launch {
                    // 加载当前专辑的歌曲列表到播放器，然后播放选中的歌曲
                    playlistController.loadPlaylist(currentAlbumSongs)
                    playlistController.setPlaylistAndPlay(index)
                }
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
                    }
                )
            },
            modifier = modifier.padding(paddingValues),
            showTopBar = false, // Hide MusicListScreen's top bar
            externalRefreshTrigger = { refreshTrigger } // Pass refresh trigger value
        )
    }
}
