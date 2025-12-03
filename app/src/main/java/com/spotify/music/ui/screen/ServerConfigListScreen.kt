package com.spotify.music.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spotify.music.data.ServerConfig
import com.spotify.music.data.ServerConfigRepository

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerConfigListScreen(
    onCreate: () -> Unit,
    refreshKey: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var configs by remember { mutableStateOf(ServerConfigRepository.load(context)) }
    var selectedConfigForDelete by remember { mutableStateOf<ServerConfig?>(null) }

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
                            horizontalArrangement = Arrangement.SpaceBetween
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
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = "Server Configuration",
                                tint = MaterialTheme.colorScheme.primary
                            )
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
        }
    }
}