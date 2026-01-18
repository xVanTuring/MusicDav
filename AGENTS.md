# AGENTS.md

This file provides guidance for AI coding agents working on the MusicDav Android project.

## Build Commands

### Core Build
```bash
./gradlew build                    # Build and test entire project
./gradlew assembleDebug            # Build debug APK only
./gradlew assembleDebug -x lint    # Build without lint checks
./gradlew clean                    # Clean build artifacts
```

### Testing
```bash
./gradlew test                     # Run all unit tests
./gradlew :app:testDebugUnitTest   # Run unit tests for debug variant
./gradlew :app:testDebugUnitTest --tests "*FilePickerDialogTest*"  # Run single test class
./gradlew :app:testDebugUnitTest --tests "*FilePickerDialogTest.testParseWebDavBaseUrl*"  # Run single test method
./gradlew connectedAndroidTest     # Run instrumented tests on connected devices
```

### Code Quality
```bash
./gradlew lint                     # Run Android lint checks
./gradlew :app:lint                # Run lint for app module
./gradlew lintDebug                # Lint debug variant specifically
```

### Installation
```bash
./gradlew installDebug             # Install debug APK to connected device
./gradlew installRelease           # Install release APK
```

## Code Style Guidelines

### Kotlin Code Style
- Follow Kotlin official coding conventions (`kotlin.code.style=official` in gradle.properties)
- Use **4 spaces** for indentation (no tabs)
- Max line length: No strict limit, but aim for readability (~100-120 characters)

### Import Organization
Imports are organized alphabetically by package name:
1. Android/AndroidX imports
2. Compose imports (androidx.compose.*)
3. Third-party library imports
4. Project imports (com.spotify.music.*)
5. java/kotlin imports (if needed)

```kotlin
import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import coil3.compose.AsyncImage
import com.spotify.music.data.Album
import kotlinx.coroutines.Dispatchers
```

### Naming Conventions

**Classes/Interfaces/Objects:**
```kotlin
class WebDavClient { }
data class Album(val name: String) { }
interface MusicRepository { }
object AlbumsRepository { }
```

**Functions/Properties:**
```kotlin
fun fetchMusicFiles(): Result<List<MusicFile>> { }
val currentSong: MusicFile? = null
```

**Constants:**
```kotlin
private const val PREF_NAME = "albums_prefs"
private const val KEY_ALBUMS = "albums_json"
```

**Composable Functions:**
```kotlin
@Composable
fun AlbumListScreen(
    albums: List<Album>,
    onSelect: (Album) -> Unit
) { }
```

### Data Models
- Use `data class` for immutable data models
- Provide default values for optional fields
- Use `val` for immutable properties
- Use `var` only when mutability is required

```kotlin
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
)

data class MusicFile(
    val name: String,
    val url: String,
    val path: String,
    val size: Long = 0L,
    val modifiedDate: Long = 0L
)
```

