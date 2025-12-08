# MusicDav 音乐缓存系统设计文档

## 概述

MusicDav 实现了一套完整的音乐文件缓存系统，用于提升用户体验，减少重复的网络请求，实现离线播放能力。该系统由三个核心组件组成：

1. **MusicCacheManager** - 缓存管理核心
2. **CachedHttpDataSource** - 透明缓存数据源
3. **CacheManagementScreen** - 缓存管理UI

## 架构设计

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    ExoPlayer (Media3)                       │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              CachedHttpDataSource (透明代理)                │
│  ┌─────────────────────┐      ┌─────────────────────────┐  │
│  │  缓存命中？         │ YES  │   读取本地文件          │  │
│  │  getCachedFile()    │─────→│   FileInputStream       │  │
│  └─────────────────────┘      └─────────────────────────┘  │
│            │ NO                                              │
│            ↓                                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  StreamingCachingInputStream (流式缓存)            │   │
│  │  - 读取上游数据                                     │   │
│  │  - 同时写入缓存文件                                 │   │
│  │  - 更新缓存进度                                     │   │
│  └─────────────────────────────────────────────────────┘   │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                  MusicCacheManager                          │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  缓存索引 (ConcurrentHashMap<String, CacheEntry>)   │  │
│  │  - URL → 缓存文件映射                               │  │
│  │  - 最后访问时间 (LRU)                               │  │
│  │  - 文件大小                                         │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  正在缓存任务 (ConcurrentHashMap)                   │  │
│  │  - 进度跟踪                                         │  │
│  │  - 实时更新                                         │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  磁盘存储 (/cache/music/)                           │  │
│  │  - 缓存文件 (MD5 命名)                              │  │
│  │  - 元数据文件 (cache_metadata.json)                │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 核心组件详解

### 1. MusicCacheManager - 缓存管理器

#### 1.1 设计模式

**单例模式 (Thread-Safe Singleton)**
```kotlin
companion object {
    @Volatile
    private var INSTANCE: MusicCacheManager? = null

    fun getInstance(context: Context): MusicCacheManager {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: MusicCacheManager(context.applicationContext)
                .also { INSTANCE = it }
        }
    }
}
```

- 使用 `@Volatile` 保证可见性
- 双重检查锁定 (Double-Checked Locking)
- 保证全局唯一实例

#### 1.2 数据结构

**缓存条目 (CacheEntry)**
```kotlin
data class CacheEntry(
    val file: File,          // 缓存文件
    val url: String,         // 原始URL
    val lastAccessed: Long,  // 最后访问时间 (用于LRU)
    val size: Long           // 文件大小
)
```

**正在缓存任务 (CachingTask)**
```kotlin
data class CachingTask(
    val url: String,
    val fileName: String,
    val totalSize: Long?,        // 可能未知
    var transferredSize: Long,   // 已传输大小
    val startTime: Long,
    var status: CachingStatus
)
```

**内存索引**
```kotlin
// 已缓存文件索引 (缓存键 → 缓存条目)
private val cachedFiles = ConcurrentHashMap<String, CacheEntry>()

// 正在缓存任务索引 (缓存键 → 任务信息)
private val cachingTasks = ConcurrentHashMap<String, CachingTask>()

// 当前缓存总大小 (原子操作)
private val currentCacheSize = AtomicLong(0L)
```

#### 1.3 缓存键生成

**MD5 Hash 算法**
```kotlin
fun getCacheKey(url: String): String {
    return try {
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(url.toByteArray())
        hash.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        url.hashCode().toString()  // 降级方案
    }
}
```

- **目的**: 将任意长度的URL转换为固定长度的文件名
- **优势**:
  - 避免文件系统对文件名长度的限制
  - 避免URL中特殊字符导致的文件系统问题
  - 保证文件名唯一性
- **降级**: MD5失败时使用hashCode

#### 1.4 LRU 缓存淘汰策略

**空间管理**
```kotlin
private val maxCacheSizeBytes = 500 * 1024 * 1024L  // 500MB
```

