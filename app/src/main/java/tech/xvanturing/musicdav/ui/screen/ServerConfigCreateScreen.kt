package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
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
import tech.xvanturing.musicdav.data.ServerConfig
import tech.xvanturing.musicdav.data.ServerConfigRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigCreateScreen(
    editingConfig: ServerConfig? = null,
    onCancel: () -> Unit,
    onSave: (ServerConfig) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(editingConfig?.name ?: "") }
    val urls = remember {
        mutableStateListOf<String>().apply {
            addAll(editingConfig?.urls?.takeIf { it.isNotEmpty() } ?: listOf(""))
        }
    }
    var username by remember { mutableStateOf(editingConfig?.username ?: "") }
    var password by remember { mutableStateOf(editingConfig?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val isEditing = editingConfig != null

    // 拦截返回键
    BackHandler {
        onCancel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Server Config" else "Create Server Config") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; errorMessage = null },
                label = { Text("Configuration Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                text = "支持配置多个地址（如内网IP + 公网域名），连接时按顺序自动探测并使用第一个可用的地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            urls.forEachIndexed { index, address ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { urls[index] = it; errorMessage = null },
                        label = { Text(if (index == 0) "WebDAV URL" else "备用地址") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(
                        onClick = { urls.removeAt(index) },
                        enabled = urls.size > 1
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "删除地址")
                    }
                }
            }
            TextButton(onClick = { urls.add("") }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("添加备用地址")
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; errorMessage = null },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                }
            )
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val cleanedUrls = urls.map { it.trim() }.filter { it.isNotBlank() }
                        if (name.isBlank() || cleanedUrls.isEmpty() || username.isBlank() || password.isBlank()) {
                            errorMessage = "Please fill in all fields"
                            return@Button
                        }

                        val config = if (isEditing) {
                            // 编辑模式：更新现有配置
                            editingConfig!!.copy(
                                name = name,
                                urls = cleanedUrls,
                                username = username,
                                password = password
                            ).also { updated ->
                                ServerConfigRepository.update(context, updated)
                            }
                        } else {
                            // 创建模式：创建新配置
                            ServerConfig(
                                id = java.util.UUID.randomUUID().toString(),
                                name = name,
                                urls = cleanedUrls,
                                username = username,
                                password = password
                            ).also { new ->
                                ServerConfigRepository.add(context, new)
                            }
                        }

                        onSave(config)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }
}