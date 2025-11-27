package com.spotify.music.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.spotify.music.data.ServerConfig
import com.spotify.music.data.ServerConfigRepository

@Composable
fun ServerConfigManagementDialog(
    onDismiss: () -> Unit,
    onConfigSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var configs by remember { mutableStateOf(ServerConfigRepository.load(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<ServerConfig?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器配置管理") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (configs.isEmpty()) {
                    Text(
                        text = "暂无服务器配置",
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn {
                        items(configs.size) { index ->
                            val config = configs[index]
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = config.name,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = config.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row {
                                        IconButton(
                                            onClick = {
                                                editingConfig = config
                                                showCreateDialog = true
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "编辑"
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                ServerConfigRepository.delete(context, config.id)
                                                configs = ServerConfigRepository.load(context)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "删除"
                                            )
                                        }
                                    }
                                }
                                Button(
                                    onClick = { onConfigSelected(config.id) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text("选择此配置")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    showCreateDialog = true
                }
            ) {
                Text("新建配置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
    
    if (showCreateDialog) {
        ServerConfigEditDialog(
            config = editingConfig,
            onDismiss = {
                showCreateDialog = false
                editingConfig = null
                configs = ServerConfigRepository.load(context)
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
                    ServerConfigRepository.update(context, updated)
                } else {
                    // Create new
                    val newConfig = ServerConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        url = url,
                        username = username,
                        password = password
                    )
                    ServerConfigRepository.add(context, newConfig)
                }
                configs = ServerConfigRepository.load(context)
                showCreateDialog = false
                editingConfig = null
            }
        )
    }
}

@Composable
fun ServerConfigEditDialog(
    config: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String) -> Unit
) {
    var name by remember { mutableStateOf(config?.name ?: "") }
    var url by remember { mutableStateOf(config?.url ?: "") }
    var username by remember { mutableStateOf(config?.username ?: "") }
    var password by remember { mutableStateOf(config?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (config != null) "编辑服务器配置" else "新建服务器配置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMessage = null },
                    label = { Text("配置名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; errorMessage = null },
                    label = { Text("WebDAV URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMessage = null },
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    }
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank() || url.isBlank() || username.isBlank() || password.isBlank()) {
                        errorMessage = "请填写所有字段"
                        return@Button
                    }
                    onSave(name, url, username, password)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
