package tech.xvanturing.musicdav.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * 全局唯一的黑胶旋转角（度）。首页轮播在播的碟、以及播放页的大碟都读这个值，
 * 因此两处旋转量永远一致、切屏无跳变。由 [VinylRotationDriver]（挂在 App 根部）唯一驱动：
 * 只在“正在播放且有胶片可见”时推进，暂停/离开时定格保持，切换专辑时归零。
 */
object VinylRotationClock {
    var angle by mutableFloatStateOf(0f)
        internal set

    // 当前 angle 属于哪张专辑。碟只在确认「angle 就是自己这张的」时才用它旋转，否则按 0 显示。
    // 因为 driver 的角度归零发生在 LaunchedEffect（组合之后），而碟在切专辑那一帧就已经用新的
    // spin=true 重组并读了 clock.angle——此时 albumId 还是上一张，碟便知道这角度不是自己的、按 0
    // 画，避免闪现上一张残留的角度（切专辑瞬间封面"闪一下/鬼影再转"）。
    var albumId by mutableStateOf<String?>(null)
        internal set
}

@Composable
fun VinylRotationDriver(playingAlbumId: String?, active: Boolean) {
    val lastAlbumId = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playingAlbumId, active) {
        if (playingAlbumId != lastAlbumId.value) {
            VinylRotationClock.angle = 0f
            VinylRotationClock.albumId = playingAlbumId
            lastAlbumId.value = playingAlbumId
        }
        if (active) {
            val degreesPerMs = 360f / 12000f   // 统一 12 秒一圈
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                VinylRotationClock.angle =
                    (VinylRotationClock.angle + (now - last) / 1_000_000f * degreesPerMs) % 360f
                last = now
            }
        }
    }
}
