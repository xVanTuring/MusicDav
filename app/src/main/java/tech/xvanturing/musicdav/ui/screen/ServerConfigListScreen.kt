package tech.xvanturing.musicdav.ui.screen


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tech.xvanturing.musicdav.data.ImportResult
import tech.xvanturing.musicdav.data.ServerConfig
import tech.xvanturing.musicdav.data.ServerConfigRepository

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerConfigListScreen(
    onCreate: () -> Unit,
    onEdit: (ServerConfig) -> Unit = {},
    refreshKey: Int = 0,
    onImportSuccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var configs by remember { mutableStateOf(ServerConfigRepository.load(context)) }
    var selectedConfigForDelete by remember { mutableStateOf<ServerConfig?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }



    // 当refreshKey变化时重新加载配置列表
    LaunchedEffect(refreshKey) {
        configs = ServerConfigRepository.load(context)
    }

    // 拦截返回键，如果在创建页面则返回列表页面
    BackHandler(enabled = false) {
        // 这里可以处理返回键逻辑，如果需要的话
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Configurations") },
                actions = {
                    IconButton(onClick = onCreate) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Server Config"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top
            ) {
                configs.forEach { config ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .combinedClickable(
                                onClick = {
                                    // 可以选择配置，暂时没有点击操作
                                },
                                onLongClick = { selectedConfigForDelete = config }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
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
                                    text = if (config.urls.size > 1) {
                                        "${config.url} (+${config.urls.size - 1} 个备用地址)"
                                    } else {
                                        config.url
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "User: ${config.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onEdit(config) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Server Configuration",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }


            selectedConfigForDelete?.let { config ->
                AlertDialog(
                    onDismissRequest = { selectedConfigForDelete = null },
                    title = { Text("Delete Server Configuration") },
                    text = { Text("Are you sure you want to delete server configuration \"${config.name}\"?") },
                    confirmButton = {
                        Button(
                            onClick = {
                                ServerConfigRepository.delete(context, config.id)
                                configs = ServerConfigRepository.load(context)
                                selectedConfigForDelete = null
                            }
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { selectedConfigForDelete = null }
                        ) {
                            Text("Cancel")
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