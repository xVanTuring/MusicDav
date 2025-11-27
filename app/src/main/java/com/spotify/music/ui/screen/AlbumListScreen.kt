package com.spotify.music.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spotify.music.data.Album

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    albums: List<Album>,
    onSelect: (Album) -> Unit,
    onCreate: (Album, String?) -> Unit,
    onDelete: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    
    // 拦截返回键，如果在创建页面则返回列表页面
    BackHandler(enabled = creating) {
        creating = false
    }
    
    if (creating) {
        AlbumCreateForm(
            onCancel = { creating = false },
            onSave = { name, url, username, password, directoryUrl, coverImageBase64, serverConfigId ->
                val config = com.spotify.music.data.WebDavConfig(url = url, username = username, password = password)
                val album = Album(
                    name = name,
                    config = config,
                    directoryUrl = directoryUrl,
                    coverImageBase64 = coverImageBase64,
                    serverConfigId = serverConfigId
                )
                onCreate(album, serverConfigId)
                creating = false
            }
        )
    } else {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Albums") }) },
            modifier = modifier
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    albums.forEach { album ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable { onSelect(album) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = album.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = album.directoryUrl ?: run {
                                            if (album.serverConfigId != null) {
                                                com.spotify.music.data.ServerConfigRepository.load(context)
                                                    .find { it.id == album.serverConfigId }
                                                    ?.toWebDavConfig()?.url ?: album.config.url
                                            } else {
                                                album.config.url
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onDelete(album) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Traffic,
                                        contentDescription = "Delete Album"
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { creating = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text("Add Album")
                    }
                }
            }
        }
    }
}
