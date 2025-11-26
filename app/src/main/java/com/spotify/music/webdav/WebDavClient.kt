package com.spotify.music.webdav

import com.spotify.music.data.MusicFile
import com.spotify.music.data.WebDavConfig
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WebDavClient {
    
    private val musicExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "wma")
    
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
                    // Construct full URL from base URL and resource path
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
            // Parse the base URL to get the protocol, host, and port
            val url = java.net.URL(baseUrl)
            val protocol = url.protocol
            val host = url.host
            val port = if (url.port != -1) ":${url.port}" else ""
            
            // Ensure the resource path starts with /
            val normalizedPath = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
            
            // Construct the full URL
            "$protocol://$host$port$normalizedPath"
        } catch (e: Exception) {
            // Fallback: if parsing fails, try to combine directly
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
            url // Return original URL, authentication will be handled via headers
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
    
    fun getAuthHeaders(username: String, password: String): Map<String, String> {
        val credentials = Credentials.basic(username, password)
        return mapOf("Authorization" to credentials)
    }
}
