package tech.xvanturing.musicdav.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tech.xvanturing.musicdav.util.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import android.net.Uri

@UnstableApi
class CachedDataSource private constructor(
    private val context: Context,
    private val httpDataSourceFactory: HttpDataSource.Factory
) : DataSource {

    private var fileInputStream: FileInputStream? = null
    private var httpDataSource: HttpDataSource? = null
    private var bytesRead: Long = 0
    private var bytesRemaining: Long = 0
    private val listeners = mutableListOf<TransferListener>()
    private var currentDataSpec: DataSpec? = null

    override fun addTransferListener(listener: TransferListener) {
        listeners.add(listener)
        httpDataSource?.addTransferListener(listener)
    }

    override fun open(dataSpec: DataSpec): Long {
        currentDataSpec = dataSpec
        // dataSpec.key carries MediaItem.customCacheKey when set (see PlaylistStateController),
        // which stays stable across server address changes; falls back to the raw URI otherwise.
        val cacheKey = dataSpec.key ?: dataSpec.uri.toString()

        val cachedPath = runBlocking(Dispatchers.IO) {
            MusicCache.getCachedPath(context, cacheKey)
        }

        val result = if (cachedPath != null) {
            openCache(dataSpec, cachedPath)
        } else {
            openNetwork(dataSpec)
        }

        return result
    }

    private fun openCache(dataSpec: DataSpec, cachedPath: String): Long {
        val file = File(cachedPath)
        if (!file.exists()) {
            AppLog.w(TAG, "缓存文件不存在，回落网络: $cachedPath")
            return openNetwork(dataSpec)
        }

        try {
            val stream = FileInputStream(file)
            fileInputStream = stream
            // skip 不保证一次跳够，必须循环补齐；跳不够会读到错位的数据，
            // 表现为 seek 之后一段噪音或直接解码失败
            var toSkip = dataSpec.position
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped <= 0) throw IOException("无法定位到 ${dataSpec.position}（缓存文件已损坏？）")
                toSkip -= skipped
            }

            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                file.length() - dataSpec.position
            } else {
                dataSpec.length
            }

            AppLog.d(TAG, "命中缓存 ${file.name} 剩余 $bytesRemaining 字节")
            return bytesRemaining
        } catch (e: IOException) {
            AppLog.e(TAG, "打开缓存文件失败，回落网络: $cachedPath", e)
            try {
                fileInputStream?.close()
            } catch (_: IOException) {
            }
            fileInputStream = null
            return openNetwork(dataSpec)
        }
    }

    private fun openNetwork(dataSpec: DataSpec): Long {
        try {
            httpDataSource = httpDataSourceFactory.createDataSource()
            for (listener in listeners) {
                httpDataSource?.addTransferListener(listener)
            }
            // 逐请求带上这首歌所属服务器的鉴权头。跨服务器列表（收藏夹/搜索）里 ExoPlayer 会
            // 提前预加载下一首，此时全局默认头还停在上一首那台服务器上，不按 URL 现查就会 401。
            val authorized = WebDavAuthStore.headerFor(dataSpec.uri.toString())
                ?.let { dataSpec.withAdditionalHeaders(mapOf("Authorization" to it)) }
                ?: dataSpec
            val length = httpDataSource!!.open(authorized)
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                length
            } else {
                dataSpec.length
            }
            AppLog.d(TAG, "走网络 ${dataSpec.uri} length=$length pos=${dataSpec.position}")
            return length
        } catch (e: IOException) {
            AppLog.e(TAG, "网络打开失败 ${dataSpec.uri} pos=${dataSpec.position}", e)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (bytesRemaining == 0L) {
            return -1
        }
        if (readLength == 0) {
            return 0
        }

        // 服务器没给 Content-Length 时 bytesRemaining 是 C.LENGTH_UNSET(-1)，
        // 原来会算出 bytesToRead=-1 传给 read()，直接抛 IndexOutOfBounds。
        // 长度未知就照请求长度读，由底层返回 -1 来标记结束。
        val bytesToRead = if (bytesRemaining in 1 until readLength.toLong()) {
            bytesRemaining.toInt()
        } else {
            readLength
        }

        val bytesRead = if (fileInputStream != null) {
            fileInputStream!!.read(buffer, offset, bytesToRead)
        } else {
            httpDataSource?.read(buffer, offset, bytesToRead) ?: -1
        }

        if (bytesRead > 0) {
            this.bytesRead += bytesRead.toLong()
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesRead.toLong()
            }
        }

        return bytesRead
    }

    override fun getUri(): Uri? {
        return currentDataSpec?.uri
    }

    override fun close() {
        try {
            fileInputStream?.close()
            fileInputStream = null
        } catch (e: IOException) {
            AppLog.w(TAG, "关闭缓存文件流失败", e)
        }

        try {
            httpDataSource?.close()
            httpDataSource = null
        } catch (e: IOException) {
            AppLog.w(TAG, "关闭网络数据源失败", e)
        }

        bytesRead = 0
        bytesRemaining = 0
        currentDataSpec = null
    }

    override fun getResponseHeaders(): MutableMap<String, MutableList<String>> {
        return httpDataSource?.responseHeaders?.let { headers ->
            mutableMapOf<String, MutableList<String>>().apply {
                for ((key, value) in headers) {
                    put(key, value.toMutableList())
                }
            }
        } ?: mutableMapOf()
    }

    class Factory(
        private val context: Context,
        private val httpDataSourceFactory: HttpDataSource.Factory
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource {
            return CachedDataSource(context, httpDataSourceFactory)
        }
    }

    private companion object {
        const val TAG = "CachedDataSource"
    }
}
