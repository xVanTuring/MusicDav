package com.spotify.music

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.spotify.music.data.MusicFile
import com.spotify.music.data.PlaylistState
import com.spotify.music.data.WebDavConfig
import com.spotify.music.ui.BottomPlayerBar
import com.spotify.music.ui.LoginScreen
import com.spotify.music.ui.MusicListScreen
import com.spotify.music.ui.theme.MusicDavTheme
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicDavTheme {
                MusicPlayerApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun MusicPlayerApp(modifier: Modifier = Modifier) {
    var webDavConfig by remember { mutableStateOf<WebDavConfig?>(null) }
    var isLoggedIn by remember { mutableStateOf(false) }
    
    if (!isLoggedIn) {
        LoginScreen(
            onLoginSuccess = { config ->
                webDavConfig = config
                isLoggedIn = true
            },
            modifier = modifier
        )
    } else {
        webDavConfig?.let { config ->
            MusicPlayerScreen(
                webDavConfig = config,
                modifier = modifier
            )
        }
    }
}

@Composable
fun MusicPlayerScreen(
    webDavConfig: WebDavConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playlistState by remember { mutableStateOf(PlaylistState()) }
    
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
    
    fun loadPlaylist(songs: List<MusicFile>) {
        playlistState = playlistState.copy(songs = songs)
        
        // Create media items for all songs
        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setUri(song.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.name.substringBeforeLast('.'))
                        .build()
                )
                .build()
        }
        
        controller?.apply {
            setMediaItems(mediaItems)
            prepare()
        }
    }

    MusicListScreen(
        webDavConfig = webDavConfig,
        currentPlayingIndex = playlistState.currentIndex,
        onPlaylistLoaded = { songs ->
            loadPlaylist(songs)
        },
        onSongSelected = { index, song ->
            controller?.apply {
                seekToDefaultPosition(index)
                play()
            }
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

private fun ensureServiceStarted(context: android.content.Context) {
    val intent = Intent(context, SimpleMusicService::class.java)
    ContextCompat.startForegroundService(context, intent)
}
