package com.spotify.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class SimpleMusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val uri = RawResourceDataSource.buildRawResourceUri(R.raw.sample_music)
        val context = this

        player = ExoPlayer.Builder(context)
            .build()
            .apply {
                val trackMetadata = MediaMetadata.Builder()
                    .setTitle("Luoji 演示音频")
                    .setArtist("内置示例")
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(trackMetadata)
                    .build()
                setMediaItem(mediaItem)
                prepare()
            }

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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

