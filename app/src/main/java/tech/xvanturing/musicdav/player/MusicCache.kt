package tech.xvanturing.musicdav.player

import android.content.Context
import android.util.Log
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.data.cacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.xvanturing.musicdav.util.AppLog
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
    private const val MAX_CACHE_BYTES = 20L * 1024 * 1024 * 1024 // 2GB

    // 下载中的临时后缀。只有整段下完并核对过长度才改名成最终文件，播放侧因此永远看不到半截文件
    private const val PART_SUFFIX = ".part"

    private val downloadMutex = Mutex()
    private val activeDownloads = mutableSetOf<String>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    /**
     * 清掉上次进程被杀时留下的 .part 残片。启动时扫一遍即可，不影响正在进行的下载
     * （下载中的那份也叫 .part，但那时进程还活着、本函数只在冷启动跑一次）。
     */
    suspend fun cleanupPartFiles(context: Context) = withContext(Dispatchers.IO) {
        try {
            val stale = getCacheDir(context).listFiles { f -> f.name.endsWith(PART_SUFFIX) } ?: return@withContext
            if (stale.isEmpty()) return@withContext
            stale.forEach { it.delete() }
            AppLog.i("MusicCache", "清理了 ${stale.size} 个上次未完成的下载残片")
        } catch (e: Exception) {
            AppLog.w("MusicCache", "清理下载残片失败", e)
        }
    }

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
        val fetchUrl = musicFile.url
        val key = musicFile.cacheKey

        downloadMutex.withLock {
            if (activeDownloads.contains(key)) {
                Log.d("MusicCache", "Song already being cached: ${musicFile.name}")
                return@withContext Result.failure(IOException("Song is already being cached"))
            }
            activeDownloads.add(key)
        }

        // 下载先落到 .part，成功校验后才改名成最终文件（见 PART_SUFFIX 说明）
        var partFile: File? = null
        try {
            val cacheDir = getCacheDir(context)
            val fileName = sha256(key) + getFileExtension(fetchUrl)
            val cacheFile = File(cacheDir, fileName)

            val existing = validatedCacheFile(context, key, cacheFile)
            if (existing != null) {
                Log.d("MusicCache", "Song already cached: ${musicFile.name}")
                CacheRepository.updateLastAccessTime(context, key)
                return@withContext Result.success(existing.absolutePath)
            }

            val cacheSize = getCurrentCacheSize(context)
            val availableSpace = MAX_CACHE_BYTES - cacheSize

            if (cacheSize >= MAX_CACHE_BYTES || cacheFile.length() > availableSpace) {
                Log.d("MusicCache", "Cache full, running cleanup before caching: ${musicFile.name}")
                ensureCacheSize(context, targetSize = (MAX_CACHE_BYTES * 0.8).toLong())
            }

            val credentials = Credentials.basic(config.username, config.password)
            val request = Request.Builder()
                .url(fetchUrl)
                .header("Authorization", credentials)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Unexpected response code: ${response.code}")
            }

            val totalBytes = response.body.contentLength()
            val inputStream = response.body.byteStream()

            val part = File(cacheDir, fileName + PART_SUFFIX)
            partFile = part
            if (part.exists()) part.delete()

            var downloadedBytes = 0L
            var lastProgress = -1

            inputStream.use { input ->
                part.outputStream().use { output ->
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

            // 服务器给了长度就核对一遍：连接被中途掐断时 read 会正常返回 -1，
            // 光看"循环结束了"根本分不清是下完了还是断了
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw IOException("下载不完整：$downloadedBytes/$totalBytes 字节")
            }

            if (cacheFile.exists()) cacheFile.delete()
            if (!part.renameTo(cacheFile)) {
                throw IOException("缓存文件改名失败：${part.name} -> ${cacheFile.name}")
            }
            partFile = null

            val metadata = CacheMetadata(
                url = key,
                fileName = fileName,
                fileSize = cacheFile.length(),
                lastAccessTime = System.currentTimeMillis(),
                cacheTime = System.currentTimeMillis()
            )
            CacheRepository.saveMetadata(context, key, metadata)

            AppLog.i("MusicCache", "缓存完成 ${musicFile.name} ${cacheFile.length()} 字节")
            Result.success(cacheFile.absolutePath)
        } catch (e: Exception) {
            AppLog.e("MusicCache", "缓存失败 ${musicFile.name}", e)
            Result.failure(e)
        } finally {
            // 失败/取消都要把半截的 .part 清掉，否则会一直占着缓存空间
            partFile?.let { if (it.exists()) it.delete() }
            downloadMutex.withLock {
                activeDownloads.remove(key)
            }
        }
    }

    
    suspend fun getCachedPath(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        val cacheDir = getCacheDir(context)
        val fileName = sha256(url) + getFileExtension(url)
        val cacheFile = File(cacheDir, fileName)

        val valid = validatedCacheFile(context, url, cacheFile)
        if (valid != null) {
            CacheRepository.updateLastAccessTime(context, url)
            Log.d("MusicCache", "Cache hit for URL: ${url.takeLast(30)}")
            return@withContext valid.absolutePath
        }

        Log.d("MusicCache", "Cache miss for URL: ${url.takeLast(30)}")
        null
    }

    /**
     * 判定一个缓存文件是否**完整可播**，不完整就地清理掉并返回 null（回落网络）。
     *
     * 老实现把下载流直接写在最终路径上，中途断网/进程被杀就会留下一个"看起来已经缓存好"的半截
     * 文件；之后每次播这首歌都会读它，表现为播到一半突然结束或直接解码失败——而且因为走的是本地
     * 文件、连网络错误都不会报，就是"没有任何异常提示的播放失败"。判据是元数据里记的大小：
     * 元数据只在下载**成功**后才写，所以"有文件没元数据"或"大小对不上"一律视为残缺。
     */
    private fun validatedCacheFile(context: Context, key: String, cacheFile: File): File? {
        if (!cacheFile.exists()) return null
        val metadata = CacheRepository.getMetadata(context, key)
        val actualSize = cacheFile.length()
        if (metadata == null || metadata.fileSize <= 0L || metadata.fileSize != actualSize) {
            AppLog.w(
                "MusicCache",
                "缓存文件不完整，丢弃：${cacheFile.name} 实际=$actualSize 记录=${metadata?.fileSize}"
            )
            cacheFile.delete()
            CacheRepository.removeMetadata(context, key)
            return null
        }
        return cacheFile
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
