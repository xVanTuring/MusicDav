package com.spotify.music.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.spotify.music.data.ServerConfigRepository
import com.spotify.music.webdav.WebDavClient
import kotlinx.coroutines.launch

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
    val webDavClient = remember { WebDavClient() }
    val coroutineScope = rememberCoroutineScope()
    
    // Server config selection
    var serverConfigs by remember { mutableStateOf(ServerConfigRepository.load(context)) }
    var selectedServerConfigId by remember { mutableStateOf<String?>(null) }
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var useExistingConfig by remember { mutableStateOf(false) }
    
    // Update server configs when dialog is shown
    LaunchedEffect(showServerConfigDialog) {
        if (showServerConfigDialog) {
            serverConfigs = ServerConfigRepository.load(context)
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
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Create Album",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
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
            OutlinedTextField(
                value = selectedServerConfigId?.let { id ->
                    serverConfigs.find { it.id == id }?.name ?: ""
                } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("选择服务器配置") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                trailingIcon = {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "选择配置"
                        )
                    }
                }
            )
            
            DropdownMenu(
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
                Divider()
                DropdownMenuItem(
                    text = { Text("管理服务器配置...") },
                    onClick = {
                        expanded = false
                        showServerConfigDialog = true
                    }
                )
            }
        } else {
            // Manual input fields
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; errorMessage = null; directoryUrl = null },
                label = { Text("WebDAV URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; errorMessage = null },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
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
        if (directoryUrl != null) {
            Text(
                text = "Selected folder: $directoryUrl",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    onCancel()
                },
                enabled = !isLoading
            ) { Text("Cancel") }
            Button(
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
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text("Choose Folder")
            }
            Button(
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
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(if (isLoading) "Saving..." else "Save")
            }
        }
    }

    LaunchedEffect(submitCounter) {
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
        AlertDialog(
            onDismissRequest = { directoryPickerVisible = false },
            confirmButton = {},
            title = { Text("Choose Folder") },
            text = {
                LazyColumn {
                    items(directories.size) { index ->
                        val dir = directories[index]
                        val displayName = dir.name.ifBlank { dir.path }
                        val currentUrl = if (useExistingConfig && selectedServerConfigId != null) {
                            serverConfigs.find { it.id == selectedServerConfigId }?.url ?: url
                        } else {
                            url
                        }
                        ListItem(
                            headlineContent = { Text(displayName) },
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
                serverConfigs = ServerConfigRepository.load(context)
            },
            onConfigSelected = { configId ->
                selectedServerConfigId = configId
                showServerConfigDialog = false
            }
        )
    }
}
