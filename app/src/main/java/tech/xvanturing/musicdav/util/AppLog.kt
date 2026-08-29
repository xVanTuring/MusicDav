package tech.xvanturing.musicdav.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 落盘日志。
 *
 * 为什么不能只靠 logcat：要排查的是"后台播放偶发失败"——出问题时 App 在后台、可能已经过了
 * 几十分钟甚至被系统回收过一轮，等用户发现再连电脑抓 logcat，环形缓冲区早就把当时的记录冲掉了。
 * 所以播放/网络/缓存这条链路上的关键事件另外写一份到应用私有目录，出问题后一键导出。
 *
 * 写入在单线程 executor 上做，调用方（含播放线程、ExoPlayer 加载线程）不会被磁盘 IO 阻塞。
 * 两个文件轮转：app.log 写满 [MAX_BYTES] 就滚到 app.log.1，总量上限约 1MB，不会无限长大。
 */
object AppLog {
    private const val DIR = "logs"
    private const val CURRENT = "app.log"
    private const val PREVIOUS = "app.log.1"
    private const val MAX_BYTES = 512L * 1024

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLog").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    /** 在 Application.onCreate 里调一次即可：UI 进程和两个 Service 同进程，共用这份初始化。 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        installCrashHandler()
        i("AppLog", "---- log start (pid=${android.os.Process.myPid()}) ----")
    }

    fun d(tag: String, message: String) = write("D", tag, message, null).also { Log.d(tag, message) }
    fun i(tag: String, message: String) = write("I", tag, message, null).also { Log.i(tag, message) }
    fun w(tag: String, message: String, t: Throwable? = null) =
        write("W", tag, message, t).also { Log.w(tag, message, t) }

    fun e(tag: String, message: String, t: Throwable? = null) =
        write("E", tag, message, t).also { Log.e(tag, message, t) }

    private fun write(level: String, tag: String, message: String, t: Throwable?) {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        executor.execute {
            try {
                val file = currentFile(context) ?: return@execute
                if (file.length() > MAX_BYTES) rotate(context, file)
                val line = buildString {
                    append(timeFormat.format(Date(now)))
                    append(' ').append(level)
                    append('/').append(tag)
                    append(" [").append(thread).append("] ")
                    append(message)
                    append('\n')
                    if (t != null) append(stackTraceOf(t))
                }
                file.appendText(line)
            } catch (_: Throwable) {
                // 日志本身出问题绝不能影响业务
            }
        }
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }

    private fun logDir(context: Context): File? = try {
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
    } catch (_: Throwable) {
        null
    }

    private fun currentFile(context: Context): File? = logDir(context)?.let { File(it, CURRENT) }

    private fun rotate(context: Context, current: File) {
        val dir = logDir(context) ?: return
        val previous = File(dir, PREVIOUS)
        if (previous.exists()) previous.delete()
        current.renameTo(previous)
    }

    /** 导出用的完整文本：旧文件在前、当前文件在后，按时间顺序读得通。 */
    fun dump(context: Context): String {
        val dir = logDir(context) ?: return ""
        return buildString {
            File(dir, PREVIOUS).takeIf { it.exists() }?.let { append(it.readText()) }
            File(dir, CURRENT).takeIf { it.exists() }?.let { append(it.readText()) }
        }
    }

    fun clear(context: Context) {
        val dir = logDir(context) ?: return
        File(dir, PREVIOUS).delete()
        File(dir, CURRENT).delete()
    }

    /** 当前落盘日志的总字节数，用于在 UI 上显示"有多少可导出"。 */
    fun sizeBytes(context: Context): Long {
        val dir = logDir(context) ?: return 0L
        return File(dir, PREVIOUS).length() + File(dir, CURRENT).length()
    }

    /**
     * 崩溃兜底：未捕获异常先写进日志再交回系统默认处理器（保留原有的崩溃弹窗/上报行为）。
     * 后台播放进程被异常打断时，这是唯一能留下现场的地方。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("Crash", "未捕获异常 thread=${thread.name}", throwable)
                // 崩溃后进程即将结束，必须同步等日志真正落盘
                executor.submit { }.get(1, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
