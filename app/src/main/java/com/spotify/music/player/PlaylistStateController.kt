package com.spotify.music.player

import android.content.ComponentName
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.spotify.music.SimpleMusicService
import com.spotify.music.data.MusicFile
import com.spotify.music.data.PlaylistState
import com.spotify.music.data.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.platform.LocalContext

class PlaylistStateController {
    private val _state = androidx.compose.runtime.mutableStateOf(PlaylistState())
    val state: PlaylistState
        get() = _state.value

    private var controller: MediaController? = null
    private val listenerRef = AtomicReference<Player.Listener>()
    private var isControllerReady = false
    private var lastCredentials: Pair<String, String>? = null

    init {
        _state.value = PlaylistState()
    }

    fun setCredentials(webDavConfig: WebDavConfig) {
        val credentials = Pair(webDavConfig.username, webDavConfig.password)

        // 只有在凭据真的改变了时才调用 Service
        if (lastCredentials != credentials) {
            lastCredentials = credentials
            SimpleMusicService.setCredentials(webDavConfig.username, webDavConfig.password)
            Log.d("PlaylistStateController", "Credentials updated for user: ${webDavConfig.username}")
        } else {
            Log.d("PlaylistStateController", "Credentials unchanged, skipping update")
        }
    }

    suspend fun initialize(context: android.content.Context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SimpleMusicService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        val mainExecutor = ContextCompat.getMainExecutor(context)

        controllerFuture.addListener({
            try {
                val mediaController = controllerFuture.get()
                controller = mediaController
                isControllerReady = mediaController.isConnected

                Log.d("PlaylistStateController", "MediaController connected successfully")
                Log.d("PlaylistStateController", "Controller ready: $isControllerReady")

                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // 只在MediaController的播放状态与当前状态不同时更新
                        // 避免覆盖用户点击时的即时状态更新
                        if (_state.value.isPlaying != isPlaying) {
                            _state.value = _state.value.copy(isPlaying = isPlaying)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlaylistStateController", "Player error: ${error.message}")
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        // Metadata updated
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                            val duration = player.duration.takeIf { it > 0 } ?: 0L
                            val currentIndex = player.currentMediaItemIndex
                            // 只在MediaController的索引与当前状态不同时更新
                            // 这样可以避免覆盖用户点击时的即时状态更新
                            if (_state.value.currentIndex != currentIndex) {
                                _state.value = _state.value.copy(
                                    duration = duration,
                                    currentIndex = currentIndex
                                )
                            } else {
                                // 只更新duration，保持currentIndex不变
                                _state.value = _state.value.copy(duration = duration)
                            }
                        }
                    }
                }
                listenerRef.set(listener)
                mediaController.addListener(listener)

                val duration = mediaController.duration.takeIf { it > 0 } ?: 0L
                val currentPosition = mediaController.currentPosition
                _state.value = _state.value.copy(
                    duration = duration,
                    currentPosition = currentPosition,
                    isPlaying = mediaController.isPlaying,
                    currentIndex = mediaController.currentMediaItemIndex
                )

                // MediaController 连接成功后，就认为是就绪的
                // 不再依赖 STATE_READY，因为空的 ExoPlayer 可能永远不会达到该状态
                isControllerReady = true
                Log.d("PlaylistStateController", "MediaController is ready (connection-based)")

                // 连接成功后，确保播放列表已加载（如果需要）
                ensurePlaylistLoaded()
            } catch (error: Exception) {
                // Handle error
                Log.e("PlaylistStateController", "MediaController failed to connect", error)
                isControllerReady = false
            }
        }, mainExecutor)
    }

    fun updateProgress(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                controller?.let {
                    val currentPosition = it.currentPosition
                    val duration = it.duration.takeIf { d -> d > 0 } ?: 0L
                    _state.value = _state.value.copy(
                        currentPosition = currentPosition,
                        duration = duration
                    )
                }
                delay(100)
            }
        }
    }

    fun loadPlaylist(songs: List<MusicFile>) {
        // 只更新播放列表数据，不自动设置到播放器
        // 播放列表的切换只在用户点击歌曲时进行
        // 如果当前播放的歌曲在新列表中，保持当前索引；否则重置索引
        val currentSong = state.currentSong
        val newIndex = if (currentSong != null) {
            songs.indexOfFirst { it.url == currentSong.url }.takeIf { it >= 0 }
                ?: state.currentIndex.takeIf { it < songs.size }
                ?: -1
        } else {
            -1
        }

        _state.value = _state.value.copy(
            songs = songs,
            currentIndex = newIndex
        )
    }

    suspend fun setPlaylistAndPlay(index: Int) {
        // 用户点击歌曲时，设置播放列表并播放
        if (state.songs.isEmpty()) return

        // 立即更新UI状态以提供即时反馈
        _state.value = _state.value.copy(currentIndex = index, isPlaying = true)

        // 等待控制器完全就绪（不仅仅是连接）
        if (!isControllerReady || controller == null) {
            Log.d("PlaylistStateController", "Controller not ready, waiting for full readiness...")
            val readySuccess = waitForConnection()
            if (!readySuccess || controller == null) {
                Log.w("PlaylistStateController", "Controller failed to become ready or timed out")
                return
            }
            Log.d("PlaylistStateController", "Controller is fully ready, proceeding with playback")
        }

        controller?.let { currentController ->
            val currentUrls = mutableListOf<String>()
            val currentMediaItemCount = currentController.mediaItemCount
            for (i in 0 until currentMediaItemCount) {
                currentController.getMediaItemAt(i).localConfiguration?.uri?.toString()?.let {
                    currentUrls.add(it)
                }
            }

            val newUrls = state.songs.map { it.url }

            // 如果播放列表不同，需要设置新的播放列表
            if (currentUrls != newUrls) {
                val mediaItems = state.songs.map { song ->
                    MediaItem.Builder()
                        .setUri(song.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.name.substringBeforeLast('.'))
                                .build()
                        )
                        .build()
                }

                // 先设置 Service 中 ExoPlayer 的播放列表
                SimpleMusicService.setPlaylist(mediaItems)
                // 然后设置 MediaController 的播放列表（实际上 MediaController 会自动同步）
                currentController.setMediaItems(mediaItems)
                currentController.prepare()
            }

            // 跳转到指定位置并播放
            if (index >= 0 && index < state.songs.size) {
                currentController.seekToDefaultPosition(index)
                currentController.play()
            }
        }
    }

    fun ensurePlaylistLoaded() {
        // 当 controller 初始化后，如果播放列表已加载但播放器为空，则设置播放列表
        // 注意：只在播放器为空时设置，不会自动切换不同的播放列表
        if (!isControllerReady || controller == null) {
            Log.w("PlaylistStateController", "Controller not ready, cannot ensure playlist loaded")
            return
        }

        controller?.let { currentController ->
            if (state.songs.isNotEmpty()) {
                val currentMediaItemCount = currentController.mediaItemCount
                // 只在播放列表为空时才设置，不自动切换不同的播放列表
                if (currentMediaItemCount == 0) {
                    val mediaItems = state.songs.map { song ->
                        MediaItem.Builder()
                            .setUri(song.url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.name.substringBeforeLast('.'))
                                    .build()
                            )
                            .build()
                    }
                    // 先设置 Service 中 ExoPlayer 的播放列表
                    SimpleMusicService.setPlaylist(mediaItems)
                    // 然后设置 MediaController 的播放列表
                    currentController.setMediaItems(mediaItems)
                    currentController.prepare()
                }
            }
        }
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun seekToNext() {
        controller?.seekToNext()
    }

    fun seekToPrevious() {
        controller?.seekToPrevious()
    }

    suspend fun waitForConnection(timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()

        // 等待连接完成（连接成功后会自动设置为就绪）
        while (!isControllerReady && System.currentTimeMillis() - startTime < timeoutMs) {
            kotlinx.coroutines.delay(100)
        }

        Log.d("PlaylistStateController", "Connection check result - ready: $isControllerReady")
        return isControllerReady
    }

    fun release() {
        listenerRef.get()?.let { controller?.removeListener(it) }
        controller?.release()
        controller = null
        isControllerReady = false
        lastCredentials = null
    }
}

@Composable
fun rememberPlaylistStateController(): PlaylistStateController {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val controller = remember { PlaylistStateController() }

    // Initialize controller
    LaunchedEffect(Unit) {
        controller.initialize(context)
        controller.updateProgress(coroutineScope)
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.release()
        }
    }

    return controller
}