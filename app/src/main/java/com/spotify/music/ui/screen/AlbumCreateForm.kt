package com.spotify.music.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import com.spotify.music.data.ServerConfigRepository
import com.spotify.music.data.Album
import com.spotify.music.webdav.WebDavClient
import kotlinx.coroutines.launch
import okhttp3.Credentials

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumCreateForm(
    onCancel: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String, directoryUrl: String?, coverImageUrl: String?, serverConfigId: String?) -> Unit,
    onCreateServerConfig: () -> Unit = {},
    editingAlbum: Album? = null
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var directoryUrl by remember { mutableStateOf<String?>(null) }
    var manuallySelectedCoverImageUrl by remember { mutableStateOf<String?>(null) }
    var isDirectoryLoading by remember { mutableStateOf(false) }
    var isCoverLoading by remember { mutableStateOf(false) }

    // Dialog visibility states
    var directoryPickerVisible by remember { mutableStateOf(false) }
    var coverPickerVisible by remember { mutableStateOf(false) }
    var showClearCoverDialog by remember { mutableStateOf(false) }

    
    val webDavClient = remember { WebDavClient() }
    val coroutineScope = rememberCoroutineScope()

    // Server config selection
    var serverConfigs by remember { mutableStateOf(ServerConfigRepository.load(context)) }
    var selectedServerConfigId by remember { mutableStateOf<String?>(null) }
    var useExistingConfig by remember { mutableStateOf(false) }

    // Initialize form with editing album data
    LaunchedEffect(editingAlbum) {
        editingAlbum?.let { album ->
            name = album.name
            directoryUrl = album.directoryUrl
            manuallySelectedCoverImageUrl = album.coverImageUrl

            if (album.serverConfigId != null) {
                // Use existing server config
                useExistingConfig = true
                selectedServerConfigId = album.serverConfigId
                val config = serverConfigs.find { it.id == album.serverConfigId }
                config?.let {
                    url = it.url
                    username = it.username
                    password = it.password
                }
            } else {
                // Use manual config
                useExistingConfig = false
                url = album.config.url
                username = album.config.username
                password = album.config.password
            }
        }
    }

    // Helper function to get current WebDAV configuration
    fun getCurrentWebDavConfig(): com.spotify.music.data.WebDavConfig {
        val currentUrl = if (useExistingConfig && selectedServerConfigId != null) {
            serverConfigs.find { it.id == selectedServerConfigId }?.url ?: url
        } else {
            url
        }
        val currentUsername = if (useExistingConfig && selectedServerConfigId != null) {
            serverConfigs.find { it.id == selectedServerConfigId }?.username ?: username
        } else {
            username
        }
        val currentPassword = if (useExistingConfig && selectedServerConfigId != null) {
            serverConfigs.find { it.id == selectedServerConfigId }?.password ?: password
        } else {
            password
        }
        return com.spotify.music.data.WebDavConfig(currentUrl, currentUsername, currentPassword)
    }

    // 拦截返回键
    BackHandler {
        when {
            showClearCoverDialog -> showClearCoverDialog = false
            directoryPickerVisible -> directoryPickerVisible = false
            coverPickerVisible -> coverPickerVisible = false
            else -> onCancel()
        }
    }


    // When a server config is selected, populate the fields
    LaunchedEffect(selectedServerConfigId) {
        selectedServerConfigId?.let { id ->
            val config = serverConfigs.find { it.id == id }
            config?.let {
                url = it.url
                username = it.username
                password = it.password
                directoryUrl = null  // Reset directory when config changes
                manuallySelectedCoverImageUrl = null  // Reset cover image when config changes
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editingAlbum != null) "Edit Album" else "Create Album") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // Server config selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = useExistingConfig,
                    onCheckedChange = {
                        useExistingConfig = it
                        if (!it) {
                            selectedServerConfigId = null
                            url = ""
                            username = ""
                            password = ""
                            manuallySelectedCoverImageUrl = null
                        }
                    }
                )
                Text(
                    text = "使用现有服务器配置",
                    modifier = Modifier.weight(1f)
                )
            }

            if (useExistingConfig) {
                // Server config dropdown
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selectedServerConfigId?.let { id ->
                            serverConfigs.find { it.id == id }?.name ?: ""
                        } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择服务器配置") },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        serverConfigs.forEach { config ->
                            DropdownMenuItem(
                                text = { Text(config.name) },
                                onClick = {
                                    selectedServerConfigId = config.id
                                    expanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("新建服务器配置...") },
                            onClick = {
                                expanded = false
                                onCreateServerConfig()
                            }
                        )
                    }
                }

            } else {
                // Manual input fields
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it; errorMessage = null; directoryUrl =
                        null; manuallySelectedCoverImageUrl = null
                    },
                    label = { Text("WebDAV URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it; errorMessage = null; manuallySelectedCoverImageUrl = null
                    },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it; errorMessage = null; manuallySelectedCoverImageUrl = null
                    },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; errorMessage = null },
                label = { Text("Album Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading
            )
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 显示目录选择和封面选择的区域
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 目录选择卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "音乐文件夹",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (directoryUrl != null) {
                            val folderName = directoryUrl!!.trimEnd('/').substringAfterLast('/')
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = "Folder",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = folderName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Text(
                                text = "未选择文件夹",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                val config = getCurrentWebDavConfig()
                                if (config.url.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                                    errorMessage = "Please fill in URL, username and password"
                                    return@Button
                                }
                                directoryPickerVisible = true
                            },
                            enabled = !isLoading && !isDirectoryLoading,
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            if (isDirectoryLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(if (directoryUrl != null) "重新选择" else "选择文件夹")
                        }
                    }
                }

                // 封面选择卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "专辑封面",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // 封面图片预览按钮
                        Box(
                            modifier = Modifier.size(120.dp)
                        ) {
                            if (manuallySelectedCoverImageUrl != null) {
                                // 已选择封面，显示图片预览
                                val config = getCurrentWebDavConfig()
                                val coverImageUrl = if (manuallySelectedCoverImageUrl!!.startsWith("http")) {
                                    manuallySelectedCoverImageUrl!!
                                } else {
                                    // 构建WebDAV URL
                                    val baseUrl = config.url.trimEnd('/')
                                    val coverPath = manuallySelectedCoverImageUrl!!.trimStart('/')
                                    "$baseUrl/$coverPath"
                                }

                                val headers = NetworkHeaders.Builder()
                                    .set("Authorization", Credentials.basic(config.username, config.password))
                                    .build()

                                AsyncImage(
                                    model = coil3.request.ImageRequest.Builder(LocalContext.current)
                                        .data(coverImageUrl)
                                        .httpHeaders(headers)
                                        .build(),
                                    contentDescription = "封面预览",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = {
                                                val webDavConfig = getCurrentWebDavConfig()
                                                if (webDavConfig.url.isBlank() || webDavConfig.username.isBlank() || webDavConfig.password.isBlank()) {
                                                    errorMessage = "Please fill in URL, username and password"
                                                    return@combinedClickable
                                                }
                                                coverPickerVisible = true
                                            },
                                            onLongClick = {
                                                showClearCoverDialog = true
                                            }
                                        )
                                )
                            } else {
                                // 未选择封面，显示默认按钮
                                Card(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = {
                                                val config = getCurrentWebDavConfig()
                                                if (config.url.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                                                    errorMessage = "Please fill in URL, username and password"
                                                    return@combinedClickable
                                                }
                                                coverPickerVisible = true
                                            }
                                        ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Image,
                                                contentDescription = "选择封面",
                                                modifier = Modifier.size(32.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "选择封面",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (manuallySelectedCoverImageUrl != null) "点击重新选择，长按清除" else "点击选择封面",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // 保存按钮区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Button(
                    onClick = {
                        val config = getCurrentWebDavConfig()
                        if (name.isBlank() || config.url.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                            errorMessage = "Please fill in all fields"
                            return@Button
                        }
                        if (directoryUrl == null) {
                            errorMessage = "Please choose a folder"
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null

                        coroutineScope.launch {
                            val targetUrl = directoryUrl ?: config.url
                            webDavClient.testConnection(config)
                                .onSuccess {
                                    val coverResult =
                                        webDavClient.findCoverImageUrl(config, targetUrl)
                                    val coverUrl = coverResult.getOrNull()
                                    // CoverImagePickerDialog now returns full HTTP URLs, so we can use them directly
                                val finalCoverUrl = manuallySelectedCoverImageUrl ?: coverUrl
                                    isLoading = false
                                    val finalServerConfigId =
                                        if (useExistingConfig) selectedServerConfigId else null
                                    if (name.isBlank()) {
                                        val folderName =
                                            targetUrl.trimEnd('/').substringAfterLast('/')
                                        onSave(
                                            folderName,
                                            config.url,
                                            config.username,
                                            config.password,
                                            targetUrl,
                                            finalCoverUrl,
                                            finalServerConfigId
                                        )
                                    } else {
                                        onSave(
                                            name,
                                            config.url,
                                            config.username,
                                            config.password,
                                            targetUrl,
                                            finalCoverUrl,
                                            finalServerConfigId
                                        )
                                    }
                                }
                                .onFailure { e ->
                                    isLoading = false
                                    errorMessage = "Connection failed: ${e.message}"
                                }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(if (isLoading) "Saving..." else (if (editingAlbum != null) "更新专辑" else "保存专辑"))
                }
            }
        }
    }

    // Folder picker dialog
    val currentWebDavConfig = getCurrentWebDavConfig()
    FolderPickerDialog(
        isVisible = directoryPickerVisible,
        webDavConfig = currentWebDavConfig,
        initialPath = currentWebDavConfig.url,
        onDismiss = { directoryPickerVisible = false },
        onFolderSelected = { path ->
            directoryUrl = path
            if (name.isBlank()) {
                val folderName = path.trimEnd('/').substringAfterLast('/')
                name = folderName
            }
        },
        onClearCoverSelection = { manuallySelectedCoverImageUrl = null },
        hasCoverSelection = manuallySelectedCoverImageUrl != null
    )

    // Cover image picker dialog
    CoverImagePickerDialog(
        isVisible = coverPickerVisible,
        webDavConfig = currentWebDavConfig,
        initialPath = currentWebDavConfig.url,
        onDismiss = { coverPickerVisible = false },
        onCoverSelected = { coverPath ->
            manuallySelectedCoverImageUrl = coverPath
        },
        initiallySelectedCover = manuallySelectedCoverImageUrl
    )

    // Clear cover confirmation dialog
    if (showClearCoverDialog) {
        AlertDialog(
            onDismissRequest = { showClearCoverDialog = false },
            title = { Text("清除封面选择") },
            text = { Text("确定要清除已选择的封面图片吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        manuallySelectedCoverImageUrl = null
                        showClearCoverDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showClearCoverDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }
}