**淘汰算法**
```kotlin
private fun ensureCacheSpace(requiredSpace: Long) {
    val availableSpace = maxCacheSizeBytes - currentCacheSize.get()

    if (requiredSpace > availableSpace) {
        // 按最后访问时间排序 (Least Recently Used)
        val sortedEntries = cachedFiles.values.sortedBy { it.lastAccessed }
        var freedSpace = 0L

        for (entry in sortedEntries) {
            if (entry.file.delete()) {
                synchronized(cacheLock) {
                    val cacheKey = getCacheKey(entry.url)
                    cachedFiles.remove(cacheKey)
                    currentCacheSize.addAndGet(-entry.size)
                }
                freedSpace += entry.size

                if (freedSpace >= requiredSpace - availableSpace) {
                    break
                }
            }
        }

        if (freedSpace > 0) {
            saveCacheMetadata()
        }
    }
}
```

**工作原理**:
1. 检查是否有足够空间
2. 如果空间不足，按访问时间排序所有缓存条目
3. 从最久未访问的文件开始删除
4. 直到释放足够空间
5. 更新元数据

#### 1.5 元数据持久化

**JSON 格式存储**
```kotlin
private fun saveCacheMetadata() {
    val jsonArray = JSONArray()
    cachedFiles.values.forEach { entry ->
        val jsonObject = JSONObject().apply {
            put("cacheKey", getCacheKey(entry.url))
            put("url", entry.url)
            put("lastAccessed", entry.lastAccessed)
            put("size", entry.size)
        }
        jsonArray.put(jsonObject)
    }

    FileOutputStream(metadataFile).use { output ->
        output.write(jsonArray.toString(2).toByteArray())
    }
}
```

**元数据文件示例**:
```json
[
  {
    "cacheKey": "5d41402abc4b2a76b9719d911017c592",
    "url": "http://example.com/music/song1.mp3",
    "lastAccessed": 1702345678901,
    "size": 4567890
  },
  {
    "cacheKey": "098f6bcd4621d373cade4e832627b4f6",
    "url": "http://example.com/music/song2.mp3",
    "lastAccessed": 1702345678902,
    "size": 3456789
  }
]
```

**持久化优势**:
- 应用重启后保留缓存索引
- 避免重新扫描文件系统
- 保留URL信息（无法从缓存文件名恢复）
- 保留访问时间（用于LRU排序）

#### 1.6 启动时缓存扫描

```kotlin
private fun scanExistingCache() {
    if (!cacheDir.exists()) return

    // 1. 加载元数据
    loadCacheMetadata()

    var totalSize = 0L

    // 2. 扫描磁盘文件
    cacheDir.listFiles()?.forEach { file ->
        if (file.isFile && file.name != "cache_metadata.json") {
            val cacheKey = file.name
            val entry = cachedFiles[cacheKey]

            if (entry != null) {
                // 验证文件大小
                val actualSize = file.length()
                if (actualSize != entry.size) {
                    cachedFiles[cacheKey] = entry.copy(size = actualSize)
                }
                totalSize += actualSize
            } else {
                // 创建缺失的元数据条目
                val fileSize = file.length()
                if (fileSize > 0) {
                    val newEntry = CacheEntry(
                        file = file,
                        url = "",
                        lastAccessed = file.lastModified(),
                        size = fileSize
                    )
                    cachedFiles[cacheKey] = newEntry
                    totalSize += fileSize
                }
            }
        }
    }

    // 3. 清理不存在的文件条目
    val existingFiles = cacheDir.listFiles()
        ?.filter { it.isFile && it.name != "cache_metadata.json" }
        ?.map { it.name }?.toSet() ?: emptySet()
    cachedFiles.keys.removeAll { it !in existingFiles }

    currentCacheSize.set(totalSize)
    saveCacheMetadata()
}
```

**扫描流程**:
1. 加载保存的元数据
2. 扫描磁盘上的实际文件
3. 同步元数据和磁盘状态
4. 清理不一致的条目
5. 更新缓存总大小

#### 1.7 缓存任务跟踪

**任务生命周期**:

```
startCaching()  →  updateCachingProgress()  →  finishCaching()
                                            ↘
                                              failCaching()
```

**API 接口**:

