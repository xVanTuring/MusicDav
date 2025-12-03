package com.spotify.music.ui.screen

import com.spotify.music.data.WebDavConfig
import org.junit.Test
import org.junit.Assert.*
import java.net.URL

/**
 * Unit tests for FilePickerDialog path calculation functions
 */
class FilePickerDialogTest {

    // Extract the helper functions from FilePickerDialog for testing
    private fun parseWebDavBaseUrl(webDavConfig: WebDavConfig): String {
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

    private fun parseWebDavRootPath(webDavConfig: WebDavConfig): String {
        val url = webDavConfig.url.trimEnd('/')
        return try {
            val urlObj = URL(url)
            urlObj.path.trimEnd('/')
        } catch (e: Exception) {
            ""
        }
    }

    private fun generatePathHistory(webDavConfig: WebDavConfig, currentPath: String): List<String> {
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

    private fun calculateParentPath(webDavConfig: WebDavConfig, currentPath: String): String? {
        val pathHistory = generatePathHistory(webDavConfig, currentPath)
        return if (pathHistory.size > 1) {
            pathHistory[pathHistory.size - 2]
        } else {
            null
        }
    }

    @Test
    fun testParseWebDavBaseUrl_withPath() {
        val config = WebDavConfig("https://webdav.example.com/remote.php/dav/files/username/", "user", "pass")
        val baseUrl = parseWebDavBaseUrl(config)
        assertEquals("https://webdav.example.com", baseUrl)
    }

    @Test
    fun testParseWebDavBaseUrl_withPort() {
        val config = WebDavConfig("https://dav.example.com:8080/storage/", "user", "pass")
        val baseUrl = parseWebDavBaseUrl(config)
        assertEquals("https://dav.example.com:8080", baseUrl)
    }

    @Test
    fun testParseWebDavBaseUrl_noPath() {
        val config = WebDavConfig("http://localhost/", "user", "pass")
        val baseUrl = parseWebDavBaseUrl(config)
        assertEquals("http://localhost", baseUrl)
    }

    @Test
    fun testParseWebDavRootPath_withPath() {
        val config = WebDavConfig("https://webdav.example.com/remote.php/dav/files/username/", "user", "pass")
        val rootPath = parseWebDavRootPath(config)
        assertEquals("/remote.php/dav/files/username", rootPath)
    }

    @Test
    fun testParseWebDavRootPath_noPath() {
        val config = WebDavConfig("https://files.example.com/", "user", "pass")
        val rootPath = parseWebDavRootPath(config)
        assertEquals("", rootPath)
    }

    @Test
    fun testGeneratePathHistory_deepNestedPath() {
        val config = WebDavConfig("https://webdav.example.com/remote.php/dav/files/username/", "user", "pass")
        val currentPath = "https://webdav.example.com/remote.php/dav/files/username/music/albums/rock"

        val pathHistory = generatePathHistory(config, currentPath)

        assertEquals(4, pathHistory.size)
        assertEquals("https://webdav.example.com/remote.php/dav/files/username", pathHistory[0])
        assertEquals("https://webdav.example.com/remote.php/dav/files/username/music", pathHistory[1])
        assertEquals("https://webdav.example.com/remote.php/dav/files/username/music/albums", pathHistory[2])
        assertEquals("https://webdav.example.com/remote.php/dav/files/username/music/albums/rock", pathHistory[3])
    }

    @Test
    fun testGeneratePathHistory_simplePath() {
        val config = WebDavConfig("http://localhost/", "user", "pass")
        val currentPath = "http://localhost/Music"

        val pathHistory = generatePathHistory(config, currentPath)

        assertEquals(2, pathHistory.size)
        assertEquals("http://localhost", pathHistory[0])
        assertEquals("http://localhost/Music", pathHistory[1])
    }

    @Test
    fun testGeneratePathHistory_atRoot() {
        val config = WebDavConfig("https://dav.example.com:8080/storage/", "user", "pass")
        val currentPath = "https://dav.example.com:8080/storage"

        val pathHistory = generatePathHistory(config, currentPath)

        assertEquals(1, pathHistory.size)
        assertEquals("https://dav.example.com:8080/storage", pathHistory[0])
    }

    @Test
    fun testCalculateParentPath_deepNestedPath() {
        val config = WebDavConfig("https://webdav.example.com/remote.php/dav/files/username/", "user", "pass")
        val currentPath = "https://webdav.example.com/remote.php/dav/files/username/music/albums/rock"

        val parentPath = calculateParentPath(config, currentPath)

        assertEquals("https://webdav.example.com/remote.php/dav/files/username/music/albums", parentPath)
    }

    @Test
    fun testCalculateParentPath_firstLevel() {
        val config = WebDavConfig("http://localhost/", "user", "pass")
        val currentPath = "http://localhost/Music"

        val parentPath = calculateParentPath(config, currentPath)

        assertEquals("http://localhost", parentPath)
    }

    @Test
    fun testCalculateParentPath_atRoot() {
        val config = WebDavConfig("https://files.example.com/storage/", "user", "pass")
        val currentPath = "https://files.example.com/storage"

        val parentPath = calculateParentPath(config, currentPath)

        assertNull(parentPath) // No parent when at root
    }

    @Test
    fun testPathHistory_edgeCases() {
        // Test with trailing slashes
        val config = WebDavConfig("https://example.com/dav/", "user", "pass")
        val currentPath = "https://example.com/dav///music//rock//"

        val pathHistory = generatePathHistory(config, currentPath)

        assertEquals(3, pathHistory.size)
        assertEquals("https://example.com/dav", pathHistory[0])
        assertEquals("https://example.com/dav/music", pathHistory[1])
        assertEquals("https://example.com/dav/music/rock", pathHistory[2])
    }
}