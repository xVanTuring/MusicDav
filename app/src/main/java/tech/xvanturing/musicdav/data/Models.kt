package tech.xvanturing.musicdav.data

import androidx.core.content.edit

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
)

// Server configuration for WebDAV.
// urls holds every candidate address for the same server (e.g. LAN IP + public domain),
// ordered by preference; the app probes them in order and uses the first reachable one.
data class ServerConfig(
    val id: String,
    val name: String,
    val urls: List<String>,
    val username: String,
    val password: String
) {
    val url: String
        get() = urls.firstOrNull { it.isNotBlank() } ?: urls.firstOrNull() ?: ""

    fun toWebDavConfig(resolvedUrl: String? = null): WebDavConfig {
        return WebDavConfig(resolvedUrl ?: url, username, password)
    }

    companion object {
        fun single(id: String, name: String, url: String, username: String, password: String): ServerConfig {
            return ServerConfig(id, name, listOf(url), username, password)
        }
    }
}

data class MusicFile(
    val name: String,
    val url: String,
    val path: String,
    val size: Long = 0L,
    val modifiedDate: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    // Server this file was fetched from; lets cache keys stay stable across server address changes
    val serverConfigId: String? = null
) {
    val displayName: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: name.substringBeforeLast('.')
}

// Stable cache identity for a music file: tied to the server + relative path rather than
// the resolved absolute URL, so caches survive a server address change (see ServerConfig.urls).
val MusicFile.cacheKey: String
    get() = serverConfigId?.takeIf { it.isNotBlank() && path.isNotBlank() }
        ?.let { "$it::$path" }
        ?: url

// 播放模式枚举
enum class PlayMode {
    PLAY_ONCE,       // 播放完自动结束
    REPEAT_SINGLE,   // 单曲循环
    REPEAT_ALL       // 列表循环
}

 data class PlaylistState(
    val songs: List<MusicFile> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    // 当前播放歌曲的专辑封面URL（来自专辑配置）
    val currentAlbumCoverUrl: String? = null,
    // 当前播放歌曲的内置封面URL（来自音乐文件本身）
    val currentEmbeddedCoverUrl: String? = null,
    // 是否正在加载歌曲元数据（用于控制封面显示时机）
    val isLoadingMetadata: Boolean = false,
    // 歌曲URL到专辑封面URL的映射
    val songToAlbumCoverMap: Map<String, String?> = emptyMap(),
    // 当前播放专辑的WebDAV配置（用于加载封面图片）
    val currentWebDavConfig: WebDavConfig? = null,
    // 播放模式
    val playMode: PlayMode = PlayMode.PLAY_ONCE,
    // 缓存的内嵌封面（文件 URL → 本地缓存路径）
    val cachedCoverMap: Map<String, String> = emptyMap(),
    // 歌曲URL到其所属服务器WebDAV配置的映射，用于播放列表内歌曲来自不同服务器时
    // （如收藏夹、搜索结果）按曲目切换鉴权信息；单一来源的播放列表可不设置，回退用 currentWebDavConfig
    val songToConfigMap: Map<String, WebDavConfig> = emptyMap()
) {
    val currentSong: MusicFile?
        get() = songs.getOrNull(currentIndex)

    // 当前歌曲实际应使用的 WebDAV 配置：优先按曲目查表，否则回退到播放列表统一配置
    val effectiveWebDavConfig: WebDavConfig?
        get() = currentSong?.let { songToConfigMap[it.url] } ?: currentWebDavConfig

    // 获取当前歌曲的封面URL，优先级：缓存封面 > 内嵌封面 > 专辑封面 > 默认占位符
    val currentCoverUrl: String?
        get() {
            val currentSong = currentSong ?: return null

            // 优先级 1: 缓存的本地封面文件
            cachedCoverMap[currentSong.url]?.let {
                return it
            }

            // 优先级 2: 当前播放器提取的内嵌封面
            currentEmbeddedCoverUrl?.let {
                return it
            }

            // 优先级 3: 专辑封面
            songToAlbumCoverMap[currentSong.url]?.let {
                return it
            }

            // 优先级 4: 无封面可用，返回 null 显示默认占位符
            return null
        }

    val hasNext: Boolean
        get() = currentIndex < songs.size - 1

    val hasPrevious: Boolean
        get() = currentIndex > 0
}

