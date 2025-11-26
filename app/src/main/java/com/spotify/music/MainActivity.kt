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
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.spotify.music.ui.theme.MusicDavTheme
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicDavTheme {
                PlayerScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun PlayerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var uiState by remember { mutableStateOf(PlayerUiState()) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
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
                        uiState = uiState.copy(isPlaying = isPlaying, errorMessage = null)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val buffering = playbackState == Player.STATE_BUFFERING
                        val ended = playbackState == Player.STATE_ENDED
                        uiState = uiState.copy(
                            isBuffering = buffering,
                            isPlaying = if (ended) false else uiState.isPlaying
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        uiState = uiState.copy(errorMessage = error.errorCodeName)
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        val title = mediaMetadata.title?.toString() ?: uiState.title
                        val subtitle = mediaMetadata.artist?.toString() ?: uiState.subtitle
                        uiState = uiState.copy(title = title, subtitle = subtitle)
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                            duration = player.duration.takeIf { it > 0 } ?: 0L
                        }
                    }
                }
                listenerRef.set(listener)
                mediaController.addListener(listener)
                val metadata = mediaController.mediaMetadata
                duration = mediaController.duration.takeIf { it > 0 } ?: 0L
                currentPosition = mediaController.currentPosition
                uiState = uiState.copy(
                    title = metadata.title?.toString() ?: uiState.title,
                    subtitle = metadata.artist?.toString() ?: uiState.subtitle,
                    isPlaying = mediaController.isPlaying
                )
            } catch (error: Exception) {
                uiState = uiState.copy(errorMessage = error.message)
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
                if (!isSeeking) {
                    currentPosition = it.currentPosition
                    if (duration <= 0) {
                        duration = it.duration.takeIf { d -> d > 0 } ?: 0L
                    }
                }
            }
            delay(100)
        }
    }

    PlayerContent(
        modifier = modifier,
        uiState = uiState,
        currentPosition = currentPosition,
        duration = duration,
        onSeekStart = { isSeeking = true },
        onSeekEnd = { position ->
            isSeeking = false
            controller?.seekTo(position)
        },
        onPlayClicked = {
            if (needsNotificationPermission && !notificationGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ensureServiceStarted(context)
                controller?.play()
            }
        },
        onPauseClicked = {
            controller?.pause()
        },
        onStopClicked = {
            controller?.stop()
            controller?.seekToDefaultPosition()
        },
        notificationGranted = notificationGranted,
        onRequestNotificationPermission = {
            if (needsNotificationPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )
}

@Composable
private fun PlayerContent(
    uiState: PlayerUiState,
    currentPosition: Long,
    duration: Long,
    onSeekStart: () -> Unit,
    onSeekEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onPlayClicked: () -> Unit,
    onPauseClicked: () -> Unit,
    onStopClicked: () -> Unit,
    notificationGranted: Boolean,
    onRequestNotificationPermission: () -> Unit
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isUserSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(currentPosition, duration) {
        if (!isUserSeeking && duration > 0) {
            sliderPosition = currentPosition.toFloat()
        }
    }
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = uiState.title, style = MaterialTheme.typography.headlineSmall)
                Text(text = uiState.subtitle, style = MaterialTheme.typography.bodyMedium)
                
                if (duration > 0) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        Slider(
                            value = sliderPosition,
                            onValueChange = { value ->
                                isUserSeeking = true
                                sliderPosition = value
                            },
                            onValueChangeFinished = {
                                isUserSeeking = false
                                onSeekStart()
                                onSeekEnd(sliderPosition.toLong())
                            },
                            valueRange = 0f..duration.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(if (isUserSeeking) sliderPosition.toLong() else currentPosition),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = formatTime(duration),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                
                if (uiState.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
                if (uiState.errorMessage != null) {
                    Text(
                        text = "播放出错：${uiState.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = notificationGranted,
                onClick = { if (uiState.isPlaying) onPauseClicked() else onPlayClicked() }
            ) {
                Text(if (uiState.isPlaying) "暂停播放" else "播放示例")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onStopClicked
            ) {
                Text("停止并复位")
            }

            if (!notificationGranted) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequestNotificationPermission
                ) {
                    Text("授予通知权限以启用系统控件")
                }
            }
        }

        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            text = "该播放器使用单一 MediaSession 管理示例 MP3 资源，可通过系统媒体控件统一控制。",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

private fun ensureServiceStarted(context: android.content.Context) {
    val intent = Intent(context, SimpleMusicService::class.java)
    ContextCompat.startForegroundService(context, intent)
}

data class PlayerUiState(
    val title: String = "MusicDav 示例音频",
    val subtitle: String = "固定内置 mp3 资源",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val errorMessage: String? = null
)

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
