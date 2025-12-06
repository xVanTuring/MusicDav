package com.spotify.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.Player
import com.spotify.music.cache.CachedHttpDataSource
import com.spotify.music.cache.MusicCacheManager
import com.spotify.music.data.PlayMode
import okhttp3.Credentials

class SimpleMusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null
    private var cacheManager: MusicCacheManager? = null

    // Store auth credentials for WebDAV
    private var webDavUsername: String? = null
    private var webDavPassword: String? = null
    private var currentPlayMode: PlayMode = PlayMode.PLAY_ONCE

    companion object {
        private var staticUsername: String? = null
        private var staticPassword: String? = null
        private var instance: SimpleMusicService? = null

        fun setCredentials(username: String, password: String) {
            staticUsername = username
            staticPassword = password
            // Update existing instance if available
            instance?.updateCredentials(username, password)
        }

        fun setPlaylist(mediaItems: List<MediaItem>) {
            instance?.setPlaylist(mediaItems)
        }

        fun playAt(index: Int) {
            instance?.playAt(index)
        }

        fun setPlayMode(mode: PlayMode) {
            instance?.setPlayMode(mode)
        }
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        instance = this
        val context = this

        // Initialize cache manager
        cacheManager = MusicCacheManager.getInstance(context)

        // Get credentials from static storage
        webDavUsername = staticUsername
        webDavPassword = staticPassword

        // Create DataSource factory with auth support
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MusicDav/1.0")

        // Apply auth headers if credentials are available
        updateAuthHeaders()

        // Create cached data source factory
        val cachedDataSourceFactory = cacheManager?.let { cache ->
            CachedHttpDataSource.Factory(httpDataSourceFactory!!, cache)
        } ?: httpDataSourceFactory

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachedDataSourceFactory as DataSource.Factory))
            .build()

        // 设置播放模式监听器
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    handlePlaybackEnded()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 当歌曲切换时，检查是否需要处理播放模式
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                    handleAutoTransition(mediaItem)
                }
            }
        })

        val sessionIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(context, player!!)
            .setSessionActivity(sessionIntent)
            .build()

        val defaultNotificationProvider = DefaultMediaNotificationProvider(context)
        setMediaNotificationProvider(defaultNotificationProvider)
    }
    
    @UnstableApi
    private fun updateCredentials(username: String, password: String) {
        // 检查 credentials 是否真的改变了
        if (webDavUsername == username && webDavPassword == password) {
            // credentials 相同，只更新 auth headers
            updateAuthHeaders()
            return
        }

        // 只有在 credentials 真正改变时才重新创建 player
        // 但是如果没有播放内容，就不重新创建（避免破坏现有的 MediaController 连接）
        val hasMediaContent = player?.let { it.mediaItemCount > 0 } ?: false

        if (!hasMediaContent) {
            // 没有媒体内容时，只更新 headers，不重新创建 player
            webDavUsername = username
            webDavPassword = password
            updateAuthHeaders()
            return
        }

        // 有媒体内容且 credentials 改变了，需要重新创建 player
        webDavUsername = username
        webDavPassword = password
        updateAuthHeaders()

        player?.let { existingPlayer ->
            val currentPosition = existingPlayer.currentPosition
            val currentIndex = existingPlayer.currentMediaItemIndex
            val wasPlaying = existingPlayer.isPlaying
            val mediaItems = mutableListOf<MediaItem>()
            for (i in 0 until existingPlayer.mediaItemCount) {
                existingPlayer.getMediaItemAt(i).let { mediaItems.add(it) }
            }
            
            // Release old player
            existingPlayer.release()
            
            // Create cached data source factory
            val cachedDataSourceFactory = cacheManager?.let { cache ->
                CachedHttpDataSource.Factory(httpDataSourceFactory!!, cache)
            } ?: httpDataSourceFactory

            // Create new player with updated credentials
            val newPlayer = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cachedDataSourceFactory as DataSource.Factory))
                .build()
                .apply {
                    if (mediaItems.isNotEmpty()) {
                        setMediaItems(mediaItems)
                        prepare()
                        seekTo(currentIndex, currentPosition)
                        if (wasPlaying) play()
                    }
                }
            
            player = newPlayer
            
            // Update session with new player
            mediaSession?.let { session ->
                val sessionActivity = session.sessionActivity
                session.release()
                
                val newSessionBuilder = MediaSession.Builder(this, newPlayer)
                sessionActivity?.let { newSessionBuilder.setSessionActivity(it) }
                mediaSession = newSessionBuilder.build()
            }
        }
    }
    
    private fun updateAuthHeaders() {
        webDavUsername?.let { username ->
            webDavPassword?.let { password ->
                httpDataSourceFactory?.setDefaultRequestProperties(
                    mapOf("Authorization" to Credentials.basic(username, password))
                )
            }
        }
    }
    
    fun setPlaylist(mediaItems: List<MediaItem>) {
        player?.apply {
            setMediaItems(mediaItems)
            prepare()
        }
    }
    
    fun playAt(index: Int) {
        player?.apply {
            seekToDefaultPosition(index)
            play()
        }
    }

  private fun handlePlaybackEnded() {
        player?.let { player ->
            when (currentPlayMode) {
                PlayMode.REPEAT_SINGLE -> {
                    // 单曲循环：重新播放当前歌曲
                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex >= 0) {
                        player.seekToDefaultPosition(currentIndex)
                        player.play()
                    }
                }
                PlayMode.REPEAT_ALL -> {
                    // 列表循环：回到第一首并播放
                    if (player.mediaItemCount > 0) {
                        player.seekToDefaultPosition(0)
                        player.play()
                    }
                }
                PlayMode.PLAY_ONCE -> {
                    // 顺序播放：不做任何操作，停止播放
                }
            }
        }
    }

    private fun handleAutoTransition(mediaItem: MediaItem) {
        player?.let { player ->
            val currentIndex = player.currentMediaItemIndex
            val isLastSong = currentIndex == player.mediaItemCount - 1

            if (currentPlayMode == PlayMode.PLAY_ONCE && isLastSong) {
                // 顺序播放模式下，播放完最后一首后停止
                player.pause()
            } else if (currentPlayMode == PlayMode.REPEAT_ALL && isLastSong) {
                // 列表循环模式下，播放完最后一首后回到第一首
                player.seekToDefaultPosition(0)
            }
            // 单曲循环模式由 handlePlaybackEnded 处理
        }
    }

    fun setPlayMode(mode: PlayMode) {
        currentPlayMode = mode
        // 设置 ExoPlayer 的重复模式
        player?.repeatMode = when (mode) {
            PlayMode.REPEAT_SINGLE -> Player.REPEAT_MODE_ONE
            PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
            PlayMode.PLAY_ONCE -> Player.REPEAT_MODE_OFF
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        instance = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

