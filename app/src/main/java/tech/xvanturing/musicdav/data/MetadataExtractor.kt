package tech.xvanturing.musicdav.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import kotlinx.coroutines.guava.await
import okhttp3.Credentials

data class CoverArt(
    val mimeType: String,
    val imageData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CoverArt

        if (mimeType != other.mimeType) return false
        if (!imageData.contentEquals(other.imageData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + imageData.contentHashCode()
        return result
    }
}

data class ExtractedMusicMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val durationMs: Long = 0L,
    val coverArt: CoverArt? = null
) {
    fun toMusicFile(originalMusicFile: MusicFile): MusicFile {
        return originalMusicFile.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
suspend fun extractMusicMetadata(
    context: Context,
    musicFile: MusicFile,
    config: WebDavConfig
): ExtractedMusicMetadata {
    return try {
        val credentials = Credentials.basic(config.username, config.password)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "Authorization" to credentials
                )
            )

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        val mediaItem = MediaItem.fromUri(musicFile.url)
        var coverArt: CoverArt? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var genre: String? = null
        var year: String? = null
        var durationMs: Long = 0L

        MetadataRetriever.Builder(context, mediaItem)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().use { retriever ->
                val trackGroups = retriever.retrieveTrackGroups().await()
                val durationUs = retriever.retrieveDurationUs().await()
                
                durationMs = durationUs / 1000
                
                if (trackGroups.length > 0) {
                    val firstTrack = trackGroups.get(0)
                    val format = firstTrack.getFormat(0)
                    val mediaMetadata = format.metadata
                    
                    for (i in 0 until (mediaMetadata?.length() ?: 0)) {
                        val entry = mediaMetadata?.get(i) ?: continue
                        
                        when (entry) {
                            is PictureFrame -> {
                                coverArt = CoverArt(
                                    mimeType = entry.mimeType,
                                    imageData = entry.pictureData
                                )
                            }

                            is VorbisComment -> {
                                when (entry.key.uppercase()) {
                                    "TITLE" -> title = entry.value
                                    "ARTIST" -> artist = entry.value
                                    "ALBUM" -> album = entry.value
                                    "GENRE" -> genre = entry.value
                                    "DATE" -> year = entry.value
                                    "YEAR" -> year = entry.value
                                }
                            }

                            is TextInformationFrame -> {
                                when (entry.id) {
                                    "TIT2" -> title = entry.values.joinToString()
                                    "TPE1" -> artist = entry.values.joinToString()
                                    "TALB" -> album = entry.values.joinToString()
                                    "TCON" -> genre = entry.values.joinToString()
                                    "TDRC", "TYER" -> year = entry.values.joinToString()
                                }
                            }

                            is ApicFrame -> {
                                coverArt = CoverArt(
                                    mimeType = entry.mimeType,
                                    imageData = entry.pictureData
                                )
                            }

                            else -> {
                                Log.d("MetadataExtractor", "Entry found: $entry")
                            }
                        }
                    }
                }
            }
        
        ExtractedMusicMetadata(
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            year = year,
            durationMs = durationMs,
            coverArt = coverArt
        )
    } catch (e: Exception) {
        Log.e("MetadataExtractor", "Error extracting metadata for ${musicFile.name}", e)
        ExtractedMusicMetadata()
    }
}

