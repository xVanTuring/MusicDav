package com.spotify.music.ui.screen

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.spotify.music.data.WebDavConfig
import com.spotify.music.webdav.WebDavClient
import kotlinx.coroutines.launch
import java.net.URL

/**
 * 解析WebDAV配置，获取基础URL（不含路径部分）
 */
private fun parseWebDavBaseUrl(webDavConfig: WebDavConfig): String {
    val url = webDavConfig.url.trimEnd('/')
    return try {
        val urlObj = URL(url)
        val baseUrl = "${urlObj.protocol}://${urlObj.host}"
        if (urlObj.port != -1 && urlObj.port != urlObj.defaultPort) {
            "$baseUrl:${urlObj.port}"
        } else {
            baseUrl
        }
    } catch (e: Exception) {
        // 如果解析失败，返回原URL
        url
    }
}

/**
 * 解析WebDAV配置，获取服务器根路径
 */
private fun parseWebDavRootPath(webDavConfig: WebDavConfig): String {
    val url = webDavConfig.url.trimEnd('/')
    return try {
        val urlObj = URL(url)
        urlObj.path.trimEnd('/')
    } catch (e: Exception) {
        // 如果解析失败，假设根路径为空
        ""
    }
}

/**
 * 根据WebDAV配置和当前路径，生成完整的历史路径栈
 */
private fun generatePathHistory(webDavConfig: WebDavConfig, currentPath: String): List<String> {
    val baseUrl = parseWebDavBaseUrl(webDavConfig)
    val rootPath = parseWebDavRootPath(webDavConfig)
    val normalizedCurrentPath = currentPath.trimEnd('/')

    // 如果当前路径是完整的URL，提取路径部分
    val currentPathOnly = if (normalizedCurrentPath.startsWith("http")) {
        try {
            URL(normalizedCurrentPath).path.trimEnd('/')
        } catch (e: Exception) {
            normalizedCurrentPath
        }
    } else {
        normalizedCurrentPath
    }

    // 构建完整路径历史
    val pathHistory = mutableListOf<String>()

    // 首先添加WebDAV服务器根URL
    if (rootPath.isNotEmpty()) {
        pathHistory.add("$baseUrl$rootPath")
    } else {
        pathHistory.add(baseUrl)
    }

    // 如果当前路径和根路径不同，添加中间路径
    if (currentPathOnly != rootPath) {
        // 计算相对路径
        val relativePath = if (rootPath.isNotEmpty()) {
            currentPathOnly.removePrefix(rootPath).trimStart('/')
        } else {
            currentPathOnly.trimStart('/')
        }

        // 逐级添加路径
        if (relativePath.isNotEmpty()) {
            val pathSegments = relativePath.split("/").filter { it.isNotEmpty() }
            var accumulatedPath = if (rootPath.isNotEmpty()) rootPath else ""

            for (segment in pathSegments) {
                accumulatedPath = "$accumulatedPath/$segment"
                pathHistory.add("$baseUrl$accumulatedPath")
            }
        }
    }

    return pathHistory.distinct()
}

/**
 * 计算上级目录路径
 */
private fun calculateParentPath(webDavConfig: WebDavConfig, currentPath: String): String? {
    val pathHistory = generatePathHistory(webDavConfig, currentPath)
    return if (pathHistory.size > 1) {
        pathHistory[pathHistory.size - 2]
    } else {
        null // 已经是根目录
    }
}

/**
 * 标准化路径，确保使用完整URL格式
 */
private fun normalizePath(webDavConfig: WebDavConfig, path: String): String {
    val normalizedPath = path.trimEnd('/')
    return if (normalizedPath.startsWith("http")) {
        normalizedPath
    } else {
        // 相对路径，需要补充为完整URL
        val baseUrl = parseWebDavBaseUrl(webDavConfig)
        val rootPath = parseWebDavRootPath(webDavConfig)
        if (rootPath.isNotEmpty()) {
            "$baseUrl$rootPath/$normalizedPath".trimEnd('/')
        } else {
            "$baseUrl/$normalizedPath".trimEnd('/')
        }
    }
}

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

/**
 * 根据文件扩展名获取对应的 Material Design 图标
 */
@Composable
private fun getFileIcon(fileExtension: String): ImageVector {
    return when (fileExtension.lowercase()) {
        // 音频文件
        "mp3", "m4a", "flac", "wav", "ogg", "aac", "wma" -> Icons.Default.AudioFile
        // 图片文件
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg" -> Icons.Default.Image
        // 文档文件
        "txt", "md", "pdf", "doc", "docx", "rtf" -> Icons.Default.Description
        // 其他文件
        else -> Icons.Default.Description
    }
}

