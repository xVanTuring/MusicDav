package com.spotify.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotify.music.data.MusicFile
import com.spotify.music.data.WebDavConfig
import com.spotify.music.webdav.WebDavClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicListScreen(
    webDavConfig: WebDavConfig,
    directoryPath: String? = null,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    currentPlayingIndex: Int,
    onSongSelected: (Int, MusicFile) -> Unit,
    onPlaylistLoaded: (List<MusicFile>) -> Unit = {},
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var musicFiles by remember { mutableStateOf<List<MusicFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val scope = rememberCoroutineScope()
    val webDavClient = remember { WebDavClient() }
    
    // 如果有 directoryPath，拼接完整路径；否则使用原始 URL
    val effectiveConfig = remember(webDavConfig, directoryPath) {
        if (directoryPath != null) {
            webDavConfig.copy(url = directoryPath)
        } else {
            webDavConfig
        }
    }
    
    fun loadMusicFiles() {
        isLoading = true
        errorMessage = null
        scope.launch {
            webDavClient.fetchMusicFiles(effectiveConfig)
                .onSuccess { files ->
                    musicFiles = files
                    isLoading = false
                }
                .onFailure { e ->
                    errorMessage = "Failed to load music: ${e.message}"
                    isLoading = false
                }
        }
    }
    
    LaunchedEffect(effectiveConfig) {
        loadMusicFiles()
    }
    
    LaunchedEffect(musicFiles) {
        if (musicFiles.isNotEmpty()) {
            onPlaylistLoaded(musicFiles)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music Library") },
                navigationIcon = if (showBack) {
                    {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                } else {
                    {}
                },
                actions = {
                    IconButton(onClick = { loadMusicFiles() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        bottomBar = bottomBar,
        modifier = modifier
    ) { paddingValues ->
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
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(musicFiles) { index, musicFile ->
                            MusicListItem(
                                musicFile = musicFile,
                                isPlaying = index == currentPlayingIndex,
                                onClick = { onSongSelected(index, musicFile) }
                            )
                        }
                    }
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
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = {
            Text(
                text = musicFile.name.substringBeforeLast('.'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = formatFileSize(musicFile.size),
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = if (isPlaying) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
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