```kotlin
// 1. 开始缓存
fun startCaching(url: String, fileName: String?, totalSize: Long?): String {
    val cacheKey = getCacheKey(url)
    val task = CachingTask(
        url = url,
        fileName = fileName ?: extractFileNameFromUrl(url),
        totalSize = totalSize,
        transferredSize = 0L
    )
    cachingTasks[cacheKey] = task
    return cacheKey
}

// 2. 更新进度
fun updateCachingProgress(url: String, transferred: Long, total: Long?) {
    val cacheKey = getCacheKey(url)
    val task = cachingTasks[cacheKey] ?: return

    synchronized(cacheLock) {
        task.transferredSize = transferred
        if (total != null) {
            cachingTasks[cacheKey] = task.copy(totalSize = total)
        }
    }
}

// 3. 完成缓存
fun finishCaching(url: String, file: File, size: Long) {
    val cacheKey = getCacheKey(url)
    cachingTasks.remove(cacheKey)
    registerCachedFile(url, file, size)
}

// 4. 缓存失败
fun failCaching(url: String) {
    val cacheKey = getCacheKey(url)
    cachingTasks.remove(cacheKey)
}
```

**UI 实时反馈**:
```kotlin
fun getCachingTasks(): List<CachingTaskInfo> {
    return cachingTasks.values.map { task ->
        val progressPercentage = if (task.totalSize != null && task.totalSize > 0) {
            (task.transferredSize.toFloat() / task.totalSize) * 100f
        } else {
            0f
        }
        CachingTaskInfo(
            url = task.url,
            fileName = task.fileName,
            totalSize = task.totalSize,
            transferredSize = task.transferredSize,
            startTime = task.startTime,
            status = task.status,
            progressPercentage = progressPercentage
        )
    }
}
```

### 2. CachedHttpDataSource - 透明缓存层

#### 2.1 设计模式

**装饰器模式 (Decorator Pattern)**

```kotlin
class CachedHttpDataSource(
    private val upstreamFactory: HttpDataSource.Factory,
    private val cacheManager: MusicCacheManager
) : HttpDataSource {
    // 包装上游HttpDataSource
    private var upstream: HttpDataSource? = null
}
```

- 不修改ExoPlayer代码
- 透明地添加缓存功能
- 对上层完全透明

#### 2.2 读取策略

**缓存命中流程**:

```kotlin
override fun open(dataSpec: DataSpec): Long {
    val url = dataSpec.uri.toString()

    // 尝试从缓存读取
    val cachedFile = cacheManager.getCachedFile(url)

    return if (cachedFile != null && cachedFile.exists()) {
        // 缓存命中 - 本地读取
        openCachedFile(cachedFile, dataSpec)
    } else {
        // 缓存未命中 - 网络读取并缓存
        openFromNetwork(dataSpec)
    }
}
```

**支持范围请求 (Range Request)**:

```kotlin
private fun openCachedFile(file: File, dataSpec: DataSpec): Long {
    val fileInputStream = FileInputStream(file)
    inputStream = fileInputStream

    val fileLength = file.length()
    val position = if (dataSpec.position >= 0) dataSpec.position else 0

    if (position > 0) {
        fileInputStream.skip(position)  // 跳转到指定位置
    }

    bytesRemaining = if (dataSpec.length != Long.MAX_VALUE) {
        dataSpec.length
    } else {
        fileLength - position
    }

    return bytesRemaining
}
```

- 支持从文件中间开始读取
- 适配ExoPlayer的seek操作
- 保证播放器功能完整性

#### 2.3 流式缓存实现

**StreamingCachingInputStream 核心原理**:

```kotlin
private class StreamingCachingInputStream(
    private val upstream: HttpDataSource,
    private val url: String,
    private val cacheManager: MusicCacheManager,
    private val totalSize: Long?
) : InputStream() {

    private var cacheOutput: FileOutputStream? = null
    private val cacheFile = File(cacheManager.cacheDir,
                                  cacheManager.getCacheKey(url))
    private var bytesReadTotal: Long = 0L

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        // 1. 从上游读取数据
        val bytesRead = upstream.read(b, off, len)

        if (bytesRead > 0) {
            bytesReadTotal += bytesRead

            // 2. 更新进度 (节流)
            if (shouldUpdateProgress()) {
                cacheManager.updateCachingProgress(url, bytesReadTotal, totalSize)
            }

            // 3. 同步写入缓存
            if (isCachingEnabled && cacheOutput != null) {
                try {
                    cacheOutput!!.write(b, off, bytesRead)
                    cacheOutput!!.flush()  // 立即刷新到磁盘
                } catch (e: Exception) {
                    // 缓存失败不影响播放
                    handleCacheFailure()
                }
            }
        }

        return bytesRead
    }
}
```

**关键特性**:

