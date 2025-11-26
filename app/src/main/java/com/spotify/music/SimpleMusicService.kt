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
import okhttp3.Credentials

class SimpleMusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null
    
    // Store auth credentials for WebDAV
    private var webDavUsername: String? = null
    private var webDavPassword: String? = null
    
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
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        instance = this
        val context = this
        
        // Get credentials from static storage
        webDavUsername = staticUsername
        webDavPassword = staticPassword
        
        // Create DataSource factory with auth support
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MusicDav/1.0")
        
        // Apply auth headers if credentials are available
        updateAuthHeaders()

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory!!))
            .build()

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
        webDavUsername = username
        webDavPassword = password
        updateAuthHeaders()
        
        // Recreate the player with new credentials if needed
        player?.let { existingPlayer ->
            val currentPosition = existingPlayer.currentPosition
            val currentIndex = existingPlayer.currentMediaItemIndex
            val wasPlaying = existingPlayer.isPlaying
            val mediaItems = mutableListOf<MediaItem>()
            for (i in 0 until existingPlayer.mediaItemCount) {
                existingPlayer.getMediaItemAt(i)?.let { mediaItems.add(it) }
            }
            
            // Release old player
            existingPlayer.release()
            
            // Create new player with updated credentials
            val newPlayer = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory!!))
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