### Compose UI Guidelines
- Composable functions start with uppercase letter
- Modifier parameter always last with default value `modifier: Modifier = Modifier`
- State management with `remember { mutableStateOf() }`
- Use `@OptIn` for experimental APIs
- Required parameters before optional parameters

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    albums: List<Album>,
    onSelect: (Album) -> Unit,
    onCreate: (Album, String?) -> Unit,
    onDelete: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    // ... implementation
}
```

### Asynchronous Operations
- Use Kotlin coroutines for async operations
- `withContext(Dispatchers.IO)` for network I/O
- Return `Result<T>` for operations that may fail

```kotlin
suspend fun fetchMusicFiles(config: WebDavConfig): Result<List<MusicFile>> =
    withContext(Dispatchers.IO) {
        try {
            val sardine: Sardine = OkHttpSardine()
            sardine.setCredentials(config.username, config.password)
            val resources = sardine.list(config.url)
            Result.success(parseMusicFiles(resources))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
```

### Error Handling
- Use Kotlin's `Result<T>` type for error propagation
- Log errors with descriptive messages
- Provide user-friendly error messages in UI

```kotlin
try {
    // operation
    Result.success(data)
} catch (e: Exception) {
    Log.e("WebDavClient", "Error fetching music files", e)
    Result.failure(e)
}
```

### Logging
- Use Android `Log` class
- Tag should match class/component name
- Use appropriate log levels: `d` (debug), `e` (error), `i` (info)

```kotlin
Log.d("WebDavClient", "Base URL: $baseUrl")
Log.e("WebDavClient", "Error listing resources", e)
```

### Type Safety
- Use nullable types (`T?`) appropriately
- Use safe call operators (`?.`) and Elvis operator (`?:`)
- Avoid `!!` operator unless absolutely necessary

```kotlin
val currentSong: MusicFile? = songs.getOrNull(currentIndex)
val coverUrl: String? = embeddedCoverUrl ?: albumCoverUrl
```

### Package Structure
```
com.spotify.music/
├── MainActivity.kt
├── SimpleMusicService.kt
├── data/
│   └── Models.kt              # Data models and repositories
├── ui/
│   ├── theme/                 # Compose theming
│   ├── BottomPlayerBar.kt
│   └── screen/                # Screen composables
├── webdav/
│   └── WebDavClient.kt        # WebDAV operations
└── player/
    └── PlaylistStateController.kt
```

## Android-Specific Guidelines

### Context Handling
- Use `LocalContext.current` in Composable functions
- Avoid storing Context in long-lived objects
- Use application context when possible

### Data Persistence
- Use SharedPreferences with manual JSON serialization
- Use org.json for JSON parsing (see Models.kt for patterns)

### WebDAV Image Loading
Use Coil3 with auth headers for WebDAV images:

```kotlin
val headers = NetworkHeaders.Builder()
    .set("Authorization", Credentials.basic(username, password))
    .build()

AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(imageUrl)
        .httpHeaders(headers)
        .crossfade(true)
        .build(),
    contentDescription = description
)
```

## Caching System

### Architecture Overview

The caching system consists of three main components:

1. **CacheManager** - High-level cache management
2. **MusicCacheService** - Foreground service handling cache tasks
3. **MusicCache** - Low-level file caching implementation

### CacheManager

**Location**: `com.spotify.music.player.CacheManager`

**Purpose**: 
- Manages communication with MusicCacheService
- Tracks cache state (individual and album tasks)
- Provides caching operations interface
- Listens to service task state changes

**Key State**:
```kotlin
data class CacheManagerState(
    val totalSize: Long = 0L,
    val cachedSongs: List<CacheMetadata> = emptyList(),
    val isCaching: Boolean = false,
    val cachingProgress: Map<String, Int> = emptyMap(),  // taskId -> progress
    val cachingStatus: String? = null,
    val albumCachingProgress: Map<String, AlbumCacheProgress> = emptyMap()
)

data class AlbumCacheProgress(
    val totalSongs: Int,
    val completedSongs: Int,
    val currentSong: String?
)
```

**Key Methods**:
```kotlin
fun bind()  // Connect to MusicCacheService
fun unbind()  // Disconnect and clean up
fun cacheSong(...)  // Cache a single song
fun cacheAlbum(...)  // Cache an entire album
suspend fun refreshCacheState()  // Update cache state
suspend fun clearCache(context: Context)  // Clear all cached files
suspend fun isCached(context: Context, url: String): Boolean  // Check if song is cached
```

**Important**: Must call `cacheService?.addListener(taskListener)` in `onServiceConnected` to receive task updates.

### MusicCacheService

**Location**: `com.spotify.music.MusicCacheService`

**Purpose**:
- Foreground service handling cache tasks
- Manages active task queue
- Provides task listener interface
- Shows download progress in notification

**Key Features**:
- Runs as foreground service for persistent downloads
- Supports multiple concurrent tasks
- Notifies listeners on task state changes
- Auto-stops foreground when all tasks complete

**Task Status Flow**:
```
PENDING -> DOWNLOADING -> COMPLETED
                     |
                     v
                   FAILED/CANCELLED
```

**CacheTaskListener Interface**:
```kotlin
interface CacheTaskListener {
    fun onTaskStarted(taskId: String, musicFile: MusicFile)
    fun onTaskProgress(taskId: String, progress: Int)
    fun onTaskCompleted(taskId: String, path: String?)
    fun onTaskFailed(taskId: String, error: Throwable)
    fun onAllTasksCompleted()
}
```

### MusicCache

**Location**: `com.spotify.music.player.MusicCache`

**Purpose**:
- Low-level file caching operations
- Manages cache metadata storage
- Provides cache existence checking

**Key Methods**:
```kotlin
suspend fun cacheSong(context: Context, musicFile: MusicFile, config: WebDavConfig, onProgress: (Int) -> Unit): Result<String>
suspend fun clearCache(context: Context): Result<Unit>
suspend fun removeCachedSong(context: Context, url: String): Result<Unit>
suspend fun isCached(context: Context, url: String): Boolean
suspend fun getCachedSongs(context: Context): List<CacheMetadata>
suspend fun getCurrentCacheSize(context: Context): Long
```

### Caching in Compose UI

**CRITICAL: Always use CacheManager state, never local state**

When implementing cache status in UI components:

❌ **WRONG** - Using local state that doesn't update:
```kotlin
@Composable
fun MusicListItem(musicFile: MusicFile, ...) {
    val isCached = remember { mutableStateOf(false) }  // Never updates!
    
    LaunchedEffect(musicFile.url) {
        isCached.value = MusicCache.isCached(context, musicFile.url)
    }
}
```

✅ **CORRECT** - Using CacheManager state for reactive updates:
```kotlin
@Composable
fun MusicListItem(
    musicFile: MusicFile,
    cacheManager: CacheManager?,  // Accept CacheManager
    ...
) {
    val isCached = cacheManager?.state?.cachedSongs?.any { it.url == musicFile.url } ?: false
    val isCaching = cacheManager?.state?.cachingProgress?.containsKey(musicFile.url) ?: false
    
    // UI automatically updates when cacheManager.state changes
}
```

**Album Caching Progress**:
```kotlin
val albumCacheProgress = cacheManager?.state?.albumCachingProgress[albumId]
if (albumCacheProgress != null) {
    CircularProgressIndicator(progress = albumCacheProgress.completedSongs / albumCacheProgress.totalSongs)
}
```

### Caching State Lifecycle

**For Individual Songs**:
1. User clicks cache button → `cacheManager.cacheSong()`
2. Service starts task → `onTaskStarted` → update `cachingProgress`
3. Progress updates → `onTaskProgress` → update progress
4. Task completes → `onTaskCompleted` → add to `cachedSongs`, remove from `cachingProgress`

**For Albums**:
1. User clicks album cache → `cacheManager.cacheAlbum()`
2. Create `AlbumCacheProgress` entry in `albumCachingProgress`
3. Queue all songs → individual tasks start
4. Each song completion → update `AlbumCacheProgress.completedSongs`
5. All songs complete → remove `AlbumCacheProgress`, call `onAllTasksCompleted`

### Common Patterns

**Starting Album Cache**:
```kotlin
fun cacheAlbum(
    albumId: String,
    musicFiles: List<MusicFile>,
    config: WebDavConfig
) {
    scope.launch {
        albumProgress[albumId] = AlbumCacheProgress(
            totalSongs = musicFiles.size,
            completedSongs = 0,
            currentSong = null
        )
        
        for (musicFile in musicFiles) {
            MusicCacheService.startCaching(context, musicFile, config)
        }
    }
}
```

**Checking Cache Status**:
```kotlin
val isCached = cacheManager?.state?.cachedSongs?.any { it.url == musicFile.url } ?: false
val isCaching = cacheManager?.state?.cachingProgress?.containsKey(musicFile.url) ?: false
```

**Cleanup on Album Completion**:
```kotlin
if (newProgress.completedSongs >= newProgress.totalSongs) {
    albumProgress.remove(albumId)
    urlToAlbumId.keys.removeAll { urlToAlbumId[it] == albumId }
    _state.value = _state.value.copy(
        albumCachingProgress = _state.value.albumCachingProgress - albumId
    )
}
```

### Pitfalls to Avoid

1. **Not registering listener**: Always call `cacheService?.addListener(taskListener)` in `onServiceConnected`
2. **Using local state**: Never use `remember { mutableStateOf() }` for cache status; always derive from `cacheManager.state`
3. **Memory leaks**: Always call `unbind()` in `DisposableEffect.onDispose`
4. **Not cleaning up album mappings**: Remove all URL→albumId mappings when album completes
5. **Stale state**: UI components must accept `CacheManager` as parameter to access real-time state

## Project Configuration

### Build Versions
- **compileSdk**: 36
- **minSdk**: 24 (Android 7.0)
- **targetSdk**: 36
- **Java**: 11
- **Kotlin**: 2.0.21

### Key Dependencies
- **UI**: Jetpack Compose with Material3
- **Media**: Android Media3 (ExoPlayer, Media Session)
- **Networking**: Sardine (WebDAV), OkHttp
- **Image Loading**: Coil3 with OkHttp integration
- **Testing**: JUnit, AndroidX Test

## Testing Guidelines

### Unit Tests
- Place in `app/src/test/java/com/spotify/music/`
- Use JUnit assertions
- Test pure functions and business logic

```kotlin
@Test
fun testParseWebDavBaseUrl_withPath() {
    val config = WebDavConfig("https://example.com/path/", "user", "pass")
    val baseUrl = parseWebDavBaseUrl(config)
    assertEquals("https://example.com", baseUrl)
}
```

### Instrumented Tests
- Place in `app/src/androidTest/java/com/spotify/music/`
- Use AndroidX Test framework
- Test Android-specific components

## When in Doubt

1. Follow existing patterns in the codebase
2. Check similar files for implementation examples
3. Run `./gradlew assembleDebug -x lint` to check compilation
4. Run `./gradlew test` to verify tests pass
5. Run `./gradlew lint` to catch code quality issues
