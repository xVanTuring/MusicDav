package tech.xvanturing.musicdav.data

import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tech.xvanturing.musicdav.player.CoverCache
import tech.xvanturing.musicdav.webdav.WebDavClient

// A favorited song. musicFile keeps its serverConfigId/path so playback can re-resolve the
// current server address (see ServerAddressResolver) instead of trusting a possibly stale
// absolute url. fallbackConfig is only used for songs from a manual (non server-linked) album,
// where there is no ServerConfig to re-resolve against.
data class FavoriteSong(
    val id: String,
    val musicFile: MusicFile,
    val fallbackConfig: WebDavConfig?,
    val addedAt: Long
)

object FavoritesRepository {
    private const val PREF_NAME = "favorites_prefs"
    private const val KEY_FAVORITES = "favorites_json"

    // Newest favorited first
    fun load(context: android.content.Context): List<FavoriteSong> {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        return parseFavorites(json).sortedByDescending { it.addedAt }
    }

    fun isFavorite(context: android.content.Context, musicFile: MusicFile): Boolean {
        return load(context).any { it.musicFile.cacheKey == musicFile.cacheKey }
    }

    fun add(context: android.content.Context, musicFile: MusicFile, fallbackConfig: WebDavConfig?) {
        val current = load(context)
        if (current.any { it.musicFile.cacheKey == musicFile.cacheKey }) return
        val favorite = FavoriteSong(
            id = java.util.UUID.randomUUID().toString(),
            musicFile = musicFile,
            fallbackConfig = if (musicFile.serverConfigId == null) fallbackConfig else null,
            addedAt = System.currentTimeMillis()
        )
        persist(context, current + favorite)
    }

    fun remove(context: android.content.Context, musicFile: MusicFile) {
        val current = load(context)
        persist(context, current.filterNot { it.musicFile.cacheKey == musicFile.cacheKey })
    }

    // Returns the new favorite state (true = now favorited)
    fun toggle(context: android.content.Context, musicFile: MusicFile, fallbackConfig: WebDavConfig?): Boolean {
        return if (isFavorite(context, musicFile)) {
            remove(context, musicFile)
            false
        } else {
            add(context, musicFile, fallbackConfig)
            true
        }
    }

    private fun persist(context: android.content.Context, favorites: List<FavoriteSong>) {
        val prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_FAVORITES, toJson(favorites)) }
    }

    private fun parseFavorites(json: String): List<FavoriteSong> {
        val arr = JSONArray(json)
        val result = mutableListOf<FavoriteSong>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val musicFileObj = obj.optJSONObject("musicFile") ?: continue
            val musicFile = musicFileFromJson(musicFileObj) ?: continue
            val fallbackConfigObj = obj.optJSONObject("fallbackConfig")
            val fallbackConfig = fallbackConfigObj?.let {
                WebDavConfig(
                    url = it.optString("url", ""),
                    username = it.optString("username", ""),
                    password = it.optString("password", "")
                )
            }
            val addedAt = obj.optLong("addedAt", 0L)
            if (id.isNotBlank()) {
                result.add(FavoriteSong(id, musicFile, fallbackConfig, addedAt))
            }
        }
        return result
    }

    private fun musicFileFromJson(obj: JSONObject): MusicFile? {
        val name = obj.optString("name", "")
        val path = obj.optString("path", "")
        if (name.isBlank() || path.isBlank()) return null
        return MusicFile(
            name = name,
            url = obj.optString("url", ""),
            path = path,
            size = obj.optLong("size", 0L),
            modifiedDate = obj.optLong("modifiedDate", 0L),
            title = obj.optString("title", null).takeIf { !it.isNullOrEmpty() },
            artist = obj.optString("artist", null).takeIf { !it.isNullOrEmpty() },
            album = obj.optString("album", null).takeIf { !it.isNullOrEmpty() },
            durationMs = obj.optLong("durationMs", 0L),
            serverConfigId = obj.optString("serverConfigId", null).takeIf { !it.isNullOrEmpty() }
        )
    }

    private fun toJson(favorites: List<FavoriteSong>): String {
        val arr = JSONArray()
        for (favorite in favorites) {
            val obj = JSONObject()
            obj.put("id", favorite.id)

            val musicFileObj = JSONObject()
            musicFileObj.put("name", favorite.musicFile.name)
            musicFileObj.put("url", favorite.musicFile.url)
            musicFileObj.put("path", favorite.musicFile.path)
            musicFileObj.put("size", favorite.musicFile.size)
            musicFileObj.put("modifiedDate", favorite.musicFile.modifiedDate)
            favorite.musicFile.title?.let { musicFileObj.put("title", it) }
            favorite.musicFile.artist?.let { musicFileObj.put("artist", it) }
            favorite.musicFile.album?.let { musicFileObj.put("album", it) }
            musicFileObj.put("durationMs", favorite.musicFile.durationMs)
            favorite.musicFile.serverConfigId?.let { musicFileObj.put("serverConfigId", it) }
            obj.put("musicFile", musicFileObj)

            favorite.fallbackConfig?.let { config ->
                val configObj = JSONObject()
                configObj.put("url", config.url)
                configObj.put("username", config.username)
                configObj.put("password", config.password)
                obj.put("fallbackConfig", configObj)
            }

            obj.put("addedAt", favorite.addedAt)
            arr.put(obj)
        }
        return arr.toString()
    }
}

