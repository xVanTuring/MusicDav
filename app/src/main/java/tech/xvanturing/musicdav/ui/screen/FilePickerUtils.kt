package tech.xvanturing.musicdav.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import tech.xvanturing.musicdav.data.WebDavConfig
import java.net.URL

fun parseWebDavBaseUrl(webDavConfig: WebDavConfig): String {
    val url = webDavConfig.url.trimEnd('/')
    return try {
        val urlObj = URL(url)
        val baseUrl = "${urlObj.protocol}://${urlObj.host}"
        if (urlObj.port != -1 && urlObj.port != urlObj.defaultPort) {
            "$baseUrl:${urlObj.port}"
        } else {
            baseUrl
        }
    } catch (e: Exception) {
        url
    }
}

fun parseWebDavRootPath(webDavConfig: WebDavConfig): String {
    val url = webDavConfig.url.trimEnd('/')
    return try {
        val urlObj = URL(url)
        urlObj.path.trimEnd('/')
    } catch (e: Exception) {
        ""
    }
}

fun generatePathHistory(webDavConfig: WebDavConfig, currentPath: String): List<String> {
    val baseUrl = parseWebDavBaseUrl(webDavConfig)
    val rootPath = parseWebDavRootPath(webDavConfig)
    val normalizedCurrentPath = currentPath.trimEnd('/')

    val currentPathOnly = if (normalizedCurrentPath.startsWith("http")) {
        try {
            URL(normalizedCurrentPath).path.trimEnd('/')
        } catch (e: Exception) {
            normalizedCurrentPath
        }
    } else {
        normalizedCurrentPath
    }

    val pathHistory = mutableListOf<String>()

    if (rootPath.isNotEmpty()) {
        pathHistory.add("$baseUrl$rootPath")
    } else {
        pathHistory.add(baseUrl)
    }

    if (currentPathOnly != rootPath) {
        val relativePath = if (rootPath.isNotEmpty()) {
            currentPathOnly.removePrefix(rootPath).trimStart('/')
        } else {
            currentPathOnly.trimStart('/')
        }

        if (relativePath.isNotEmpty()) {
            val pathSegments = relativePath.split("/").filter { it.isNotEmpty() }
            var accumulatedPath = if (rootPath.isNotEmpty()) rootPath else ""

            for (segment in pathSegments) {
                accumulatedPath = "$accumulatedPath/$segment"
                pathHistory.add("$baseUrl$accumulatedPath")
            }
        }
    }

    return pathHistory.distinct()
}

fun calculateParentPath(webDavConfig: WebDavConfig, currentPath: String): String? {
    val pathHistory = generatePathHistory(webDavConfig, currentPath)
    return if (pathHistory.size > 1) {
        pathHistory[pathHistory.size - 2]
    } else {
        null
    }
}

fun normalizePath(webDavConfig: WebDavConfig, path: String): String {
    val normalizedPath = path.trimEnd('/')
    return if (normalizedPath.startsWith("http")) {
        normalizedPath
    } else {
        val baseUrl = parseWebDavBaseUrl(webDavConfig)
        val rootPath = parseWebDavRootPath(webDavConfig)
        if (rootPath.isNotEmpty()) {
            "$baseUrl$rootPath/$normalizedPath".trimEnd('/')
        } else {
            "$baseUrl/$normalizedPath".trimEnd('/')
        }
    }
}

@Composable
fun getFileIcon(fileExtension: String): ImageVector {
    return when (fileExtension.lowercase()) {
        "mp3", "m4a", "flac", "wav", "ogg", "aac", "wma" -> Icons.Default.AudioFile
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg" -> Icons.Default.Image
        "txt", "md", "pdf", "doc", "docx", "rtf" -> Icons.Default.Description
        else -> Icons.Default.Description
    }
}
