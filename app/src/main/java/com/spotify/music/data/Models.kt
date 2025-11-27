package com.spotify.music.data

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
)

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
    val coverImageBase64: String? = null
)

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
            result.add(
                Album(
                    name = name,
                    config = WebDavConfig(url, username, password),
                    directoryUrl = directoryUrl,
                    coverImageBase64 = coverBase64
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
