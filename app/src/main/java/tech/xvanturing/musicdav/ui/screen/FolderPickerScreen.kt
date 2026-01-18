package tech.xvanturing.musicdav.ui.screen

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.webdav.WebDavClient
import kotlinx.coroutines.launch
import java.net.URL

enum class FilePickerMode {
    DIRECTORY_ONLY,    // 只能选择目录
    FILE_ONLY,         // 只能选择文件
    FILE_AND_DIRECTORY // 两者都可以选择
}

data class FilePickerConfig(
    val title: String,
    val subtitle: String? = null,
    val mode: FilePickerMode = FilePickerMode.DIRECTORY_ONLY,
    val allowedFileExtensions: Set<String> = emptySet(), // 文件扩展名过滤，如 setOf("jpg", "png", "webp")
    val showFileIcons: Boolean = true,
    val showClearSelectionButton: Boolean = false,
    val showFilesInDirectoryMode: Boolean = true // 在目录模式下是否显示文件（只读）
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    webDavConfig: WebDavConfig,
    initialPath: String,
    config: FilePickerConfig,
    onConfirm: (selectedPath: String?) -> Unit,
    onCancel: () -> Unit,
    initiallySelectedPath: String? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val webDavClient = remember { WebDavClient() }

    var directories by remember { mutableStateOf<List<com.thegrizzlylabs.sardineandroid.DavResource>>(emptyList()) }
    var allResources by remember { mutableStateOf<List<com.thegrizzlylabs.sardineandroid.DavResource>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var currentBrowsingPath by remember { mutableStateOf<String?>(null) }
    var pathHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPath by remember { mutableStateOf<String?>(initiallySelectedPath) }

    LaunchedEffect(initialPath) {
        isLoading = true
        val normalizedInitialPath = normalizePath(webDavConfig, initialPath)
        currentBrowsingPath = normalizedInitialPath
        pathHistory = generatePathHistory(webDavConfig, normalizedInitialPath)

        coroutineScope.launch {
            webDavClient.listAllResources(webDavConfig, normalizedInitialPath)
                .onSuccess { (dirs, allRes) ->
                    directories = dirs
                    allResources = allRes
                }
                .onFailure { e ->
                    Log.e("FolderPickerScreen", "Failed to load directories: ${e.message}")
                }
            isLoading = false
        }
    }

    fun handleGoUp() {
        val parentPath = calculateParentPath(webDavConfig, currentBrowsingPath ?: return)
        if (parentPath != null) {
            currentBrowsingPath = parentPath
            pathHistory = generatePathHistory(webDavConfig, parentPath)

            isLoading = true
            coroutineScope.launch {
                webDavClient.listAllResources(webDavConfig, parentPath)
                    .onSuccess { (dirs, allRes) ->
                        directories = dirs
                        allResources = allRes
                    }
                    .onFailure { e ->
                        Log.e("FolderPickerScreen", "Failed to load parent directory: ${e.message}")
                    }
                isLoading = false
            }
        }
    }

    fun handleEnterDirectory(dir: com.thegrizzlylabs.sardineandroid.DavResource) {
        val currentPath = currentBrowsingPath ?: return
        val newPath = if (dir.path.startsWith("http")) {
            dir.path.trimEnd('/')
        } else {
            val baseUrl = parseWebDavBaseUrl(webDavConfig)
            val currentPathOnly = if (currentPath.startsWith("http")) {
                try { URL(currentPath).path.trimEnd('/') } catch (e: Exception) { currentPath }
            } else {
                currentPath.trimEnd('/')
            }
            "$baseUrl${currentPathOnly}/${dir.name.trim('/')}".trimEnd('/')
        }
        currentBrowsingPath = newPath
        pathHistory = generatePathHistory(webDavConfig, newPath)

        isLoading = true
        coroutineScope.launch {
            webDavClient.listAllResources(webDavConfig, newPath)
                .onSuccess { (dirs, allRes) ->
                    directories = dirs
                    allResources = allRes
                }
                .onFailure { e ->
                    Log.e("FolderPickerScreen", "Failed to load directories: ${e.message}")
                    val parentPath = calculateParentPath(webDavConfig, newPath)
                    if (parentPath != null) {
                        currentBrowsingPath = parentPath
                        pathHistory = generatePathHistory(webDavConfig, parentPath)
                    }
                }
            isLoading = false
        }
    }

    fun handleConfirm() {
        val resultPath = when (config.mode) {
            FilePickerMode.DIRECTORY_ONLY -> currentBrowsingPath
            FilePickerMode.FILE_ONLY -> selectedPath
            FilePickerMode.FILE_AND_DIRECTORY -> selectedPath ?: currentBrowsingPath
        }
        onConfirm(resultPath)
    }

    val canGoUp = calculateParentPath(webDavConfig, currentBrowsingPath ?: "") != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(config.title)
                        config.subtitle?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        currentBrowsingPath?.let { path ->
                            Text(
                                text = path.trimEnd('/').substringAfterLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { handleConfirm() },
                        enabled = when (config.mode) {
                            FilePickerMode.DIRECTORY_ONLY -> currentBrowsingPath != null
                            FilePickerMode.FILE_ONLY -> selectedPath != null
                            FilePickerMode.FILE_AND_DIRECTORY -> currentBrowsingPath != null || selectedPath != null
                        }
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "确认"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        if (isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(
                    text = "加载中...",
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (canGoUp) {
                    item {
                        ListItem(
                            headlineContent = { Text("返回上级") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = "返回上级目录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { handleGoUp() }
                        )
                    }
                }

                val filteredDirectories = directories.filter { dir ->
                    val dirPath = dir.path.trimEnd('/')
                    val currentPath = currentBrowsingPath?.trimEnd('/') ?: ""
                    val currentPathOnly = if (currentPath.startsWith("http")) {
                        try {
                            val url = java.net.URL(currentPath)
                            url.path.trimEnd('/')
                        } catch (e: Exception) {
                            currentPath.trimEnd('/')
                        }
                    } else {
                        currentPath.trimEnd('/')
                    }

                    val isCurrentDirectory = dirPath == currentPathOnly
                    !isCurrentDirectory && dir.name != "."
                }

                items(filteredDirectories) { dir ->
                    val displayName = dir.name.ifBlank { dir.path }
                    ListItem(
                        headlineContent = { Text(displayName) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = "子文件夹",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "进入文件夹",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { handleEnterDirectory(dir) }
                    )
                }

                val shouldShowFiles = when (config.mode) {
                    FilePickerMode.FILE_ONLY, FilePickerMode.FILE_AND_DIRECTORY -> true
                    FilePickerMode.DIRECTORY_ONLY -> config.showFilesInDirectoryMode
                }

                if (shouldShowFiles) {
                    val files = allResources.filter { !it.isDirectory }
                    val filteredFiles = if (config.allowedFileExtensions.isNotEmpty()) {
                        files.filter { file ->
                            val displayName = file.name.ifBlank { file.path }
                            val fileExtension = displayName.substringAfterLast('.', "").lowercase()
                            fileExtension in config.allowedFileExtensions
                        }
                    } else {
                        files
                    }

                    items(filteredFiles) { file ->
                        val displayName = file.name.ifBlank { file.path }
                        val fileExtension = displayName.substringAfterLast('.', "").lowercase()
                        val isSelectable = when (config.mode) {
                            FilePickerMode.FILE_ONLY, FilePickerMode.FILE_AND_DIRECTORY -> {
                                config.allowedFileExtensions.isEmpty() || fileExtension in config.allowedFileExtensions
                            }
                            FilePickerMode.DIRECTORY_ONLY -> false
                        }
                        val isSelected = selectedPath == file.path

                        ListItem(
                            headlineContent = {
                                Text(
                                    text = displayName,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                if (config.showFileIcons) {
                                    Icon(
                                        imageVector = getFileIcon(fileExtension),
                                        contentDescription = "文件",
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (isSelectable) Modifier.clickable {
                                    selectedPath = if (isSelected) null else file.path
                                } else Modifier)
                        )
                    }
                }
            }
        }
    }
}


