package tech.xvanturing.musicdav.ui.screen

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import okhttp3.Credentials
import tech.xvanturing.musicdav.data.Album
import tech.xvanturing.musicdav.data.AlbumsRepository
import tech.xvanturing.musicdav.data.ConfigExportManager
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.ImportResult
import tech.xvanturing.musicdav.data.ImportStrategy
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.favoritesCoverPath
import tech.xvanturing.musicdav.data.getWebDavConfig
import tech.xvanturing.musicdav.data.resolveAlbumUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumListScreen(
    albums: List<Album>,
    onSelect: (Album) -> Unit,
    onCreate: (Album, String?) -> Unit,
    onDelete: (Album) -> Unit,
    modifier: Modifier = Modifier,
    onAddButtonClick: (() -> Unit)? = null,
    onImportSuccess: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSearch: () -> Unit = {},

) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val favorites = remember { FavoritesRepository.load(context) }
    val favoritesCover = remember(favorites) { favoritesCoverPath(context, favorites) }
    var creating by remember { mutableStateOf(false) }
    var selectedAlbumForDelete by remember { mutableStateOf<Album?>(null) }
    var showImportStrategyDialog by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showMenuDialog by remember { mutableStateOf(false) }

    // 文件选择器launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            showImportStrategyDialog = true
        }
    }

    // 文件保存launcher
    val fileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                isExporting = true
                val result = ConfigExportManager.exportConfigs(context, it)
                isExporting = false
                result.onSuccess { message ->
                    val configs = ServerConfigRepository.load(context)
                    val albums = AlbumsRepository.load(context)
                    exportMessage =
                        "导出成功！\n服务器配置: ${configs.size} 个\n专辑: ${albums.size} 个"
                    showExportDialog = true
                }.onFailure { e ->
                    exportMessage = "导出失败: ${e.message}"
                    showExportDialog = true
                }
            }
        }
    }

    // 拦截返回键，如果在创建页面则返回列表页面
    BackHandler(enabled = onAddButtonClick == null && creating) {
        creating = false
    }

    if (onAddButtonClick == null && creating) {
        AlbumCreateForm(
            onCancel = { creating = false },
            onSave = { name, url, username, password, directoryUrl, coverImageUrl, serverConfigId ->
                val config = tech.xvanturing.musicdav.data.WebDavConfig(
                    url = url,
                    username = username,
                    password = password
                )
                val album = Album(
                    id = java.util.UUID.randomUUID().toString(),
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
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        }
                        IconButton(onClick = {
                            onAddButtonClick?.invoke() ?: run { creating = true }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Album"
                            )
                        }
                        IconButton(
                            onClick = { showMenuDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu"
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
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        FavoritesGridItem(
                            coverPath = favoritesCover,
                            songCount = favorites.size,
                            onClick = onOpenFavorites
                        )
                    }
                    items(albums) { album ->
                        AlbumGridItem(
                            album = album,
                            context = context,
                            onClick = { onSelect(album) },
                            onLongClick = { selectedAlbumForDelete = album }
                        )
                    }
                }


                if (showMenuDialog) {
                    AlertDialog(
                        onDismissRequest = { showMenuDialog = false },
                        title = { Text("导入/导出") },
                        text = {
                            Column {
                                Button(
                                    onClick = {
                                        showMenuDialog = false
                                        val timestamp = SimpleDateFormat(
                                            "yyyyMMdd_HHmmss",
                                            Locale.getDefault()
                                        ).format(Date())
                                        val fileName = "musicdav_config_$timestamp.json"
                                        fileSaveLauncher.launch(fileName)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("导出配置")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        showMenuDialog = false
                                        filePickerLauncher.launch("application/json")
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("导入配置")
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showMenuDialog = false }) {
                                Text("取消")
                            }
                        }
                    )
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

                if (showImportStrategyDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showImportStrategyDialog = false
                            selectedFileUri = null
                        },
                        title = { Text("导入策略") },
                        text = {
                            Column {
                                Text("请选择导入方式：")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "• 覆盖：删除现有配置，导入新配置",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "• 合并：保留现有配置，添加新配置",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "• 更新：同名配置更新，其他保留",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        selectedFileUri?.let { uri ->
                                            coroutineScope.launch {
                                                isImporting = true
                                                val result = ConfigExportManager.importConfigs(
                                                    context,
                                                    uri,
                                                    ImportStrategy.OVERWRITE
                                                )
                                                isImporting = false
                                                result.onSuccess {
                                                    importResult = it
                                                    showImportStrategyDialog = false
                                                    onImportSuccess()
                                                    showResultDialog = true
                                                }.onFailure { e ->
                                                    exportMessage = "导入失败: ${e.message}"
                                                    showImportStrategyDialog = false
                                                    showExportDialog = true
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("覆盖")
                                }
                                Button(
                                    onClick = {
                                        selectedFileUri?.let { uri ->
                                            coroutineScope.launch {
                                                isImporting = true
                                                val result = ConfigExportManager.importConfigs(
                                                    context,
                                                    uri,
                                                    ImportStrategy.MERGE
                                                )
                                                isImporting = false
                                                result.onSuccess {
                                                    importResult = it
                                                    showImportStrategyDialog = false
                                                    onImportSuccess()
                                                    showResultDialog = true
                                                }.onFailure { e ->
                                                    exportMessage = "导入失败: ${e.message}"
                                                    showImportStrategyDialog = false
                                                    showExportDialog = true
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("合并")
                                }
                                Button(
                                    onClick = {
                                        selectedFileUri?.let { uri ->
                                            coroutineScope.launch {
                                                isImporting = true
                                                val result = ConfigExportManager.importConfigs(
                                                    context,
                                                    uri,
                                                    ImportStrategy.UPDATE
                                                )
                                                isImporting = false
                                                result.onSuccess {
                                                    importResult = it
                                                    showImportStrategyDialog = false
                                                    onImportSuccess()
                                                    showResultDialog = true
                                                }.onFailure { e ->
                                                    exportMessage = "导入失败: ${e.message}"
                                                    showImportStrategyDialog = false
                                                    showExportDialog = true
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("更新")
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showImportStrategyDialog = false
                                selectedFileUri = null
                            }) {
                                Text("取消")
                            }
                        }
                    )
                }

                if (showExportDialog) {
                    AlertDialog(
                        onDismissRequest = { showExportDialog = false },
                        title = { Text("提示") },
                        text = {
                            Column {
                                if (isExporting || isImporting) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Text(exportMessage ?: "")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showExportDialog = false }) {
                                Text("确定")
                            }
                        }
                    )
                }

                if (showResultDialog) {
                    importResult?.let { result ->
                        AlertDialog(
                            onDismissRequest = {
                                showResultDialog = false
                                importResult = null
                            },
                            title = { Text("导入结果") },
                            text = {
                                Column {
                                    Text("导入完成！", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (result.importedServerConfigs > 0) {
                                        Text("导入服务器配置: ${result.importedServerConfigs}")
                                    }
                                    if (result.updatedServerConfigs > 0) {
                                        Text("更新服务器配置: ${result.updatedServerConfigs}")
                                    }
                                    if (result.importedAlbums > 0) {
                                        Text("导入专辑: ${result.importedAlbums}")
                                    }
                                    if (result.skippedServerConfigs > 0) {
                                        Text("跳过服务器配置: ${result.skippedServerConfigs}")
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showResultDialog = false
                                    importResult = null
                                }) {
                                    Text("确定")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesGridItem(
    coverPath: String?,
    songCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            if (coverPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverPath)
                        .crossfade(true)
                        .build(),
                    contentDescription = "收藏夹",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "收藏夹",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        Text(
            text = "收藏夹",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp, end = 4.dp)
        )
        Text(
            text = "$songCount 首歌曲",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
        )
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        horizontalAlignment = Alignment.Start
    ) {
        // 专辑封面（关联了服务器配置时 coverImageUrl 存的是相对路径，需要用当前服务器地址拼出完整URL）
        val webDavConfig = album.getWebDavConfig(context)
        val effectiveCoverImageUrl = resolveAlbumUrl(album.coverImageUrl, webDavConfig.url)
        val headers = NetworkHeaders.Builder()
            .set("Authorization", Credentials.basic(webDavConfig.username, webDavConfig.password))
            .build()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            if (effectiveCoverImageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(effectiveCoverImageUrl)
                        .httpHeaders(headers)
                        .crossfade(true)
                        .listener(
                            onError = { _, throwable ->
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

        // 专辑名显示在封面下面，靠左对齐
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)
        )
    }
}