package com.spotify.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import com.spotify.music.data.PlaylistState
import com.spotify.music.data.PlayMode
import com.spotify.music.data.WebDavConfig
import okhttp3.Credentials

@Composable
fun BottomPlayerBar(
    playlistState: PlaylistState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
//    if (playlistState.currentSong == null) return
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // Thin progress bar
            val rawProgress = if (playlistState.duration > 0) {
                (playlistState.currentPosition.toFloat() / playlistState.duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val animatedProgress by animateFloatAsState(
                targetValue = rawProgress,
                label = "progress"
            )
            
            // Custom progress bar with rounded corners
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(2.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Album cover and song info
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Album cover
                    if (playlistState.currentCoverUrl != null) {
                        val coverUrl = playlistState.currentCoverUrl!!

                        if (playlistState.currentWebDavConfig != null &&
                            playlistState.currentWebDavConfig.username.isNotBlank() &&
                            playlistState.currentWebDavConfig.password.isNotBlank() &&
                            coverUrl.startsWith("http")) {
                            // WebDAV URL with authentication
                            val headers = NetworkHeaders.Builder()
                                .set("Authorization", Credentials.basic(playlistState.currentWebDavConfig.username, playlistState.currentWebDavConfig.password))
                                .build()

                            AsyncImage(
                                model = coil3.request.ImageRequest.Builder(LocalContext.current)
                                    .data(coverUrl)
                                    .httpHeaders(headers)
                                    .build(),
                                contentDescription = "Album Cover",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            // Local file or no authentication needed
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = "Album Cover",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    } else {
                        // Default music note icon when no cover
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "Default Album",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Song info
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = playlistState.currentSong?.name?.substringBeforeLast('.') ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatTime(playlistState.currentPosition)} / ${formatTime(playlistState.duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 播放模式按钮
                    IconButton(
                        onClick = onTogglePlayMode,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = when (playlistState.playMode) {
                                PlayMode.REPEAT_SINGLE -> Icons.Default.RepeatOne
                                PlayMode.REPEAT_ALL -> Icons.Default.Repeat
                                PlayMode.PLAY_ONCE -> Icons.Default.Repeat
                            },
                            contentDescription = when (playlistState.playMode) {
                                PlayMode.REPEAT_SINGLE -> "单曲循环"
                                PlayMode.REPEAT_ALL -> "列表循环"
                                PlayMode.PLAY_ONCE -> "顺序播放"
                            },
                            tint = when (playlistState.playMode) {
                                PlayMode.REPEAT_SINGLE -> MaterialTheme.colorScheme.primary
                                PlayMode.REPEAT_ALL -> MaterialTheme.colorScheme.primary
                                PlayMode.PLAY_ONCE -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = onPrevious,
                        enabled = playlistState.hasPrevious
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = onPlayPause) {
                        Icon(
                            imageVector = if (playlistState.isPlaying)
                                Icons.Default.Pause
                            else
                                Icons.Default.PlayArrow,
                            contentDescription = if (playlistState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    IconButton(
                        onClick = onNext,
                        enabled = playlistState.hasNext
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
