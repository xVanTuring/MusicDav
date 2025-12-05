package com.spotify.music.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 音乐文件缓存管理器
 * 提供透明的音乐文件缓存功能
 */
class MusicCacheManager private constructor(private val context: Context) {

    val cacheDir = File(context.cacheDir, "music")
    private val metadataFile = File(cacheDir, "cache_metadata.json")
    private val maxCacheSizeBytes = 500 * 1024 * 1024L // 500MB 默认缓存大小
    private val currentCacheSize = AtomicLong(0L)
    private val cacheLock = Any()

    // 内存缓存，记录已缓存的文件
    private val cachedFiles = ConcurrentHashMap<String, CacheEntry>()

    data class CacheEntry(
        val file: File,
        val url: String,
        val lastAccessed: Long,
        val size: Long
    )

    init {
        // 确保缓存目录存在
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        // 启动时扫描已有缓存
        scanExistingCache()
    }

    companion object {
        @Volatile
        private var INSTANCE: MusicCacheManager? = null

        fun getInstance(context: Context): MusicCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicCacheManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * 根据URL获取缓存文件
     * @param url 音乐文件的原始URL
     * @return 缓存文件，如果不存在则返回null
     */
    fun getCachedFile(url: String): File? {
        val cacheKey = getCacheKey(url)
        val entry = cachedFiles[cacheKey]

        return if (entry?.file?.exists() == true) {
            // 更新访问时间
            entry.copy(lastAccessed = System.currentTimeMillis()).also { updatedEntry ->
                cachedFiles[cacheKey] = updatedEntry
                // 保存更新的元数据
                saveCacheMetadata()
            }
            entry.file
        } else {
            // 缓存不存在或已删除，从内存中移除
            cachedFiles.remove(cacheKey)
            null
        }
    }

    /**
     * 将字节数组缓存到文件
     * @param url 原始URL
     * @param data 音频数据
     * @return 缓存文件
     */
    suspend fun cacheFile(url: String, data: ByteArray): File = withContext(Dispatchers.IO) {
        val cacheKey = getCacheKey(url)
        val cacheFile = File(cacheDir, cacheKey)

        try {
            val dataSize = data.size.toLong()

            // 检查缓存空间
            ensureCacheSpace(dataSize)

            // 写入缓存
            FileOutputStream(cacheFile).use { output ->
                output.write(data)
            }

            val entry = CacheEntry(
                file = cacheFile,
                url = url,
                lastAccessed = System.currentTimeMillis(),
                size = dataSize
            )

            synchronized(cacheLock) {
                cachedFiles[cacheKey] = entry
                currentCacheSize.addAndGet(dataSize)
            }

            // 保存元数据
            saveCacheMetadata()

            Log.d("MusicCacheManager", "Cached file: $url, size: $dataSize bytes")
            cacheFile

        } catch (e: IOException) {
            Log.e("MusicCacheManager", "Failed to cache file: $url", e)
            // 清理可能的部分文件
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            throw e
        }
    }

    /**
     * 预缓存下一个文件
     */
    suspend fun prefetchNext(url: String, onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        // 检查是否已缓存
        if (getCachedFile(url) != null) {
            return@withContext true
        }

        try {
            // 这里可以添加预下载逻辑
            // 注意：预缓存会增加网络流量，需要谨慎使用
            Log.d("MusicCacheManager", "Prefetching next file: $url")
            true
        } catch (e: Exception) {
            Log.e("MusicCacheManager", "Failed to prefetch: $url", e)
            false
        }
    }

    /**
     * 清理缓存以释放空间
     */
    private fun ensureCacheSpace(requiredSpace: Long) {
        val availableSpace = maxCacheSizeBytes - currentCacheSize.get()

        if (requiredSpace > availableSpace) {
            // 按LRU策略清理缓存
            val sortedEntries = cachedFiles.values.sortedBy { it.lastAccessed }
            var freedSpace = 0L

            for (entry in sortedEntries) {
                if (entry.file.delete()) {
                    synchronized(cacheLock) {
                        val cacheKey = getCacheKey(entry.url)
                        cachedFiles.remove(cacheKey)
                        currentCacheSize.addAndGet(-entry.size)
                    }
                    freedSpace += entry.size
                    Log.d("MusicCacheManager", "Evicted cache: ${entry.url}")

                    if (freedSpace >= requiredSpace - availableSpace) {
                        break
                    }
                }
            }

            // 保存更新后的元数据
            if (freedSpace > 0) {
                saveCacheMetadata()
            }
        }
    }

    /**
     * 扫描现有缓存文件
     */
    private fun scanExistingCache() {
        if (!cacheDir.exists()) return

        // 先加载保存的元数据
        loadCacheMetadata()

        var totalSize = 0L
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != "cache_metadata.json") {
                val cacheKey = file.name
                val entry = cachedFiles[cacheKey]

                if (entry != null) {
                    // 使用已加载的元数据，同时验证文件大小
                    val actualSize = file.length()
                    if (actualSize != entry.size) {
                        // 更新文件大小
                        val updatedEntry = entry.copy(size = actualSize)
                        cachedFiles[cacheKey] = updatedEntry
                    }
                    totalSize += actualSize
                } else {
                    // 如果没有元数据，创建基本条目
                    val fileSize = file.length()
                    if (fileSize > 0) { // 只添加有效文件
                        val newEntry = CacheEntry(
                            file = file,
                            url = "", // URL无法从文件恢复
                            lastAccessed = file.lastModified(),
                            size = fileSize
                        )
                        cachedFiles[cacheKey] = newEntry
                        totalSize += fileSize
                    }
                }
            }
        }