@Composable
fun FilePickerDialog(
    isVisible: Boolean,
    webDavConfig: WebDavConfig,
    initialPath: String,
    config: FilePickerConfig,
    onDismiss: () -> Unit,
    onPathSelected: (selectedPath: String?) -> Unit,
    initiallySelectedPath: String? = null,
    customButtons: @Composable (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val webDavClient = remember { WebDavClient() }

    var directories by remember { mutableStateOf<List<com.thegrizzlylabs.sardineandroid.DavResource>>(emptyList()) }
    var allResources by remember { mutableStateOf<List<com.thegrizzlylabs.sardineandroid.DavResource>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var currentBrowsingPath by remember { mutableStateOf<String?>(null) }
    var pathHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPath by remember { mutableStateOf<String?>(initiallySelectedPath) }

    // Initialize the file picker when it becomes visible
    LaunchedEffect(isVisible) {
        if (isVisible && currentBrowsingPath == null) {
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
                        println("Failed to load directories: ${e.message}")
                    }
                isLoading = false
            }
        }
    }

    if (isVisible) {
        AlertDialog(
            onDismissRequest = {
                onDismiss()
                currentBrowsingPath = null
                pathHistory = emptyList()
                allResources = emptyList()
                selectedPath = initiallySelectedPath
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onDismiss()
                            currentBrowsingPath = null
                            pathHistory = emptyList()
                            allResources = emptyList()
                            selectedPath = initiallySelectedPath
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "取消",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (config.showClearSelectionButton && selectedPath != null) {
                        Button(
                            onClick = {
                                selectedPath = null
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "清除选择",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    customButtons?.invoke()
                    Button(
                        onClick = {
                            val resultPath = when (config.mode) {
                                FilePickerMode.DIRECTORY_ONLY -> currentBrowsingPath
                                else -> selectedPath
                            }
                            onPathSelected(resultPath)
                            onDismiss()
                            currentBrowsingPath = null
                            pathHistory = emptyList()
                            allResources = emptyList()
                        },
                        enabled = when (config.mode) {
                            FilePickerMode.DIRECTORY_ONLY -> currentBrowsingPath != null
                            FilePickerMode.FILE_ONLY -> selectedPath != null
                            FilePickerMode.FILE_AND_DIRECTORY -> currentBrowsingPath != null || selectedPath != null
                        }
                    ) {
                        Text("确认选择")
                    }
                }
            },
            title = {
                Column {
                    Text(config.title)
                    config.subtitle?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    currentBrowsingPath?.let { path ->
                        Text(
                            text = "当前路径: $path",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    selectedPath?.let { selected ->
                        val fileName = selected.trimEnd('/').substringAfterLast('/')
                        Text(
                            text = "已选择: $fileName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            text = {
                if (isLoading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "加载中...",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    LazyColumn {
                        // 显示 "../" 返回上级目录项
                        val canGoUp = calculateParentPath(webDavConfig, currentBrowsingPath ?: "") != null
                        if (canGoUp) {
                            item {
                                ListItem(
                                    headlineContent = { Text(".. (返回上级)") },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = "返回上级目录",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val parentPath = calculateParentPath(webDavConfig, currentBrowsingPath ?: return@clickable)
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
                                                            println("Failed to load parent directory: ${e.message}")
                                                        }
                                                    isLoading = false
                                                }
                                            }
                                        }
                                )
                            }
                        }

                        // Log.d("FilePickerDialog", "Current browsing path: $currentBrowsingPath")
                        // Log.d("FilePickerDialog", "Directories count: ${directories.size}")
                        // if(directories.isNotEmpty()){
                        //     Log.d("FilePickerDialog", "First directory path: ${directories[0].path}")
                        //     Log.d("FilePickerDialog", "First directory name: ${directories[0].name}")
                        // }

                        // 额外过滤：在UI层也确保不显示当前目录
                        val filteredDirectories = directories.filter { dir ->
                            val dirPath = dir.path.trimEnd('/')
                            val currentPath = currentBrowsingPath?.trimEnd('/') ?: ""

                            // 从URL中提取路径部分
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
                            // Log.d("FilePickerDialog", "Comparing: '$dirPath' with '$currentPathOnly' (from $currentPath)")

                            !isCurrentDirectory && dir.name != "."
                        }

                        Log.d("FilePickerDialog", "Filtered directories count: ${filteredDirectories.size}")
                        // Show filtered subdirectories (always clickable)
                        items(filteredDirectories) { dir ->
                            val displayName = dir.name.ifBlank { dir.path }
                            ListItem(
                                headlineContent = { Text(displayName) },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = "子文件夹",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = "进入文件夹",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val currentPath = currentBrowsingPath ?: return@clickable
                                        // 构建完整的子目录路径
                                        val newPath = if (dir.path.startsWith("http")) {
                                            dir.path.trimEnd('/')
                                        } else {
                                            // 如果是相对路径，需要构建完整URL
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
                                                    println("Failed to load directories: ${e.message}")
                                                    // If loading fails, fallback to previous level
                                                    val parentPath = calculateParentPath(webDavConfig, newPath)
                                                    if (parentPath != null) {
                                                        currentBrowsingPath = parentPath
                                                        pathHistory = generatePathHistory(webDavConfig, parentPath)
                                                    }
                                                }
                                            isLoading = false
                                        }
                                    }
                            )
                        }

                        // Show files based on mode and configuration
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
        )
    }
}