package com.spotify.music.ui.screen

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.spotify.music.SimpleMusicService
import com.spotify.music.data.Album
import com.spotify.music.data.MusicFile
import com.spotify.music.data.PlaylistState
import com.spotify.music.data.ServerConfigRepository
import com.spotify.music.ui.BottomPlayerBar
import com.spotify.music.ui.MusicListScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicReference

@Composable
fun AlbumDetailScreen(
    album: Album,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playlistState by remember { mutableStateOf(PlaylistState()) }
    
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
    
    // Set WebDAV credentials for the service - will dynamically update if service is already running
    LaunchedEffect(webDavConfig) {
        SimpleMusicService.setCredentials(webDavConfig.username, webDavConfig.password)
    }
    
    val needsNotificationPermission = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
    var notificationGranted by remember {
        mutableStateOf(
            !needsNotificationPermission || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationAsked by remember { mutableStateOf(false) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationGranted = granted
            notificationAsked = true
        }

    LaunchedEffect(needsNotificationPermission, notificationAsked, notificationGranted) {
        if (needsNotificationPermission && !notificationGranted && !notificationAsked) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(Unit) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SimpleMusicService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val listenerRef = AtomicReference<Player.Listener>()

        controllerFuture.addListener({
            try {
                val mediaController = controllerFuture.get()
                controller = mediaController
                
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playlistState = playlistState.copy(isPlaying = isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // Handle playback state changes
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Handle errors
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        // Metadata updated
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                            val duration = player.duration.takeIf { it > 0 } ?: 0L
                            val currentIndex = player.currentMediaItemIndex
                            playlistState = playlistState.copy(
                                duration = duration,
                                currentIndex = currentIndex
                            )
                        }
                    }
                }
                listenerRef.set(listener)
                mediaController.addListener(listener)
                
                val duration = mediaController.duration.takeIf { it > 0 } ?: 0L
                val currentPosition = mediaController.currentPosition
                playlistState = playlistState.copy(
                    duration = duration,
                    currentPosition = currentPosition,
                    isPlaying = mediaController.isPlaying,
                    currentIndex = mediaController.currentMediaItemIndex
                )
            } catch (error: Exception) {
                // Handle error
            }
        }, mainExecutor)

        onDispose {
            listenerRef.get()?.let { controller?.removeListener(it) }
            controller?.release()
            controller = null
            controllerFuture.cancel(true)
        }
    }

    LaunchedEffect(controller) {
        while (isActive) {
            controller?.let {
                val currentPosition = it.currentPosition
                val duration = it.duration.takeIf { d -> d > 0 } ?: 0L
                playlistState = playlistState.copy(
                    currentPosition = currentPosition,
                    duration = duration
                )
            }
            delay(100)
        }
    }
    
    // 当 controller 初始化后，如果播放列表已加载但播放器为空，则设置播放列表
    // 注意：只在播放器为空时设置，不会自动切换不同的播放列表
    LaunchedEffect(controller, playlistState.songs) {
        controller?.let { currentController ->
            if (playlistState.songs.isNotEmpty()) {
                val currentMediaItemCount = currentController.mediaItemCount
                // 只在播放列表为空时才设置，不自动切换不同的播放列表
                if (currentMediaItemCount == 0) {
                    val mediaItems = playlistState.songs.map { song ->
                        MediaItem.Builder()
                            .setUri(song.url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.name.substringBeforeLast('.'))
                                    .build()
                            )
                            .build()
                    }
                    currentController.setMediaItems(mediaItems)
                    currentController.prepare()
                }
            }
        }
    }
    
    fun loadPlaylist(songs: List<MusicFile>) {
        // 只更新播放列表数据，不自动设置到播放器
        // 播放列表的切换只在用户点击歌曲时进行
        // 如果当前播放的歌曲在新列表中，保持当前索引；否则重置索引
        val currentSong = playlistState.currentSong
        val newIndex = if (currentSong != null) {
            songs.indexOfFirst { it.url == currentSong.url }.takeIf { it >= 0 } 
                ?: playlistState.currentIndex.takeIf { it < songs.size } 
                ?: -1
        } else {
            -1
        }
        
        playlistState = playlistState.copy(
            songs = songs,
            currentIndex = newIndex
        )
    }
    
    fun setPlaylistAndPlay(index: Int) {
        // 用户点击歌曲时，设置播放列表并播放
        if (playlistState.songs.isEmpty()) return
        
        controller?.let { currentController ->
            val currentUrls = mutableListOf<String>()
            val currentMediaItemCount = currentController.mediaItemCount
            for (i in 0 until currentMediaItemCount) {
                currentController.getMediaItemAt(i)?.localConfiguration?.uri?.toString()?.let {
                    currentUrls.add(it)
                }
            }
            
            val newUrls = playlistState.songs.map { it.url }
            
            // 如果播放列表不同，需要设置新的播放列表
            if (currentUrls != newUrls) {
                val mediaItems = playlistState.songs.map { song ->
                    MediaItem.Builder()
                        .setUri(song.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.name.substringBeforeLast('.'))
                                .build()
                        )
                        .build()
                }
                
                currentController.setMediaItems(mediaItems)
                currentController.prepare()
            }
            
            // 跳转到指定位置并播放
            if (index >= 0 && index < playlistState.songs.size) {
                currentController.seekToDefaultPosition(index)
                currentController.play()
            }
        }
    }

    MusicListScreen(
        webDavConfig = webDavConfig,
        directoryPath = album.directoryUrl,
        showBack = true,
        onBack = onBack,
        currentPlayingIndex = playlistState.currentIndex,
        onPlaylistLoaded = { songs ->
            loadPlaylist(songs)
        },
        onSongSelected = { index, song ->
            setPlaylistAndPlay(index)
        },
        bottomBar = {
            BottomPlayerBar(
                playlistState = playlistState,
                onPlayPause = {
                    controller?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                },
                onNext = {
                    controller?.seekToNext()
                },
                onPrevious = {
                    controller?.seekToPrevious()
                }
            )
        },
        modifier = modifier
    )
}
