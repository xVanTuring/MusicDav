package tech.xvanturing.musicdav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.player.MusicCache
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
                    CircularProgressIndicator()
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
                        Text(
                            text = errorMessage ?: "",
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
                    Text(
                        text = "No music files found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MusicListItem(
    musicFile: MusicFile,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enableCache: Boolean = false,
    onCacheRequest: (MusicFile) -> Unit = {},
    context: android.content.Context? = null,
    cacheManager: tech.xvanturing.musicdav.player.CacheManager? = null,
    playlistController: tech.xvanturing.musicdav.player.PlaylistStateController? = null
) {
    val isCached = cacheManager?.state?.cachedSongs?.any { it.url == musicFile.url } ?: false
    val isCaching = cacheManager?.state?.cachingProgress?.containsKey(musicFile.url) ?: false

    val cachedCoverUrl = playlistController?.state?.cachedCoverMap?.get(musicFile.url)
    val albumCoverUrl = playlistController?.state?.songToAlbumCoverMap?.get(musicFile.url)
    val currentWebDavConfig = playlistController?.state?.currentWebDavConfig

    val coverUrl = cachedCoverUrl ?: albumCoverUrl

    ListItem(
        headlineContent = {
            Column {
                Text(
                    text = musicFile.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                musicFile.artist?.let { artist ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = formatFileSize(musicFile.size),
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            if (coverUrl != null && context != null) {
                if (cachedCoverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = musicFile.displayName,
                        modifier = Modifier.size(48.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
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
                            .build(),
                        contentDescription = musicFile.displayName,
                        modifier = Modifier.size(48.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (isPlaying)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = if (enableCache && context != null) {
            {
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
                            contentDescription = "Cached",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Cache"
                        )
                    }
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isPlaying)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.clickable(onClick = onClick)
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
