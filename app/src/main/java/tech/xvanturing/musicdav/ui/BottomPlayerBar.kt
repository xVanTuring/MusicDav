package tech.xvanturing.musicdav.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.crossfade
import okhttp3.Credentials
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.PlayMode
import tech.xvanturing.musicdav.data.PlaylistState
import tech.xvanturing.musicdav.ui.theme.MotionSpec

private val CoverSize = 48.dp

@Composable
fun BottomPlayerBar(
    playlistState: PlaylistState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayMode: () -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = playlistState.currentSong != null,
                    onClick = onOpenNowPlaying
                )
        ) {
            // Thin progress line in primary (gold)
            val rawProgress = if (playlistState.duration > 0) {
                (playlistState.currentPosition.toFloat() / playlistState.duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val animatedProgress by animateFloatAsState(
                targetValue = rawProgress,
                label = "progress"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary)
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
                    val coverUrl = playlistState.currentCoverUrl
                    if (coverUrl != null) {
                        val headers = if (coverUrl.startsWith("http")) {
                            val effectiveConfig = playlistState.effectiveWebDavConfig
                            if (effectiveConfig != null &&
                                effectiveConfig.username.isNotBlank() &&
                                effectiveConfig.password.isNotBlank()
                            ) {
                                NetworkHeaders.Builder()
                                    .set(
                                        "Authorization",
                                        Credentials.basic(effectiveConfig.username, effectiveConfig.password)
                                    )
                                    .build()
                            } else {
                                NetworkHeaders.EMPTY
                            }
                        } else {
                            NetworkHeaders.EMPTY
                        }

                        AsyncImage(
                            model = coil3.request.ImageRequest.Builder(LocalContext.current)
                                .data(coverUrl)
                                .httpHeaders(headers)
                                .crossfade(true)
                                .build(),
                            contentDescription = stringResource(R.string.player_cover_desc),
                            modifier = Modifier
                                .size(CoverSize)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Default placeholder - same size/shape as the real cover.
                        Box(
                            modifier = Modifier
                                .size(CoverSize)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Song info
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = playlistState.currentSong?.displayName ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(),
                            softWrap = false
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
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play mode toggle - kept subtle here.
                    IconButton(
                        onClick = onTogglePlayMode,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = when (playlistState.playMode) {
                                PlayMode.REPEAT_SINGLE -> Icons.Default.RepeatOne
                                PlayMode.REPEAT_ALL -> Icons.Default.Repeat
                                PlayMode.PLAY_ONCE -> Icons.Default.Repeat
                            },
                            contentDescription = when (playlistState.playMode) {
                                PlayMode.REPEAT_SINGLE -> stringResource(R.string.player_mode_repeat_single)
                                PlayMode.REPEAT_ALL -> stringResource(R.string.player_mode_repeat_all)
                                PlayMode.PLAY_ONCE -> stringResource(R.string.player_mode_play_once)
                            },
                            tint = when (playlistState.playMode) {
                                PlayMode.REPEAT_SINGLE, PlayMode.REPEAT_ALL -> MaterialTheme.colorScheme.primary
                                PlayMode.PLAY_ONCE -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onPrevious,
                        enabled = playlistState.hasPrevious,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.player_previous_desc),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Play/pause - circular tinted background, icon morphs via AnimatedContent.
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                            AnimatedContent(
                                targetState = playlistState.isPlaying,
                                transitionSpec = {
                                    (fadeIn(tween(MotionSpec.fast)) + scaleIn(
                                        initialScale = 0.7f,
                                        animationSpec = tween(MotionSpec.fast)
                                    )) togetherWith
                                        (fadeOut(tween(MotionSpec.fast)) + scaleOut(
                                            targetScale = 0.7f,
                                            animationSpec = tween(MotionSpec.fast)
                                        ))
                                },
                                label = "playPause"
                            ) { isPlaying ->
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) {
                                        stringResource(R.string.player_pause_desc)
                                    } else {
                                        stringResource(R.string.player_play_desc)
                                    },
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onNext,
                        enabled = playlistState.hasNext,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.player_next_desc),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
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
