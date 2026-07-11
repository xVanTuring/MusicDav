package tech.xvanturing.musicdav.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.xvanturing.musicdav.data.Album
import tech.xvanturing.musicdav.data.PlaylistCache
import tech.xvanturing.musicdav.data.getWebDavConfig
import tech.xvanturing.musicdav.data.resolveAlbumUrl

/**
 * 从头播放某专辑（首页胶片点击用）。用已预热的 PlaylistCache 读取该专辑歌曲列表，
 * 设置好凭据/封面映射后从第 0 首开始播。若无缓存歌曲，回退调用 onNeedsDetail()（打开详情页去抓取）。
 */
suspend fun playAlbumFromStart(
    context: Context,
    album: Album,
    playlistController: PlaylistStateController,
    onNeedsDetail: () -> Unit
) {
    val config = album.getWebDavConfig(context)
    val songs = withContext(Dispatchers.IO) { PlaylistCache.load(context, album.id) }
    if (songs.isEmpty()) {
        onNeedsDetail()
        return
    }
    val effectiveCover = resolveAlbumUrl(album.coverImageUrl, config.url)
    playlistController.setCredentials(config)
    playlistController.setSongAlbumCovers(songs, effectiveCover)
    playlistController.setCurrentWebDavConfig(config)
    playlistController.loadCachedCovers(context, songs)
    playlistController.setCurrentAlbumId(album.id)
    playlistController.loadPlaylist(songs)
    playlistController.setPlaylistAndPlay(0)
}
