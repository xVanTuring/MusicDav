package tech.xvanturing.musicdav.player

import okhttp3.Credentials

/**
 * 按 URL 前缀查 WebDAV 鉴权头。
 *
 * 起因：收藏夹/搜索结果是**跨服务器**的播放列表。原先整个 ExoPlayer 只有一份全局默认请求头，
 * 靠 onMediaItemTransition 在切歌**之后**改——而 ExoPlayer 会提前几秒预加载下一首，预加载发生时
 * 头还是上一首那台服务器的凭据，下一首落在另一台服务器上就 401，表现为"后台播到某首突然停，
 * 也没提示"。这里按歌曲 URL 所属服务器现查现用，预加载也拿得到对的凭据。
 */
object WebDavAuthStore {
    // baseUrl -> Basic 头。用 LinkedHashMap + synchronized，写少读多（每次网络 open 一次）。
    private val byBaseUrl = LinkedHashMap<String, String>()

    @Volatile
    private var defaultHeader: String? = null

    /** 兜底凭据：没有任何 baseUrl 命中时用它（单专辑播放的常见情形）。 */
    fun setDefault(username: String, password: String) {
        defaultHeader = Credentials.basic(username, password)
    }

    fun register(baseUrl: String, username: String, password: String) {
        if (baseUrl.isBlank()) return
        val normalized = baseUrl.trimEnd('/')
        val header = Credentials.basic(username, password)
        synchronized(byBaseUrl) { byBaseUrl[normalized] = header }
    }

    /** 最长前缀匹配：同一台服务器上配了多个子目录时，取最具体的那条。 */
    fun headerFor(url: String): String? {
        synchronized(byBaseUrl) {
            var best: String? = null
            var bestLength = -1
            for ((base, header) in byBaseUrl) {
                if (base.length > bestLength && url.startsWith(base)) {
                    best = header
                    bestLength = base.length
                }
            }
            return best ?: defaultHeader
        }
    }
}
