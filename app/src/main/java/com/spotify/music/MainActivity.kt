package com.spotify.music

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.spotify.music.player.rememberNotificationPermissionState
import com.spotify.music.player.rememberPlaylistStateController
import com.spotify.music.ui.screen.AlbumListScreen
import com.spotify.music.ui.screen.AlbumDetailScreen
import com.spotify.music.ui.screen.ServerConfigListScreen
import com.spotify.music.ui.screen.ServerConfigCreateScreen
import com.spotify.music.ui.screen.AlbumCreateForm
import com.spotify.music.ui.theme.MusicDavTheme
import com.spotify.music.ui.BottomPlayerBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.spotify.music.data.getWebDavConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 打印已保存的配置信息
        printConfigurations()

        setContent {
            MusicDavTheme {
                MusicPlayerApp(modifier = Modifier.fillMaxSize())
            }
        }
    }

    private fun printConfigurations() {
        try {
            // 打印服务器配置
            val serverConfigs = com.spotify.music.data.ServerConfigRepository.load(this)
            Log.d("MusicDav", "=== 已保存的服务器配置 ===")
            if (serverConfigs.isEmpty()) {
                Log.d("MusicDav", "暂无服务器配置")
            } else {
                serverConfigs.forEach { config ->
                    Log.d("MusicDav", "服务器配置: ${config.name} (ID: ${config.id})")
                    Log.d("MusicDav", "  URL: ${config.url}")
                    Log.d("MusicDav", "  用户名: ${config.username}")
                }
            }

            // 打印专辑配置
            val albums = com.spotify.music.data.AlbumsRepository.load(this)
            Log.d("MusicDav", "=== 已保存的专辑配置 ===")
            if (albums.isEmpty()) {
                Log.d("MusicDav", "暂无专辑配置")
            } else {
                albums.forEach { album ->
                    val webDavConfig = album.getWebDavConfig(this)
                    Log.d("MusicDav", "专辑: ${album.name}")
                    if (album.serverConfigId != null) {
                        val serverConfig = com.spotify.music.data.ServerConfigRepository.getById(this, album.serverConfigId!!)
                        Log.d("MusicDav", "  引用服务器配置: ${serverConfig?.name} (ID: ${album.serverConfigId})")
                    }
                    Log.d("MusicDav", "  WebDAV URL: ${webDavConfig.url}")
                    Log.d("MusicDav", "  用户名: ${webDavConfig.username}")
                    Log.d("MusicDav", "  目录URL: ${album.directoryUrl ?: "根目录"}")
                    Log.d("MusicDav", "  封面图片: ${album.coverImageUrl ?: "无"}")
                }
            }
            Log.d("MusicDav", "====================")
        } catch (e: Exception) {
            Log.e("MusicDav", "打印配置信息时出错: ${e.message}", e)
        }
    }
}

@Composable
fun MusicPlayerApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var albums by remember { mutableStateOf(com.spotify.music.data.AlbumsRepository.load(context)) }
    var selectedAlbum by remember { mutableStateOf<com.spotify.music.data.Album?>(null) }
    val playlistController = rememberPlaylistStateController()

    // 处理通知权限
    rememberNotificationPermissionState()

    // 在主页面的返回键处理：双击退出
    var lastBackPressTime by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = selectedAlbum == null) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            // 两次点击间隔小于2秒，退出应用
            (context as? android.app.Activity)?.finish()
        } else {
            // 第一次点击，显示提示
            lastBackPressTime = currentTime
            android.widget.Toast.makeText(context, "再按一次退出应用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    if (selectedAlbum == null) {
        MainTabScreen(
            albums = albums,
            onSelectAlbum = { selectedAlbum = it },
            onCreateAlbum = { album, serverConfigId ->
                val updated = albums + album
                albums = updated
                com.spotify.music.data.AlbumsRepository.save(context, updated)
                // 不自动导航到专辑详情，保持在列表页面
            },
            onDeleteAlbum = { album ->
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
            playlistController = playlistController,
            modifier = modifier
        )
    } else {
        AlbumDetailScreen(
            album = selectedAlbum!!,
            onBack = { selectedAlbum = null },
            playlistController = playlistController,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabScreen(
    albums: List<com.spotify.music.data.Album>,
    onSelectAlbum: (com.spotify.music.data.Album) -> Unit,
    onCreateAlbum: (com.spotify.music.data.Album, String?) -> Unit,
    onDeleteAlbum: (com.spotify.music.data.Album) -> Unit,
    playlistController: com.spotify.music.player.PlaylistStateController,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    var creatingAlbum by remember { mutableStateOf(false) }
    var creatingServerConfig by remember { mutableStateOf(false) }
    var serverConfigsRefreshKey by remember { mutableStateOf(0) }
    val context = LocalContext.current

    // 拦截返回键处理创建状态
    androidx.activity.compose.BackHandler(enabled = creatingAlbum || creatingServerConfig) {
        if (creatingAlbum) {
            creatingAlbum = false
        } else if (creatingServerConfig) {
            creatingServerConfig = false
        }
    }

    // 如果正在创建专辑或服务器配置，显示相应的创建屏幕
    if (creatingAlbum) {
        AlbumCreateForm(
            onCancel = { creatingAlbum = false },
            onSave = { name, url, username, password, directoryUrl, coverImageUrl, serverConfigId ->
                val config = com.spotify.music.data.WebDavConfig(url = url, username = username, password = password)
                val album = com.spotify.music.data.Album(
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

    if (creatingServerConfig) {
        ServerConfigCreateScreen(
            onCancel = { creatingServerConfig = false },
            onSave = { config ->
                // 服务器配置已保存，返回到专辑创建界面
                creatingServerConfig = false
                creatingAlbum = true
                serverConfigsRefreshKey++
            }
        )
        return
    }

    // 主标签页布局
    androidx.compose.material3.Scaffold(
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
                                imageVector = Icons.Default.Storage,
                                contentDescription = "Server Configs"
                            )
                        },
                        label = { Text("Servers") }
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
                    // 专辑列表标签页
                    AlbumListScreen(
                        albums = albums,
                        onSelect = onSelectAlbum,
                        onCreate = { album, serverConfigId ->
                            // 这里不应该被调用，因为AlbumListScreen的添加按钮会触发creatingAlbum状态
                            // 但为了安全，保留这个处理
                            onCreateAlbum(album, serverConfigId)
                        },
                        onDelete = onDeleteAlbum,
                        onAddButtonClick = { creatingAlbum = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                1 -> {
                    // 服务器配置列表标签页
                    ServerConfigListScreen(
                        onCreate = { creatingServerConfig = true },
                        refreshKey = serverConfigsRefreshKey,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}