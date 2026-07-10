package tech.xvanturing.musicdav.data

import android.util.Log
import tech.xvanturing.musicdav.webdav.WebDavClient

// Resolves which of a ServerConfig's candidate addresses (urls) is currently reachable.
// Candidates are probed in order (e.g. LAN address first, public domain as fallback) and the
// first reachable one wins; the choice is cached in memory for the process lifetime so we don't
// re-probe on every request. Call invalidate() when a request using the cached address fails, so
// the next resolve() re-probes instead of sticking to a dead address.
object ServerAddressResolver {
    private const val TAG = "ServerAddressResolver"

    private val resolvedUrls = mutableMapOf<String, String>()
    private val webDavClient = WebDavClient()

    suspend fun resolve(serverConfig: ServerConfig): String {
        resolvedUrls[serverConfig.id]?.let { return it }

        for (candidate in serverConfig.urls) {
            if (candidate.isBlank()) continue
            val reachable = webDavClient.probeReachable(candidate, serverConfig.username, serverConfig.password)
            if (reachable) {
                Log.d(TAG, "Resolved ${serverConfig.name} -> $candidate")
                resolvedUrls[serverConfig.id] = candidate
                return candidate
            }
        }

        Log.w(TAG, "No reachable address for ${serverConfig.name}, falling back to primary")
        return serverConfig.url
    }

    fun invalidate(serverConfigId: String) {
        resolvedUrls.remove(serverConfigId)
    }

    // Runs action against the currently resolved address; if it fails, invalidates the choice and
    // retries with the next reachable candidate (up to one attempt per candidate address).
    suspend fun <T> withReachableAddress(
        serverConfig: ServerConfig,
        action: suspend (baseUrl: String) -> Result<T>
    ): Result<T> {
        val attempted = mutableSetOf<String>()
        var lastFailure: Result<T>? = null
        repeat(serverConfig.urls.size.coerceAtLeast(1)) {
            val baseUrl = resolve(serverConfig)
            if (!attempted.add(baseUrl)) return@repeat
            val result = action(baseUrl)
            if (result.isSuccess) return result
            lastFailure = result
            invalidate(serverConfig.id)
        }
        return lastFailure ?: Result.failure(java.io.IOException("No reachable address for ${serverConfig.name}"))
    }
}
