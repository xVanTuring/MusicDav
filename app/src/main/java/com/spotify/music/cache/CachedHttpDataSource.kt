package com.spotify.music.cache

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.spotify.music.SimpleMusicService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 支持缓存的HttpDataSource
 * transparently caches music files for faster playback
 */
class CachedHttpDataSource(
    private val upstreamFactory: HttpDataSource.Factory,
    private val cacheManager: MusicCacheManager
) : HttpDataSource {

    private var dataSpec: DataSpec? = null
    private var upstream: HttpDataSource? = null
    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = 0
    private var bytesTransferred: Long = 0
    private var isClosed = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.upstream = upstreamFactory.createDataSource()

        val url = dataSpec.uri.toString()

        // 尝试从缓存读取
        val cachedFile = cacheManager.getCachedFile(url)

        return if (cachedFile != null && cachedFile.exists()) {
            // 从缓存读取
            openCachedFile(cachedFile, dataSpec)
        } else {
            // 从网络读取并缓存
            openFromNetwork(dataSpec)
        }
    }

    private fun openCachedFile(file: java.io.File, dataSpec: DataSpec): Long {
        val fileInputStream = java.io.FileInputStream(file)
        inputStream = fileInputStream

        // 处理范围请求
        val fileLength = file.length()
        val position = if (dataSpec.position >= 0) dataSpec.position else 0

        if (position > 0) {
            fileInputStream.skip(position)
        }

        bytesRemaining = if (dataSpec.length != Long.MAX_VALUE) {
            dataSpec.length
        } else {
            fileLength - position
        }

        bytesTransferred = 0
        return bytesRemaining
    }

    private fun openFromNetwork(dataSpec: DataSpec): Long {
        // 打开上游数据源
        val upstreamLength = upstream!!.open(dataSpec)

        // 创建流式缓存输入流
        val totalSize = if (upstreamLength != Long.MAX_VALUE) upstreamLength else null
        inputStream = StreamingCachingInputStream(
            upstream!!,
            dataSpec.uri.toString(),
            cacheManager,
            totalSize
        )

        bytesRemaining = upstreamLength
        bytesTransferred = 0
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isClosed) {
            throw IOException("DataSource is closed")
        }

        val bytesRead = inputStream?.read(buffer, offset, length) ?: -1

        if (bytesRead > 0) {
            bytesTransferred += bytesRead
            if (bytesRemaining != Long.MAX_VALUE) {
                bytesRemaining -= bytesRead
            }
        }

        return bytesRead
    }

    override fun getUri() = dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return upstream?.responseHeaders ?: emptyMap()
    }

    override fun getResponseCode(): Int {
        return upstream?.getResponseCode() ?: -1
    }

    override fun setRequestProperty(name: String, value: String) {
        upstream?.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        upstream?.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        upstream?.clearAllRequestProperties()
    }

    override fun close() {
        if (!isClosed) {
            inputStream?.close()
            upstream?.close()
            isClosed = true
        }
    }

    /**
     * 流式缓存输入流包装器
     * 边读取上游数据边写入缓存文件，实现真正的流式缓存
     */
    private class StreamingCachingInputStream(
        private val upstream: HttpDataSource,
        private val url: String,
        private val cacheManager: MusicCacheManager,
        private val totalSize: Long? // 总大小，可能未知
    ) : InputStream() {

        private var cacheOutput: java.io.FileOutputStream? = null
        private var isCachingEnabled = true
        private var upstreamClosed = false
        private val cacheFile = java.io.File(cacheManager.cacheDir, cacheManager.getCacheKey(url))
        private var bytesReadTotal: Long = 0L
        private var lastProgressUpdateTime: Long = 0L
        private val progressUpdateThreshold = 64 * 1024L // 每64KB更新一次进度

        init {
            try {
                // 创建缓存文件
                cacheOutput = java.io.FileOutputStream(cacheFile)
                // 开始缓存任务
                cacheManager.startCaching(url, totalSize = totalSize)
            } catch (e: Exception) {
                // 缓存失败，但不影响播放
                isCachingEnabled = false
                // 清理可能创建的空文件
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
                // 标记缓存失败
                cacheManager.failCaching(url)
            }
        }

        override fun read(): Int {
            val singleByte = ByteArray(1)
            val result = read(singleByte, 0, 1)
            return if (result == -1) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (upstreamClosed) return -1

            val bytesRead = upstream.read(b, off, len)

            if (bytesRead > 0) {
                // 更新总读取字节数
                bytesReadTotal += bytesRead

                // 更新缓存进度（避免过于频繁）
                val currentTime = System.currentTimeMillis()
                if (bytesReadTotal >= progressUpdateThreshold &&
                    (currentTime - lastProgressUpdateTime > 500 || bytesReadTotal % progressUpdateThreshold == 0L)) {
                    cacheManager.updateCachingProgress(url, bytesReadTotal, totalSize)
                    lastProgressUpdateTime = currentTime
                }

                // 同时写入缓存文件
                if (isCachingEnabled && cacheOutput != null) {
                    try {
                        cacheOutput!!.write(b, off, bytesRead)
                        cacheOutput!!.flush() // 确保数据写入磁盘
                    } catch (e: Exception) {
                        // 缓存写入失败，关闭缓存
                        closeCache()
                        isCachingEnabled = false
                        // 删除不完整的缓存文件
                        if (cacheFile.exists()) {
                            cacheFile.delete()
                        }
                        // 标记缓存失败
                        cacheManager.failCaching(url)
                    }
                }
            }

            return bytesRead
        }

        override fun close() {
            if (!upstreamClosed) {
                upstreamClosed = true
                upstream.close()

                // 完成缓存
                closeCache()

                // 更新最终进度
                cacheManager.updateCachingProgress(url, bytesReadTotal, totalSize)

                // 如果缓存成功，记录到缓存管理器
                if (isCachingEnabled && cacheFile.exists()) {
                    val size = cacheFile.length()
                    cacheManager.finishCaching(url, cacheFile, size)
                } else {
                    // 如果缓存失败，删除文件
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                    }
                    cacheManager.failCaching(url)
                }
            }
        }

        private fun closeCache() {
            try {
                cacheOutput?.close()
            } catch (e: Exception) {
                // 忽略关闭错误
            }
            cacheOutput = null
        }
    }

    /**
     * Factory for creating CachedHttpDataSource instances
     */
    class Factory(
        private val upstreamFactory: HttpDataSource.Factory,
        private val cacheManager: MusicCacheManager
    ) : HttpDataSource.Factory {

        override fun createDataSource(): CachedHttpDataSource {
            return CachedHttpDataSource(upstreamFactory, cacheManager)
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): Factory {
            upstreamFactory.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }
    }
}

// 为MusicCacheManager添加getCacheKey方法的扩展
private fun MusicCacheManager.getCacheKey(url: String): String {
    return try {
        val md = java.security.MessageDigest.getInstance("MD5")
        val hash = md.digest(url.toByteArray())
        hash.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        url.hashCode().toString()
    }
}