// Album configuration for a playlist.
// When serverConfigId is set, directoryUrl/coverImageUrl are stored as paths relative to the
// linked ServerConfig's address (so changing that address doesn't break the album). When
// serverConfigId is null (manual/legacy config), they remain absolute URLs as before.
 data class Album(
    val id: String,
    val name: String,
    val config: WebDavConfig,
    val directoryUrl: String? = null,
    val coverImageUrl: String? = null,
    val serverConfigId: String? = null  // Reference to ServerConfig, if null use config directly
)

// Helper function to get WebDavConfig from Album (uses the server's primary/legacy url,
// without probing candidate addresses; prefer ServerAddressResolver where network calls happen)
fun Album.getWebDavConfig(context: android.content.Context): WebDavConfig {
    return if (serverConfigId != null) {
        ServerConfigRepository.load(context)
            .find { it.id == serverConfigId }
            ?.toWebDavConfig()
            ?: config
    } else {
        config
    }
}

// Resolves a stored directoryUrl/coverImageUrl against the currently resolved server base URL.
// Absolute values (legacy or manual-config albums) are returned as-is; relative paths are joined
// with baseServerUrl.
fun resolveAlbumUrl(storedValue: String?, baseServerUrl: String): String? {
    if (storedValue == null) return null
    if (storedValue.startsWith("http://") || storedValue.startsWith("https://")) return storedValue
    val base = baseServerUrl.trimEnd('/')
    val relative = if (storedValue.startsWith("/")) storedValue else "/$storedValue"
    return "$base$relative"
}

// Converts an absolute URL into a path relative to baseServerUrl, if it actually lives under it;
// otherwise returns the URL unchanged (kept absolute, e.g. cover picked from a different host).
// The result is relative to baseServerUrl's own path (e.g. base "https://host/dav" + target
// "https://host/dav/Music/Album1" -> "/Music/Album1"), NOT the domain root — resolveAlbumUrl()
// re-joins it onto baseServerUrl, and baseServerUrl already carries any WebDAV root sub-path
// (e.g. Nextcloud's "/remote.php/dav/files/user"), so stripping it here avoids a duplicated
// path segment on reconstruction.
fun relativizeAlbumUrl(absoluteUrl: String?, baseServerUrl: String): String? {
    if (absoluteUrl == null) return null
    if (!absoluteUrl.startsWith("http://") && !absoluteUrl.startsWith("https://")) return absoluteUrl
    return try {
        val base = java.net.URL(baseServerUrl.trimEnd('/'))
        val target = java.net.URL(absoluteUrl)
        val sameHost = base.host.equals(target.host, ignoreCase = true) && base.port == target.port
        if (!sameHost) return absoluteUrl
        val basePath = base.path.trimEnd('/')
        val targetPath = target.path
        val relativePath = if (basePath.isNotEmpty() && targetPath.startsWith(basePath)) {
            targetPath.substring(basePath.length)
        } else {
            targetPath
        }
        relativePath.ifEmpty { "/" }
    } catch (e: Exception) {
        absoluteUrl
    }
}

// Simple persistence for server configurations using SharedPreferences and JSON
object ServerConfigRepository {
    private const val PREF_NAME = "server_config_prefs"
    private const val KEY_CONFIGS = "server_configs_json"

