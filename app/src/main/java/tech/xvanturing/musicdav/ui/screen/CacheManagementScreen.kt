package tech.xvanturing.musicdav.ui.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.xvanturing.musicdav.CacheTask
import tech.xvanturing.musicdav.CacheTaskStatus
import tech.xvanturing.musicdav.MusicCacheService
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.player.CacheMetadata
import tech.xvanturing.musicdav.player.MusicCache
import kotlinx.coroutines.launch

private const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagementScreen(
    modifier: Modifier = Modifier
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
            diff < 60 * 1000 -> "just now"
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} min ago"
            diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)} hours ago"
            diff < 7 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)} days ago"
            else -> "${diff / (7 * 24 * 60 * 60 * 1000)} weeks ago"
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
            TopAppBar(
                title = { Text("Cache Management") },
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
                            contentDescription = "Refresh"
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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
                        text = "Active Downloads",
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
                        text = "Cached Songs (${cachedSongs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Button(
                        onClick = { showClearConfirm = true },
                        enabled = cachedSongs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All")
                    }
                }
            }
            
            if (cachedSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No cached songs",
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
            title = { Text("Clear All Cache") },
            text = { Text("Are you sure you want to delete all cached songs? This cannot be undone.") },
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
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (songToDelete != null) {
        AlertDialog(
            onDismissRequest = { songToDelete = null },
            title = { Text("Delete Cached Song") },
            text = { Text("Are you sure you want to delete \"${extractSongName(songToDelete!!.url)}\" from cache?") },
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
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) {
                    Text("Cancel")
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Cache Usage",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "$songCount songs cached",
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
                text = "${formatBytes(freeSpace)} free space available",
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
    Card(
        modifier = Modifier.fillMaxWidth()
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
                Text(
                    text = formatBytes(task.musicFile.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            when (task.status) {
                CacheTaskStatus.DOWNLOADING -> {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                CacheTaskStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                CacheTaskStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Failed",
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
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
                        shape = RoundedCornerShape(8.dp)
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
                    style = MaterialTheme.typography.bodyLarge,
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
                        text = "Last accessed ${formatRelativeTime(metadata.lastAccessTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
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
