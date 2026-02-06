package tech.xvanturing.musicdav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import tech.xvanturing.musicdav.player.rememberNotificationPermissionState
import tech.xvanturing.musicdav.player.rememberPlaylistStateController
import tech.xvanturing.musicdav.ui.BottomPlayerBar
import tech.xvanturing.musicdav.ui.screen.AlbumCreateForm
import tech.xvanturing.musicdav.ui.screen.AlbumDetailScreen
import tech.xvanturing.musicdav.ui.screen.AlbumListScreen
import tech.xvanturing.musicdav.ui.screen.CacheManagementScreen
import tech.xvanturing.musicdav.ui.screen.ServerConfigCreateScreen
import tech.xvanturing.musicdav.ui.screen.ServerConfigListScreen
import tech.xvanturing.musicdav.ui.theme.MusicDavTheme


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
    var albums by remember {
        mutableStateOf(
            tech.xvanturing.musicdav.data.AlbumsRepository.load(
                context
            )
        )
    }
    var selectedAlbum by remember { mutableStateOf<tech.xvanturing.musicdav.data.Album?>(null) }
    var editingAlbum by remember { mutableStateOf<tech.xvanturing.musicdav.data.Album?>(null) }
    val playlistController = rememberPlaylistStateController()

    // 处理通知权限
    rememberNotificationPermissionState()

    // 在主页面的返回键处理：双击退出
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = selectedAlbum == null && editingAlbum == null) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            // 两次点击间隔小于2秒，退出应用
            (context as? android.app.Activity)?.finish()
        } else {
            // 第一次点击，显示提示
            lastBackPressTime = currentTime
            android.widget.Toast.makeText(
                context,
                "再按一次退出应用",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    if (editingAlbum != null) {
        // Show edit form
        AlbumCreateForm(
            onCancel = { editingAlbum = null },
            onSave = { name, url, username, password, directoryUrl, coverImageUrl, serverConfigId ->
                val config = tech.xvanturing.musicdav.data.WebDavConfig(
                    url = url,
                    username = username,
                    password = password
                )
                val updatedAlbum = editingAlbum!!.copy(
                    name = name,
                    config = config,
                    directoryUrl = directoryUrl,
                    coverImageUrl = coverImageUrl,
                    serverConfigId = serverConfigId
                )
                // Update the album in the list
                val updatedAlbums = albums.map { if (it == editingAlbum) updatedAlbum else it }
                albums = updatedAlbums
                tech.xvanturing.musicdav.data.AlbumsRepository.save(context, updatedAlbums)
                // Update selected album if it's the same one
                if (selectedAlbum == editingAlbum) {
                    selectedAlbum = updatedAlbum
                }
                editingAlbum = null
            },
            editingAlbum = editingAlbum
        )
    } else if (selectedAlbum == null) {
        MainTabScreen(
            albums = albums,
            onRefreshAlbums = {
                albums = tech.xvanturing.musicdav.data.AlbumsRepository.load(context)
            },
            onSelectAlbum = { selectedAlbum = it },
            onCreateAlbum = { album, serverConfigId ->
                val updated = albums + album
                albums = updated
                tech.xvanturing.musicdav.data.AlbumsRepository.save(context, updated)
                // 不自动导航到专辑详情，保持在列表页面
            },
            onDeleteAlbum = { album ->
                val updated = albums.filterNot {
                    val itConfig = if (it.serverConfigId != null) {
                        tech.xvanturing.musicdav.data.ServerConfigRepository.load(context)
                            .find { config -> config.id == it.serverConfigId }
                            ?.toWebDavConfig() ?: it.config
                    } else {
                        it.config
                    }
                    val albumConfig = if (album.serverConfigId != null) {
                        tech.xvanturing.musicdav.data.ServerConfigRepository.load(context)
                            .find { config -> config.id == album.serverConfigId }
                            ?.toWebDavConfig() ?: album.config
                    } else {
                        album.config
                    }
                    it.name == album.name && itConfig.url == albumConfig.url
                }
                albums = updated
                tech.xvanturing.musicdav.data.AlbumsRepository.save(context, updated)
            },
            playlistController = playlistController,
            modifier = modifier
        )
    } else {
        AlbumDetailScreen(
            album = selectedAlbum!!,
            onBack = { selectedAlbum = null },
            onEdit = { album -> editingAlbum = album },
            playlistController = playlistController,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabScreen(
    albums: List<tech.xvanturing.musicdav.data.Album>,
    onRefreshAlbums: () -> Unit,
    onSelectAlbum: (tech.xvanturing.musicdav.data.Album) -> Unit,
    onCreateAlbum: (tech.xvanturing.musicdav.data.Album, String?) -> Unit,
    onDeleteAlbum: (tech.xvanturing.musicdav.data.Album) -> Unit,
    playlistController: tech.xvanturing.musicdav.player.PlaylistStateController,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var creatingAlbum by remember { mutableStateOf(false) }
    var creatingServerConfig by remember { mutableStateOf(false) }
    var editingServerConfig by remember {
        mutableStateOf<tech.xvanturing.musicdav.data.ServerConfig?>(
            null
        )
    }
    var serverConfigsRefreshKey by remember { mutableIntStateOf(0) }

    // 拦截返回键处理创建状态
    androidx.activity.compose.BackHandler(enabled = creatingAlbum || creatingServerConfig || editingServerConfig != null) {
        if (creatingAlbum) {
            creatingAlbum = false
        } else if (creatingServerConfig) {
            creatingServerConfig = false
        } else if (editingServerConfig != null) {
            editingServerConfig = null
        }
    }

    // 如果正在创建专辑或服务器配置，显示相应的创建屏幕
    if (creatingAlbum) {
        AlbumCreateForm(
            onCancel = { creatingAlbum = false },
            onSave = { name, url, username, password, directoryUrl, coverImageUrl, serverConfigId ->
                val config = tech.xvanturing.musicdav.data.WebDavConfig(
                    url = url,
                    username = username,
                    password = password
                )
                val album = tech.xvanturing.musicdav.data.Album(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    config = config,
                    directoryUrl = directoryUrl,
                    coverImageUrl = coverImageUrl,
                    serverConfigId = serverConfigId
                )
                onCreateAlbum(album, serverConfigId)
                creatingAlbum = false
            },
            onCreateServerConfig = {
                creatingAlbum = false
                creatingServerConfig = true
            }
        )
        return
    }

    if (creatingServerConfig || editingServerConfig != null) {
        ServerConfigCreateScreen(
            editingConfig = editingServerConfig,
            onCancel = {
                creatingServerConfig = false
                editingServerConfig = null
            },
            onSave = { config ->
                // 服务器配置已保存
                creatingServerConfig = false
                editingServerConfig = null
                serverConfigsRefreshKey++
            }
        )
        return
    }

    // 主标签页布局
    androidx.compose.material3.Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                // 播放控件在标签栏上方
                BottomPlayerBar(
                    playlistState = playlistController.state,
                    onPlayPause = {
                        if (playlistController.state.isPlaying) {
                            playlistController.pause()
                        } else {
                            playlistController.play()
                        }
                    },
                    onNext = {
                        playlistController.seekToNext()
                    },
                    onPrevious = {
                        playlistController.seekToPrevious()
                    },
                    onTogglePlayMode = {
                        playlistController.togglePlayMode()
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 底部导航栏
                NavigationBar(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    NavigationBarItem(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = "Albums"
                            )
                        },
                        label = { Text("Albums") }
                    )
                    NavigationBarItem(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Server Configs"
                            )
                        },
                        label = { Text("Servers") }
                    )
                    NavigationBarItem(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = "Cache"
                            )
                        },
                        label = { Text("Cache") }
                    )
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTabIndex) {
                0 -> {
                    AlbumListScreen(
                        albums = albums,
                        onSelect = onSelectAlbum,
                        onCreate = { album, serverConfigId ->
                            onCreateAlbum(album, serverConfigId)
                        },
                        onDelete = onDeleteAlbum,
                        onAddButtonClick = { creatingAlbum = true },
                        onImportSuccess = { onRefreshAlbums() },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                1 -> {
                    ServerConfigListScreen(
                        onCreate = { creatingServerConfig = true },
                        onEdit = { config -> editingServerConfig = config },
                        refreshKey = serverConfigsRefreshKey,
                        onImportSuccess = { onRefreshAlbums() },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                2 -> {
                    CacheManagementScreen(
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}