package tech.xvanturing.musicdav.player

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
            Log.w("CachedDataSource", "Cached file not found: $cachedPath, falling back to network")
            return openNetwork(dataSpec)
        }

        try {
            fileInputStream = FileInputStream(file)
            val skipped = fileInputStream?.skip(dataSpec.position) ?: 0

            bytesRemaining = if (dataSpec.length == -1L) {
                file.length() - dataSpec.position
            } else {
                dataSpec.length
            }

            Log.d("CachedDataSource", "Opened cache file: ${file.name}, remaining: $bytesRemaining bytes")
            return bytesRemaining
        } catch (e: IOException) {
            Log.e("CachedDataSource", "Failed to open cache file: $cachedPath", e)
            fileInputStream?.close()
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
            val length = httpDataSource!!.open(dataSpec)
            bytesRemaining = if (dataSpec.length == -1L) {
                length
            } else {
                dataSpec.length
            }
            Log.d("CachedDataSource", "Opened network source: ${dataSpec.uri}, length: $length")
            return length
        } catch (e: IOException) {
            Log.e("CachedDataSource", "Failed to open network source: ${dataSpec.uri}", e)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (bytesRemaining == 0L) {
            return -1
        }

        val bytesToRead = if (bytesRemaining < readLength) {
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
            bytesRemaining -= bytesRead.toLong()
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
            Log.e("CachedDataSource", "Error closing file input stream", e)
        }

        try {
            httpDataSource?.close()
            httpDataSource = null
        } catch (e: IOException) {
            Log.e("CachedDataSource", "Error closing http data source", e)
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
}
