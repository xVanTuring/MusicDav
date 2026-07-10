package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.launch
import tech.xvanturing.musicdav.data.Album
import tech.xvanturing.musicdav.data.AlbumsRepository
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.PlaylistCache
import tech.xvanturing.musicdav.data.ServerAddressResolver
import tech.xvanturing.musicdav.data.ServerConfigRepository
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.data.resolveAlbumUrl
import tech.xvanturing.musicdav.player.CacheManager
import tech.xvanturing.musicdav.player.PlaylistStateController
import tech.xvanturing.musicdav.ui.BottomPlayerBar
import tech.xvanturing.musicdav.ui.MusicListScreen
import tech.xvanturing.musicdav.webdav.WebDavClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    playlistController: PlaylistStateController,
    modifier: Modifier = Modifier,
    onOpenNowPlaying: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cacheManager = remember { CacheManager(context) }
    val focusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var isIndexing by remember { mutableStateOf(true) }
    // 已解析出可播放地址的全部曲目（跨专辑/服务器），本地过滤时直接从这里搜
    var indexedSongs by remember { mutableStateOf<List<Pair<MusicFile, Album>>>(emptyList()) }
    var songConfigMap by remember { mutableStateOf<Map<String, WebDavConfig>>(emptyMap()) }

    BackHandler { onBack() }

    // 遍历所有专辑本地已缓存的歌曲列表建立搜索索引，不发起任何网络请求
    LaunchedEffect(Unit) {
        val albums = AlbumsRepository.load(context)
        val serverConfigs = ServerConfigRepository.load(context)
        val webDavClient = WebDavClient()
        val configCache = mutableMapOf<String, WebDavConfig>() // key: serverConfigId or album.id
        val configMap = mutableMapOf<String, WebDavConfig>() // key: song.url
        val entries = mutableListOf<Pair<MusicFile, Album>>()
        val allResolvedSongs = mutableListOf<MusicFile>()

        for (album in albums) {
            val cachedSongs = PlaylistCache.load(context, album.id)
            if (cachedSongs.isEmpty()) continue

            val configKey = album.serverConfigId ?: album.id
            val config = configCache.getOrPut(configKey) {
                if (album.serverConfigId != null) {
                    val server = serverConfigs.find { it.id == album.serverConfigId }
                    if (server != null) {
                        val baseUrl = ServerAddressResolver.resolve(server)
                        server.toWebDavConfig(baseUrl)
                    } else {
                        album.config
                    }
                } else {
                    album.config
                }
            }

            // serverConfigId 关联的曲目重建一次完整地址，避免用到过期的绝对URL
            val resolvedSongs = cachedSongs.map { song ->
                if (song.serverConfigId != null) {
                    song.copy(url = webDavClient.buildFullUrl(config.url, song.path))
                } else {
                    song
                }
            }

            resolvedSongs.forEach { song ->
                entries.add(song to album)
                configMap[song.url] = config
            }
            allResolvedSongs.addAll(resolvedSongs)

            val albumCoverUrl = resolveAlbumUrl(album.coverImageUrl, config.url)
            playlistController.setSongAlbumCovers(resolvedSongs, albumCoverUrl)
        }

        indexedSongs = entries
        songConfigMap = configMap
        playlistController.setSongConfigs(configMap)
        playlistController.loadCachedCovers(context, allResolvedSongs)
        isIndexing = false
    }

    val filtered = remember(query, indexedSongs) {
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            indexedSongs.filter { (song, _) ->
                song.displayName.contains(q, ignoreCase = true) ||
                    song.name.contains(q, ignoreCase = true) ||
                    (song.artist?.contains(q, ignoreCase = true) == true)
            }.map { it.first }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("搜索歌曲名 / 艺术家") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清除")
                                }
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (query.isBlank()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                    Text(
                        text = if (isIndexing) "正在建立搜索索引…" else "输入关键字搜索全部专辑里的歌曲",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } else {
                MusicListScreen(
                    musicFiles = filtered,
                    isLoading = false,
                    errorMessage = null,
                    currentPlayingSong = playlistController.state.currentSong,
                    onSongSelected = { index, _ ->
                        coroutineScope.launch {
                            playlistController.loadPlaylist(filtered)
                            playlistController.setSongConfigs(songConfigMap)
                            playlistController.setCurrentWebDavConfig(songConfigMap[filtered[index].url])
                            playlistController.loadCachedCovers(context, filtered)
                            playlistController.setPlaylistAndPlay(index)
                        }
                    },
                    enableFavorite = true,
                    isFavorite = { musicFile -> FavoritesRepository.isFavorite(context, musicFile) },
                    onToggleFavorite = { musicFile ->
                        val config = songConfigMap[musicFile.url]
                        FavoritesRepository.toggle(
                            context,
                            musicFile,
                            if (musicFile.serverConfigId == null) config else null
                        )
                    },
                    enableCache = true,
                    onCacheRequest = { musicFile ->
                        val config = songConfigMap[musicFile.url] ?: return@MusicListScreen
                        cacheManager.cacheSong(
                            scope = coroutineScope,
                            context = context,
                            musicFile = musicFile,
                            config = config,
                            onSuccess = {
                                Toast.makeText(context, "Cached: ${musicFile.name}", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(context, "Failed to cache: ${error.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    bottomBar = {
                        BottomPlayerBar(
                            playlistState = playlistController.state,
                            onPlayPause = {
                                if (playlistController.state.isPlaying) {
                                    playlistController.pause()
                                } else {
                                    playlistController.play()
                                }
                            },
                            onNext = { playlistController.seekToNext() },
                            onPrevious = { playlistController.seekToPrevious() },
                            onTogglePlayMode = { playlistController.togglePlayMode() },
                            onOpenNowPlaying = onOpenNowPlaying
                        )
                    },
                    cacheManager = cacheManager,
                    playlistController = playlistController
                )
            }
        }
    }
}
