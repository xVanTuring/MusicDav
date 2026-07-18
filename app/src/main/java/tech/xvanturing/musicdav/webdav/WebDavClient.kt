package tech.xvanturing.musicdav.webdav

import android.content.Context
import android.util.Log
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.MusicMetadataCache
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.data.cacheKey
import tech.xvanturing.musicdav.data.extractMusicMetadata
import tech.xvanturing.musicdav.player.CoverCache
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials

class WebDavClient {
    
    private val musicExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "wma")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    
    suspend fun fetchMusicFiles(
        config: WebDavConfig,
        context: Context,
        serverConfigId: String? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<List<MusicFile>> = withContext(Dispatchers.IO) {
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
                        modifiedDate = resource.modified?.time ?: 0L,
                        serverConfigId = serverConfigId
                    )
                }
                .sortedBy { it.name }

            Log.d("WebDavClient", "Found ${musicFiles.size} music files")

            val metadataMap = MusicMetadataCache.getBatch(context, musicFiles.map { it.cacheKey })
            val filesWithoutMetadata = musicFiles.filter { !metadataMap.containsKey(it.cacheKey) }

            Log.d("WebDavClient", "Cached metadata: ${metadataMap.size}, Need extraction: ${filesWithoutMetadata.size}")

            if (filesWithoutMetadata.isNotEmpty()) {
                for ((index, file) in filesWithoutMetadata.withIndex()) {
                    onProgress(index + 1, filesWithoutMetadata.size)
                    try {
                        val extractedMetadata = extractMusicMetadata(context, file, config)
                        val cachedMetadata = tech.xvanturing.musicdav.data.CachedMetadata(
                            url = file.cacheKey,
                            title = extractedMetadata.title,
                            artist = extractedMetadata.artist,
                            album = extractedMetadata.album,
                            durationMs = extractedMetadata.durationMs
                        )
                        MusicMetadataCache.save(context, cachedMetadata)

                        extractedMetadata.coverArt?.let { coverArt ->
                            launch {
                                CoverCache.saveCover(context, file.cacheKey, coverArt.imageData, coverArt.mimeType)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WebDavClient", "Failed to extract metadata for ${file.name}", e)
                    }
                }
            }

            val updatedMetadataMap = MusicMetadataCache.getBatch(context, musicFiles.map { it.cacheKey })

            val enrichedMusicFiles = musicFiles.map { file ->
                updatedMetadataMap[file.cacheKey]?.let { metadata ->
                    file.copy(
                        title = metadata.title,
                        artist = metadata.artist,
                        album = metadata.album,
                        durationMs = metadata.durationMs
                    )
                } ?: file
            }

            Result.success(enrichedMusicFiles)
        } catch (e: CancellationException) {
            // 协程取消不是拉取失败，向上传播，别包进 Result 害调用方当网络错误弹提示
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun buildFullUrl(baseUrl: String, resourcePath: String): String {
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

    // Lightweight reachability probe used to pick a working address among a server's candidates.
    // Any HTTP response (even 401/404) proves the host is reachable; only network-level failures
    // (timeout, connection refused, unknown host) count as unreachable.
    suspend fun probeReachable(
        url: String,
        username: String,
        password: String,
        timeoutMs: Long = 2500
    ): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(username, password))
                .head()
                .build()
            client.newCall(request).execute().use { true }
        } catch (e: Exception) {
            false
        }
    }
}
