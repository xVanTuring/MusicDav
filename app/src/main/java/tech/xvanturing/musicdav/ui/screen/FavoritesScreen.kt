package tech.xvanturing.musicdav.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    playlistController: PlaylistStateController,
    modifier: Modifier = Modifier,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
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
            Box(
                modifier = Modifier.pointerInput(Unit) {
                    var dragY = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragY > 120f) onBack(); dragY = 0f },
                        onVerticalDrag = { change, amount -> dragY += amount; change.consume() }
                    )
                }
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
