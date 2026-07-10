package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import android.widget.Toast
import kotlinx.coroutines.launch
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.data.resolvePlayable
import tech.xvanturing.musicdav.player.CacheManager
import tech.xvanturing.musicdav.player.PlaylistStateController
import tech.xvanturing.musicdav.ui.BottomPlayerBar
import tech.xvanturing.musicdav.ui.MusicListScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    playlistController: PlaylistStateController,
    modifier: Modifier = Modifier,
    onOpenNowPlaying: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cacheManager = remember { CacheManager(context) }

    var songs by remember { mutableStateOf<List<MusicFile>>(emptyList()) }
    var songConfigs by remember { mutableStateOf<Map<String, WebDavConfig>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    LaunchedEffect(refreshKey) {
        isLoading = true
        val favorites = FavoritesRepository.load(context)
        val resolved = favorites.mapNotNull { it.resolvePlayable(context) }
        songs = resolved.map { it.first }
        songConfigs = resolved.associate { it.first.url to it.second }
        playlistController.setSongConfigs(songConfigs)
        playlistController.loadCachedCovers(context, songs)
        isLoading = false
    }

    fun refresh() {
        refreshKey++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "收藏夹",
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(),
                        softWrap = false
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
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
