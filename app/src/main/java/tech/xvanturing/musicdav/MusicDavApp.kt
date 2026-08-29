package tech.xvanturing.musicdav

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.xvanturing.musicdav.player.MusicCache
import tech.xvanturing.musicdav.util.AppLog

/**
 * 主要作用是尽早初始化落盘日志：UI 进程、SimpleMusicService、MusicCacheService 同进程，
 * 任何一个入口被系统拉起（比如通知栏点了播放、App 界面早已销毁）都会先走到这里，
 * 后台播放链路的日志才不会漏。顺带清一次上次进程被杀留下的下载残片。
 */
class MusicDavApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            MusicCache.cleanupPartFiles(this@MusicDavApp)
        }
    }
}
