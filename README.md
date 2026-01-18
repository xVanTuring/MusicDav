# MusicDav

一个基于 WebDAV 协议的 Android 音乐播放器应用。

## 功能特性

### 核心功能
- **WebDAV 音乐服务器连接** - 通过 WebDAV 协议访问和播放远程音乐文件
- **专辑管理** - 创建、编辑、删除音乐专辑，支持多个 WebDAV 服务器配置
- **音乐播放** - 基于 ExoPlayer (Media3) 的高质量音乐播放
- **缓存系统** - 支持单曲和整张专辑缓存到本地
- **封面显示** - 支持专辑封面和内嵌专辑封面显示
- **播放模式** - 单次播放、单曲循环、列表循环三种模式
- **后台播放** - 支持媒体会话和通知栏控制

### 技术亮点
- 完全使用 **Kotlin** 开发
- **Jetpack Compose** 现代化 UI 框架
- **Material3** 设计规范
- **Android Media3** (ExoPlayer) 媒体播放框架
- **Coil3** 图片加载库
- **Sardine** WebDAV 客户端库

## 架构概述

### 主要模块

```
tech.xvanturing.musicdav/
├── MainActivity.kt                      # 主活动，应用入口
├── SimpleMusicService.kt                # 音乐播放服务 (MediaSessionService)
├── MusicCacheService.kt                 # 缓存下载服务 (ForegroundService)
├── data/
│   ├── Models.kt                        # 数据模型 (WebDavConfig, Album, MusicFile 等)
│   └── ConfigExportManager.kt           # 配置导出/导入管理
├── ui/
│   ├── screen/                         # 各个屏幕 UI
│   │   ├── AlbumListScreen.kt          # 专辑列表
│   │   ├── AlbumDetailScreen.kt        # 专辑详情
│   │   ├── AlbumCreateForm.kt          # 专辑创建/编辑
│   │   ├── ServerConfigListScreen.kt   # 服务器配置列表
│   │   ├── ServerConfigCreateScreen.kt # 服务器配置创建/编辑
│   │   ├── CacheManagementScreen.kt    # 缓存管理
│   │   └── FolderPickerScreen.kt       # 文件夹选择器
│   └── theme/                          # Compose 主题配置
├── webdav/
│   └── WebDavClient.kt                 # WebDAV 客户端封装
└── player/
    ├── PlaylistStateController.kt      # 播放列表状态管理
    ├── CacheManager.kt                 # 缓存管理器
    ├── MusicCache.kt                   # 音乐文件缓存实现
    ├── CacheRepository.kt              # 缓存数据仓储
    ├── CachedDataSource.kt             # 缓存数据源 (ExoPlayer)
    ├── CoverCache.kt                   # 封面缓存
    └── NotificationPermissionManager.kt # 通知权限管理
```

### 缓存系统

缓存系统由三个核心组件组成：

1. **CacheManager** - 高级缓存管理
   - 管理与 MusicCacheService 的通信
   - 跟踪缓存状态（单曲和专辑任务）
   - 提供缓存操作接口
   - 监听服务任务状态变化

2. **MusicCacheService** - 前台服务
   - 处理缓存下载任务
   - 管理活动任务队列
   - 提供任务监听器接口
   - 显示下载进度通知

3. **MusicCache** - 低级文件缓存实现
   - 文件缓存操作
   - 缓存元数据存储
   - 缓存存在性检查

### 数据持久化

- **ServerConfigRepository** - 服务器配置持久化 (SharedPreferences + JSON)
- **AlbumsRepository** - 专辑数据持久化 (SharedPreferences + JSON)
- **PlaylistCache** - 播放列表缓存 (SharedPreferences + JSON)

## 系统要求

- **Android 版本**: 7.0 (API 24) 及以上
- **最低 SDK**: 24
- **目标 SDK**: 36
- **Java**: 11
- **Kotlin**: 2.0.21

## 依赖库

```gradle
// UI 框架
androidx.compose.bom
androidx.compose.material3

// 媒体播放
androidx.media3:exoplayer
androidx.media3:session

// WebDAV 和网络
com.github.thegrizzlylabs:sardine-android:0.8
com.squareup.okhttp3:okhttp:4.12.0

// 图片加载
io.coil-kt.coil3:coil-compose:3.2.0
io.coil-kt.coil3:coil-network-okhttp:3.2.0

// 图标
androidx.compose.material:material-icons-extended:1.7.5
```

## 构建

### 前置要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 11 或更高版本
- Android SDK 36

### 构建命令

```bash
# 构建并测试整个项目
./gradlew build

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 构建跳过 Lint 检查
./gradlew assembleDebug -x lint

# 清理构建产物
./gradlew clean
```

### 安装到设备

```bash
# 安装 Debug 版本
./gradlew installDebug

# 安装 Release 版本
./gradlew installRelease
```

## 测试

```bash
# 运行所有单元测试
./gradlew test

# 运行模块单元测试
./gradlew :app:testDebugUnitTest

# 运行特定测试类
./gradlew :app:testDebugUnitTest --tests "*FilePickerDialogTest*"

# 运行特定测试方法
./gradlew :app:testDebugUnitTest --tests "*FilePickerDialogTest.testParseWebDavBaseUrl*"

# 运行设备上的仪器化测试
./gradlew connectedAndroidTest
```

## 代码质量

```bash
# 运行 Lint 检查
./gradlew lint

# 运行应用模块 Lint
./gradlew :app:lint

# 运行 Debug 变体 Lint
./gradlew lintDebug
```

## 代码风格

项目遵循 Kotlin 官方编码规范：
- **缩进**: 4 空格
- **最大行长**: ~100-120 字符
- **导入顺序**: Android/AndroidX → Compose → 第三方库 → 项目包 → java/kotlin

## 权限说明

应用需要以下权限：

- `INTERNET` - 访问网络
- `ACCESS_NETWORK_STATE` - 检查网络状态
- `POST_NOTIFICATIONS` - 显示播放通知
- `FOREGROUND_SERVICE` - 运行前台服务
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` - 媒体播放前台服务
- `FOREGROUND_SERVICE_DATA_SYNC` - 数据同步前台服务
- `WAKE_LOCK` - 保持 CPU 运行
- `BLUETOOTH` - 蓝牙连接支持

## 使用说明

### 1. 配置 WebDAV 服务器
- 在 "Servers" 标签页添加服务器配置
- 填写服务器 URL、用户名和密码
- 支持多个服务器配置

### 2. 创建专辑
- 在 "Albums" 标签页点击 "+" 按钮
- 选择服务器配置或直接填写 WebDAV 凭证
- 选择音乐文件夹
- 设置专辑名称和封面

### 3. 播放音乐
- 点击专辑进入详情页
- 选择歌曲开始播放
- 使用底部播放栏控制播放
- 切换播放模式：单次播放 / 单曲循环 / 列表循环

### 4. 缓存管理
- 在 "Cache" 标签页查看缓存状态
- 缓存单曲或整张专辑到本地
- 清理缓存释放存储空间

## 许可证

本项目仅供学习和个人使用。

## 贡献

欢迎提交 Issue 和 Pull Request！

## 版本历史

### v1.0.0 (2025)
- 初始版本发布
- WebDAV 音乐播放
- 专辑管理功能
- 缓存系统
- 多播放模式支持
