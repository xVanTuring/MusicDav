package tech.xvanturing.musicdav.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object CoverCache {
    private const val CACHE_DIR = "music_covers"

    suspend fun saveCover(context: Context, url: String, data: ByteArray, mimeType: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, CACHE_DIR)
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                val extension = mimeTypeToExtension(mimeType)
                val fileName = "${sha256(url)}.$extension"
                val cacheFile = File(cacheDir, fileName)

                cacheFile.writeBytes(data)
                Log.d("CoverCache", "Cover saved to cache: ${cacheFile.absolutePath}, type: $mimeType")
                cacheFile.absolutePath
            } catch (e: Exception) {
                Log.e("CoverCache", "Failed to save cover for $url", e)
                null
            }
        }
    }

    fun getCoverPath(context: Context, url: String): String? {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (!cacheDir.exists()) {
            Log.d("CoverCache", "Cache directory does not exist")
            return null
        }

        val extensions = listOf("jpg", "jpeg", "png", "webp")
        val baseName = sha256(url)

        for (ext in extensions) {
            val fileName = "$baseName.$ext"
            val cacheFile = File(cacheDir, fileName)
            if (cacheFile.exists()) {
                Log.d("CoverCache", "Found cached cover for URL: ${url.takeLast(30)}, file: $fileName")
                return cacheFile.absolutePath
            }
        }

        Log.d("CoverCache", "No cached cover found for URL: ${url.takeLast(30)}")
        return null
    }

    fun clear(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, CACHE_DIR)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Log.d("CoverCache", "All cached covers cleared")
            }
        } catch (e: Exception) {
            Log.e("CoverCache", "Failed to clear cache", e)
        }
    }

    private fun sha256(input: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: NoSuchAlgorithmException) {
            Log.e("CoverCache", "SHA-256 algorithm not available", e)
            return input.hashCode().toString()
        }
    }

    private fun mimeTypeToExtension(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
    }
}