1. **边播放边缓存**: 不需要等待完整下载
2. **进度跟踪**: 实时更新已缓存大小
3. **容错设计**: 缓存失败不影响播放
4. **性能优化**: 进度更新节流，避免过度频繁

**进度更新节流**:

```kotlin
private var lastProgressUpdateTime: Long = 0L
private val progressUpdateThreshold = 64 * 1024L  // 每64KB更新

private fun shouldUpdateProgress(): Boolean {
    val currentTime = System.currentTimeMillis()
    return bytesReadTotal >= progressUpdateThreshold &&
           (currentTime - lastProgressUpdateTime > 500 ||
            bytesReadTotal % progressUpdateThreshold == 0L)
}
```

- 避免UI过度刷新
- 减少锁竞争
- 提升性能

#### 2.4 缓存完成处理

```kotlin
override fun close() {
    if (!upstreamClosed) {
        upstreamClosed = true
        upstream.close()
        closeCache()

        // 更新最终进度
        cacheManager.updateCachingProgress(url, bytesReadTotal, totalSize)

        // 注册缓存文件
        if (isCachingEnabled && cacheFile.exists()) {
            val size = cacheFile.length()
            cacheManager.finishCaching(url, cacheFile, size)
        } else {
            // 清理失败的缓存
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            cacheManager.failCaching(url)
        }
    }
}
```

**完整性保证**:
- 只有完整下载才注册到缓存
- 失败或中断的文件会被清理
- 防止部分文件被识别为有效缓存

### 3. CacheManagementScreen - UI界面

#### 3.1 实时更新机制

```kotlin
LaunchedEffect(Unit) {
    while (true) {
        kotlinx.coroutines.delay(1000)  // 每秒更新
        cacheStats = cacheManager.getCacheStats()
        cachedFiles = cacheManager.getCachedFilesList()
        cachingTasks = cacheManager.getCachingTasks()
    }
}
```

**使用 LaunchedEffect**:
- 自动管理协程生命周期
- 组件销毁时自动取消
- 避免内存泄漏

#### 3.2 缓存统计显示

**数据结构**:
```kotlin
data class CacheStats(
    val totalFiles: Int,
    val totalSize: Long,
    val maxCacheSize: Long
) {
    val usagePercentage: Float
        get() = if (maxCacheSize > 0)
                (totalSize.toFloat() / maxCacheSize) * 100
                else 0f

    val formattedSize: String
        get() = formatFileSize(totalSize)

    val formattedMaxSize: String
        get() = formatFileSize(maxCacheSize)
}
```

**UI 组件**:
```kotlin
// 进度条
LinearProgressIndicator(
    progress = { cacheStats.usagePercentage / 100f },
    modifier = Modifier.fillMaxWidth().height(8.dp)
)

// 百分比文本
Text(
    text = "${String.format("%.1f", cacheStats.usagePercentage)}% 已使用"
)
```

#### 3.3 正在缓存任务展示

```kotlin
if (cachingTasks.isNotEmpty()) {
    Card {
        // 待缓存总大小
        val pendingTotalSize = cachingTasks.sumOf { it.totalSize ?: 0L }
        Text("待缓存文件总大小: ${cacheManager.formatFileSize(pendingTotalSize)}")

        // 任务列表
        LazyColumn {
            items(cachingTasks) { task ->
                CachingTaskItem(task)
            }
        }
    }
}
```

**单个任务卡片**:
```kotlin
@Composable
private fun CachingTaskItem(task: CachingTaskInfo) {
    Card {
        Column {
            // 文件名
            Text(text = task.fileName)

            // 进度文本
            Text("${formatFileSize(task.transferredSize)} / ${formatFileSize(task.totalSize)}")

            // 进度条
            LinearProgressIndicator(
                progress = { task.progressPercentage / 100f }
            )

            // 百分比
            Text("${String.format("%.1f", task.progressPercentage)}%")
        }
    }
}
```

#### 3.4 缓存清理

```kotlin
OutlinedButton(
    onClick = {
        scope.launch {
            isClearingCache = true
            try {
                cacheManager.clearCache()
                cacheStats = cacheManager.getCacheStats()
                cachedFiles = cacheManager.getCachedFilesList()
            } finally {
                isClearingCache = false
            }
        }
    },
    enabled = cacheStats.totalFiles > 0 && !isClearingCache
) {
    if (isClearingCache) {
        CircularProgressIndicator()
        Text("清理中...")
    } else {
        Text("清空缓存")
    }
}
```

