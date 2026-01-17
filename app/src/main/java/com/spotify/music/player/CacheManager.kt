package com.spotify.music.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spotify.music.data.MusicFile
import com.spotify.music.data.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class CacheManagerState(
    val totalSize: Long = 0L,
    val cachedSongs: List<CacheMetadata> = emptyList(),
    val isCaching: Boolean = false,
    val cachingProgress: Map<String, Int> = emptyMap(),
    val cachingStatus: String? = null
)

class CacheManager {
    private val _state = mutableStateOf(CacheManagerState())
    val state: CacheManagerState get() = _state.value
    
    suspend fun refreshCacheState(context: android.content.Context) {
        _state.value = _state.value.copy(
            totalSize = MusicCache.getCurrentCacheSize(context),
            cachedSongs = MusicCache.getCachedSongs(context)
        )
    }
    
    fun cacheSong(
        scope: CoroutineScope,
        context: android.content.Context,
        musicFile: MusicFile,
        config: WebDavConfig,
        onSuccess: (String) -> Unit = {},
        onFailure: (Throwable) -> Unit = {}
    ) {
        scope.launch {
            _state.value = _state.value.copy(
                isCaching = true,
                cachingStatus = "Caching: ${musicFile.name}"
            )
            
            MusicCache.cacheSong(context, musicFile, config) { progress ->
                _state.value = _state.value.copy(
                    cachingProgress = mapOf(musicFile.url to progress)
                )
            }
                .onSuccess { path ->
                    onSuccess(path)
                    refreshCacheState(context)
                }
                .onFailure { error ->
                    onFailure(error)
                }
            
            _state.value = _state.value.copy(
                isCaching = false,
                cachingProgress = emptyMap(),
                cachingStatus = null
            )
        }
    }
    
    fun cacheAlbum(
        scope: CoroutineScope,
        context: android.content.Context,
        musicFiles: List<MusicFile>,
        config: WebDavConfig,
        onSuccess: (List<String>) -> Unit = {},
        onFailure: (Throwable) -> Unit = {}
    ) {
        scope.launch {
            _state.value = _state.value.copy(
                isCaching = true,
                cachingStatus = "Caching ${musicFiles.size} songs..."
            )
            
            MusicCache.cacheAlbum(context, musicFiles, config) { current, total ->
                _state.value = _state.value.copy(
                    cachingStatus = "Caching: $current/$total"
                )
            }
                .onSuccess { paths ->
                    onSuccess(paths)
                    refreshCacheState(context)
                }
                .onFailure { error ->
                    onFailure(error)
                }
            
            _state.value = _state.value.copy(
                isCaching = false,
                cachingStatus = null
            )
        }
    }
    
    suspend fun clearCache(context: android.content.Context): Result<Unit> {
        val result = MusicCache.clearCache(context)
        if (result.isSuccess) {
            refreshCacheState(context)
        }
        return result
    }
    
    suspend fun removeCachedSong(context: android.content.Context, url: String): Result<Unit> {
        val result = MusicCache.removeCachedSong(context, url)
        if (result.isSuccess) {
            refreshCacheState(context)
        }
        return result
    }
    
    suspend fun isCached(context: android.content.Context, url: String): Boolean {
        return MusicCache.isCached(context, url)
    }
    
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