        // 清理不存在的文件条目
        val existingFiles = cacheDir.listFiles()?.filter { it.isFile && it.name != "cache_metadata.json" }?.map { it.name }?.toSet() ?: emptySet()
        cachedFiles.keys.removeAll { it !in existingFiles }

        currentCacheSize.set(totalSize)

        // 保存更新后的元数据
        saveCacheMetadata()

        Log.d("MusicCacheManager", "Scanned existing cache: ${cachedFiles.size} files, $totalSize bytes")
    }

    /**
     * 保存缓存元数据到文件
     */
    private fun saveCacheMetadata() {
        try {
            val jsonArray = JSONArray()
            cachedFiles.values.forEach { entry ->
                val jsonObject = JSONObject().apply {
                    put("cacheKey", getCacheKey(entry.url))
                    put("url", entry.url)
                    put("lastAccessed", entry.lastAccessed)
                    put("size", entry.size)
                }
                jsonArray.put(jsonObject)
            }

            FileOutputStream(metadataFile).use { output ->
                output.write(jsonArray.toString(2).toByteArray())
            }
        } catch (e: Exception) {
            Log.e("MusicCacheManager", "Failed to save cache metadata", e)
        }
    }

    /**
     * 从文件加载缓存元数据
     */
    private fun loadCacheMetadata() {
        if (!metadataFile.exists()) return

        try {
            FileInputStream(metadataFile).use { input ->
                val content = input.readBytes().toString(Charsets.UTF_8)
                val jsonArray = JSONArray(content)

                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val cacheKey = jsonObject.getString("cacheKey")
                    val url = jsonObject.getString("url")
                    val lastAccessed = jsonObject.getLong("lastAccessed")
                    val size = jsonObject.getLong("size")

                    // 检查文件是否存在
                    val file = File(cacheDir, cacheKey)
                    if (file.exists()) {
                        val entry = CacheEntry(
                            file = file,
                            url = url,
                            lastAccessed = lastAccessed,
                            size = size
                        )
                        cachedFiles[cacheKey] = entry
                    }
                }
            }
            Log.d("MusicCacheManager", "Loaded cache metadata: ${cachedFiles.size} entries")
        } catch (e: Exception) {
            Log.e("MusicCacheManager", "Failed to load cache metadata", e)
        }
    }

    /**
     * 生成缓存键
     */
    fun getCacheKey(url: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(url.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // 如果MD5失败，使用URL的hash code
            url.hashCode().toString()
        }
    }

    /**
     * 注册已缓存的文件（用于流式缓存完成后的记录）
     */
    fun registerCachedFile(url: String, file: File, size: Long) {
        val cacheKey = getCacheKey(url)
        val entry = CacheEntry(
            file = file,
            url = url,
            lastAccessed = System.currentTimeMillis(),
            size = size
        )

        synchronized(cacheLock) {
            cachedFiles[cacheKey] = entry
            currentCacheSize.addAndGet(size)
        }

        // 保存元数据
        saveCacheMetadata()

        Log.d("MusicCacheManager", "Registered cached file: $url, size: $size bytes")
    }

    /**
     * 清空所有缓存
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            cacheDir.listFiles()?.forEach { file ->
                if (file.delete()) {
                    currentCacheSize.addAndGet(-file.length())
                }
            }
            cachedFiles.clear()

            // 删除元数据文件
            if (metadataFile.exists()) {
                metadataFile.delete()
            }
        }
        Log.d("MusicCacheManager", "Cache cleared")
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        synchronized(cacheLock) {
            return CacheStats(
                totalFiles = cachedFiles.size,
                totalSize = currentCacheSize.get(),
                maxCacheSize = maxCacheSizeBytes
            )
        }
    }

    /**
     * 获取所有缓存文件的详细信息列表
     */
    fun getCachedFilesList(): List<CachedFileInfo> {
        synchronized(cacheLock) {
            return cachedFiles.values.map { entry ->
                // 尝试从URL中提取文件名
                val fileName = try {
                    if (entry.url.isNotEmpty()) {
                        // 尝试多种方式提取文件名
                        var name = entry.url.substringAfterLast('/').takeIf { it.isNotEmpty() }
                        if (name == null || name.contains('?')) {
                            // 如果包含查询参数，进一步处理
                            name = name?.substringBefore('?') ?: entry.url.substringAfterLast('=').takeIf { it.isNotEmpty() }
                        }
                        if (name != null && name.isNotEmpty()) {
                            // 移除常见的参数后缀
                            name = name.replace(Regex("\\?.*$"), "")
                            name.trim()
                        } else {
                            // 无法从URL提取，使用缓存键的一部分
                            "缓存文件_${entry.file.name.take(8)}"
                        }
                    } else {
                        // URL为空，使用缓存键的一部分
                        "缓存文件_${entry.file.name.take(8)}"
                    }
                } catch (e: Exception) {
                    "缓存文件_${entry.file.name.take(8)}"
                }

                CachedFileInfo(
                    url = entry.url,
                    fileName = fileName,
                    size = entry.size,
                    lastAccessed = entry.lastAccessed,
                    formattedSize = formatFileSize(entry.size)
                )
            }.sortedByDescending { it.lastAccessed } // 按最近访问时间排序
        }
    }

    data class CachedFileInfo(
        val url: String,
        val fileName: String,
        val size: Long,
        val lastAccessed: Long,
        val formattedSize: String
    )

    fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> "%.1f GB".format(gb)
            mb >= 1 -> "%.1f MB".format(mb)
            kb >= 1 -> "%.1f KB".format(kb)
            else -> "$bytes B"
        }
    }

    data class CacheStats(
        val totalFiles: Int,
        val totalSize: Long,
        val maxCacheSize: Long
    ) {
        val usagePercentage: Float
            get() = if (maxCacheSize > 0) (totalSize.toFloat() / maxCacheSize) * 100 else 0f

        val formattedSize: String
            get() = formatFileSize(totalSize)

        val formattedMaxSize: String
            get() = formatFileSize(maxCacheSize)

        private fun formatFileSize(bytes: Long): String {
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0

            return when {
                gb >= 1 -> "%.1f GB".format(gb)
                mb >= 1 -> "%.1f MB".format(mb)
                kb >= 1 -> "%.1f KB".format(kb)
                else -> "$bytes B"
            }
        }
    }
}