package tech.xvanturing.musicdav.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.cacheKey
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import okhttp3.Credentials
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.crossfade

@Composable
fun MusicListScreen(
    musicFiles: List<MusicFile>,
    isLoading: Boolean,
    errorMessage: String?,
    currentPlayingSong: MusicFile? = null,
    onSongSelected: (Int, MusicFile) -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enableCache: Boolean = false,
    onCacheRequest: (MusicFile) -> Unit = {},
    enableFavorite: Boolean = false,
    isFavorite: (MusicFile) -> Boolean = { false },
    onToggleFavorite: (MusicFile) -> Unit = {},
    cacheManager: tech.xvanturing.musicdav.player.CacheManager? = null,
    playlistController: tech.xvanturing.musicdav.player.PlaylistStateController? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        bottomBar = bottomBar,
        modifier = modifier
    ) { paddingValues ->
        Content(
            paddingValues = paddingValues,
            isLoading = isLoading,
            errorMessage = errorMessage,
            musicFiles = musicFiles,
            currentPlayingSong = currentPlayingSong,
            onSongSelected = onSongSelected,
            enableCache = enableCache,
            onCacheRequest = onCacheRequest,
            enableFavorite = enableFavorite,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            context = context,
            cacheManager = cacheManager,
            playlistController = playlistController
        )
    }
}

@Composable
private fun Content(
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    isLoading: Boolean,
    errorMessage: String?,
    musicFiles: List<MusicFile>,
    currentPlayingSong: MusicFile?,
    onSongSelected: (Int, MusicFile) -> Unit,
    enableCache: Boolean,
    onCacheRequest: (MusicFile) -> Unit,
    enableFavorite: Boolean = false,
    isFavorite: (MusicFile) -> Boolean = { false },
    onToggleFavorite: (MusicFile) -> Unit = {},
    context: android.content.Context,
    cacheManager: tech.xvanturing.musicdav.player.CacheManager? = null,
    playlistController: tech.xvanturing.musicdav.player.PlaylistStateController? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            !musicFiles.isEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(musicFiles) { index, musicFile ->
                        MusicListItem(
                            musicFile = musicFile,
                            isPlaying = currentPlayingSong != null && musicFile.url == currentPlayingSong.url,
                            onClick = { onSongSelected(index, musicFile) },
                            enableCache = enableCache,
                            onCacheRequest = onCacheRequest,
                            enableFavorite = enableFavorite,
                            isFavorite = isFavorite(musicFile),
                            onToggleFavorite = { onToggleFavorite(musicFile) },
                            context = context,
                            cacheManager = cacheManager,
                            playlistController = playlistController
                        )
                    }
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            musicFiles.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = stringResource(R.string.common_no_music),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private val ItemCoverSize = 52.dp

@Composable
fun MusicListItem(
    musicFile: MusicFile,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enableCache: Boolean = false,
    onCacheRequest: (MusicFile) -> Unit = {},
    enableFavorite: Boolean = false,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    context: android.content.Context? = null,
    cacheManager: tech.xvanturing.musicdav.player.CacheManager? = null,
    playlistController: tech.xvanturing.musicdav.player.PlaylistStateController? = null
) {
    val isCached = cacheManager?.state?.cachedSongs?.any { it.url == musicFile.cacheKey } ?: false
    val isCaching = cacheManager?.state?.cachingProgress?.containsKey(musicFile.url) ?: false

    val cachedCoverUrl = playlistController?.state?.cachedCoverMap?.get(musicFile.url)
    val albumCoverUrl = playlistController?.state?.songToAlbumCoverMap?.get(musicFile.url)
    // 该曲目所属服务器的配置优先（跨服务器列表如收藏夹/搜索结果），否则回退播放列表统一配置
    val currentWebDavConfig = playlistController?.state?.songToConfigMap?.get(musicFile.url)
        ?: playlistController?.state?.currentWebDavConfig

    val coverUrl = cachedCoverUrl ?: albumCoverUrl

    // Memoize the remote-cover Coil request so the Basic-auth header block and
    // ImageRequest allocation aren't rebuilt on every recomposition (e.g. on
    // playback-state changes) — only when the cover URL or WebDAV config change.
    val remoteCoverRequest = if (coverUrl != null && context != null && cachedCoverUrl == null) {
        remember(coverUrl, currentWebDavConfig) {
            ImageRequest.Builder(context)
                .data(coverUrl)
                .httpHeaders(
                    if (currentWebDavConfig != null) {
                        NetworkHeaders.Builder()
                            .set(
                                "Authorization",
                                Credentials.basic(
                                    currentWebDavConfig.username,
                                    currentWebDavConfig.password
                                )
                            )
                            .build()
                    } else {
                        NetworkHeaders.EMPTY
                    }
                )
                .crossfade(true)
                .build()
        }
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (isPlaying) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(ItemCoverSize)) {
                if (coverUrl != null && context != null) {
                    if (cachedCoverUrl != null) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = musicFile.displayName,
                            modifier = Modifier
                                .size(ItemCoverSize)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        AsyncImage(
                            model = remoteCoverRequest,
                            contentDescription = musicFile.displayName,
                            modifier = Modifier
                                .size(ItemCoverSize)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(ItemCoverSize)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (isPlaying) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // "Now playing" equalizer indicator, overlaid on the leading edge of the cover.
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayingEqualizer()
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = musicFile.displayName,
                    maxLines = 1,
                    overflow = if (isPlaying) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (isPlaying) Modifier.basicMarquee() else Modifier,
                    softWrap = false,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isPlaying) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                musicFile.artist?.let { artist ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = artist,
                        maxLines = 1,
                        overflow = if (isPlaying) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (isPlaying) Modifier.basicMarquee() else Modifier,
                        softWrap = false,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                val durationText = if (musicFile.durationMs > 0) formatDuration(musicFile.durationMs) else null
                val sizeText = formatFileSize(musicFile.size)
                val supportingText = if (durationText != null) "$durationText • $sizeText" else sizeText
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (enableCache || enableFavorite) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (enableFavorite) {
                        val favoriteScale by animateFloatAsState(
                            targetValue = if (isFavorite) 1.15f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "favoriteScale"
                        )
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (isFavorite) {
                                    stringResource(R.string.common_unfavorite_desc)
                                } else {
                                    stringResource(R.string.common_favorite_desc)
                                },
                                tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.scale(favoriteScale)
                            )
                        }
                    }
                    if (enableCache && context != null) {
                        IconButton(
                            onClick = {
                                if (!isCaching) {
                                    onCacheRequest(musicFile)
                                }
                            },
                            enabled = !isCaching
                        ) {
                            if (isCaching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else if (isCached) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.common_cached_desc),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = stringResource(R.string.common_cache_desc)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Small animated "now playing" equalizer: 3 gold bars bouncing at slightly
 * different phases/durations so it reads as organic rather than mechanical.
 */
@Composable
private fun PlayingEqualizer(
    modifier: Modifier = Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(560, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(340, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )
    Row(
        modifier = modifier.height(16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf(bar1, bar2, bar3).forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction)
                    .background(barColor, RoundedCornerShape(1.dp))
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
}
