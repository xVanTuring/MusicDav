package tech.xvanturing.musicdav.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import androidx.core.content.edit

data class CachedMetadata(
    val url: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long
)

object MusicMetadataCache {
    private const val PREF_NAME = "music_metadata_prefs"
    private const val KEY_METADATA = "metadata_json"

    fun save(context: Context, metadata: CachedMetadata) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val allMetadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
        val allMetadata = JSONObject(allMetadataJson)

        val metadataJson = JSONObject().apply {
            put("url", metadata.url)
            put("title", metadata.title)
            put("artist", metadata.artist)
            put("album", metadata.album)
            put("durationMs", metadata.durationMs)
        }

        allMetadata.put(metadata.url, metadataJson)
        prefs.edit { putString(KEY_METADATA, allMetadata.toString()) }
        Log.d("MusicMetadataCache", "Saved metadata for: ${metadata.url.takeLast(30)}")
    }

    fun get(context: Context, url: String): CachedMetadata? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val allMetadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
        val allMetadata = JSONObject(allMetadataJson)

        if (!allMetadata.has(url)) {
            return null
        }

        val metadataJson = allMetadata.getJSONObject(url)
        return CachedMetadata(
            url = metadataJson.optString("url", ""),
            title = metadataJson.optString("title", null).takeIf { !it.isNullOrEmpty() },
            artist = metadataJson.optString("artist", null).takeIf { !it.isNullOrEmpty() },
            album = metadataJson.optString("album", null).takeIf { !it.isNullOrEmpty() },
            durationMs = metadataJson.optLong("durationMs", 0L)
        )
    }

    fun getBatch(context: Context, urls: List<String>): Map<String, CachedMetadata> {
        val result = mutableMapOf<String, CachedMetadata>()
        urls.forEach { url ->
            get(context, url)?.let { metadata ->
                result[url] = metadata
            }
        }
        return result
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit {remove(KEY_METADATA)}
        Log.d("MusicMetadataCache", "Cleared all metadata")
    }

    fun remove(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val allMetadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
        val allMetadata = JSONObject(allMetadataJson)
        allMetadata.remove(url)
        prefs.edit {putString(KEY_METADATA, allMetadata.toString())}
        Log.d("MusicMetadataCache", "Removed metadata for: ${url.takeLast(30)}")
    }
}
