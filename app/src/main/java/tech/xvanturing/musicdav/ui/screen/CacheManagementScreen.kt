package tech.xvanturing.musicdav.ui.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.xvanturing.musicdav.CacheTask
import tech.xvanturing.musicdav.CacheTaskStatus
import tech.xvanturing.musicdav.MusicCacheService
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.player.CacheMetadata
import tech.xvanturing.musicdav.player.MusicCache
import tech.xvanturing.musicdav.ui.components.AppTopBar
import kotlinx.coroutines.launch

private const val MAX_CACHE_BYTES = 20L * 1024 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagementScreen(
    modifier: Modifier = Modifier,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var cachedSongs by remember { mutableStateOf<List<CacheMetadata>>(emptyList()) }
    var activeTasks by remember { mutableStateOf<List<CacheTask>>(emptyList()) }
    var totalCacheSize by remember { mutableStateOf(0L) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var songToDelete by remember { mutableStateOf<CacheMetadata?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MusicCacheService.LocalBinder
                val musicCacheService = binder.getService()

                musicCacheService.addListener(object : MusicCacheService.CacheTaskListener {
                    override fun onTaskStarted(taskId: String, musicFile: MusicFile) {
                        activeTasks = musicCacheService.getActiveTasks()
                    }

                    override fun onTaskProgress(taskId: String, progress: Int) {
                        activeTasks = musicCacheService.getActiveTasks()
                    }

                    override fun onTaskCompleted(taskId: String, path: String?) {
                        activeTasks = musicCacheService.getActiveTasks()
                        coroutineScope.launch {
                            refreshCacheData(context, coroutineScope) { songs, size ->
                                cachedSongs = songs
                                totalCacheSize = size
                            }
                        }
                    }

                    override fun onTaskFailed(taskId: String, error: Throwable) {
                        activeTasks = musicCacheService.getActiveTasks()
                    }

                    override fun onAllTasksCompleted() {
                        activeTasks = musicCacheService.getActiveTasks()
                        coroutineScope.launch {
                            refreshCacheData(context, coroutineScope) { songs, size ->
                                cachedSongs = songs
                                totalCacheSize = size
                            }
                        }
                    }
                })

                activeTasks = musicCacheService.getActiveTasks()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                activeTasks = emptyList()
            }
        }
    }

    LaunchedEffect(refreshKey) {
        refreshCacheData(context, coroutineScope) { songs, size ->
            cachedSongs = songs
            totalCacheSize = size
        }
    }

    DisposableEffect(context) {
        val intent = Intent(context, MusicCacheService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(serviceConnection)
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60 * 1000 -> context.getString(R.string.cache_last_accessed_now)
            diff < 60 * 60 * 1000 -> context.getString(
                R.string.cache_last_accessed,
                context.getString(R.string.cache_unit_minutes, diff / (60 * 1000))
            )
            diff < 24 * 60 * 60 * 1000 -> context.getString(
                R.string.cache_last_accessed,
                context.getString(R.string.cache_unit_hours, diff / (60 * 60 * 1000))
            )
            diff < 7 * 24 * 60 * 60 * 1000 -> context.getString(
                R.string.cache_last_accessed,
                context.getString(R.string.cache_unit_days, diff / (24 * 60 * 60 * 1000))
            )
            else -> context.getString(
                R.string.cache_last_accessed,
                context.getString(R.string.cache_unit_weeks, diff / (7 * 24 * 60 * 60 * 1000))
            )
        }
    }

    fun extractSongName(url: String): String {
        val lastSlash = url.lastIndexOf('/')
        if (lastSlash != -1) {
            var name = url.substring(lastSlash + 1)
            val dotIndex = name.lastIndexOf('.')
            if (dotIndex != -1) {
                name = name.substring(0, dotIndex)
            }
            return name
        }
        return url
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.cache_title),
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            refreshCacheData(context, coroutineScope) { songs, size ->
                                cachedSongs = songs
                                totalCacheSize = size
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 16.dp + bottomInset
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CacheStatisticsCard(
                    totalCacheSize = totalCacheSize,
                    maxCacheSize = MAX_CACHE_BYTES,
                    songCount = cachedSongs.size,
                    formatBytes = ::formatBytes
                )
            }

            if (activeTasks.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.cache_active_downloads),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(activeTasks) { task ->
                    ActiveDownloadItem(
                        task = task,
                        formatBytes = ::formatBytes,
                        onCancel = {
                            MusicCacheService.cancelCaching(context, task.id)
                        }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.cache_cached_songs, cachedSongs.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        enabled = cachedSongs.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.cache_clear_all))
                    }
                }
            }

            if (cachedSongs.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.cache_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(cachedSongs) { metadata ->
                    CachedSongItem(
                        metadata = metadata,
                        formatBytes = ::formatBytes,
                        formatRelativeTime = ::formatRelativeTime,
                        extractSongName = ::extractSongName,
                        onDelete = { songToDelete = metadata }
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.cache_clear_all_title)) },
            text = { Text(stringResource(R.string.cache_clear_all_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            MusicCache.clearCache(context)
                            refreshCacheData(context, coroutineScope) { songs, size ->
                                cachedSongs = songs
                                totalCacheSize = size
                            }
                        }
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.cache_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (songToDelete != null) {
        AlertDialog(
            onDismissRequest = { songToDelete = null },
            title = { Text(stringResource(R.string.cache_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.cache_delete_message,
                        extractSongName(songToDelete!!.url)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            MusicCache.removeCachedSong(context, songToDelete!!.url)
                            refreshCacheData(context, coroutineScope) { songs, size ->
                                cachedSongs = songs
                                totalCacheSize = size
                            }
                        }
                        songToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun CacheStatisticsCard(
    totalCacheSize: Long,
    maxCacheSize: Long,
    songCount: Int,
    formatBytes: (Long) -> String
) {
    val usagePercent = ((totalCacheSize.toFloat() / maxCacheSize) * 100).coerceIn(0f, 100f)
    val freeSpace = maxCacheSize - totalCacheSize

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.cache_usage),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.cache_songs_cached, songCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                Text(
                    text = "${formatBytes(totalCacheSize)} / ${formatBytes(maxCacheSize)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            LinearProgressIndicator(
                progress = { usagePercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (usagePercent > 80f)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )

            Text(
                text = stringResource(R.string.cache_free_space, formatBytes(freeSpace)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun ActiveDownloadItem(
    task: CacheTask,
    formatBytes: (Long) -> String,
    onCancel: () -> Unit
) {
    val statusText = when (task.status) {
        CacheTaskStatus.DOWNLOADING -> stringResource(R.string.cache_downloading)
        CacheTaskStatus.COMPLETED -> stringResource(R.string.cache_completed_desc)
        CacheTaskStatus.FAILED -> stringResource(R.string.cache_failed_desc)
        else -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text = "${task.progress}%",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = task.musicFile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatBytes(task.musicFile.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (statusText != null) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            when (task.status) {
                CacheTaskStatus.DOWNLOADING -> {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.cache_cancel_desc),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                CacheTaskStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.cache_completed_desc),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                CacheTaskStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = stringResource(R.string.cache_failed_desc),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
fun CachedSongItem(
    metadata: CacheMetadata,
    formatBytes: (Long) -> String,
    formatRelativeTime: (Long) -> String,
    extractSongName: (String) -> String,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable(onClick = onDelete),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = extractSongName(metadata.url),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatBytes(metadata.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatRelativeTime(metadata.lastAccessTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cache_delete_desc),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private suspend fun refreshCacheData(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onResult: (List<CacheMetadata>, Long) -> Unit
) {
    coroutineScope.launch {
        val songs = MusicCache.getCachedSongs(context)
        val size = MusicCache.getCurrentCacheSize(context)
        onResult(songs, size)
    }
}
