package com.spotify.music

import android.os.Bundle
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
import com.spotify.music.ui.screen.AlbumListScreen
import com.spotify.music.ui.screen.MusicPlayerScreen
import com.spotify.music.ui.theme.MusicDavTheme

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
    var albums by remember { mutableStateOf(com.spotify.music.data.AlbumsRepository.load(context)) }
    var selectedAlbum by remember { mutableStateOf<com.spotify.music.data.Album?>(null) }
    
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
        AlbumListScreen(
            albums = albums,
            onSelect = { selectedAlbum = it },
            onCreate = { album, serverConfigId ->
                val updated = albums + album
                albums = updated
                com.spotify.music.data.AlbumsRepository.save(context, updated)
                selectedAlbum = album
            },
            onDelete = { album ->
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
            modifier = modifier
        )
    } else {
        MusicPlayerScreen(
            album = selectedAlbum!!,
            onBack = { selectedAlbum = null },
            modifier = modifier
        )
    }
}


