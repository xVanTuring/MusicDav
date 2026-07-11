package tech.xvanturing.musicdav.ui.screen


import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.ImportResult
import tech.xvanturing.musicdav.data.ServerConfig
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.ui.components.AppTopBar
import tech.xvanturing.musicdav.ui.theme.StatusOnline
import tech.xvanturing.musicdav.webdav.WebDavClient

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerConfigListScreen(
    onCreate: () -> Unit,
    onEdit: (ServerConfig) -> Unit = {},
    refreshKey: Int = 0,
    onImportSuccess: () -> Unit = {},
    modifier: Modifier = Modifier,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
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
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.server_list_title),
                actions = {
                    IconButton(onClick = onCreate) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.server_add)
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
            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.server_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp + bottomInset
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(configs, key = { it.id }) { config ->
                        ServerConfigCard(
                            config = config,
                            onLongClick = { selectedConfigForDelete = config },
                            onEdit = { onEdit(config) }
                        )
                    }
                }
            }


            selectedConfigForDelete?.let { config ->
                AlertDialog(
                    onDismissRequest = { selectedConfigForDelete = null },
                    title = { Text(stringResource(R.string.server_delete_title)) },
                    text = { Text(stringResource(R.string.server_delete_message, config.name)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                ServerConfigRepository.delete(context, config.id)
                                configs = ServerConfigRepository.load(context)
                                selectedConfigForDelete = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { selectedConfigForDelete = null }
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }


            if (showExportDialog) {
                AlertDialog(
                    onDismissRequest = { showExportDialog = false },
                    title = { Text(stringResource(R.string.ie_hint_title)) },
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
                            Text(stringResource(R.string.action_confirm))
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
                        title = { Text(stringResource(R.string.ie_result_title)) },
                        text = {
                            Column {
                                Text(
                                    stringResource(R.string.ie_result_done),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (result.importedServerConfigs > 0) {
                                    Text(
                                        stringResource(
                                            R.string.ie_result_imported_configs,
                                            result.importedServerConfigs
                                        )
                                    )
                                }
                                if (result.updatedServerConfigs > 0) {
                                    Text(
                                        stringResource(
                                            R.string.ie_result_updated_configs,
                                            result.updatedServerConfigs
                                        )
                                    )
                                }
                                if (result.importedAlbums > 0) {
                                    Text(
                                        stringResource(
                                            R.string.ie_result_imported_albums,
                                            result.importedAlbums
                                        )
                                    )
                                }
                                if (result.skippedServerConfigs > 0) {
                                    Text(
                                        stringResource(
                                            R.string.ie_result_skipped_configs,
                                            result.skippedServerConfigs
                                        )
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showResultDialog = false
                                importResult = null
                            }) {
                                Text(stringResource(R.string.action_confirm))
                            }
                        }
                    )
                }
            }
        }
    }
}

// Local availability state for a single server-config card. Kept per-card (see ServerConfigCard)
// so every card tests independently and re-tapping re-runs the probe.
private sealed interface AvailabilityState {
    data object Idle : AvailabilityState
    data object Testing : AvailabilityState
    data class Available(val addressIndex: Int) : AvailabilityState // which url in config.urls responded
    data object Unavailable : AvailabilityState
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerConfigCard(
    config: ServerConfig,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val webDavClient = remember { WebDavClient() }
    var availability by remember { mutableStateOf<AvailabilityState>(AvailabilityState.Idle) }

    fun testAvailability() {
        if (availability is AvailabilityState.Testing) return
        availability = AvailabilityState.Testing
        coroutineScope.launch {
            var result: AvailabilityState = AvailabilityState.Unavailable
            for ((index, address) in config.urls.withIndex()) {
                if (address.isBlank()) continue
                val testResult = webDavClient.testConnection(
                    WebDavConfig(address, config.username, config.password)
                )
                if (testResult.getOrDefault(false)) {
                    result = AvailabilityState.Available(index)
                    break
                }
            }
            availability = result
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { testAvailability() },
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = config.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (config.urls.size > 1) {
                        stringResource(
                            R.string.server_address_with_backup,
                            config.url,
                            config.urls.size - 1
                        )
                    } else {
                        config.url
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.server_user_label, config.username),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                ServerAvailabilityRow(availability = availability)
            }
            IconButton(
                onClick = onEdit
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.action_edit),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// Compact status row shown under a server card's text column. Reflects the card's local
// AvailabilityState and animates between states with a Crossfade.
@Composable
private fun ServerAvailabilityRow(
    availability: AvailabilityState,
    modifier: Modifier = Modifier
) {
    Crossfade(targetState = availability, label = "server-availability", modifier = modifier) { state ->
        when (state) {
            AvailabilityState.Idle -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.server_tap_to_check),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            AvailabilityState.Testing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.server_checking),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is AvailabilityState.Available -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.server_available),
                    tint = StatusOnline,
                    modifier = Modifier.size(14.dp)
                )
                val addressLabel = if (state.addressIndex == 0) {
                    stringResource(R.string.server_addr_primary)
                } else {
                    stringResource(R.string.server_addr_backup, state.addressIndex)
                }
                Text(
                    text = "${stringResource(R.string.server_available)} · $addressLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusOnline
                )
            }

            AvailabilityState.Unavailable -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = stringResource(R.string.server_unavailable),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.server_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
