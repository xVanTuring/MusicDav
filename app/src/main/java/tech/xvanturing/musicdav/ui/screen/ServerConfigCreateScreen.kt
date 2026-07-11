package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.coroutines.launch
import tech.xvanturing.musicdav.data.ServerConfig
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.webdav.WebDavClient

private enum class ConnTestStatus { TESTING, SUCCESS, FAILURE }
private data class UrlTestState(val status: ConnTestStatus, val message: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigCreateScreen(
    editingConfig: ServerConfig? = null,
    onCancel: () -> Unit,
    onSave: (ServerConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val webDavClient = remember { WebDavClient() }

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
    var isTesting by remember { mutableStateOf(false) }
    val testStates = remember { mutableStateMapOf<Int, UrlTestState>() }

    val isEditing = editingConfig != null

    BackHandler {
        onCancel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "编辑服务器配置" else "新建服务器配置") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                val cleanedUrls = urls.map { it.trim() }.filter { it.isNotBlank() }
                                if (name.isBlank() || cleanedUrls.isEmpty() || username.isBlank() || password.isBlank()) {
                                    errorMessage = "请填写所有必填项"
                                    return@Button
                                }

                                val config = if (isEditing) {
                                    editingConfig!!.copy(
                                        name = name,
                                        urls = cleanedUrls,
                                        username = username,
                                        password = password
                                    ).also { updated ->
                                        ServerConfigRepository.update(context, updated)
                                    }
                                } else {
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
                            Text("保存")
                        }
                    }
                }
            }
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
            ConfigSection(title = "基本信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMessage = null },
                    label = { Text("配置名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            ConfigSection(title = "服务器地址") {
                Text(
                    text = "支持配置多个地址（如内网IP + 公网域名），连接时按顺序自动探测并使用第一个可用的地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                urls.forEachIndexed { index, address ->
                    val testState = testStates[index]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { urls[index] = it; errorMessage = null; testStates.remove(index) },
                            label = { Text(if (index == 0) "主地址" else "备用地址 $index") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            isError = testState?.status == ConnTestStatus.FAILURE,
                            trailingIcon = {
                                when (testState?.status) {
                                    ConnTestStatus.TESTING -> CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    ConnTestStatus.SUCCESS -> Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "连接成功",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    ConnTestStatus.FAILURE -> Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = "连接失败",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    null -> {}
                                }
                            },
                            supportingText = {
                                if (testState?.status == ConnTestStatus.FAILURE && testState.message != null) {
                                    Text(
                                        text = testState.message,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                        IconButton(
                            onClick = { urls.removeAt(index); testStates.clear() },
                            enabled = urls.size > 1
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "删除地址")
                        }
                    }
                }
                TextButton(onClick = { urls.add("") }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" 添加备用地址")
                }
            }

            ConfigSection(title = "登录凭据") {
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
            }

            OutlinedButton(
                onClick = {
                    val candidates = urls.mapIndexed { index, url -> index to url.trim() }
                        .filter { it.second.isNotBlank() }
                    if (candidates.isEmpty() || username.isBlank() || password.isBlank()) {
                        errorMessage = "请先填写地址、用户名和密码后再测试连接"
                        return@OutlinedButton
                    }
                    errorMessage = null
                    isTesting = true
                    candidates.forEach { (index, _) -> testStates[index] = UrlTestState(ConnTestStatus.TESTING) }
                    coroutineScope.launch {
                        for ((index, url) in candidates) {
                            val result = webDavClient.testConnection(WebDavConfig(url, username, password))
                            testStates[index] = result.fold(
                                onSuccess = { UrlTestState(ConnTestStatus.SUCCESS) },
                                onFailure = { e -> UrlTestState(ConnTestStatus.FAILURE, e.message ?: "无法连接") }
                            )
                        }
                        isTesting = false
                    }
                },
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(if (isTesting) " 正在测试连接..." else " 测试连接")
            }
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
