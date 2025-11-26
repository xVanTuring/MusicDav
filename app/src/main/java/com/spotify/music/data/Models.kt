package com.spotify.music.data

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

data class PlaylistState(
    val songs: List<MusicFile> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L
) {
    val currentSong: MusicFile? 
        get() = songs.getOrNull(currentIndex)
    
    val hasNext: Boolean 
        get() = currentIndex < songs.size - 1
    
    val hasPrevious: Boolean 
        get() = currentIndex > 0
}
