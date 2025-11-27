package com.spotify.music.data

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
)

// Server configuration for WebDAV
data class ServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val username: String,
    val password: String
) {
    fun toWebDavConfig(): WebDavConfig {
        return WebDavConfig(url, username, password)
    }
}

data class MusicFile(
    val name: String,
    val url: String,
    val path: String,
    val size: Long = 0L,
    val modifiedDate: Long = 0L
)

data class PlaylistState(
    val songs: List<MusicFile> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L
) {
    val currentSong: MusicFile? 
        get() = songs.getOrNull(currentIndex)
    
    val hasNext: Boolean 
        get() = currentIndex < songs.size - 1
    
    val hasPrevious: Boolean 
        get() = currentIndex > 0
}

// Album configuration for a playlist
 data class Album(
    val name: String,
    val config: WebDavConfig,
    val directoryUrl: String? = null,
    val coverImageBase64: String? = null,
    val serverConfigId: String? = null  // Reference to ServerConfig, if null use config directly
)

// Helper function to get WebDavConfig from Album
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
        prefs.edit().putString(KEY_CONFIGS, toJson(configs)).apply()
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
            val url = obj.optString("url", "")
            val username = obj.optString("username", "")
            val password = obj.optString("password", "")
            if (id.isNotBlank() && name.isNotBlank()) {
                result.add(ServerConfig(id, name, url, username, password))
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
        return parseAlbums(json)
    }

    fun save(context: android.content.Context, albums: List<Album>) {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ALBUMS, toJson(albums)).apply()
    }

    private fun parseAlbums(json: String): List<Album> {
        val arr = org.json.JSONArray(json)
        val result = mutableListOf<Album>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val cfg = obj.optJSONObject("config") ?: org.json.JSONObject()
            val url = cfg.optString("url", "")
            val username = cfg.optString("username", "")
            val password = cfg.optString("password", "")
            val directoryUrl = if (obj.has("directoryUrl")) obj.optString("directoryUrl", null) else null
            val coverBase64 = if (obj.has("coverImageBase64")) obj.optString("coverImageBase64", null) else null
            val serverConfigId = if (obj.has("serverConfigId")) obj.optString("serverConfigId", null) else null
            result.add(
                Album(
                    name = name,
                    config = WebDavConfig(url, username, password),
                    directoryUrl = directoryUrl,
                    coverImageBase64 = coverBase64,
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
            obj.put("name", album.name)
            val cfg = org.json.JSONObject()
            cfg.put("url", album.config.url)
            cfg.put("username", album.config.username)
            cfg.put("password", album.config.password)
            obj.put("config", cfg)
            obj.put("directoryUrl", album.directoryUrl)
            obj.put("coverImageBase64", album.coverImageBase64)
            if (album.serverConfigId != null) {
                obj.put("serverConfigId", album.serverConfigId)
            }
            arr.put(obj)
        }
        return arr.toString()
    }
}

// Playlist cache for storing music files
object PlaylistCache {
    private const val PREF_NAME = "playlist_cache_prefs"
    
    private fun getCacheKey(directoryUrl: String?): String {
        return directoryUrl ?: "default"
    }
    
    fun load(context: android.content.Context, directoryUrl: String?): List<MusicFile> {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        val key = getCacheKey(directoryUrl)
        val json = prefs.getString(key, "[]") ?: "[]"
        return parseMusicFiles(json)
    }
    
    fun save(context: android.content.Context, directoryUrl: String?, musicFiles: List<MusicFile>) {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        val key = getCacheKey(directoryUrl)
        prefs.edit().putString(key, toJson(musicFiles)).apply()
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
            result.add(MusicFile(name, url, path, size, modifiedDate))
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
            arr.put(obj)
        }
        return arr.toString()
    }
}
