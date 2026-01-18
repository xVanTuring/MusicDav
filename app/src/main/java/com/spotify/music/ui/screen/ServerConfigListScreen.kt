package com.spotify.music.ui.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
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
import com.spotify.music.data.AlbumsRepository
import com.spotify.music.data.ConfigExportManager
import com.spotify.music.data.ImportResult
import com.spotify.music.data.ImportStrategy
import com.spotify.music.data.ServerConfig
import com.spotify.music.data.ServerConfigRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerConfigListScreen(
    onCreate: () -> Unit,
    onEdit: (ServerConfig) -> Unit = {},
    refreshKey: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var configs by remember { mutableStateOf(ServerConfigRepository.load(context)) }
    var selectedConfigForDelete by remember { mutableStateOf<ServerConfig?>(null) }
    var showImportStrategyDialog by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showMenuDialog by remember { mutableStateOf(false) }

    // 文件选择器launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            showImportStrategyDialog = true
        }
    }

    // 文件保存launcher
    val fileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                isExporting = true
                val result = ConfigExportManager.exportConfigs(context, it)
                isExporting = false
                result.onSuccess { message ->
                    val configs = ServerConfigRepository.load(context)
                    val albums = AlbumsRepository.load(context)
                    exportMessage = "导出成功！\n服务器配置: ${configs.size} 个\n专辑: ${albums.size} 个"
                    showExportDialog = true
                }.onFailure { e ->
                    exportMessage = "导出失败: ${e.message}"
                    showExportDialog = true
                }
            }
        }
    }

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
                    IconButton(
                        onClick = { showMenuDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu"
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
                                    text = config.url,
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

            if (showMenuDialog) {
                AlertDialog(
                    onDismissRequest = { showMenuDialog = false },
                    title = { Text("导入/导出") },
                    text = {
                        Column {
                            Button(
                                onClick = {
                                    showMenuDialog = false
                                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    val fileName = "musicdav_config_$timestamp.json"
                                    fileSaveLauncher.launch(fileName)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("导出配置")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    showMenuDialog = false
                                    filePickerLauncher.launch("application/json")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("导入配置")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showMenuDialog = false }) {
                            Text("取消")
                        }
                    }
                )
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

            if (showImportStrategyDialog) {
                AlertDialog(
                    onDismissRequest = { 
                        showImportStrategyDialog = false
                        selectedFileUri = null
                    },
                    title = { Text("导入策略") },
                    text = { 
                        Column {
                            Text("请选择导入方式：")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("• 覆盖：删除现有配置，导入新配置", style = MaterialTheme.typography.bodySmall)
                            Text("• 合并：保留现有配置，添加新配置", style = MaterialTheme.typography.bodySmall)
                            Text("• 更新：同名配置更新，其他保留", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    selectedFileUri?.let { uri ->
                                        coroutineScope.launch {
                                            isImporting = true
                                            val result = ConfigExportManager.importConfigs(context, uri, ImportStrategy.OVERWRITE)
                                            isImporting = false
                                            result.onSuccess {
                                                importResult = it
                                                showImportStrategyDialog = false
                                                configs = ServerConfigRepository.load(context)
                                                showResultDialog = true
                                            }.onFailure { e ->
                                                exportMessage = "导入失败: ${e.message}"
                                                showImportStrategyDialog = false
                                                showExportDialog = true
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("覆盖")
                            }
                            Button(
                                onClick = {
                                    selectedFileUri?.let { uri ->
                                        coroutineScope.launch {
                                            isImporting = true
                                            val result = ConfigExportManager.importConfigs(context, uri, ImportStrategy.MERGE)
                                            isImporting = false
                                            result.onSuccess {
                                                importResult = it
                                                showImportStrategyDialog = false
                                                configs = ServerConfigRepository.load(context)
                                                showResultDialog = true
                                            }.onFailure { e ->
                                                exportMessage = "导入失败: ${e.message}"
                                                showImportStrategyDialog = false
                                                showExportDialog = true
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("合并")
                            }
                            Button(
                                onClick = {
                                    selectedFileUri?.let { uri ->
                                        coroutineScope.launch {
                                            isImporting = true
                                            val result = ConfigExportManager.importConfigs(context, uri, ImportStrategy.UPDATE)
                                            isImporting = false
                                            result.onSuccess {
                                                importResult = it
                                                showImportStrategyDialog = false
                                                configs = ServerConfigRepository.load(context)
                                                showResultDialog = true
                                            }.onFailure { e ->
                                                exportMessage = "导入失败: ${e.message}"
                                                showImportStrategyDialog = false
                                                showExportDialog = true
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("更新")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { 
                            showImportStrategyDialog = false
                            selectedFileUri = null
                        }) {
                            Text("取消")
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