**清理逻辑**:
```kotlin
suspend fun clearCache() = withContext(Dispatchers.IO) {
    synchronized(cacheLock) {
        // 删除所有文件
        cacheDir.listFiles()?.forEach { file ->
            if (file.delete()) {
                currentCacheSize.addAndGet(-file.length())
            }
        }

        // 清空索引
        cachedFiles.clear()

        // 删除元数据
        if (metadataFile.exists()) {
            metadataFile.delete()
        }
    }
}
```

## 并发安全设计

### 1. 数据结构选择

```kotlin
// 线程安全的Map
private val cachedFiles = ConcurrentHashMap<String, CacheEntry>()
private val cachingTasks = ConcurrentHashMap<String, CachingTask>()

// 原子操作的Long
private val currentCacheSize = AtomicLong(0L)
```

### 2. 同步锁保护

```kotlin
private val cacheLock = Any()

synchronized(cacheLock) {
    cachedFiles[cacheKey] = entry
    currentCacheSize.addAndGet(size)
}
```

**锁的使用原则**:
- 保护复合操作
- 保护多个变量的一致性
- 避免长时间持有锁

### 3. 协程调度

```kotlin
// IO操作使用IO调度器
suspend fun cacheFile(url: String, data: ByteArray): File =
    withContext(Dispatchers.IO) {
        // 文件写入操作
    }

// UI更新在主线程
scope.launch {
    // 更新UI状态
    cacheStats = cacheManager.getCacheStats()
}
```

## 性能优化

### 1. 减少磁盘IO

**内存索引优先**:
```kotlin
fun getCachedFile(url: String): File? {
    val cacheKey = getCacheKey(url)
    val entry = cachedFiles[cacheKey]  // 内存查找

    return if (entry?.file?.exists() == true) {
        entry.file
    } else {
        cachedFiles.remove(cacheKey)  // 清理失效条目
        null
    }
}
```

- 避免遍历文件系统
- O(1)时间复杂度查找
- 懒惰验证文件存在性

### 2. 元数据批量持久化

```kotlin
// 不是每次修改都写入磁盘
// 在关键时刻才持久化:
// - 缓存新文件后
// - LRU淘汰后
// - 应用退出时
saveCacheMetadata()
```

### 3. 进度更新节流

```kotlin
// 避免每次read()都更新进度
if (bytesReadTotal >= progressUpdateThreshold &&
    (currentTime - lastProgressUpdateTime > 500)) {
    cacheManager.updateCachingProgress(url, bytesReadTotal, totalSize)
    lastProgressUpdateTime = currentTime
}
```

### 4. 流式缓存

```kotlin
// 不等待完整下载，边读边写
override fun read(b: ByteArray, off: Int, len: Int): Int {
    val bytesRead = upstream.read(b, off, len)

    if (bytesRead > 0) {
        cacheOutput!!.write(b, off, bytesRead)  // 同步写入
        cacheOutput!!.flush()
    }

    return bytesRead
}
```

- 用户无需等待下载完成
- 立即开始播放
- 后台持续缓存

## 容错设计

### 1. 缓存不影响播放

```kotlin
try {
    cacheOutput = FileOutputStream(cacheFile)
    cacheManager.startCaching(url, totalSize = totalSize)
} catch (e: Exception) {
    // 缓存初始化失败，禁用缓存
    isCachingEnabled = false
    if (cacheFile.exists()) {
        cacheFile.delete()
    }
    cacheManager.failCaching(url)
    // 不抛出异常，继续播放
}
```

### 2. 降级处理

```kotlin
fun getCacheKey(url: String): String {
    return try {
        // 尝试MD5
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(url.toByteArray())
        hash.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        // MD5失败，降级为hashCode
        url.hashCode().toString()
    }
}
```

### 3. 元数据修复

```kotlin
// 启动时对比元数据和实际文件
private fun scanExistingCache() {
    loadCacheMetadata()

    cacheDir.listFiles()?.forEach { file ->
        val entry = cachedFiles[file.name]

        if (entry != null) {
            // 修复大小不一致
            if (file.length() != entry.size) {
                cachedFiles[file.name] = entry.copy(size = file.length())
            }
        } else {
            // 补充缺失的元数据
            createBasicEntry(file)
        }
    }

    // 清理已删除文件的元数据
    cleanupStaleEntries()
}
```

### 4. 部分缓存清理

