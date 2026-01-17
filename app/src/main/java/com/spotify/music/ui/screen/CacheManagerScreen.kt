package com.spotify.music.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotify.music.player.CacheManager
import com.spotify.music.player.CacheMetadata
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagerScreen(
    cacheManager: CacheManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            cacheManager.refreshCacheState(context)
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Cache") },
            text = { Text("Are you sure you want to clear all cached music?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            cacheManager.clearCache(context)
                            isLoading = false
                            showClearConfirm = false
                        }
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteConfirm && itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Cached Song") },
            text = { Text("Are you sure you want to delete this cached song?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            cacheManager.removeCachedSong(context, itemToDelete!!)
                            isLoading = false
                            showDeleteConfirm = false
                            itemToDelete = null
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cache Manager") },
                navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            if (isLoading) {
                                Box(
                                    modifier = Modifier.padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            isLoading = true
                                            cacheManager.refreshCacheState(context)
                                            isLoading = false
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            }
                        }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Content(
            paddingValues = paddingValues,
            cacheManager = cacheManager,
            isLoading = isLoading,
            onClearCache = { showClearConfirm = true },
            onDeleteItem = { url ->
                itemToDelete = url
                showDeleteConfirm = true
            }
        )
    }
}

@Composable
private fun Content(
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    cacheManager: CacheManager,
    isLoading: Boolean,
    onClearCache: () -> Unit,
    onDeleteItem: (String) -> Unit
) {
    val state = cacheManager.state

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        CacheSummaryCard(
            totalSize = state.totalSize,
            cachedCount = state.cachedSongs.size,
            formatBytes = { cacheManager.formatBytes(it) },
            onClearCache = onClearCache
        )

        if (state.isCaching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = state.cachingStatus ?: "Caching...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(state.cachedSongs, key = { it.url }) { metadata ->
                CachedSongItem(
                    metadata = metadata,
                    formatBytes = { cacheManager.formatBytes(it) },
                    onDelete = { onDeleteItem(metadata.url) }
                )
            }
        }
    }
}

@Composable
private fun CacheSummaryCard(
    totalSize: Long,
    cachedCount: Int,
    formatBytes: (Long) -> String,
    onClearCache: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text("Cache Summary")
        },
        supportingContent = {
            Text("${formatBytes(totalSize)} used • $cachedCount songs cached")
        },
        trailingContent = {
            Button(onClick = onClearCache) {
                Text("Clear All")
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@Composable
private fun CachedSongItem(
    metadata: CacheMetadata,
    formatBytes: (Long) -> String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fileName = metadata.fileName.takeLastWhile { it != '.' }
    
    ListItem(
        headlineContent = {
            Text(
                text = fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = formatBytes(metadata.fileSize),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Cached: ${formatDate(metadata.cacheTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        },
        modifier = modifier
    )
}

private fun formatDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        diff < 604800000 -> "${diff / 86400000}d ago"
        else -> java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}
