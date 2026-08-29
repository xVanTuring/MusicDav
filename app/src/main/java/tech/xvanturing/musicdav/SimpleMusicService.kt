package tech.xvanturing.musicdav

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.Player
import tech.xvanturing.musicdav.data.PlayMode
import tech.xvanturing.musicdav.player.CachedDataSource
import tech.xvanturing.musicdav.player.WebDavAuthStore
import tech.xvanturing.musicdav.util.AppLog

class SimpleMusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Store auth credentials for WebDAV
    private var webDavUsername: String? = null
    private var webDavPassword: String? = null
    private var currentPlayMode: PlayMode = PlayMode.PLAY_ONCE

    // 网络抖动/服务器短暂不可用时的自动重试计数，播到 READY 就清零
    private var retryCount = 0
    private var pendingRetry: Runnable? = null

    companion object {
        private const val TAG = "MusicService"

        // 后台播放期间网络抖一下就永久停住是最恼人的失败形态，自动重试几次再放弃
        private const val MAX_RETRY = 4

        // 连接/读取超时。默认 8s 在"设备刚从 doze 醒来、WiFi 还没握好手"时太短，容易直接判失败
        private const val HTTP_TIMEOUT_MS = 30_000

        private var staticUsername: String? = null
        private var staticPassword: String? = null

        // 服务被系统回收后重建时，界面不会再推一次播放模式，不记住的话循环模式会悄悄退回顺序播放
        private var staticPlayMode: PlayMode = PlayMode.PLAY_ONCE
        private var instance: SimpleMusicService? = null

        @OptIn(UnstableApi::class)
        fun setCredentials(username: String, password: String) {
            staticUsername = username
            staticPassword = password
            WebDavAuthStore.setDefault(username, password)
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
            staticPlayMode = mode
            instance?.setPlayMode(mode)
        }
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        instance = this
        val context = this
        AppLog.i(TAG, "服务创建")

        // Get credentials from static storage
        webDavUsername = staticUsername
        webDavPassword = staticPassword

        // Create DataSource factory with auth support
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MusicDav/1.0")
            .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)

        // Apply auth headers if credentials are available
        updateAuthHeaders()

        // Create cached data source factory
        val cachedDataSourceFactory = CachedDataSource.Factory(context, httpDataSourceFactory!!)

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachedDataSourceFactory))
            // 后台流式播放的关键：不持锁的话息屏进 doze 后 CPU/WiFi 会睡，缓冲断流 →
            // 表现为"后台放着放着就停了，也没提示"。WAKE_MODE_NETWORK 只在播放期间持锁。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // 拔耳机/断蓝牙时暂停，而不是外放
            .setHandleAudioBecomingNoisy(true)
            .build()

        player?.addListener(playerListener)
        setPlayMode(staticPlayMode)

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

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            AppLog.d(TAG, "播放状态=${stateName(playbackState)} item=${player?.currentMediaItemIndex}")
            if (playbackState == Player.STATE_READY) {
                // 成功播起来了，之前的重试记录作废
                if (retryCount != 0) AppLog.i(TAG, "恢复播放成功，重试计数清零")
                retryCount = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player
            AppLog.e(
                TAG,
                "播放错误 code=${error.errorCodeName} index=${p?.currentMediaItemIndex} " +
                    "pos=${p?.currentPosition} uri=${p?.currentMediaItem?.localConfiguration?.uri}",
                error
            )
            scheduleRetry()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            AppLog.i(
                TAG,
                "切歌 reason=$reason index=${player?.currentMediaItemIndex} " +
                    "uri=${mediaItem?.localConfiguration?.uri}"
            )
            // 换了一首就重新给满重试次数，避免上一首用掉的次数拖累这一首
            retryCount = 0
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    /**
     * 播放出错后的自动重试：退避 1s/2s/3s… 重新 prepare 并回到出错时的位置继续。
     * 之前这里什么都没有——任何一次瞬时网络错误都会让播放永久停在原地，界面上也毫无提示。
     */
    private fun scheduleRetry() {
        val p = player ?: return
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "已重试 $retryCount 次仍失败，放弃")
            return
        }
        retryCount++
        val delayMs = 1000L * retryCount
        val index = p.currentMediaItemIndex
        val position = p.currentPosition.coerceAtLeast(0L)
        AppLog.i(TAG, "第 $retryCount 次重试将在 ${delayMs}ms 后进行 index=$index pos=$position")

        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            val current = player ?: return@Runnable
            current.prepare()
            if (index != C.INDEX_UNSET && index < current.mediaItemCount) {
                current.seekTo(index, position)
            }
            current.play()
        }
        pendingRetry = task
        mainHandler.postDelayed(task, delayMs)
    }

    /**
     * 凭据变化只需要更新请求头。
     *
     * 原实现在"已有播放内容且凭据变了"时会 release 掉 ExoPlayer 和 MediaSession 再重建一套——
     * 而收藏夹/搜索这种跨服务器列表每切一首都可能触发凭据变化，于是播放中途整个 session 被换掉，
     * 已连上的 MediaController 变成死连接，播放直接停住且界面状态不更新。现在一律只改请求头：
     * DefaultHttpDataSource.Factory 的 defaultRequestProperties 是按引用共享给已创建的数据源的，
     * 改完立刻生效，不需要也不应该重建播放器。
     */
    @UnstableApi
    private fun updateCredentials(username: String, password: String) {
        if (webDavUsername == username && webDavPassword == password) {
            updateAuthHeaders()
            return
        }
        webDavUsername = username
        webDavPassword = password
        updateAuthHeaders()
        AppLog.i(TAG, "凭据已更新 user=$username（不重建播放器）")
    }

    @OptIn(UnstableApi::class)
    private fun updateAuthHeaders() {
        val username = webDavUsername ?: return
        val password = webDavPassword ?: return
        httpDataSourceFactory?.setDefaultRequestProperties(
            mapOf("Authorization" to okhttp3.Credentials.basic(username, password))
        )
        WebDavAuthStore.setDefault(username, password)
    }

    fun setPlaylist(mediaItems: List<MediaItem>) {
        AppLog.i(TAG, "设置播放列表 ${mediaItems.size} 首")
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

    /**
     * 播放模式全部交给 ExoPlayer 的 repeatMode。
     *
     * 这里原本还有一对 handlePlaybackEnded/handleAutoTransition 手工实现同样的逻辑，而且是错的：
     * onMediaItemTransition(REASON_AUTO) 是"刚开始播下一首"时回调，判到 isLastSong 就
     * PLAY_ONCE→pause()、REPEAT_ALL→跳回第 0 首。结果顺序播放**自动播到最后一首的瞬间就暂停**、
     * 列表循环**永远跳过最后一首**。而且 currentPlayMode 只靠 setPlayMode 从界面推下来，服务被
     * 重建后会退回 PLAY_ONCE，于是循环模式下也照样在最后一首停掉——正是"后台播着播着就没声了"。
     */
    fun setPlayMode(mode: PlayMode) {
        currentPlayMode = mode
        player?.repeatMode = when (mode) {
            PlayMode.REPEAT_SINGLE -> Player.REPEAT_MODE_ONE
            PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
            PlayMode.PLAY_ONCE -> Player.REPEAT_MODE_OFF
        }
        AppLog.i(TAG, "播放模式=$mode")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        AppLog.i(TAG, "服务销毁")
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
        instance = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        super.onDestroy()
    }
}
