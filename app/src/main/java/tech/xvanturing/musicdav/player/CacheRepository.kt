package tech.xvanturing.musicdav.player

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

object CacheRepository {
    private const val PREF_NAME = "music_cache_prefs"
    private const val KEY_METADATA = "cache_metadata"
    
    fun saveMetadata(context: Context, url: String, metadata: CacheMetadata) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val allMetadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
        val allMetadata = JSONObject(allMetadataJson)
        
        val metadataJson = JSONObject().apply {
            put("url", metadata.url)
            put("fileName", metadata.fileName)
            put("fileSize", metadata.fileSize)
            put("lastAccessTime", metadata.lastAccessTime)
            put("cacheTime", metadata.cacheTime)
        }
        
        allMetadata.put(url, metadataJson)
        prefs.edit().putString(KEY_METADATA, allMetadata.toString()).apply()
        Log.d("CacheRepository", "Saved metadata for: ${url.takeLast(30)}")
    }
    
    fun getMetadata(context: Context, url: String): CacheMetadata? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val allMetadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
        val allMetadata = JSONObject(allMetadataJson)
        
        if (!allMetadata.has(url)) {
            return null
        }
        
        val metadataJson = allMetadata.getJSONObject(url)
        return CacheMetadata(
            url = metadataJson.optString("url", ""),
            fileName = metadataJson.optString("fileName", ""),
            fileSize = metadataJson.optLong("fileSize", 0L),
            lastAccessTime = metadataJson.optLong("lastAccessTime", 0L),
            cacheTime = metadataJson.optLong("cacheTime", 0L)
        )
    }
    
    fun getAllMetadata(context: Context): List<CacheMetadata> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val allMetadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
        val allMetadata = JSONObject(allMetadataJson)
        
        val result = mutableListOf<CacheMetadata>()
        val keys = allMetadata.keys()
        
        while (keys.hasNext()) {
            val url = keys.next()
            val metadataJson = allMetadata.optJSONObject(url) ?: continue
            result.add(
                CacheMetadata(
                    url = metadataJson.optString("url", ""),
                    fileName = metadataJson.optString("fileName", ""),
                    fileSize = metadataJson.optLong("fileSize", 0L),
                    lastAccessTime = metadataJson.optLong("lastAccessTime", 0L),
                    cacheTime = metadataJson.optLong("cacheTime", 0L)
                )
            )
        }
        
        return result
    }
    
    fun removeMetadata(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val allMetadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
        val allMetadata = JSONObject(allMetadataJson)
        
        allMetadata.remove(url)
        prefs.edit().putString(KEY_METADATA, allMetadata.toString()).apply()
        Log.d("CacheRepository", "Removed metadata for: ${url.takeLast(30)}")
    }
    
    fun updateLastAccessTime(context: Context, url: String) {
        val metadata = getMetadata(context, url) ?: return
        val updatedMetadata = metadata.copy(lastAccessTime = System.currentTimeMillis())
        saveMetadata(context, url, updatedMetadata)
    }
    
    fun getTotalCacheSize(context: Context): Long {
        return getAllMetadata(context).sumOf { it.fileSize }
    }
    
    fun clearMetadata(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_METADATA).apply()
        Log.d("CacheRepository", "Cleared all metadata")
    }
    
    fun repairMetadata(context: Context) {
        val cacheDir = MusicCache.getCacheDir(context)
        val existingFiles = cacheDir.listFiles()?.map { it.name } ?: emptySet()
        
        val metadataList = getAllMetadata(context)
        val validMetadata = mutableListOf<CacheMetadata>()
        
        for (metadata in metadataList) {
            if (metadata.fileName in existingFiles) {
                val file = File(cacheDir, metadata.fileName)
                val updatedMetadata = metadata.copy(fileSize = file.length())
                validMetadata.add(updatedMetadata)
            } else {
                removeMetadata(context, metadata.url)
            }
        }
        
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val allMetadata = JSONObject()
        
        for (metadata in validMetadata) {
            val metadataJson = JSONObject().apply {
                put("url", metadata.url)
                put("fileName", metadata.fileName)
                put("fileSize", metadata.fileSize)
                put("lastAccessTime", metadata.lastAccessTime)
                put("cacheTime", metadata.cacheTime)
            }
            allMetadata.put(metadata.url, metadataJson)
        }
        
        prefs.edit().putString(KEY_METADATA, allMetadata.toString()).apply()
        Log.d("CacheRepository", "Metadata repaired: ${validMetadata.size} valid entries")
    }
}