    fun load(context: android.content.Context): List<ServerConfig> {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIGS, "[]") ?: "[]"
        return parseConfigs(json)
    }

    fun save(context: android.content.Context, configs: List<ServerConfig>) {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_CONFIGS, toJson(configs)) }
    }

    fun add(context: android.content.Context, config: ServerConfig) {
        val configs = load(context).toMutableList()
        configs.add(config)
        save(context, configs)
    }

    fun update(context: android.content.Context, config: ServerConfig) {
        val configs = load(context).toMutableList()
        val index = configs.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            configs[index] = config
            save(context, configs)
        }
    }

    fun delete(context: android.content.Context, id: String) {
        val configs = load(context).filterNot { it.id == id }
        save(context, configs)
    }

    fun getById(context: android.content.Context, id: String): ServerConfig? {
        return load(context).find { it.id == id }
    }

    private fun parseConfigs(json: String): List<ServerConfig> {
        val arr = org.json.JSONArray(json)
        val result = mutableListOf<ServerConfig>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val name = obj.optString("name", "")
            val urls = if (obj.has("urls")) {
                val urlsArr = obj.optJSONArray("urls") ?: org.json.JSONArray()
                (0 until urlsArr.length()).mapNotNull { urlsArr.optString(it, null) }
                    .filter { it.isNotBlank() }
            } else {
                // Legacy single-url format
                listOfNotNull(obj.optString("url", "").takeIf { it.isNotBlank() })
            }
            val username = obj.optString("username", "")
            val password = obj.optString("password", "")
            if (id.isNotBlank() && name.isNotBlank() && urls.isNotEmpty()) {
                result.add(ServerConfig(id, name, urls, username, password))
            }
        }
        return result
    }

    private fun toJson(configs: List<ServerConfig>): String {
        val arr = org.json.JSONArray()
        for (config in configs) {
            val obj = org.json.JSONObject()
            obj.put("id", config.id)
            obj.put("name", config.name)
            obj.put("urls", org.json.JSONArray(config.urls))
            // Kept for backward compatibility with older app versions reading this prefs file
            obj.put("url", config.url)
            obj.put("username", config.username)
            obj.put("password", config.password)
            arr.put(obj)
        }
        return arr.toString()
    }
}

// Simple persistence for albums using SharedPreferences and JSON
 object AlbumsRepository {
    private const val PREF_NAME = "albums_prefs"
    private const val KEY_ALBUMS = "albums_json"

    fun load(context: android.content.Context): List<Album> {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ALBUMS, "[]") ?: "[]"
        val albums = parseAlbums(json)
        val migrated = migrateToRelativePaths(context, albums)
        if (migrated !== albums) {
            save(context, migrated)
        }
        return migrated
    }

    fun save(context: android.content.Context, albums: List<Album>) {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_ALBUMS, toJson(albums)) }
    }

    // One-time migration for existing installs: albums linked to a ServerConfig used to store
    // directoryUrl/coverImageUrl as absolute URLs baked with that server's host at creation time.
    // Rewrite them to paths relative to the server's current address so future address changes
    // (see ServerConfig.urls) don't break the album.
    private fun migrateToRelativePaths(context: android.content.Context, albums: List<Album>): List<Album> {
        if (albums.none { it.serverConfigId != null }) return albums
        val serverConfigs = ServerConfigRepository.load(context)
        if (serverConfigs.isEmpty()) return albums

        var changed = false
        val result = albums.map { album ->
            val serverConfigId = album.serverConfigId ?: return@map album
            val server = serverConfigs.find { it.id == serverConfigId } ?: return@map album
            val newDirectoryUrl = relativizeAlbumUrl(album.directoryUrl, server.url)
            val newCoverImageUrl = relativizeAlbumUrl(album.coverImageUrl, server.url)
            if (newDirectoryUrl != album.directoryUrl || newCoverImageUrl != album.coverImageUrl) {
                changed = true
                album.copy(directoryUrl = newDirectoryUrl, coverImageUrl = newCoverImageUrl)
            } else {
                album
            }
        }
        return if (changed) result else albums
    }

    private fun parseAlbums(json: String): List<Album> {
        val arr = org.json.JSONArray(json)
        val result = mutableListOf<Album>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            var id = obj.optString("id", "")
            val name = obj.optString("name", "")
            val cfg = obj.optJSONObject("config") ?: org.json.JSONObject()
            val url = cfg.optString("url", "")
            val username = cfg.optString("username", "")
            val password = cfg.optString("password", "")
            val directoryUrl = if (obj.has("directoryUrl")) obj.optString("directoryUrl", null) else null
            val coverImageUrl = if (obj.has("coverImageUrl")) obj.optString("coverImageUrl", null) else null
            val serverConfigId = if (obj.has("serverConfigId")) obj.optString("serverConfigId", null) else null
            
            if (id.isBlank()) {
                id = java.util.UUID.randomUUID().toString()
            }
            
            result.add(
                Album(
                    id = id,
                    name = name,
                    config = WebDavConfig(url, username, password),
                    directoryUrl = directoryUrl,
                    coverImageUrl = coverImageUrl,
                    serverConfigId = serverConfigId
                )
            )
        }
        return result
    }

    private fun toJson(albums: List<Album>): String {
        val arr = org.json.JSONArray()
        for (album in albums) {
            val obj = org.json.JSONObject()
            obj.put("id", album.id)
            obj.put("name", album.name)
            val cfg = org.json.JSONObject()
            cfg.put("url", album.config.url)
            cfg.put("username", album.config.username)
            cfg.put("password", album.config.password)
            obj.put("config", cfg)
            obj.put("directoryUrl", album.directoryUrl)
            obj.put("coverImageUrl", album.coverImageUrl)
            if (album.serverConfigId != null) {
                obj.put("serverConfigId", album.serverConfigId)
            }
            arr.put(obj)
        }
        return arr.toString()
    }
}

