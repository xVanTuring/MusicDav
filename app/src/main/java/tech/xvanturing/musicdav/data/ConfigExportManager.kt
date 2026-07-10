package tech.xvanturing.musicdav.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ExportData(
    val version: String = "1.0",
    val exportTime: Long = System.currentTimeMillis(),
    val serverConfigs: List<ServerConfig>,
    val albums: List<Album>
)

enum class ImportStrategy {
    OVERWRITE, // 覆盖：删除现有配置，导入新配置
    MERGE,     // 合并：保留现有配置，添加新配置
    UPDATE     // 更新：同名配置更新，其他保留
}

object ConfigExportManager {
    private const val TAG = "ConfigExportManager"
    
    suspend fun exportConfigs(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val serverConfigs = ServerConfigRepository.load(context)
            val albums = AlbumsRepository.load(context)
            
            val exportData = ExportData(
                serverConfigs = serverConfigs,
                albums = albums
            )
            
            val jsonData = exportData.toJson()
            
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonData.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw Exception("无法写入文件")
            
            Log.d(TAG, "配置导出成功: $uri")
            Result.success("配置已成功导出")
        } catch (e: Exception) {
            Log.e(TAG, "配置导出失败", e)
            Result.failure(e)
        }
    }
    
    suspend fun importConfigs(
        context: Context,
        uri: Uri,
        strategy: ImportStrategy
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: throw Exception("无法读取文件")
            
            val exportData = parseExportData(content)
            
            val result = when (strategy) {
                ImportStrategy.OVERWRITE -> overwriteConfigs(context, exportData)
                ImportStrategy.MERGE -> mergeConfigs(context, exportData)
                ImportStrategy.UPDATE -> updateConfigs(context, exportData)
            }
            
            Log.d(TAG, "配置导入成功: 策略=$strategy, 结果=$result")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "配置导入失败", e)
            Result.failure(e)
        }
    }
    
    private fun ExportData.toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("exportTime", exportTime)
        
        val configsArr = JSONArray()
        for (config in serverConfigs) {
            val configObj = JSONObject()
            configObj.put("id", config.id)
            configObj.put("name", config.name)
            configObj.put("urls", JSONArray(config.urls))
            // Kept for compatibility with older app versions importing this file
            configObj.put("url", config.url)
            configObj.put("username", config.username)
            configObj.put("password", config.password)
            configsArr.put(configObj)
        }
        obj.put("serverConfigs", configsArr)
        
        val albumsArr = JSONArray()
        for (album in albums) {
            val albumObj = JSONObject()
            albumObj.put("id", album.id)
            albumObj.put("name", album.name)
            
            val configObj = JSONObject()
            configObj.put("url", album.config.url)
            configObj.put("username", album.config.username)
            configObj.put("password", album.config.password)
            albumObj.put("config", configObj)
            
            albumObj.put("directoryUrl", album.directoryUrl)
            albumObj.put("coverImageUrl", album.coverImageUrl)
            albumObj.put("serverConfigId", album.serverConfigId)
            albumsArr.put(albumObj)
        }
        obj.put("albums", albumsArr)
        
        return obj.toString(2)
    }
    
    private fun parseExportData(json: String): ExportData {
        val obj = JSONObject(json)
        
        val configs = mutableListOf<ServerConfig>()
        val configsArr = obj.optJSONArray("serverConfigs") ?: JSONArray()
        for (i in 0 until configsArr.length()) {
            val configObj = configsArr.optJSONObject(i) ?: continue
            val urls = if (configObj.has("urls")) {
                val urlsArr = configObj.optJSONArray("urls") ?: JSONArray()
                (0 until urlsArr.length()).mapNotNull { urlsArr.optString(it, null) }
                    .filter { it.isNotBlank() }
            } else {
                listOfNotNull(configObj.optString("url", "").takeIf { it.isNotBlank() })
            }
            val config = ServerConfig(
                id = configObj.optString("id", ""),
                name = configObj.optString("name", ""),
                urls = urls,
                username = configObj.optString("username", ""),
                password = configObj.optString("password", "")
            )
            if (config.id.isNotBlank() && config.name.isNotBlank() && config.urls.isNotEmpty()) {
                configs.add(config)
            }
        }
        
        val albums = mutableListOf<Album>()
        val albumsArr = obj.optJSONArray("albums") ?: JSONArray()
        for (i in 0 until albumsArr.length()) {
            val albumObj = albumsArr.optJSONObject(i) ?: continue
            val cfg = albumObj.optJSONObject("config") ?: JSONObject()
            var id = albumObj.optString("id", "")
            if (id.isBlank()) {
                id = java.util.UUID.randomUUID().toString()
            }
            val album = Album(
                id = id,
                name = albumObj.optString("name", ""),
                config = WebDavConfig(
                    url = cfg.optString("url", ""),
                    username = cfg.optString("username", ""),
                    password = cfg.optString("password", "")
                ),
                directoryUrl = if (albumObj.has("directoryUrl")) 
                    albumObj.optString("directoryUrl", null) else null,
                coverImageUrl = if (albumObj.has("coverImageUrl")) 
                    albumObj.optString("coverImageUrl", null) else null,
                serverConfigId = if (albumObj.has("serverConfigId")) 
                    albumObj.optString("serverConfigId", null) else null
            )
            if (album.name.isNotBlank()) {
                albums.add(album)
            }
        }
        
        return ExportData(
            version = obj.optString("version", "1.0"),
            exportTime = obj.optLong("exportTime", System.currentTimeMillis()),
            serverConfigs = configs,
            albums = albums
        )
    }
    
    private fun overwriteConfigs(context: Context, data: ExportData): ImportResult {
        ServerConfigRepository.save(context, data.serverConfigs)
        AlbumsRepository.save(context, data.albums)
        
        return ImportResult(
            importedServerConfigs = data.serverConfigs.size,
            importedAlbums = data.albums.size,
            skippedServerConfigs = 0,
            skippedAlbums = 0,
            updatedServerConfigs = 0,
            updatedAlbums = 0
        )
    }
    
    private fun mergeConfigs(context: Context, data: ExportData): ImportResult {
        val existingConfigs = ServerConfigRepository.load(context)
        val existingAlbums = AlbumsRepository.load(context)
        
        val existingConfigIds = existingConfigs.map { it.id }.toSet()
        val newConfigs = data.serverConfigs.filter { it.id !in existingConfigIds }
        
        val mergedConfigs = existingConfigs + newConfigs
        ServerConfigRepository.save(context, mergedConfigs)
        
        val mergedAlbums = existingAlbums + data.albums
        AlbumsRepository.save(context, mergedAlbums)
        
        return ImportResult(
            importedServerConfigs = newConfigs.size,
            importedAlbums = data.albums.size,
            skippedServerConfigs = 0,
            skippedAlbums = 0,
            updatedServerConfigs = 0,
            updatedAlbums = 0
        )
    }
    
    private fun updateConfigs(context: Context, data: ExportData): ImportResult {
        val existingConfigs = ServerConfigRepository.load(context)
        val existingAlbums = AlbumsRepository.load(context)
        
        val existingConfigMap = existingConfigs.associateBy { it.id }
        
        val updatedConfigs = mutableListOf<ServerConfig>()
        var updatedConfigCount = 0
        var skippedConfigCount = 0
        
        for (newConfig in data.serverConfigs) {
            if (newConfig.id in existingConfigMap) {
                updatedConfigs.add(newConfig)
                updatedConfigCount++
            } else {
                skippedConfigCount++
            }
        }
        
        val finalConfigs = existingConfigs.toMutableList()
        for (updatedConfig in updatedConfigs) {
            val index = finalConfigs.indexOfFirst { it.id == updatedConfig.id }
            if (index >= 0) {
                finalConfigs[index] = updatedConfig
            }
        }
        ServerConfigRepository.save(context, finalConfigs)
        
        val finalAlbums = existingAlbums + data.albums
        AlbumsRepository.save(context, finalAlbums)
        
        return ImportResult(
            importedServerConfigs = 0,
            importedAlbums = data.albums.size,
            skippedServerConfigs = skippedConfigCount,
            skippedAlbums = 0,
            updatedServerConfigs = updatedConfigCount,
            updatedAlbums = 0
        )
    }
}

data class ImportResult(
    val importedServerConfigs: Int,
    val importedAlbums: Int,
    val skippedServerConfigs: Int,
    val skippedAlbums: Int,
    val updatedServerConfigs: Int,
    val updatedAlbums: Int
) {
    val totalChanges: Int
        get() = importedServerConfigs + importedAlbums + updatedServerConfigs + updatedAlbums
    
    val hasChanges: Boolean
        get() = totalChanges > 0
}
