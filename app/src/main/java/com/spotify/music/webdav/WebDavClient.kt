package com.spotify.music.webdav

import android.graphics.BitmapFactory
import android.util.Log
import com.spotify.music.data.MusicFile
import com.spotify.music.data.WebDavConfig
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class WebDavClient {
    
    private val musicExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "wma")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    
    suspend fun fetchMusicFiles(config: WebDavConfig): Result<List<MusicFile>> = withContext(Dispatchers.IO) {
        try {
            val sardine: Sardine = OkHttpSardine()
            sardine.setCredentials(config.username, config.password)
            
            val normalizedUrl = config.url.trimEnd('/')
            val resources = sardine.list(normalizedUrl)
            
            val musicFiles = resources
                .filter { !it.isDirectory }
                .filter { resource ->
                    val extension = resource.name.substringAfterLast('.', "").lowercase()
                    extension in musicExtensions
                }
                .map { resource ->
                    val fullUrl = buildFullUrl(normalizedUrl, resource.path)
                    MusicFile(
                        name = resource.name,
                        url = fullUrl,
                        path = resource.path,
                        size = resource.contentLength ?: 0L,
                        modifiedDate = resource.modified?.time ?: 0L
                    )
                }
                .sortedBy { it.name }
            
            Result.success(musicFiles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun buildFullUrl(baseUrl: String, resourcePath: String): String {
        return try {
            val url = java.net.URL(baseUrl)
            val protocol = url.protocol
            val host = url.host
            val port = if (url.port != -1) ":${url.port}" else ""
            
            val normalizedPath = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
            
            "$protocol://$host$port$normalizedPath"
        } catch (e: Exception) {
            if (resourcePath.startsWith("http")) {
                resourcePath
            } else {
                val normalizedPath = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
                "$baseUrl$normalizedPath"
            }
        }
    }
    
    fun createAuthenticatedUrl(url: String, username: String, password: String): String {
        return try {
            val urlObj = java.net.URL(url)
            val credentials = "$username:$password"
            val encodedCredentials = android.util.Base64.encodeToString(
                credentials.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            url
        } catch (e: Exception) {
            url
        }
    }
    
    suspend fun testConnection(config: WebDavConfig): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val sardine: Sardine = OkHttpSardine()
            sardine.setCredentials(config.username, config.password)
            
            val normalizedUrl = config.url.trimEnd('/')
            sardine.list(normalizedUrl)
            
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun listDirectories(config: WebDavConfig, basePath: String? = null): Result<List<DavResource>> = withContext(Dispatchers.IO) {
        try {
            val sardine: Sardine = OkHttpSardine()
            sardine.setCredentials(config.username, config.password)
            val baseUrl = (basePath ?: config.url).trimEnd('/')
            val resources = sardine.list(baseUrl)

            // 过滤掉当前目录自身
            val filteredResources = resources.filter { resource ->
                val resourcePath = resource.path.trimEnd('/')
                val baseUrlPath = baseUrl.trimEnd('/')
                resourcePath != baseUrlPath && resource.name != "."
            }

            val directories = filteredResources.filter { it.isDirectory }
            Result.success(directories)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun listAllResources(config: WebDavConfig, basePath: String? = null): Result<Pair<List<DavResource>, List<DavResource>>> = withContext(Dispatchers.IO) {
        try {
            val sardine: Sardine = OkHttpSardine()
            sardine.setCredentials(config.username, config.password)
            val baseUrl = (basePath ?: config.url).trimEnd('/')
            val resources = sardine.list(baseUrl)

            Log.d("WebDavClient", "Base URL: $baseUrl")
            Log.d("WebDavClient", "Total resources: ${resources.size}")
            resources.forEachIndexed { index, resource ->
                Log.d("WebDavClient", "Resource $index: path=${resource.path}, name=${resource.name}, isDir=${resource.isDirectory}")
            }

            // 过滤掉当前目录自身（通常是 . 或与 baseUrl 相同的路径）
            val filteredResources = resources.filter { resource ->
                val resourcePath = resource.path.trimEnd('/')
                val baseUrlPath = baseUrl.trimEnd('/')

                // 检查是否是当前目录本身
                val isCurrentDirectory = (
                    resourcePath == baseUrlPath ||
                    resource.name == "." ||
                    (baseUrl.endsWith('/') && resourcePath == baseUrl.dropLast(1)) ||
                    (!baseUrl.endsWith('/') && resourcePath == baseUrl + "/")
                )

                val shouldKeep = !isCurrentDirectory && resource.name.isNotEmpty()

                if (isCurrentDirectory) {
                    Log.d("WebDavClient", "Filtering out current directory: path=${resource.path}, name=${resource.name}")
                    Log.d("WebDavClient", "Current directory check: resourcePath='$resourcePath', baseUrlPath='$baseUrlPath'")
                }

                shouldKeep
            }

            Log.d("WebDavClient", "Filtered resources: ${filteredResources.size}")

            val directories = filteredResources.filter { it.isDirectory }
            val allResources = filteredResources
            Result.success(Pair(directories, allResources))
        } catch (e: Exception) {
            Log.e("WebDavClient", "Error listing resources", e)
            Result.failure(e)
        }
    }
    
    suspend fun findCoverImageUrl(config: WebDavConfig, directoryUrl: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val sardine: Sardine = OkHttpSardine()
            sardine.setCredentials(config.username, config.password)
            val normalizedUrl = directoryUrl.trimEnd('/')
            val resources = sardine.list(normalizedUrl)
            val imageResource = resources
                .filter { !it.isDirectory }
                .firstOrNull { resource ->
                    val extension = resource.name.substringAfterLast('.', "").lowercase()
                    extension in imageExtensions
                }
            if (imageResource != null) {
                val fullUrl = buildFullUrl(normalizedUrl, imageResource.path)
                Result.success(fullUrl)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun getAuthHeaders(username: String, password: String): Map<String, String> {
        val credentials = Credentials.basic(username, password)
        return mapOf("Authorization" to credentials)
    }
}