```kotlin
override fun close() {
    // 只有完整下载才保留
    if (isCachingEnabled && cacheFile.exists()) {
        cacheManager.finishCaching(url, cacheFile, cacheFile.length())
    } else {
        // 清理不完整的缓存
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        cacheManager.failCaching(url)
    }
}
```

## 文件大小格式化

```kotlin
fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0

    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}
```

**人性化显示**:
- 自动选择合适单位
- 保留一位小数
- 提升可读性

## 使用流程

### 完整播放流程

```
用户点击播放
    ↓
ExoPlayer 请求数据
    ↓
CachedHttpDataSource.open(dataSpec)
    ↓
检查缓存: cacheManager.getCachedFile(url)
    ↓
    ├─ 缓存命中
    │      ↓
    │  openCachedFile()
    │      ↓
    │  返回 FileInputStream
    │      ↓
    │  从本地文件读取 (快速)
    │
    └─ 缓存未命中
           ↓
       openFromNetwork()
           ↓
       创建 StreamingCachingInputStream
           ↓
       upstream.open(dataSpec)
           ↓
       cacheManager.startCaching()
           ↓
       开始读取数据
           ↓
       ┌────────────────────┐
       │  每次 read() 调用  │
       │  ├─ 从网络读取     │
       │  ├─ 写入缓存文件   │
       │  └─ 更新进度       │
       └────────────────────┘
           ↓
       播放完成/用户停止
           ↓
       StreamingCachingInputStream.close()
           ↓
       cacheManager.finishCaching()
           ↓
       缓存完成，下次播放直接命中
```

### 缓存管理UI流程

```
打开缓存管理界面
    ↓
CacheManagementScreen 初始化
    ↓
LaunchedEffect 启动定时任务
    ↓
每秒执行:
    ├─ getCacheStats() - 获取统计信息
    ├─ getCachedFilesList() - 获取文件列表
    └─ getCachingTasks() - 获取正在缓存的任务
    ↓
更新UI显示:
    ├─ 缓存使用百分比
    ├─ 已缓存文件数量
    ├─ 正在缓存的任务进度
    └─ 文件列表
    ↓
用户点击"清空缓存"
    ↓
cacheManager.clearCache()
    ↓
删除所有缓存文件和元数据
    ↓
刷新UI
```

## 优势总结

### 1. 透明性
- 对ExoPlayer完全透明
- 无需修改播放器代码
- 可随时启用/禁用

### 2. 实时性
- 边播放边缓存
- 无需等待下载完成
- 实时进度反馈

### 3. 可靠性
- 缓存失败不影响播放
- 元数据持久化
- 启动时自动修复

### 4. 高效性
- LRU自动淘汰
- 内存索引快速查找
- 进度更新节流

### 5. 可观测性
- 实时缓存统计
- 正在缓存任务跟踪
- 完整的UI管理界面

## 潜在改进方向

### 1. 缓存策略优化
- **智能预缓存**: 根据播放历史预测下一首歌曲
- **网络感知**: WiFi环境下更激进的缓存策略
- **用户偏好**: 允许用户自定义缓存大小

### 2. 性能优化
- **并行下载**: 支持多线程下载加速
- **增量更新**: 支持断点续传
- **压缩存储**: 对某些格式进行压缩存储

### 3. 功能扩展
- **选择性缓存**: 用户手动标记特定歌曲缓存
- **导出功能**: 导出缓存文件
- **缓存分析**: 统计最常播放的歌曲

### 4. 稳定性增强
- **健康检查**: 定期验证缓存文件完整性
- **错误恢复**: 自动重试失败的缓存任务
- **监控告警**: 磁盘空间不足时的提示

## 总结

MusicDav的缓存系统是一个设计良好的透明缓存层，具有以下特点：

1. **架构清晰**: 职责分离，MusicCacheManager负责存储管理，CachedHttpDataSource负责数据流控制
2. **性能优秀**: 内存索引、流式缓存、进度节流等优化措施
3. **并发安全**: 使用ConcurrentHashMap、AtomicLong、synchronized保证线程安全
4. **容错健壮**: 缓存失败不影响播放，支持降级和修复
5. **用户体验**: 实时反馈、人性化界面、无感知缓存

该系统展示了如何在不修改第三方库(ExoPlayer)的前提下，通过装饰器模式优雅地添加缓存功能，是Android音乐应用缓存设计的优秀实践案例。
