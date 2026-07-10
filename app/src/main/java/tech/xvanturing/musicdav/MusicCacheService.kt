package tech.xvanturing.musicdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import tech.xvanturing.musicdav.data.MusicFile
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.player.CacheMetadata
import tech.xvanturing.musicdav.player.MusicCache

data class CacheTask(
    val id: String,
    val musicFile: MusicFile,
    val config: WebDavConfig,
    var progress: Int = 0,
    var status: CacheTaskStatus = CacheTaskStatus.PENDING
)

enum class CacheTaskStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

class MusicCacheService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationManager: NotificationManager? = null

    private val activeTasks = mutableMapOf<String, CacheTask>()
    private val taskListeners = mutableListOf<CacheTaskListener>()

    companion object {
        private const val CHANNEL_ID = "music_cache_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_CACHE = "tech.xvanturing.musicdav.action.START_CACHE"
        const val ACTION_CANCEL_CACHE = "tech.xvanturing.musicdav.action.CANCEL_CACHE"
        const val EXTRA_MUSIC_FILE = "music_file"
        const val EXTRA_WEBDAV_CONFIG = "webdav_config"
        const val EXTRA_TASK_ID = "task_id"

        fun startCaching(context: Context, musicFile: MusicFile, config: WebDavConfig) {
            val intent = Intent(context, MusicCacheService::class.java).apply {
                action = ACTION_START_CACHE
                putExtra(EXTRA_MUSIC_FILE, musicFileToJson(musicFile))
                putExtra(EXTRA_WEBDAV_CONFIG, configToJson(config))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelCaching(context: Context, taskId: String) {
            val intent = Intent(context, MusicCacheService::class.java).apply {
                action = ACTION_CANCEL_CACHE
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }

        private fun musicFileToJson(musicFile: MusicFile): String {
            return JSONObject().apply {
                put("name", musicFile.name)
                put("url", musicFile.url)
                put("path", musicFile.path)
                put("size", musicFile.size)
                put("modifiedDate", musicFile.modifiedDate)
                if (musicFile.serverConfigId != null) put("serverConfigId", musicFile.serverConfigId)
            }.toString()
        }

        private fun jsonToMusicFile(json: String): MusicFile {
            val obj = JSONObject(json)
            return MusicFile(
                name = obj.optString("name", ""),
                url = obj.optString("url", ""),
                path = obj.optString("path", ""),
                size = obj.optLong("size", 0L),
                modifiedDate = obj.optLong("modifiedDate", 0L),
                serverConfigId = if (obj.has("serverConfigId")) obj.optString("serverConfigId", null) else null
            )
        }

        private fun configToJson(config: WebDavConfig): String {
            return JSONObject().apply {
                put("url", config.url)
                put("username", config.username)
                put("password", config.password)
            }.toString()
        }

        private fun jsonToConfig(json: String): WebDavConfig {
            val obj = JSONObject(json)
            return WebDavConfig(
                url = obj.optString("url", ""),
                username = obj.optString("username", ""),
                password = obj.optString("password", "")
            )
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicCacheService = this@MusicCacheService
    }

    interface CacheTaskListener {
        fun onTaskStarted(taskId: String, musicFile: MusicFile)
        fun onTaskProgress(taskId: String, progress: Int)
        fun onTaskCompleted(taskId: String, path: String?)
        fun onTaskFailed(taskId: String, error: Throwable)
        fun onAllTasksCompleted()
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d("MusicCacheService", "✅ Service created")
        Log.d("MusicCacheService", "   Active tasks: ${activeTasks.size}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MusicCacheService", "📡 onStartCommand: ${intent?.action}")
        Log.d("MusicCacheService", "   Active tasks: ${activeTasks.size}")

        when (intent?.action) {
            ACTION_START_CACHE -> {
                val musicFileJson = intent.getStringExtra(EXTRA_MUSIC_FILE) ?: run {
                    Log.e("MusicCacheService", "❌ Missing EXTRA_MUSIC_FILE")
                    return START_NOT_STICKY
                }
                val configJson = intent.getStringExtra(EXTRA_WEBDAV_CONFIG) ?: run {
                    Log.e("MusicCacheService", "❌ Missing EXTRA_WEBDAV_CONFIG")
                    return START_NOT_STICKY
                }
                val musicFile = Companion.jsonToMusicFile(musicFileJson)
                val config = Companion.jsonToConfig(configJson)
                Log.d("MusicCacheService", "📥 Queueing cache task: ${musicFile.name}")
                startCaching(musicFile, config)
            }
            ACTION_CANCEL_CACHE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: run {
                    Log.e("MusicCacheService", "❌ Missing EXTRA_TASK_ID")
                    return START_NOT_STICKY
                }
                Log.d("MusicCacheService", "🛑 Cancelling task: $taskId")
                cancelTask(taskId)
            }
            else -> {
                Log.w("MusicCacheService", "⚠️ Unknown action: ${intent?.action}")
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MusicCacheService", "🛑 Service destroyed")
        Log.d("MusicCacheService", "   Active tasks: ${activeTasks.size}")
        serviceScope.coroutineContext[Job]?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Cache",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music download notifications"
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun startCaching(musicFile: MusicFile, config: WebDavConfig) {
        val taskId = generateTaskId(musicFile)
        val task = CacheTask(
            id = taskId,
            musicFile = musicFile,
            config = config,
            status = CacheTaskStatus.DOWNLOADING
        )
        activeTasks[taskId] = task

        Log.d("MusicCacheService", "🎵 Starting cache: ${musicFile.name} (ID: $taskId)")
        notifyListeners { it.onTaskStarted(taskId, musicFile) }
        updateForegroundNotification()

        serviceScope.launch {
            MusicCache.cacheSong(applicationContext, musicFile, config) { progress ->
                task.progress = progress
               Log.d("MusicCacheService", "📥 Cache progress: ${musicFile.name} - $progress%")
                notifyListeners { it.onTaskProgress(taskId, progress) }
                updateForegroundNotification()
            }
                .onSuccess { path ->
                    Log.d("MusicCacheService", "✅ Cache completed: ${musicFile.name}")
                    Log.d("MusicCacheService", "   Cached at: $path")
                    
                    // 保持 DOWNLOADING 状态来显示 100% 的通知
                    task.progress = 100
                    updateForegroundNotification()
                    
                    // 给通知系统时间来显示 100%
                    kotlinx.coroutines.delay(200)
                    
                    // 然后更新状态并移除任务
                    task.status = CacheTaskStatus.COMPLETED
                    notifyListeners { it.onTaskCompleted(taskId, path) }
                    activeTasks.remove(taskId)
                    checkAllTasksCompleted()
                }
                .onFailure { error ->
                    Log.e("MusicCacheService", "❌ Cache failed: ${musicFile.name}", error)
                    Log.e("MusicCacheService", "   Error: ${error.message}")
                    task.status = CacheTaskStatus.FAILED
                    updateForegroundNotification()
                    notifyListeners { it.onTaskFailed(taskId, error) }
                    activeTasks.remove(taskId)
                    checkAllTasksCompleted()
                }
        }
    }

    private fun cancelTask(taskId: String) {
        val task = activeTasks[taskId]
        if (task != null && task.status == CacheTaskStatus.DOWNLOADING) {
            task.status = CacheTaskStatus.CANCELLED
            activeTasks.remove(taskId)
            updateForegroundNotification()
            notifyListeners { it.onTaskFailed(taskId, RuntimeException("Task cancelled")) }
            checkAllTasksCompleted()
        }
    }

    private fun checkAllTasksCompleted() {
        if (activeTasks.isEmpty()) {
            notifyListeners { it.onAllTasksCompleted() }
        }
    }

    private fun updateForegroundNotification() {
        if (activeTasks.isEmpty()) {
            Log.d("MusicCacheService", "🔕 Stopping foreground notification (no active tasks)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val downloadingTasks = activeTasks.values.filter { it.status == CacheTaskStatus.DOWNLOADING }
        if (downloadingTasks.isEmpty()) {
            Log.d("MusicCacheService", "🔕 Stopping foreground notification (no downloading tasks)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val firstTask = downloadingTasks.first()
        Log.d("MusicCacheService", "📢 Updating notification: ${firstTask.musicFile.name} (${firstTask.progress}%)")
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caching Music")
            .setContentText("${firstTask.musicFile.name} - ${firstTask.progress}%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun generateTaskId(musicFile: MusicFile): String {
        return "${musicFile.url}_${System.currentTimeMillis()}"
    }

    fun addListener(listener: CacheTaskListener) {
        taskListeners.add(listener)
    }

    fun removeListener(listener: CacheTaskListener) {
        taskListeners.remove(listener)
    }

    private fun notifyListeners(block: (CacheTaskListener) -> Unit) {
        taskListeners.toList().forEach { block(it) }
    }

    fun getActiveTasks(): List<CacheTask> {
        return activeTasks.values.toList()
    }

    fun getTask(taskId: String): CacheTask? {
        return activeTasks[taskId]
    }

    fun getCachedSongs(): List<CacheMetadata> {
        return runBlocking {
            MusicCache.getCachedSongs(applicationContext)
        }
    }

    fun getTotalCacheSize(): Long {
        return runBlocking {
            MusicCache.getCurrentCacheSize(applicationContext)
        }
    }
}
