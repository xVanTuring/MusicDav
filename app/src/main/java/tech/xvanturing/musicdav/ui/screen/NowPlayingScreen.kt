package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import okhttp3.Credentials
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.PlayMode
import tech.xvanturing.musicdav.data.cacheKey
import tech.xvanturing.musicdav.player.PlaylistStateController

@Composable
fun NowPlayingScreen(
    playlistController: PlaylistStateController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state = playlistController.state
    val currentSong = state.currentSong

    BackHandler { onBack() }

    var isFavorite by remember(currentSong?.cacheKey) {
        mutableStateOf(currentSong?.let { FavoritesRepository.isFavorite(context, it) } ?: false)
    }

    // 播放时持续旋转，暂停时定格在当前角度，恢复播放时从原角度继续转
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 9000, easing = LinearEasing)
                )
            )
        } else {
            rotation.stop()
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "收起")
                }
                Spacer(modifier = Modifier.weight(1f))
                if (currentSong != null) {
                    IconButton(
                        onClick = {
                            val fallbackConfig = if (currentSong.serverConfigId == null) state.effectiveWebDavConfig else null
                            isFavorite = FavoritesRepository.toggle(context, currentSong, fallbackConfig)
                        }
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavorite) "取消收藏" else "收藏",
                            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                VinylCover(
                    coverUrl = state.currentCoverUrl,
                    webDavConfig = state.effectiveWebDavConfig,
                    rotationDegrees = rotation.value
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = currentSong?.displayName ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                    softWrap = false
                )
                if (currentSong?.artist != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentSong.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val duration = state.duration.coerceAtLeast(1L)
                val sliderValue = (if (isDragging) dragPosition else state.currentPosition.toFloat())
                    .coerceIn(0f, duration.toFloat())
                Slider(
                    value = sliderValue,
                    valueRange = 0f..duration.toFloat(),
                    onValueChange = {
                        isDragging = true
                        dragPosition = it
                    },
                    onValueChangeFinished = {
                        playlistController.seekTo(dragPosition.toLong())
                        isDragging = false
                    },
                    colors = SliderDefaults.colors()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(if (isDragging) dragPosition.toLong() else state.currentPosition),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTime(state.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { playlistController.togglePlayMode() }) {
                        Icon(
                            imageVector = when (state.playMode) {
                                PlayMode.REPEAT_SINGLE -> Icons.Default.RepeatOne
                                PlayMode.REPEAT_ALL -> Icons.Default.Repeat
                                PlayMode.PLAY_ONCE -> Icons.Default.Repeat
                            },
                            contentDescription = "播放模式",
                            tint = when (state.playMode) {
                                PlayMode.PLAY_ONCE -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    IconButton(
                        onClick = { playlistController.seekToPrevious() },
                        enabled = state.hasPrevious
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (state.isPlaying) playlistController.pause() else playlistController.play()
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(
                        onClick = { playlistController.seekToNext() },
                        enabled = state.hasNext
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    // 占位，让播放控件在视觉上居中（与左侧播放模式按钮对称）
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
private fun VinylCover(
    coverUrl: String?,
    webDavConfig: tech.xvanturing.musicdav.data.WebDavConfig?,
    rotationDegrees: Float
) {
    Box(
        modifier = Modifier
            .size(280.dp)
            .graphicsLayer { rotationZ = rotationDegrees }
            .clip(CircleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (coverUrl != null) {
            val model = when {
                coverUrl.startsWith("/") || coverUrl.startsWith("file://") || coverUrl.startsWith("data:") -> {
                    ImageRequest.Builder(LocalContext.current)
                        .data(coverUrl)
                        .crossfade(true)
                        .build()
                }
                else -> {
                    val headers = if (webDavConfig != null && webDavConfig.username.isNotBlank() && webDavConfig.password.isNotBlank()) {
                        NetworkHeaders.Builder()
                            .set("Authorization", Credentials.basic(webDavConfig.username, webDavConfig.password))
                            .build()
                    } else {
                        NetworkHeaders.EMPTY
                    }
                    ImageRequest.Builder(LocalContext.current)
                        .data(coverUrl)
                        .httpHeaders(headers)
                        .crossfade(true)
                        .build()
                }
            }
            AsyncImage(
                model = model,
                contentDescription = "Album Cover",
                modifier = Modifier
                    .fillMaxSize(0.86f)
                    .clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = Color.White.copy(alpha = 0.7f)
            )
        }

        // 唱片中心孔
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background)
        )
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
