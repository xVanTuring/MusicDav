package tech.xvanturing.musicdav.player

import android.content.Context
import android.util.Log
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class CacheMetadata(
    val url: String,
    val fileName: String,
    val fileSize: Long,
    val lastAccessTime: Long,
    val cacheTime: Long
)

object MusicCache {
    private const val CACHE_DIR = "music_cache"
    private const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB
    private const val MAX_CONCURRENT_DOWNLOADS = 3
    
    private val downloadMutex = Mutex()
    private val activeDownloads = mutableSetOf<String>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    fun getCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    suspend fun cacheSong(
        context: Context,
        musicFile: MusicFile,
        config: WebDavConfig,
        onProgress: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val url = musicFile.url
        
        downloadMutex.withLock {
            if (activeDownloads.contains(url)) {
                Log.d("MusicCache", "Song already being cached: ${musicFile.name}")
                return@withContext Result.failure(IOException("Song is already being cached"))
            }
            activeDownloads.add(url)
        }
        
        try {
            val cacheDir = getCacheDir(context)
            val fileName = sha256(url) + getFileExtension(url)
            val cacheFile = File(cacheDir, fileName)
            
            if (cacheFile.exists()) {
                Log.d("MusicCache", "Song already cached: ${musicFile.name}")
                CacheRepository.updateLastAccessTime(context, url)
                return@withContext Result.success(cacheFile.absolutePath)
            }
            
            val cacheSize = getCurrentCacheSize(context)
            val availableSpace = MAX_CACHE_BYTES - cacheSize
            
            if (cacheSize >= MAX_CACHE_BYTES || cacheFile.length() > availableSpace) {
                Log.d("MusicCache", "Cache full, running cleanup before caching: ${musicFile.name}")
                ensureCacheSize(context, targetSize = (MAX_CACHE_BYTES * 0.8).toLong())
            }
            
            val credentials = Credentials.basic(config.username, config.password)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", credentials)
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Unexpected response code: ${response.code}")
            }
            
            val totalBytes = response.body?.contentLength() ?: -1L
            val inputStream = response.body?.byteStream() ?: throw IOException("Response body is null")
            
            var downloadedBytes = 0L
            var lastProgress = -1
            
            inputStream.use { input ->
                cacheFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (progress != lastProgress) {
                                onProgress(progress)
                                lastProgress = progress
                            }
                        }
                    }
                }
            }
            
            val metadata = CacheMetadata(
                url = url,
                fileName = fileName,
                fileSize = cacheFile.length(),
                lastAccessTime = System.currentTimeMillis(),
                cacheTime = System.currentTimeMillis()
            )
            CacheRepository.saveMetadata(context, url, metadata)
            
            Log.d("MusicCache", "Song cached successfully: ${musicFile.name}, size: ${cacheFile.length()}")
            Result.success(cacheFile.absolutePath)
        } catch (e: Exception) {
            Log.e("MusicCache", "Failed to cache song: ${musicFile.name}", e)
            Result.failure(e)
        } finally {
            downloadMutex.withLock {
                activeDownloads.remove(url)
            }
        }
    }
    
    suspend fun cacheAlbum(
        context: Context,
        musicFiles: List<MusicFile>,
        config: WebDavConfig,
        onProgress: (Int, Int) -> Unit = { current, total -> }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val cachedPaths = mutableListOf<String>()
        var cachedCount = 0
        
        for ((index, musicFile) in musicFiles.withIndex()) {
            onProgress(index + 1, musicFiles.size)
            
            val result = cacheSong(context, musicFile, config) { progress ->
            }
            
            result.onSuccess { path ->
                cachedPaths.add(path)
                cachedCount++
            }.onFailure {
                Log.w("MusicCache", "Failed to cache: ${musicFile.name}")
            }
        }
        
        Result.success(cachedPaths)
    }
    
    suspend fun getCachedPath(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        val cacheDir = getCacheDir(context)
        val fileName = sha256(url) + getFileExtension(url)
        val cacheFile = File(cacheDir, fileName)
        
        if (cacheFile.exists()) {
            CacheRepository.updateLastAccessTime(context, url)
            Log.d("MusicCache", "Cache hit for URL: ${url.takeLast(30)}")
            return@withContext cacheFile.absolutePath
        }
        
        Log.d("MusicCache", "Cache miss for URL: ${url.takeLast(30)}")
        null
    }
    
    suspend fun isCached(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        getCachedPath(context, url) != null
    }
    
    suspend fun getCurrentCacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        CacheRepository.getTotalCacheSize(context)
    }
    
    suspend fun getCachedSongs(context: Context): List<CacheMetadata> = withContext(Dispatchers.IO) {
        CacheRepository.getAllMetadata(context)
    }
    
    suspend fun clearCache(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getCacheDir(context)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
            }
            CacheRepository.clearMetadata(context)
            Log.d("MusicCache", "Cache cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("MusicCache", "Failed to clear cache", e)
            Result.failure(e)
        }
    }
    
    suspend fun removeCachedSong(context: Context, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getCacheDir(context)
            val fileName = sha256(url) + getFileExtension(url)
            val cacheFile = File(cacheDir, fileName)
            
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            CacheRepository.removeMetadata(context, url)
            Log.d("MusicCache", "Removed cached song: ${url.takeLast(30)}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("MusicCache", "Failed to remove cached song", e)
            Result.failure(e)
        }
    }
    
    suspend fun ensureCacheSize(context: Context, targetSize: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var currentSize = getCurrentCacheSize(context)
            if (currentSize <= targetSize) {
                return@withContext Result.success(Unit)
            }
            
            val allMetadata = CacheRepository.getAllMetadata(context)
            val sortedByLRU = allMetadata.sortedBy { it.lastAccessTime }
            
            for (metadata in sortedByLRU) {
                if (currentSize <= targetSize) break
                
                removeCachedSong(context, metadata.url)
                currentSize -= metadata.fileSize
            }
            
            Log.d("MusicCache", "Cache cleaned to ${currentSize / (1024 * 1024)}MB")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("MusicCache", "Failed to ensure cache size", e)
            Result.failure(e)
        }
    }
    
    private fun getFileExtension(url: String): String {
        val lastDot = url.lastIndexOf('.')
        return if (lastDot != -1) {
            url.substring(lastDot)
        } else {
            ""
        }
    }
    
    private fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("MusicCache", "SHA-256 algorithm not available", e)
            input.hashCode().toString()
        }
    }
}
