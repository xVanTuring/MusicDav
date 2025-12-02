package com.spotify.music.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.spotify.music.data.Album
import com.spotify.music.data.MusicFile
import com.spotify.music.data.PlaylistState
import com.spotify.music.data.ServerConfigRepository
import com.spotify.music.player.PlaylistStateController
import com.spotify.music.ui.BottomPlayerBar
import com.spotify.music.ui.MusicListScreen

@Composable
fun AlbumDetailScreen(
    album: Album,
    onBack: () -> Unit,
    playlistController: PlaylistStateController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
    }

    // 确保播放列表已加载到播放器
    LaunchedEffect(playlistController) {
        playlistController.ensurePlaylistLoaded()
    }

    MusicListScreen(
        webDavConfig = webDavConfig,
        directoryPath = album.directoryUrl,
        showBack = true,
        onBack = onBack,
        currentPlayingIndex = playlistController.state.currentIndex,
        onPlaylistLoaded = { songs ->
            playlistController.loadPlaylist(songs)
        },
        onSongSelected = { index, _ ->
            coroutineScope.launch {
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
        modifier = modifier
    )
}
