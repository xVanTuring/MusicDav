package com.spotify.music.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object CoverCache {
    private const val CACHE_DIR = "music_covers"

    suspend fun saveCover(context: Context, url: String, data: ByteArray): String? {
        return withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, CACHE_DIR)
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                val fileName = "${sha256(url)}.jpg"
                val cacheFile = File(cacheDir, fileName)

                cacheFile.writeBytes(data)
                Log.d("CoverCache", "Cover saved to cache: ${cacheFile.absolutePath}")
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

        val fileName = "${sha256(url)}.jpg"
        val cacheFile = File(cacheDir, fileName)
        val exists = cacheFile.exists()
        Log.d("CoverCache", "Checking cache for URL: ${url.takeLast(30)}, file: $fileName, exists: $exists")

        return if (exists) {
            cacheFile.absolutePath
        } else {
            null
        }
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
}