// 收藏夹封面：取列表中第一首有本地缓存封面的歌，没有则依次往后找；都没有则返回 null（用占位图）
fun favoritesCoverPath(context: android.content.Context, favorites: List<FavoriteSong>): String? {
    for (favorite in favorites) {
        CoverCache.getCoverPath(context, favorite.musicFile.cacheKey)?.let { return it }
    }
    return null
}

// 收藏夹封面的内存缓存：跨屏幕(专辑列表被销毁重建)保留上次解析出的封面路径，返回列表页时能立刻
// 显示、无需闪一下占位图。signature 用收藏项 id 拼成，收藏集合变化时自动失效。isResolved 用于
// 区分"还没解析过"和"已解析但确实没有封面"，避免对没有内嵌封面的收藏每次返回都重复联网提取。
object FavoritesCoverCache {
    @Volatile private var signature: String? = null
    @Volatile private var path: String? = null

    fun current(): String? = path
    fun isResolved(sig: String): Boolean = sig == signature
    fun get(sig: String): String? = if (sig == signature) path else null
    fun put(sig: String, coverPath: String?) {
        signature = sig
        path = coverPath
    }
}

// 稳定解析收藏夹封面（#3 修"封面要来回切换很多次才显示"）：
// 1) 先看本地已缓存封面(favoritesCoverPath)，命中即返回——无网络、最快。
// 2) 本地没有时，依次对前若干首收藏歌曲做一次内嵌封面提取（会联网），第一首取到封面就写入
//    CoverCache 并返回其路径。这样即使用户从没进过对应专辑详情，收藏封面也能自己冒出来。
// 3) 都取不到则返回 null（继续用占位图）。
// 注意：写入 CoverCache 用 favorite.musicFile.cacheKey，与 favoritesCoverPath 的读取键一致。
suspend fun resolveFavoritesCover(
    context: android.content.Context,
    favorites: List<FavoriteSong>
): String? = withContext(Dispatchers.IO) {
    favoritesCoverPath(context, favorites)?.let { return@withContext it }
    for (favorite in favorites.take(5)) {
        val key = favorite.musicFile.cacheKey
        CoverCache.getCoverPath(context, key)?.let { return@withContext it }
        val playable = favorite.resolvePlayable(context) ?: continue
        val art = runCatching { extractMusicMetadata(context, playable.first, playable.second).coverArt }
            .getOrNull() ?: continue
        val saved = CoverCache.saveCover(context, key, art.imageData, art.mimeType)
        if (saved != null) return@withContext saved
    }
    null
}

// Resolves a favorite into a currently-playable MusicFile + WebDavConfig, re-resolving the
// server's current address for server-linked favorites (see ServerAddressResolver) rather than
// trusting the possibly stale absolute url stored on the MusicFile.
suspend fun FavoriteSong.resolvePlayable(context: android.content.Context): Pair<MusicFile, WebDavConfig>? {
    val serverConfigId = musicFile.serverConfigId
    return if (serverConfigId != null) {
        val server = ServerConfigRepository.load(context).find { it.id == serverConfigId } ?: return null
        val baseUrl = ServerAddressResolver.resolve(server)
        val rebuiltUrl = WebDavClient().buildFullUrl(baseUrl, musicFile.path)
        musicFile.copy(url = rebuiltUrl) to server.toWebDavConfig(baseUrl)
    } else if (fallbackConfig != null) {
        musicFile to fallbackConfig
    } else {
        null
    }
}
