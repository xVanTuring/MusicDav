package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.launch
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.data.resolvePlayable
import tech.xvanturing.musicdav.player.CacheManager
import tech.xvanturing.musicdav.player.PlaylistStateController
import tech.xvanturing.musicdav.ui.MusicListScreen
import tech.xvanturing.musicdav.ui.components.AppTopBar
import tech.xvanturing.musicdav.ui.components.sheetTopBarDrag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 已解析收藏列表的进程内缓存：sheet 每次完全开合都会卸载重建本组件、remember 状态清零，没有
// 这层缓存时每次打开都要等 sheet 全开→重新解析→转圈。首次解析后存这里，之后挂载即显示；
// active 后仍会重新解析一遍静默更新（收藏增减/服务器地址变化都能跟上），只是不再转圈。
private object FavoritesSessionCache {
    var songs: List<MusicFile>? = null
    var configs: Map<String, WebDavConfig> = emptyMap()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    playlistController: PlaylistStateController,
    modifier: Modifier = Modifier,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    // 是否"真正打开"：只有 true 时才发起 resolvePlayable 里的服务器地址解析(网络探测)。用来
    // 避免 sheet 上拉一点又松开的瞬时挂载也发起探测请求，与 AlbumDetailScreen 的 active 同一用意。
    active: Boolean = true,
    // 顶栏下拉的手指跟随通道，同 AlbumDetailScreen
    onSheetDrag: ((Float) -> Unit)? = null,
    onSheetDragEnd: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cacheManager = remember { CacheManager(context) }

    // 挂载即用会话缓存填充：上拉过程中就能看到列表，不用等全开、不转圈（首次除外）
    var songs by remember { mutableStateOf(FavoritesSessionCache.songs ?: emptyList()) }
    var songConfigs by remember { mutableStateOf(FavoritesSessionCache.configs) }
    var isLoading by remember { mutableStateOf(FavoritesSessionCache.songs == null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    LaunchedEffect(refreshKey, active) {
        if (!active) return@LaunchedEffect
        // 只有本进程从没解析过（无缓存可显示）才转圈；有缓存时静默刷新
        if (FavoritesSessionCache.songs == null) isLoading = true
        val resolved = withContext(Dispatchers.IO) {
            FavoritesRepository.load(context).mapNotNull { it.resolvePlayable(context) }
        }
        songs = resolved.map { it.first }
        songConfigs = resolved.associate { it.first.url to it.second }
        FavoritesSessionCache.songs = songs
        FavoritesSessionCache.configs = songConfigs
        playlistController.setSongConfigs(songConfigs)
        playlistController.loadCachedCovers(context, songs)
        isLoading = false
    }

    fun refresh() {
        refreshKey++
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier.sheetTopBarDrag(onSheetDrag, onSheetDragEnd) { onBack() }
            ) {
                AppTopBar(
                    title = stringResource(R.string.common_favorites),
                    onBack = onBack,
                    navigationIcon = Icons.Default.KeyboardArrowDown
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = modifier.padding(paddingValues)) {
            MusicListScreen(
                musicFiles = songs,
                isLoading = isLoading,
                errorMessage = null,
                currentPlayingSong = playlistController.state.currentSong,
                onSongSelected = { index, _ ->
                    coroutineScope.launch {
                        val tapped = songs[index]
                        playlistController.loadPlaylist(songs)
                        playlistController.setSongConfigs(songConfigs)
                        playlistController.setCurrentWebDavConfig(songConfigs[tapped.url])
                        playlistController.setCurrentAlbumId(null)
                        playlistController.loadCachedCovers(context, songs)
                        playlistController.setPlaylistAndPlay(index)
                    }
                },
                enableFavorite = true,
                isFavorite = { true },
                onToggleFavorite = { musicFile ->
                    FavoritesRepository.remove(context, musicFile)
                    refresh()
                },
                enableCache = true,
                onCacheRequest = { musicFile ->
                    val config = songConfigs[musicFile.url] ?: return@MusicListScreen
                    cacheManager.cacheSong(
                        scope = coroutineScope,
                        context = context,
                        musicFile = musicFile,
                        config = config,
                        onSuccess = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_cached_song, musicFile.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_cache_song_failed, error.message ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                },
                bottomInset = bottomInset,
                cacheManager = cacheManager,
                playlistController = playlistController
            )
        }
    }
}
