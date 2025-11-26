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
            val directories = resources.filter { it.isDirectory }
            Result.success(directories)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun findCoverImage(config: WebDavConfig, directoryUrl: String): Result<ByteArray?> = withContext(Dispatchers.IO) {
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
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(fullUrl)
                    .headers(getAuthHeaders(config.username, config.password).let { okhttp3.Headers.Builder().apply {
                        it.forEach { (k, v) -> add(k, v) }
                    }.build() })
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.success(null)
                    }
                    val body = response.body ?: return@withContext Result.success(null)
                    val bytes = body.bytes()
                    Result.success(bytes)
                }
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
