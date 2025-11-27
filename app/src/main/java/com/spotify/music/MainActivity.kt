package com.spotify.music

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.spotify.music.data.Album
import com.spotify.music.data.MusicFile
import com.spotify.music.data.PlaylistState
import com.spotify.music.data.WebDavConfig
import com.spotify.music.ui.BottomPlayerBar
import com.spotify.music.ui.LoginScreen
import com.spotify.music.ui.MusicListScreen
import com.spotify.music.ui.theme.MusicDavTheme
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicDavTheme {
                MusicPlayerApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun MusicPlayerApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var albums by remember { mutableStateOf(com.spotify.music.data.AlbumsRepository.load(context)) }
    var selectedAlbum by remember { mutableStateOf<com.spotify.music.data.Album?>(null) }
    
    if (selectedAlbum == null) {
        AlbumListScreen(
            albums = albums,
            onSelect = { selectedAlbum = it },
            onCreate = { album, serverConfigId ->
                val updated = albums + album
                albums = updated
                com.spotify.music.data.AlbumsRepository.save(context, updated)
                selectedAlbum = album
            },
            onDelete = { album ->
                val updated = albums.filterNot { 
                    val itConfig = if (it.serverConfigId != null) {
                        com.spotify.music.data.ServerConfigRepository.load(context)
                            .find { config -> config.id == it.serverConfigId }
                            ?.toWebDavConfig() ?: it.config
                    } else {
                        it.config
                    }
                    val albumConfig = if (album.serverConfigId != null) {
                        com.spotify.music.data.ServerConfigRepository.load(context)
                            .find { config -> config.id == album.serverConfigId }
                            ?.toWebDavConfig() ?: album.config
                    } else {
                        album.config
                    }
                    it.name == album.name && itConfig.url == albumConfig.url
                }
                albums = updated
                com.spotify.music.data.AlbumsRepository.save(context, updated)
            },
            modifier = modifier
        )
    } else {
        MusicPlayerScreen(
            album = selectedAlbum!!,
            onBack = { selectedAlbum = null },
            modifier = modifier
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    albums: List<com.spotify.music.data.Album>,
    onSelect: (com.spotify.music.data.Album) -> Unit,
    onCreate: (com.spotify.music.data.Album, String?) -> Unit,
    onDelete: (com.spotify.music.data.Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var creating by remember { mutableStateOf(false) }
    androidx.compose.material3.Scaffold(
        topBar = { androidx.compose.material3.TopAppBar(title = { androidx.compose.material3.Text("Albums") }) },
        modifier = modifier
    ) { paddingValues ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (creating) {
                AlbumCreateForm(
                    onCancel = { creating = false },
                    onSave = { name, url, username, password, directoryUrl, coverImageBase64, serverConfigId ->
                        val config = com.spotify.music.data.WebDavConfig(url = url, username = username, password = password)
                        val album = com.spotify.music.data.Album(
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
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    albums.forEach { album ->
                        androidx.compose.material3.Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable { onSelect(album) },
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    androidx.compose.material3.Text(
                                        text = album.name,
                                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                                    )
                                    androidx.compose.material3.Text(
                                        text = album.directoryUrl ?: run {
                                            if (album.serverConfigId != null) {
                                                com.spotify.music.data.ServerConfigRepository.load(context)
                                                    .find { it.id == album.serverConfigId }
                                                    ?.toWebDavConfig()?.url ?: album.config.url
                                            } else {
                                                album.config.url
                                            }
                                        },
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.IconButton(
                                    onClick = { onDelete(album) }
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Traffic,
                                        contentDescription = "Delete Album"
                                    )
                                }
                            }
                        }
                    }
                    androidx.compose.material3.Button(
                        onClick = { creating = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        androidx.compose.material3.Text("Add Album")
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCreateForm(
    onCancel: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String, directoryUrl: String?, coverImageBase64: String?, serverConfigId: String?) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var submitCounter by remember { mutableStateOf(0) }
    var directoryUrl by remember { mutableStateOf<String?>(null) }
    var directoryPickerVisible by remember { mutableStateOf(false) }
    var directories by remember { mutableStateOf<List<com.thegrizzlylabs.sardineandroid.DavResource>>(emptyList()) }
    var isDirectoryLoading by remember { mutableStateOf(false) }
    val webDavClient = remember { com.spotify.music.webdav.WebDavClient() }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    
    // Server config selection
    var serverConfigs by remember { mutableStateOf(com.spotify.music.data.ServerConfigRepository.load(context)) }
    var selectedServerConfigId by remember { mutableStateOf<String?>(null) }
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var useExistingConfig by remember { mutableStateOf(false) }
    
    // Update server configs when dialog is shown
    androidx.compose.runtime.LaunchedEffect(showServerConfigDialog) {
        if (showServerConfigDialog) {
            serverConfigs = com.spotify.music.data.ServerConfigRepository.load(context)
        }
    }
    
    // When a server config is selected, populate the fields
    androidx.compose.runtime.LaunchedEffect(selectedServerConfigId) {
        selectedServerConfigId?.let { id ->
            val config = serverConfigs.find { it.id == id }
            config?.let {
                url = it.url
                username = it.username
                password = it.password
                directoryUrl = null  // Reset directory when config changes
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        androidx.compose.material3.Text(
            text = "Create Album",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Server config selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(
                checked = useExistingConfig,
                onCheckedChange = { 
                    useExistingConfig = it
                    if (!it) {
                        selectedServerConfigId = null
                        url = ""
                        username = ""
                        password = ""
                    }
                }
            )
            androidx.compose.material3.Text(
                text = "使用现有服务器配置",
                modifier = Modifier.weight(1f)
            )
        }
        
        if (useExistingConfig) {
            // Server config dropdown
            var expanded by remember { mutableStateOf(false) }
            androidx.compose.material3.OutlinedTextField(
                value = selectedServerConfigId?.let { id ->
                    serverConfigs.find { it.id == id }?.name ?: ""
                } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { androidx.compose.material3.Text("选择服务器配置") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = { expanded = true }) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Edit,
                            contentDescription = "选择配置"
                        )
                    }
                }
            )
            
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                serverConfigs.forEach { config ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(config.name) },
                        onClick = {
                            selectedServerConfigId = config.id
                            expanded = false
                        }
                    )
                }
                androidx.compose.material3.Divider()
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text("管理服务器配置...") },
                    onClick = {
                        expanded = false
                        showServerConfigDialog = true
                    }
                )
            }
        } else {
            // Manual input fields
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it; errorMessage = null; directoryUrl = null },
                label = { androidx.compose.material3.Text("WebDAV URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading
            )
            androidx.compose.material3.OutlinedTextField(
                value = username,
                onValueChange = { username = it; errorMessage = null },
                label = { androidx.compose.material3.Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading
            )
            androidx.compose.material3.OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { androidx.compose.material3.Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading,
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)
            )
        }
        
        androidx.compose.material3.OutlinedTextField(
            value = name,
            onValueChange = { name = it; errorMessage = null },
            label = { androidx.compose.material3.Text("Album Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isLoading
        )
        if (errorMessage != null) {
            androidx.compose.material3.Text(
                text = errorMessage!!,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (directoryUrl != null) {
            androidx.compose.material3.Text(
                text = "Selected folder: $directoryUrl",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.material3.Button(
                onClick = {
                    onCancel()
                },
                enabled = !isLoading
            ) { androidx.compose.material3.Text("Cancel") }
            androidx.compose.material3.Button(
                onClick = {
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
                    
                    if (currentUrl.isBlank() || currentUsername.isBlank() || currentPassword.isBlank()) {
                        errorMessage = "Please fill in URL, username and password"
                        return@Button
                    }
                    isDirectoryLoading = true
                    errorMessage = null
                    val config = com.spotify.music.data.WebDavConfig(currentUrl, currentUsername, currentPassword)
                    coroutineScope.launch {
                        webDavClient.listDirectories(config)
                            .onSuccess { list ->
                                directories = list
                                directoryPickerVisible = true
                            }
                            .onFailure { e ->
                                errorMessage = "Failed to load directories: ${e.message}"
                            }
                        isDirectoryLoading = false
                    }
                },
                enabled = !isLoading && !isDirectoryLoading
            ) {
                if (isDirectoryLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                    )
                }
                androidx.compose.material3.Text("Choose Folder")
            }
            androidx.compose.material3.Button(
                onClick = {
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
                    
                    if (name.isBlank() || currentUrl.isBlank() || currentUsername.isBlank() || currentPassword.isBlank()) {
                        errorMessage = "Please fill in all fields"
                        return@Button
                    }
                    if (directoryUrl == null) {
                        errorMessage = "Please choose a folder"
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null
                    submitCounter++
                },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                    )
                }
                androidx.compose.material3.Text(if (isLoading) "Saving..." else "Save")
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(submitCounter) {
        if (submitCounter > 0) {
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
            
            val config = com.spotify.music.data.WebDavConfig(currentUrl, currentUsername, currentPassword)
            val targetUrl = directoryUrl ?: currentUrl
            webDavClient.testConnection(config)
                .onSuccess {
                    val coverResult = webDavClient.findCoverImage(config, targetUrl)
                    val coverBytes = coverResult.getOrNull()
                    val coverBase64 = coverBytes?.let { bytes ->
                        android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    }
                    isLoading = false
                    val finalServerConfigId = if (useExistingConfig) selectedServerConfigId else null
                    if (name.isBlank()) {
                        val folderName = targetUrl.trimEnd('/').substringAfterLast('/')
                        onSave(folderName, currentUrl, currentUsername, currentPassword, targetUrl, coverBase64, finalServerConfigId)
                    } else {
                        onSave(name, currentUrl, currentUsername, currentPassword, targetUrl, coverBase64, finalServerConfigId)
                    }
                }
                .onFailure { e ->
                    isLoading = false
                    errorMessage = "Connection failed: ${e.message}"
                }
        }
    }

    if (directoryPickerVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { directoryPickerVisible = false },
            confirmButton = {},
            title = { androidx.compose.material3.Text("Choose Folder") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(directories.size) { index ->
                        val dir = directories[index]
                        val displayName = dir.name.ifBlank { dir.path }
                        val currentUrl = if (useExistingConfig && selectedServerConfigId != null) {
                            serverConfigs.find { it.id == selectedServerConfigId }?.url ?: url
                        } else {
                            url
                        }
                        androidx.compose.material3.ListItem(
                            headlineContent = { androidx.compose.material3.Text(displayName) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    directoryUrl = (currentUrl.trimEnd('/') + "/" + dir.name.trim('/')).trimEnd('/')
                                    if (name.isBlank()) {
                                        name = dir.name.trim('/')
                                    }
                                    directoryPickerVisible = false
                                }
                        )
                    }
                }
            }
        )
    }
    
    // Server config management dialog
    if (showServerConfigDialog) {
        ServerConfigManagementDialog(
            onDismiss = { 
                showServerConfigDialog = false
                serverConfigs = com.spotify.music.data.ServerConfigRepository.load(context)
            },
            onConfigSelected = { configId ->
                selectedServerConfigId = configId
                showServerConfigDialog = false
            }
        )
    }
}

@Composable
fun ServerConfigManagementDialog(
    onDismiss: () -> Unit,
    onConfigSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var configs by remember { mutableStateOf(com.spotify.music.data.ServerConfigRepository.load(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<com.spotify.music.data.ServerConfig?>(null) }
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("服务器配置管理") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (configs.isEmpty()) {
                    androidx.compose.material3.Text(
                        text = "暂无服务器配置",
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(configs.size) { index ->
                            val config = configs[index]
                            androidx.compose.material3.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.foundation.layout.Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        androidx.compose.material3.Text(
                                            text = config.name,
                                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                                        )
                                        androidx.compose.material3.Text(
                                            text = config.url,
                                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row {
                                        androidx.compose.material3.IconButton(
                                            onClick = {
                                                editingConfig = config
                                                showCreateDialog = true
                                            }
                                        ) {
                                            androidx.compose.material3.Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Default.Edit,
                                                contentDescription = "编辑"
                                            )
                                        }
                                        androidx.compose.material3.IconButton(
                                            onClick = {
                                                com.spotify.music.data.ServerConfigRepository.delete(context, config.id)
                                                configs = com.spotify.music.data.ServerConfigRepository.load(context)
                                            }
                                        ) {
                                            androidx.compose.material3.Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                                contentDescription = "删除"
                                            )
                                        }
                                    }
                                }
                                androidx.compose.material3.Button(
                                    onClick = { onConfigSelected(config.id) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    androidx.compose.material3.Text("选择此配置")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    showCreateDialog = true
                }
            ) {
                androidx.compose.material3.Text("新建配置")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("关闭")
            }
        }
    )
    
    if (showCreateDialog) {
        ServerConfigEditDialog(
            config = editingConfig,
            onDismiss = {
                showCreateDialog = false
                editingConfig = null
                configs = com.spotify.music.data.ServerConfigRepository.load(context)
            },
            onSave = { name, url, username, password ->
                if (editingConfig != null) {
                    // Update existing
                    val updated = editingConfig!!.copy(
                        name = name,
                        url = url,
                        username = username,
                        password = password
                    )
                    com.spotify.music.data.ServerConfigRepository.update(context, updated)
                } else {
                    // Create new
                    val newConfig = com.spotify.music.data.ServerConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        url = url,
                        username = username,
                        password = password
                    )
                    com.spotify.music.data.ServerConfigRepository.add(context, newConfig)
                }
                configs = com.spotify.music.data.ServerConfigRepository.load(context)
                showCreateDialog = false
                editingConfig = null
            }
        )
    }
}

@Composable
fun ServerConfigEditDialog(
    config: com.spotify.music.data.ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String) -> Unit
) {
    var name by remember { mutableStateOf(config?.name ?: "") }
    var url by remember { mutableStateOf(config?.url ?: "") }
    var username by remember { mutableStateOf(config?.username ?: "") }
    var password by remember { mutableStateOf(config?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(if (config != null) "编辑服务器配置" else "新建服务器配置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMessage = null },
                    label = { androidx.compose.material3.Text("配置名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                androidx.compose.material3.OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; errorMessage = null },
                    label = { androidx.compose.material3.Text("WebDAV URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                androidx.compose.material3.OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMessage = null },
                    label = { androidx.compose.material3.Text("用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { androidx.compose.material3.Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            androidx.compose.material3.Icon(
                                imageVector = if (passwordVisible) androidx.compose.material.icons.Icons.Default.VisibilityOff else androidx.compose.material.icons.Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    }
                )
                if (errorMessage != null) {
                    androidx.compose.material3.Text(
                        text = errorMessage!!,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    if (name.isBlank() || url.isBlank() || username.isBlank() || password.isBlank()) {
                        errorMessage = "请填写所有字段"
                        return@Button
                    }
                    onSave(name, url, username, password)
                }
            ) {
                androidx.compose.material3.Text("保存")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("取消")
            }
        }
    )
}
@Composable
fun MusicPlayerScreen(
    album: com.spotify.music.data.Album,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playlistState by remember { mutableStateOf(PlaylistState()) }
    
    val webDavConfig = if (album.serverConfigId != null) {
        com.spotify.music.data.ServerConfigRepository.load(context)
            .find { it.id == album.serverConfigId }
            ?.toWebDavConfig() ?: album.config
    } else {
        album.config
    }
    
    // Set WebDAV credentials for the service - will dynamically update if service is already running
    LaunchedEffect(webDavConfig) {
        SimpleMusicService.setCredentials(webDavConfig.username, webDavConfig.password)
    }
    
    val needsNotificationPermission = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
    var notificationGranted by remember {
        mutableStateOf(
            !needsNotificationPermission || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationAsked by remember { mutableStateOf(false) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationGranted = granted
            notificationAsked = true
        }

    LaunchedEffect(needsNotificationPermission, notificationAsked, notificationGranted) {
        if (needsNotificationPermission && !notificationGranted && !notificationAsked) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(Unit) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SimpleMusicService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val listenerRef = AtomicReference<Player.Listener>()

        controllerFuture.addListener({
            try {
                val mediaController = controllerFuture.get()
                controller = mediaController
                
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playlistState = playlistState.copy(isPlaying = isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // Handle playback state changes
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Handle errors
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        // Metadata updated
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                            val duration = player.duration.takeIf { it > 0 } ?: 0L
                            val currentIndex = player.currentMediaItemIndex
                            playlistState = playlistState.copy(
                                duration = duration,
                                currentIndex = currentIndex
                            )
                        }
                    }
                }
                listenerRef.set(listener)
                mediaController.addListener(listener)
                
                val duration = mediaController.duration.takeIf { it > 0 } ?: 0L
                val currentPosition = mediaController.currentPosition
                playlistState = playlistState.copy(
                    duration = duration,
                    currentPosition = currentPosition,
                    isPlaying = mediaController.isPlaying,
                    currentIndex = mediaController.currentMediaItemIndex
                )
            } catch (error: Exception) {
                // Handle error
            }
        }, mainExecutor)

        onDispose {
            listenerRef.get()?.let { controller?.removeListener(it) }
            controller?.release()
            controller = null
            controllerFuture.cancel(true)
        }
    }

    LaunchedEffect(controller) {
        while (isActive) {
            controller?.let {
                val currentPosition = it.currentPosition
                val duration = it.duration.takeIf { d -> d > 0 } ?: 0L
                playlistState = playlistState.copy(
                    currentPosition = currentPosition,
                    duration = duration
                )
            }
            delay(100)
        }
    }
    
    // 当 controller 初始化后，如果播放列表已加载但播放器为空，则设置播放列表
    // 注意：只在播放器为空时设置，不会自动切换不同的播放列表
    LaunchedEffect(controller, playlistState.songs) {
        controller?.let { currentController ->
            if (playlistState.songs.isNotEmpty()) {
                val currentMediaItemCount = currentController.mediaItemCount
                // 只在播放列表为空时才设置，不自动切换不同的播放列表
                if (currentMediaItemCount == 0) {
                    val mediaItems = playlistState.songs.map { song ->
                        MediaItem.Builder()
                            .setUri(song.url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.name.substringBeforeLast('.'))
                                    .build()
                            )
                            .build()
                    }
                    currentController.setMediaItems(mediaItems)
                    currentController.prepare()
                }
            }
        }
    }
    
    fun loadPlaylist(songs: List<MusicFile>) {
        // 只更新播放列表数据，不自动设置到播放器
        // 播放列表的切换只在用户点击歌曲时进行
        // 如果当前播放的歌曲在新列表中，保持当前索引；否则重置索引
        val currentSong = playlistState.currentSong
        val newIndex = if (currentSong != null) {
            songs.indexOfFirst { it.url == currentSong.url }.takeIf { it >= 0 } 
                ?: playlistState.currentIndex.takeIf { it < songs.size } 
                ?: -1
        } else {
            -1
        }
        
        playlistState = playlistState.copy(
            songs = songs,
            currentIndex = newIndex
        )
    }
    
    fun setPlaylistAndPlay(index: Int) {
        // 用户点击歌曲时，设置播放列表并播放
        if (playlistState.songs.isEmpty()) return
        
        controller?.let { currentController ->
            val currentUrls = mutableListOf<String>()
            val currentMediaItemCount = currentController.mediaItemCount
            for (i in 0 until currentMediaItemCount) {
                currentController.getMediaItemAt(i)?.localConfiguration?.uri?.toString()?.let {
                    currentUrls.add(it)
                }
            }
            
            val newUrls = playlistState.songs.map { it.url }
            
            // 如果播放列表不同，需要设置新的播放列表
            if (currentUrls != newUrls) {
                val mediaItems = playlistState.songs.map { song ->
                    MediaItem.Builder()
                        .setUri(song.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.name.substringBeforeLast('.'))
                                .build()
                        )
                        .build()
                }
                
                currentController.setMediaItems(mediaItems)
                currentController.prepare()
            }
            
            // 跳转到指定位置并播放
            if (index >= 0 && index < playlistState.songs.size) {
                currentController.seekToDefaultPosition(index)
                currentController.play()
            }
        }
    }

    MusicListScreen(
        webDavConfig = webDavConfig,
        directoryPath = album.directoryUrl,
        showBack = true,
        onBack = onBack,
        currentPlayingIndex = playlistState.currentIndex,
        onPlaylistLoaded = { songs ->
            loadPlaylist(songs)
        },
        onSongSelected = { index, song ->
            setPlaylistAndPlay(index)
        },
        bottomBar = {
            BottomPlayerBar(
                playlistState = playlistState,
                onPlayPause = {
                    controller?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                },
                onNext = {
                    controller?.seekToNext()
                },
                onPrevious = {
                    controller?.seekToPrevious()
                }
            )
        },
        modifier = modifier
    )
}

private fun ensureServiceStarted(context: android.content.Context) {
    val intent = Intent(context, SimpleMusicService::class.java)
    ContextCompat.startForegroundService(context, intent)
}
