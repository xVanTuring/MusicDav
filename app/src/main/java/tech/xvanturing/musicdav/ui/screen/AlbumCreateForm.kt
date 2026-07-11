package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.Album
import tech.xvanturing.musicdav.data.relativizeAlbumUrl
import tech.xvanturing.musicdav.data.resolveAlbumUrl
import tech.xvanturing.musicdav.ui.components.AppTopBar
import tech.xvanturing.musicdav.webdav.WebDavClient
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
    var selectingFolder by remember { mutableStateOf(false) }
    var selectingCover by remember { mutableStateOf(false) }
    var showClearCoverDialog by remember { mutableStateOf(false) }


    val webDavClient = remember { WebDavClient() }
    val coroutineScope = rememberCoroutineScope()

    // Server config selection
    var serverConfigs by remember { mutableStateOf(ServerConfigRepository.load(context)) }
    var selectedServerConfigId by remember { mutableStateOf<String?>(null) }
    var useExistingConfig by remember { mutableStateOf(false) }
    var isInitializingEdit by remember { mutableStateOf(false) }

    // Initialize form with editing album data
    LaunchedEffect(editingAlbum) {
        editingAlbum?.let { album ->
            isInitializingEdit = true
            name = album.name

            val baseUrl: String
            if (album.serverConfigId != null) {
                // Use existing server config
                useExistingConfig = true
                selectedServerConfigId = album.serverConfigId
                val config = serverConfigs.find { it.id == album.serverConfigId }
                baseUrl = config?.url ?: album.config.url
                url = baseUrl
                username = config?.username ?: album.config.username
                password = config?.password ?: album.config.password
            } else {
                // Use manual config
                useExistingConfig = false
                baseUrl = album.config.url
                url = baseUrl
                username = album.config.username
                password = album.config.password
            }

            // album.directoryUrl/coverImageUrl 在关联了服务器时存的是相对路径，
            // 这里还原成完整地址供表单内部（文件夹选择器/封面预览）使用
            directoryUrl = resolveAlbumUrl(album.directoryUrl, baseUrl)
            manuallySelectedCoverImageUrl = resolveAlbumUrl(album.coverImageUrl, baseUrl)

            // 延迟重置初始化标志，确保 serverConfig 的 LaunchedEffect 不会重置数据
            kotlinx.coroutines.delay(100)
            isInitializingEdit = false
        }
    }

    // Initialize with default server config for new album
    LaunchedEffect(Unit) {
        if (editingAlbum == null && serverConfigs.isNotEmpty()) {
            useExistingConfig = true
            selectedServerConfigId = serverConfigs.first().id
            val config = serverConfigs.first()
            url = config.url
            username = config.username
            password = config.password
        }
    }

    // Helper function to get current WebDAV configuration
    fun getCurrentWebDavConfig(): tech.xvanturing.musicdav.data.WebDavConfig {
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
        return tech.xvanturing.musicdav.data.WebDavConfig(currentUrl, currentUsername, currentPassword)
    }

    // 拦截返回键
    BackHandler {
        when {
            selectingFolder -> selectingFolder = false
            selectingCover -> selectingCover = false
            showClearCoverDialog -> showClearCoverDialog = false
            else -> onCancel()
        }
    }


    // When a server config is selected, populate the fields
    LaunchedEffect(selectedServerConfigId) {
        selectedServerConfigId?.let { id ->
            // 只有在非初始化状态下才重置数据
            if (!isInitializingEdit) {
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
    }

    if (selectingCover) {
        FolderPickerScreen(
            webDavConfig = getCurrentWebDavConfig(),
            initialPath = directoryUrl ?: getCurrentWebDavConfig().url,
            config = FilePickerConfig(
                title = stringResource(R.string.album_cover_picker_title),
                subtitle = stringResource(R.string.album_cover_picker_subtitle),
                mode = FilePickerMode.FILE_ONLY,
                allowedFileExtensions = setOf("jpg", "jpeg", "png", "webp"),
                showFileIcons = true,
                showClearSelectionButton = false,
                showFilesInDirectoryMode = false
            ),
            initiallySelectedPath = manuallySelectedCoverImageUrl,
            onConfirm = { path ->
                path?.let {
                    if (it.startsWith("http")) {
                        manuallySelectedCoverImageUrl = it
                    } else {
                        val webDavClient = tech.xvanturing.musicdav.webdav.WebDavClient()
                        val fullUrl = webDavClient.buildFullUrl(getCurrentWebDavConfig().url, it)
                        manuallySelectedCoverImageUrl = fullUrl
                    }
                }
                selectingCover = false
            },
            onCancel = {
                selectingCover = false
            }
        )
    } else if (selectingFolder) {
        FolderPickerScreen(
            webDavConfig = getCurrentWebDavConfig(),
            initialPath = directoryUrl ?: getCurrentWebDavConfig().url,
            config = FilePickerConfig(
                title = stringResource(R.string.album_select_folder),
                mode = FilePickerMode.DIRECTORY_ONLY,
                showClearSelectionButton = false,
                showFileIcons = true,
                showFilesInDirectoryMode = true
            ),
            onConfirm = { path ->
                path?.let {
                    directoryUrl = it
                    if (name.isBlank()) {
                        name = it.trimEnd('/').substringAfterLast('/')
                    }
                }
                selectingFolder = false
            },
            onCancel = {
                selectingFolder = false
            }
        )
    } else {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(
                        if (editingAlbum != null) R.string.album_edit_title else R.string.album_create_title
                    ),
                    onBack = onCancel
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Server config selection
                ConfigSection(title = stringResource(R.string.album_section_server)) {
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
                            text = stringResource(R.string.album_use_existing_server),
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
                                label = { Text(stringResource(R.string.album_select_server)) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
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
                                    text = { Text(stringResource(R.string.album_create_server_config)) },
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
                            label = { Text(stringResource(R.string.album_webdav_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            singleLine = true,
                            enabled = !isLoading
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = it; errorMessage = null; manuallySelectedCoverImageUrl = null
                            },
                            label = { Text(stringResource(R.string.album_username)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            singleLine = true,
                            enabled = !isLoading
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it; errorMessage = null; manuallySelectedCoverImageUrl = null
                            },
                            label = { Text(stringResource(R.string.album_password)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            singleLine = true,
                            enabled = !isLoading,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                    }
                }

                // Album name section
                ConfigSection(title = stringResource(R.string.album_section_basic)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; errorMessage = null },
                        label = { Text(stringResource(R.string.album_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        singleLine = true,
                        enabled = !isLoading
                    )
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 音乐文件夹选择
                ConfigSection(title = stringResource(R.string.album_music_folder)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (directoryUrl != null) {
                            val folderName = directoryUrl!!.trimEnd('/').substringAfterLast('/')
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = stringResource(R.string.album_folder_desc),
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
                                text = stringResource(R.string.album_no_folder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                val config = getCurrentWebDavConfig()
                                if (config.url.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                                    errorMessage = context.getString(R.string.album_error_fill_credentials)
                                    return@Button
                                }
                                selectingFolder = true
                            },
                            enabled = !isLoading && !isDirectoryLoading
                        ) {
                            if (isDirectoryLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(
                                if (directoryUrl != null) stringResource(R.string.album_reselect_folder)
                                else stringResource(R.string.album_select_folder)
                            )
                        }
                    }
                }

                // 专辑封面选择
                ConfigSection(title = stringResource(R.string.album_cover)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
                                    contentDescription = stringResource(R.string.album_cover_preview_desc),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(MaterialTheme.shapes.medium)
                                        .combinedClickable(
                                            onClick = {
                                                val webDavConfig = getCurrentWebDavConfig()
                                                if (webDavConfig.url.isBlank() || webDavConfig.username.isBlank() || webDavConfig.password.isBlank()) {
                                                    errorMessage = context.getString(R.string.album_error_fill_credentials)
                                                    return@combinedClickable
                                                }
                                                selectingCover = true
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
                                        .clip(MaterialTheme.shapes.medium)
                                        .combinedClickable(
                                            onClick = {
                                                val config = getCurrentWebDavConfig()
                                                if (config.url.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                                                    errorMessage = context.getString(R.string.album_error_fill_credentials)
                                                    return@combinedClickable
                                                }
                                                selectingCover = true
                                            }
                                        ),
                                    shape = MaterialTheme.shapes.medium,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    )
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
                                                contentDescription = stringResource(R.string.album_select_cover),
                                                modifier = Modifier.size(32.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(R.string.album_select_cover),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (manuallySelectedCoverImageUrl != null) {
                                stringResource(R.string.album_cover_reselect_hint)
                            } else {
                                stringResource(R.string.album_tap_select_cover)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                // 保存按钮
                Button(
                    onClick = {
                        val config = getCurrentWebDavConfig()
                        if (name.isBlank() || config.url.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                            errorMessage = context.getString(R.string.album_error_fill_all)
                            return@Button
                        }
                        if (directoryUrl == null) {
                            errorMessage = context.getString(R.string.album_error_choose_folder)
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
                                    // 关联了服务器配置时，目录/封面存相对路径，这样服务器地址调整了也不受影响
                                    val savedDirectoryUrl =
                                        if (finalServerConfigId != null) relativizeAlbumUrl(targetUrl, config.url) else targetUrl
                                    val savedCoverUrl =
                                        if (finalServerConfigId != null) relativizeAlbumUrl(finalCoverUrl, config.url) else finalCoverUrl
                                    if (name.isBlank()) {
                                        val folderName =
                                            targetUrl.trimEnd('/').substringAfterLast('/')
                                        onSave(
                                            folderName,
                                            config.url,
                                            config.username,
                                            config.password,
                                            savedDirectoryUrl,
                                            savedCoverUrl,
                                            finalServerConfigId
                                        )
                                    } else {
                                        onSave(
                                            name,
                                            config.url,
                                            config.username,
                                            config.password,
                                            savedDirectoryUrl,
                                            savedCoverUrl,
                                            finalServerConfigId
                                        )
                                    }
                                }
                                .onFailure { e ->
                                    isLoading = false
                                    errorMessage = context.getString(
                                        R.string.album_error_connection_failed,
                                        e.message
                                    )
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
                    Text(if (isLoading) stringResource(R.string.album_saving) else stringResource(R.string.album_save))
                }
            }
        }

        // Clear cover confirmation dialog
        if (showClearCoverDialog) {
            AlertDialog(
                onDismissRequest = { showClearCoverDialog = false },
                title = { Text(stringResource(R.string.album_clear_cover_title)) },
                text = { Text(stringResource(R.string.album_clear_cover_message)) },
                confirmButton = {
                    Button(
                        onClick = {
                            manuallySelectedCoverImageUrl = null
                            showClearCoverDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showClearCoverDialog = false }
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}
