# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MusicDav is an Android WebDAV music player application built with Jetpack Compose and Kotlin. The app allows users to create albums (playlists) by configuring WebDAV server connections and automatically fetches music files from those directories.

## Build Commands

### Building the Project
```bash
./gradlew build
```

### Running Tests
```bash
# Unit tests
./gradlew test

# Instrumented tests
./gradlew connectedAndroidTest
```

### Code Quality
```bash
# Run lint checks
./gradlew lint

# Clean build
./gradlew clean
```

### Installation
```bash
# Install debug APK
./gradlew installDebug

# Install release APK
./gradlew installRelease
```

## Architecture Overview

### Core Components

1. **MainActivity**: Entry point that manages navigation between album list and music player screens
2. **Data Layer** (`com.spotify.music.data`):
   - `Models.kt`: Core data models including `Album`, `WebDavConfig`, `ServerConfig`, `MusicFile`, `PlaylistState`
   - `AlbumsRepository`: Persists albums using SharedPreferences with JSON serialization
   - `ServerConfigRepository`: Manages reusable WebDAV server configurations
   - `PlaylistCache`: Caches music file listings for performance

3. **WebDAV Integration** (`com.spotify.music.webdav`):
   - `WebDavClient`: Handles WebDAV operations using Sardine library
   - Supports music file discovery, cover image detection, and connection testing

4. **UI Screens** (`com.spotify.music.ui.screen`):
   - `AlbumListScreen`: Main screen showing all configured albums
   - `AlbumDetailScreen`: Playback interface with player controls
   - `AlbumCreateForm`: Form for creating new albums with WebDAV configuration
   - `ServerConfigManagement`: Management interface for reusable server configurations

5. **Player Components**:
   - `SimpleMusicService`: Background music service using Android Media3
   - `BottomPlayerBar`: Mini player component for navigation

### Key Patterns

- **State Management**: Uses Jetpack Compose state with `mutableStateOf` and `remember`
- **Data Persistence**: SharedPreferences with manual JSON serialization using org.json
- **Navigation**: Simple screen-based navigation managed in MainActivity
- **Async Operations**: Kotlin coroutines with `withContext(Dispatchers.IO)` for network operations
- **Error Handling**: Result type pattern for WebDAV operations

### Dependencies

- **UI**: Jetpack Compose, Material3
- **Media**: Android Media3 (ExoPlayer, Media Session)
- **Networking**: Sardine (WebDAV), OkHttp
- **Architecture**: Android ViewModel, Navigation Component (minimal usage)

### File Structure

```
app/src/main/java/com/spotify/music/
├── MainActivity.kt                 # App entry point and navigation
├── SimpleMusicService.kt           # Background music playback service
├── data/
│   └── Models.kt                   # All data models and repositories
├── ui/
│   ├── theme/                      # Compose theming
│   ├── BottomPlayerBar.kt          # Mini player component
│   ├── AlbumDetailScreen.kt          # Music list display
│   └── screen/                     # Main app screens
└── webdav/
    └── WebDavClient.kt             # WebDAV operations
```

## Development Notes

### WebDAV Configuration
- Albums can reference server configurations by ID for reusability
- Supports automatic cover image detection from common image formats
- Music file filtering supports: mp3, m4a, flac, wav, ogg, aac, wma

### Testing Strategy
- Unit tests for data models and WebDAV client operations
- Instrumented tests for Android components and UI interactions
- Mock WebDAV server recommended for integration testing

### Performance Considerations
- Music file listings are cached to reduce network requests
- Cover images are loaded asynchronously and cached as Base64
- ExoPlayer handles audio caching automatically