package com.spotify.music.ui.screen

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder

import com.spotify.music.data.Album
import com.spotify.music.data.getWebDavConfig
import okhttp3.Credentials
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumListScreen(
    albums: List<Album>,
    onSelect: (Album) -> Unit,
    onCreate: (Album, String?) -> Unit,
    onDelete: (Album) -> Unit,
    onAddButtonClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var selectedAlbumForDelete by remember { mutableStateOf<Album?>(null) }

    // 拦截返回键，如果在创建页面则返回列表页面
    BackHandler(enabled = onAddButtonClick == null && creating) {
        creating = false
    }

    if (onAddButtonClick == null && creating) {
        AlbumCreateForm(
            onCancel = { creating = false },
            onSave = { name, url, username, password, directoryUrl, coverImageUrl, serverConfigId ->
                val config = com.spotify.music.data.WebDavConfig(url = url, username = username, password = password)
                val album = Album(
                    name = name,
                    config = config,
                    directoryUrl = directoryUrl,
                    coverImageUrl = coverImageUrl,
                    serverConfigId = serverConfigId
                )
                onCreate(album, serverConfigId)
                creating = false
            }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Albums") },
                    actions = {
                        IconButton(onClick = {
                            onAddButtonClick?.invoke() ?: run { creating = true }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Album"
                            )
                        }
                    }
                )
            },
            modifier = modifier
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(albums) { album ->
                        AlbumGridItem(
                            album = album,
                            context = context,
                            onClick = { onSelect(album) },
                            onLongClick = { selectedAlbumForDelete = album }
                        )
                    }
                }


                selectedAlbumForDelete?.let { album ->
                    AlertDialog(
                        onDismissRequest = { selectedAlbumForDelete = null },
                        title = { Text("Delete Album") },
                        text = { Text("Are you sure you want to delete album \"${album.name}\"?") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    onDelete(album)
                                    selectedAlbumForDelete = null
                                }
                            ) {
                                Text("Delete")
                            }
                        },
                        dismissButton = {
                            Button(
                                onClick = { selectedAlbumForDelete = null }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGridItem(
    album: Album,
    context: android.content.Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // 专辑封面
        val webDavConfig = album.getWebDavConfig(context)
        val headers = NetworkHeaders.Builder()
            .set("Authorization", Credentials.basic(webDavConfig.username, webDavConfig.password))
            .build()

        // Default album background with Album icon
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (album.coverImageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(album.coverImageUrl)
                        .httpHeaders(headers)
                        .crossfade(true)
                        .listener(
                            onError = {_, throwable->
                                Log.e("IMAGE_LOAD", "Load error: $throwable")
                            }
                        )
                        .build(),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Default album icon background
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = "Default Album Cover",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 专辑名显示在封面左下角
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
                .fillMaxWidth()
        )
    }
}