package tech.xvanturing.musicdav.player

import android.content.ComponentName
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import tech.xvanturing.musicdav.SimpleMusicService
import tech.xvanturing.musicdav.data.AlbumsRepository
import tech.xvanturing.musicdav.data.CachedMetadata
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.MusicMetadataCache
import tech.xvanturing.musicdav.data.PlaylistCache
import tech.xvanturing.musicdav.data.PlaylistState
import tech.xvanturing.musicdav.data.PlayMode
import tech.xvanturing.musicdav.data.ServerAddressResolver
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.data.cacheKey
import tech.xvanturing.musicdav.data.resolveAlbumUrl
import tech.xvanturing.musicdav.MusicCacheService
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
    private var context: android.content.Context? = null

    init {
        _state.value = PlaylistState()
    }

    fun setCredentials(webDavConfig: WebDavConfig) {
        val credentials = Pair(webDavConfig.username, webDavConfig.password)

        // 只有在凭据真的改变了时才调用 Service
        if (lastCredentials != credentials) {
            lastCredentials = credentials
            SimpleMusicService.setCredentials(webDavConfig.username, webDavConfig.password)
            Log.d(
                "PlaylistStateController",
                "Credentials updated for user: ${webDavConfig.username}"
            )
        } else {
            Log.d("PlaylistStateController", "Credentials unchanged, skipping update")
        }
    }

    fun setCurrentAlbumCover(coverUrl: String?) {
        _state.value = _state.value.copy(currentAlbumCoverUrl = coverUrl)
    }

    // 设置歌曲到专辑封面的映射
    fun setSongAlbumCovers(songs: List<MusicFile>, albumCoverUrl: String?) {
        val songToAlbumCoverMap = songs.associateBy({ it.url }, { albumCoverUrl })
        // 合并现有的映射，而不是完全替换
        val newMap = _state.value.songToAlbumCoverMap.toMutableMap()
        newMap.putAll(songToAlbumCoverMap)

        _state.value = _state.value.copy(
            songToAlbumCoverMap = newMap
        )
    }

    // 设置当前播放专辑的WebDAV配置
    fun setCurrentWebDavConfig(webDavConfig: WebDavConfig?) {
        _state.value = _state.value.copy(currentWebDavConfig = webDavConfig)
    }

    // 设置当前播放列表所属专辑的 id（跨专辑列表传 null），用于首页胶片高亮旋转
    fun setCurrentAlbumId(albumId: String?) {
        _state.value = _state.value.copy(currentAlbumId = albumId)
    }

    // 设置歌曲到其所属服务器配置的映射（收藏夹/搜索结果等跨服务器播放列表使用）
    fun setSongConfigs(configs: Map<String, WebDavConfig>) {
        val merged = _state.value.songToConfigMap.toMutableMap()
        merged.putAll(configs)
        _state.value = _state.value.copy(songToConfigMap = merged)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _state.value = _state.value.copy(currentPosition = positionMs)
    }

    suspend fun initialize(context: android.content.Context) {
        this.context = context
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
                    

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                        ) {
                            val duration = player.duration.takeIf { it > 0 } ?: 0L
                            val currentIndex = player.currentMediaItemIndex
                            // 只在MediaController的索引与当前状态不同时更新
                            // 这样可以避免覆盖用户点击时的即时状态更新
                            if (_state.value.currentIndex != currentIndex) {
                                Log.d(
                                    "PlaylistStateController",
                                    "自动切换歌曲: 设置加载状态 isLoadingMetadata=true, 重置封面"
                                )
                                _state.value = _state.value.copy(
                                    duration = duration,
                                    currentIndex = currentIndex,
                                    currentEmbeddedCoverUrl = null,
                                    isLoadingMetadata = true,
                                    cachedCoverMap = _state.value.cachedCoverMap // 保留缓存映射
                                )
                                
                                // 播放列表内歌曲可能来自不同服务器（如收藏夹/搜索结果），
                                // 按曲目切换鉴权信息，确保接下来的网络请求用对凭据
                                val newCurrentSong = state.songs.getOrNull(currentIndex)
                                val songConfig = newCurrentSong?.let { state.songToConfigMap[it.url] }
                                if (songConfig != null) {
                                    setCredentials(songConfig)
                                    setCurrentWebDavConfig(songConfig)
                                }
                                val effectiveConfig = songConfig ?: state.currentWebDavConfig

                                // 触发新歌曲缓存
                                context.let { ctx ->
                                    if (newCurrentSong != null && effectiveConfig != null) {
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                            try {
                                                MusicCacheService.startCaching(ctx, newCurrentSong, effectiveConfig)
                                                Log.d("PlaylistStateController", "🎵 自动缓存: ${newCurrentSong.name}")
                                            } catch (e: Exception) {
                                                Log.e("PlaylistStateController", "自动缓存失败", e)
                                            }
                                        }
                                    }
                                }
                            } else {
                                _state.value = _state.value.copy(duration = duration)
                            }
                        }
                    }
                }
                listenerRef.set(listener)
                mediaController.addListener(listener)

                val duration = mediaController.duration.takeIf { it > 0 } ?: 0L
                val currentPosition = mediaController.currentPosition
                val currentIndex = mediaController.currentMediaItemIndex

                // 检查 MediaController 是否有媒体内容，如果有则同步播放列表
                syncPlaylistFromMediaController(mediaController, currentIndex)

                _state.value = _state.value.copy(
                    duration = duration,
                    currentPosition = currentPosition,
                    isPlaying = mediaController.isPlaying,
                    currentIndex = currentIndex
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

        // 立即更新UI状态以提供即时反馈，同时重置内置封面
        Log.d(
            "PlaylistStateController",
            "用户点击歌曲: 设置加载状态 isLoadingMetadata=true, 重置封面"
        )

        _state.value = _state.value.copy(
            currentIndex = index,
            isPlaying = true,
            currentEmbeddedCoverUrl = null,
            isLoadingMetadata = true,
            cachedCoverMap = _state.value.cachedCoverMap // 保留缓存映射
        )

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
                        .setCustomCacheKey(song.cacheKey)
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

                // Cache the current song in background
                context?.let { ctx ->
                    CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        state.currentSong?.let { song ->
                            if (state.currentWebDavConfig != null) {
                                cacheCurrentSong(state.currentWebDavConfig!!)
                            }
                        }
                    }
                }
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
                            .setCustomCacheKey(song.cacheKey)
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

    private fun syncPlaylistFromMediaController(
        mediaController: MediaController,
        currentIndex: Int
    ) {
        try {
            val mediaItemCount = mediaController.mediaItemCount

            // 如果 MediaController 有媒体内容但本地 songs 列表为空，需要同步
            if (mediaItemCount > 0 && state.songs.isEmpty()) {
                Log.d(
                    "PlaylistStateController",
                    "Syncing playlist from MediaController, items count: $mediaItemCount"
                )

                val syncedSongs = mutableListOf<MusicFile>()

                for (i in 0 until mediaItemCount) {
                    val mediaItem = mediaController.getMediaItemAt(i)
                    val uri = mediaItem.localConfiguration?.uri?.toString()
                    val title = mediaItem.mediaMetadata?.title?.toString() ?: "Unknown"

                    if (uri != null) {
                        // 从 URL 推断文件名
                        val fileName = uri.substringAfterLast('/')
                        // customCacheKey 是 setPlaylistAndPlay 里写入的 song.cacheKey，形如
                        // "serverConfigId::path"（无关联服务器时就是 url）。解析回来恢复稳定缓存
                        // 身份，否则重建出的 MusicFile 的 cacheKey 退化成 url，封面/元数据缓存全部查不到
                        val customKey = mediaItem.localConfiguration?.customCacheKey
                        val serverConfigId = customKey
                            ?.takeIf { it.contains("::") }
                            ?.substringBefore("::")
                        val path = if (serverConfigId != null) customKey!!.substringAfter("::") else ""
                        val musicFile = MusicFile(
                            name = if (title != "Unknown") title else fileName,
                            url = uri,
                            path = path,
                            serverConfigId = serverConfigId
                        )
                        syncedSongs.add(musicFile)
                    }
                }

                if (syncedSongs.isNotEmpty()) {
                    _state.value = _state.value.copy(songs = syncedSongs)
                    Log.d(
                        "PlaylistStateController",
                        "Synced ${syncedSongs.size} songs from MediaController"
                    )
                    restorePlaybackContext(syncedSongs)
                }
            } else if (mediaItemCount > 0 && state.songs.isNotEmpty()) {
                // 如果都有内容，检查是否需要同步当前索引
                Log.d(
                    "PlaylistStateController",
                    "Both MediaController and local playlist have content, checking sync"
                )
            }
        } catch (error: Exception) {
            Log.e(
                "PlaylistStateController",
                "Error syncing playlist from MediaController: ${error.message}",
                error
            )
        }
    }

    // Activity 被系统回收重建（挂后台一段时间、旋转等）后 remember 的本控制器是全新实例：
    // MediaController 只能还原 url 列表，封面映射/元数据/专辑上下文都随旧实例丢了，表现为
    // 底部播放条只剩占位图标和文件名。这里从本地缓存把它们恢复回来：元数据缓存恢复标题、
    // CoverCache 恢复内嵌封面，再反查哪个专辑的缓存列表包含这些歌，恢复专辑封面兜底、
    // WebDAV 配置（http 封面加载要鉴权）和 currentAlbumId（首页胶片高亮）。
    private fun restorePlaybackContext(syncedSongs: List<MusicFile>) {
        val ctx = context ?: return
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // 1) 元数据缓存 → 标题/艺人/时长（displayName 恢复成正经标题而不是 URL 文件名）
                val metaMap = MusicMetadataCache.getBatch(ctx, syncedSongs.map { it.cacheKey })
                if (metaMap.isNotEmpty()) {
                    val enriched = _state.value.songs.map { song ->
                        metaMap[song.cacheKey]?.let { meta ->
                            song.copy(
                                title = meta.title,
                                artist = meta.artist,
                                album = meta.album,
                                durationMs = meta.durationMs
                            )
                        } ?: song
                    }
                    _state.value = _state.value.copy(songs = enriched)
                }

                // 2) 内嵌封面本地缓存（currentCoverUrl 优先级 1）
                loadCachedCovers(ctx, syncedSongs)

                // 3) 反查所属专辑：恢复专辑封面兜底、鉴权配置、专辑 id
                val urls = syncedSongs.map { it.url }.toSet()
                val album = AlbumsRepository.load(ctx).firstOrNull { album ->
                    PlaylistCache.load(ctx, album.id).any { it.url in urls }
                } ?: return@launch
                val serverConfig = album.serverConfigId
                    ?.let { id -> ServerConfigRepository.load(ctx).find { it.id == id } }
                val config = if (serverConfig != null) {
                    serverConfig.toWebDavConfig(ServerAddressResolver.resolve(serverConfig))
                } else {
                    album.config
                }
                val coverUrl = resolveAlbumUrl(album.coverImageUrl, config.url)
                setSongAlbumCovers(syncedSongs, coverUrl)
                _state.value = _state.value.copy(
                    currentWebDavConfig = config,
                    currentAlbumId = album.id
                )
                setCredentials(config)
                Log.d(
                    "PlaylistStateController",
                    "Restored playback context from album ${album.name}"
                )
            } catch (e: Exception) {
                Log.e("PlaylistStateController", "Failed to restore playback context", e)
            }
        }
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

    // 切换播放模式
    fun togglePlayMode() {
        val currentMode = _state.value.playMode
        val nextMode = when (currentMode) {
            PlayMode.PLAY_ONCE -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_SINGLE
            PlayMode.REPEAT_SINGLE -> PlayMode.PLAY_ONCE
        }
        _state.value = _state.value.copy(playMode = nextMode)
        // 通知 Service 更新播放模式
        SimpleMusicService.setPlayMode(nextMode)
        Log.d("PlaylistStateController", "播放模式切换: $currentMode -> $nextMode")
    }

    // 设置播放模式
    fun setPlayMode(mode: PlayMode) {
        if (_state.value.playMode != mode) {
            _state.value = _state.value.copy(playMode = mode)
            SimpleMusicService.setPlayMode(mode)
            Log.d("PlaylistStateController", "设置播放模式: $mode")
        }
    }

    fun release() {
        listenerRef.get()?.let { controller?.removeListener(it) }
        controller?.release()
        controller = null
        isControllerReady = false
        lastCredentials = null
    }

    fun loadCachedCovers(context: android.content.Context, songs: List<MusicFile>) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val newCachedCovers = mutableMapOf<String, String>()
            for (song in songs) {
                CoverCache.getCoverPath(context, song.cacheKey)?.let { path ->
                    newCachedCovers[song.url] = path
                    Log.d("PlaylistStateController", "Cached cover found for: ${song.name} -> $path")
                }
            }
            Log.d("PlaylistStateController", "Loaded ${newCachedCovers.size} cached covers for current album")

            val mergedMap = _state.value.cachedCoverMap.toMutableMap()
            mergedMap.putAll(newCachedCovers)

            _state.value = _state.value.copy(cachedCoverMap = mergedMap)
        }
    }

    fun cacheCurrentSong(webDavConfig: WebDavConfig) {
        val currentSong = state.currentSong ?: return
        context?.let { ctx ->
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
            scope.launch {
                val isCached = MusicCache.isCached(ctx, currentSong.cacheKey)
                if (!isCached) {
                    Log.d(
                        "PlaylistStateController",
                        "🎵 Requesting cache for current song: ${currentSong.name}"
                    )
                    MusicCacheService.startCaching(ctx, currentSong, webDavConfig)
                } else {
                    Log.d("PlaylistStateController", "✅ Song already cached: ${currentSong.name}")
                }
            }
        }
    }

    fun prefetchNextSong(webDavConfig: WebDavConfig) {
        val nextIndex = state.currentIndex + 1
        if (nextIndex < state.songs.size) {
            val nextSong = state.songs[nextIndex]
            context?.let { ctx ->
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                scope.launch {
                    val isCached = MusicCache.isCached(ctx, nextSong.cacheKey)
                    if (!isCached) {
                        Log.d(
                            "PlaylistStateController",
                            "⏭️ Prefetching cache for next song: ${nextSong.name}"
                        )
                        MusicCacheService.startCaching(ctx, nextSong, webDavConfig)
                    }
                }
            }
        }
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