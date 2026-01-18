package com.spotify.music.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.spotify.music.CacheTaskStatus
import com.spotify.music.MusicCacheService
import com.spotify.music.data.MusicFile
import com.spotify.music.data.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class AlbumCacheProgress(
    val totalSongs: Int,
    val completedSongs: Int,
    val currentSong: String?
)

data class CacheManagerState(
    val totalSize: Long = 0L,
    val cachedSongs: List<CacheMetadata> = emptyList(),
    val isCaching: Boolean = false,
    val cachingProgress: Map<String, Int> = emptyMap(),
    val cachingStatus: String? = null,
    val albumCachingProgress: Map<String, AlbumCacheProgress> = emptyMap()
)

class CacheManager(private val context: Context) {

    private var cacheService: MusicCacheService? = null
    private var isBound = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private val _state = mutableStateOf(CacheManagerState())
    val state: CacheManagerState get() = _state.value

    private val urlToAlbumId = mutableMapOf<String, String>()
    private val albumProgress = mutableMapOf<String, AlbumCacheProgress>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicCacheService.LocalBinder
            cacheService = binder.getService()
            cacheService?.addListener(taskListener)
            isBound = true
            Log.d("CacheManager", "MusicCacheService connected")

            serviceScope.launch {
                refreshCacheState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            isBound = false
            Log.d("CacheManager", "MusicCacheService disconnected")
        }
    }

    private val taskListener = object : MusicCacheService.CacheTaskListener {
        override fun onTaskStarted(taskId: String, musicFile: MusicFile) {
            Log.d("CacheManager", "Task started: ${musicFile.name}")
            
            val albumId = urlToAlbumId[musicFile.url]
            if (albumId != null) {
                val currentProgress = albumProgress[albumId]
                if (currentProgress != null) {
                    albumProgress[albumId] = currentProgress.copy(
                        currentSong = musicFile.name
                    )
                    _state.value = _state.value.copy(
                        albumCachingProgress = _state.value.albumCachingProgress + (albumId to albumProgress[albumId]!!)
                    )
                }
            }
        }

        override fun onTaskProgress(taskId: String, progress: Int) {
            _state.value = _state.value.copy(
                isCaching = true,
                cachingProgress = mapOf(taskId to progress)
            )
        }

        override fun onTaskCompleted(taskId: String, path: String?) {
            Log.d("CacheManager", "Task completed: $taskId")
            
            val task = cacheService?.getTask(taskId)
            val musicFileUrl = task?.musicFile?.url
            val albumId = musicFileUrl?.let { urlToAlbumId[it] }
            
            if (albumId != null) {
                val currentProgress = albumProgress[albumId]
                if (currentProgress != null) {
                    val newProgress = currentProgress.copy(
                        completedSongs = currentProgress.completedSongs + 1
                    )
                    albumProgress[albumId] = newProgress
                    _state.value = _state.value.copy(
                        albumCachingProgress = _state.value.albumCachingProgress + (albumId to newProgress)
                    )
                    
                    if (newProgress.completedSongs >= newProgress.totalSongs) {
                        albumProgress.remove(albumId)
                        urlToAlbumId.keys.removeAll { urlToAlbumId[it] == albumId }
                        _state.value = _state.value.copy(
                            albumCachingProgress = _state.value.albumCachingProgress - albumId
                        )
                    }
                }
            }
            
            serviceScope.launch {
                refreshCacheState()
            }
        }

        override fun onTaskFailed(taskId: String, error: Throwable) {
            Log.e("CacheManager", "Task failed: $taskId", error)
            serviceScope.launch {
                refreshCacheState()
            }
        }

        override fun onAllTasksCompleted() {
            _state.value = _state.value.copy(
                isCaching = false,
                cachingProgress = emptyMap(),
                cachingStatus = null,
                albumCachingProgress = emptyMap()
            )
        }
    }

    init {
        serviceScope.launch {
            bind()
        }
    }

    fun bind() {
        if (!isBound) {
            val intent = Intent(context, MusicCacheService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d("CacheManager", "Binding to MusicCacheService")
        }
    }

    fun unbind() {
        if (isBound) {
            cacheService?.removeListener(taskListener)
            context.unbindService(serviceConnection)
            isBound = false
            Log.d("CacheManager", "Unbinding from MusicCacheService")
        }
    }

    suspend fun refreshCacheState() {
        cacheService?.let { service ->
            val cachedSongs = service.getCachedSongs()
            val totalSize = service.getTotalCacheSize()
            val activeTasks = service.getActiveTasks()

            _state.value = _state.value.copy(
                totalSize = totalSize,
                cachedSongs = cachedSongs,
                isCaching = activeTasks.any { it.status == CacheTaskStatus.DOWNLOADING },
                cachingProgress = activeTasks
                    .filter { it.status == CacheTaskStatus.DOWNLOADING }
                    .associate { it.id to it.progress }
            )
        }
    }

    fun cacheSong(
        scope: CoroutineScope,
        context: Context,
        musicFile: MusicFile,
        config: WebDavConfig,
        onSuccess: (String) -> Unit = {},
        onFailure: (Throwable) -> Unit = {}
    ) {
        scope.launch {
            Log.d("CacheManager", "🎵 Requesting cache for: ${musicFile.name}")
            MusicCacheService.startCaching(context, musicFile, config)
        }
    }

    fun cacheAlbum(
        scope: CoroutineScope,
        context: Context,
        musicFiles: List<MusicFile>,
        config: WebDavConfig,
        albumId: String,
        onSuccess: (Int) -> Unit = {},
        onFailure: (Throwable) -> Unit = {}
    ) {
        scope.launch {
            val albumKey = albumId
            
            musicFiles.forEach { musicFile ->
                urlToAlbumId[musicFile.url] = albumKey
            }

            albumProgress[albumKey] = AlbumCacheProgress(
                totalSongs = musicFiles.size,
                completedSongs = 0,
                currentSong = null
            )
            
            _state.value = _state.value.copy(
                isCaching = true,
                cachingStatus = "Caching ${musicFiles.size} songs...",
                albumCachingProgress = _state.value.albumCachingProgress + (albumKey to AlbumCacheProgress(
                    totalSongs = musicFiles.size,
                    completedSongs = 0,
                    currentSong = null
                ))
            )

            for (musicFile in musicFiles) {
                MusicCacheService.startCaching(context, musicFile, config)
            }

            onSuccess(musicFiles.size)
        }
    }
 
    suspend fun clearCache(context: Context): Result<Unit> {
        val result = MusicCache.clearCache(context)
        if (result.isSuccess) {
            serviceScope.launch {
                refreshCacheState()
            }
        }
        return result
    }

    suspend fun removeCachedSong(context: Context, url: String): Result<Unit> {
        val result = MusicCache.removeCachedSong(context, url)
        if (result.isSuccess) {
            serviceScope.launch {
                refreshCacheState()
            }
        }
        return result
    }

    suspend fun isCached(context: Context, url: String): Boolean {
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

    fun getAlbumCacheProgress(albumId: String): AlbumCacheProgress? {
        return _state.value.albumCachingProgress[albumId]
    }

    protected fun finalize() {
        if (isBound) {
            try {
                unbind()
            } catch (e: Exception) {
                Log.e("CacheManager", "Error unbinding service", e)
            }
        }
    }
}