// Playlist cache for storing music files, keyed by album id (stable regardless of server address)
object PlaylistCache {
    private const val PREF_NAME = "playlist_cache_prefs"

    fun load(context: android.content.Context, albumId: String): List<MusicFile> {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        val json = prefs.getString(albumId, "[]") ?: "[]"
        return parseMusicFiles(json)
    }

    fun save(context: android.content.Context, albumId: String, musicFiles: List<MusicFile>) {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit { putString(albumId, toJson(musicFiles)) }
    }

    fun clear(context: android.content.Context, albumId: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit { remove(albumId) }
    }
    
    private fun parseMusicFiles(json: String): List<MusicFile> {
        val arr = org.json.JSONArray(json)
        val result = mutableListOf<MusicFile>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val url = obj.optString("url", "")
            val path = obj.optString("path", "")
            val size = obj.optLong("size", 0L)
            val modifiedDate = obj.optLong("modifiedDate", 0L)
            val title = if (obj.has("title")) obj.optString("title", null).takeIf { !it.isNullOrEmpty() } else null
            val artist = if (obj.has("artist")) obj.optString("artist", null).takeIf { !it.isNullOrEmpty() } else null
            val album = if (obj.has("album")) obj.optString("album", null).takeIf { !it.isNullOrEmpty() } else null
            val durationMs = obj.optLong("durationMs", 0L)
            val serverConfigId = if (obj.has("serverConfigId")) obj.optString("serverConfigId", null).takeIf { !it.isNullOrEmpty() } else null
            result.add(MusicFile(name, url, path, size, modifiedDate, title, artist, album, durationMs, serverConfigId))
        }
        return result
    }

    private fun toJson(musicFiles: List<MusicFile>): String {
        val arr = org.json.JSONArray()
        for (file in musicFiles) {
            val obj = org.json.JSONObject()
            obj.put("name", file.name)
            obj.put("url", file.url)
            obj.put("path", file.path)
            obj.put("size", file.size)
            obj.put("modifiedDate", file.modifiedDate)
            if (file.title != null) obj.put("title", file.title)
            if (file.artist != null) obj.put("artist", file.artist)
            if (file.album != null) obj.put("album", file.album)
            obj.put("durationMs", file.durationMs)
            if (file.serverConfigId != null) obj.put("serverConfigId", file.serverConfigId)
            arr.put(obj)
        }
        return arr.toString()
    }
}
