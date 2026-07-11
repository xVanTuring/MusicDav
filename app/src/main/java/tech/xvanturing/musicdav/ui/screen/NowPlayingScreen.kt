package tech.xvanturing.musicdav.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import okhttp3.Credentials
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.FavoritesRepository
import tech.xvanturing.musicdav.data.PlayMode
import tech.xvanturing.musicdav.data.WebDavConfig
import tech.xvanturing.musicdav.data.cacheKey
import tech.xvanturing.musicdav.player.PlaylistStateController
import tech.xvanturing.musicdav.ui.theme.MotionSpec

// Near-black vinyl body - a literal colour is fine here (grooves/disc body only,
// never brand colour), matching the reference VinylRecord in AlbumListScreen.
private val VinylBodyColor = Color(0xFF141414)
private val DiscMaxSize = 320.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playlistController: PlaylistStateController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state = playlistController.state
    val currentSong = state.currentSong

    BackHandler { onBack() }

    var isFavorite by remember(currentSong?.cacheKey) {
        mutableStateOf(currentSong?.let { FavoritesRepository.isFavorite(context, it) } ?: false)
    }
    val favoriteScale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()

    // 播放时持续旋转，暂停时定格在当前角度，恢复播放时从原角度继续转
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 9000, easing = LinearEasing)
                )
            )
        } else {
            rotation.stop()
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }

    Box(modifier = modifier.fillMaxSize()) {
        // Signature "background reflects the cover" ambiance layer.
        NowPlayingBackground(
            coverUrl = state.currentCoverUrl,
            webDavConfig = state.effectiveWebDavConfig
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.player_now_playing),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    actions = {
                        if (currentSong != null) {
                            IconButton(
                                onClick = {
                                    val fallbackConfig = if (currentSong.serverConfigId == null) {
                                        state.effectiveWebDavConfig
                                    } else {
                                        null
                                    }
                                    isFavorite = FavoritesRepository.toggle(context, currentSong, fallbackConfig)
                                    coroutineScope.launch {
                                        favoriteScale.animateTo(
                                            targetValue = 1.3f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessHigh
                                            )
                                        )
                                        favoriteScale.animateTo(
                                            targetValue = 1f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (isFavorite) {
                                        stringResource(R.string.common_unfavorite_desc)
                                    } else {
                                        stringResource(R.string.common_favorite_desc)
                                    },
                                    tint = if (isFavorite) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = favoriteScale.value
                                        scaleY = favoriteScale.value
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    windowInsets = TopAppBarDefaults.windowInsets
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    VinylDisc(
                        coverUrl = state.currentCoverUrl,
                        webDavConfig = state.effectiveWebDavConfig,
                        rotationProvider = { rotation.value },
                        modifier = Modifier
                            .fillMaxSize(0.88f)
                            .aspectRatio(1f)
                            .widthIn(max = DiscMaxSize)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currentSong?.displayName ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee()
                    )
                    if (currentSong?.artist != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentSong.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                val duration = state.duration.coerceAtLeast(1L)
                val sliderValue = (if (isDragging) dragPosition else state.currentPosition.toFloat())
                    .coerceIn(0f, duration.toFloat())
                Slider(
                    value = sliderValue,
                    valueRange = 0f..duration.toFloat(),
                    onValueChange = {
                        isDragging = true
                        dragPosition = it
                    },
                    onValueChangeFinished = {
                        playlistController.seekTo(dragPosition.toLong())
                        isDragging = false
                    },
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        thumbColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(if (isDragging) dragPosition.toLong() else state.currentPosition),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTime(state.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RepeatModeButton(
                        playMode = state.playMode,
                        onClick = { playlistController.togglePlayMode() }
                    )
                    IconButton(
                        onClick = { playlistController.seekToPrevious() },
                        enabled = state.hasPrevious
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.player_previous_desc),
                            tint = if (state.hasPrevious) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            },
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    PlayPauseButton(
                        isPlaying = state.isPlaying,
                        onClick = {
                            if (state.isPlaying) playlistController.pause() else playlistController.play()
                        }
                    )
                    IconButton(
                        onClick = { playlistController.seekToNext() },
                        enabled = state.hasNext
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.player_next_desc),
                            tint = if (state.hasNext) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            },
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    // 占位，让播放控件在视觉上居中（与左侧播放模式按钮对称）
                    Spacer(modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

// Full-bleed blurred cover behind the whole screen, scrimmed with the theme
// background colour (lighter at top -> nearly opaque at bottom) so controls
// stay legible in both light and dark themes while still picking up the
// cover's ambiance. Falls back to a flat background when there is no cover.
@Composable
private fun NowPlayingBackground(coverUrl: String?, webDavConfig: WebDavConfig?) {
    val backgroundColor = MaterialTheme.colorScheme.background
    if (coverUrl == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        )
        return
    }

    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = buildCoverImageRequest(context, coverUrl, webDavConfig),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // Scale up before blurring so the RenderEffect's edge sampling
                // never exposes a transparent fringe at the screen bounds.
                .graphicsLayer {
                    scaleX = 1.2f
                    scaleY = 1.2f
                }
                .blur(40.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.5f),
                            backgroundColor.copy(alpha = 0.9f)
                        )
                    )
                )
        )
    }
}

// Three-state repeat-mode button. PLAY_ONCE reads as clearly "off" (no fill,
// muted tint); REPEAT_ALL / REPEAT_SINGLE both get a filled primary pill so
// "on" is unmistakable, and the icon itself (Repeat vs RepeatOne) tells them
// apart.
@Composable
private fun RepeatModeButton(playMode: PlayMode, onClick: () -> Unit) {
    val isOn = playMode != PlayMode.PLAY_ONCE
    val icon = if (playMode == PlayMode.REPEAT_SINGLE) Icons.Default.RepeatOne else Icons.Default.Repeat
    val tint = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val pillColor = if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
    val description = when (playMode) {
        PlayMode.REPEAT_SINGLE -> stringResource(R.string.player_mode_repeat_single)
        PlayMode.REPEAT_ALL -> stringResource(R.string.player_mode_repeat_all)
        PlayMode.PLAY_ONCE -> stringResource(R.string.player_mode_play_once)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(pillColor),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(imageVector = icon, contentDescription = description, tint = tint)
        }
    }
}

// Big gold play/pause button; the icon itself crossfades + scales in/out on
// state change (mirrors the mini player's transition).
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(72.dp)) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (fadeIn(tween(MotionSpec.fast)) + scaleIn(
                        initialScale = 0.7f,
                        animationSpec = tween(MotionSpec.fast)
                    )) togetherWith
                        (fadeOut(tween(MotionSpec.fast)) + scaleOut(
                            targetScale = 0.7f,
                            animationSpec = tween(MotionSpec.fast)
                        ))
                },
                label = "nowPlayingPlayPause"
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) {
                        stringResource(R.string.player_pause_desc)
                    } else {
                        stringResource(R.string.player_play_desc)
                    },
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

// The hero vinyl disc: black body + faint groove rings + a glossy sheen
// highlight (Canvas), the cover cropped into a small inner label ring (a
// real record's label is much smaller than the disc), a thin gold ring
// around the label edge, and the centre spindle hole on top. Rotation is
// read via a lambda inside the graphicsLayer block (not the composable
// body) so the continuous spin while playing never forces this whole
// screen to recompose every frame - same intent as AlbumListScreen's
// VinylRecord, which reads its rotation State<Float> in the same spot.
@Composable
private fun VinylDisc(
    coverUrl: String?,
    webDavConfig: WebDavConfig?,
    rotationProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current

    Box(
        modifier = modifier
            .shadow(elevation = 24.dp, shape = CircleShape, clip = false)
            .graphicsLayer { rotationZ = rotationProvider() }
            .clip(CircleShape)
            .background(VinylBodyColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = size.minDimension / 2f
            val grooveFractions = listOf(0.96f, 0.90f, 0.84f, 0.78f, 0.72f, 0.665f)
            grooveFractions.forEachIndexed { index, fraction ->
                drawCircle(
                    color = Color.White.copy(alpha = if (index % 2 == 0) 0.06f else 0.035f),
                    radius = radius * fraction,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            // Subtle glossy sheen sweeping across the upper-left of the disc.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.30f, size.height * 0.26f),
                    radius = radius * 0.95f
                ),
                radius = radius,
                center = Offset(size.width / 2f, size.height / 2f)
            )
        }

        val labelModifier = Modifier
            .fillMaxSize(0.58f)
            .clip(CircleShape)
            .border(BorderStroke(2.dp, primary), CircleShape)

        if (coverUrl != null) {
            AsyncImage(
                model = buildCoverImageRequest(context, coverUrl, webDavConfig),
                contentDescription = stringResource(R.string.player_cover_desc),
                modifier = labelModifier,
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = labelModifier.background(surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = onSurfaceVariant
                )
            }
        }

        // 唱片中心孔
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(backgroundColor)
        )
    }
}

// Shared WebDAV-aware cover ImageRequest builder, reused by both the
// blurred background and the disc's inner label so auth headers/local-file
// handling stay identical between the two.
private fun buildCoverImageRequest(
    context: Context,
    coverUrl: String,
    webDavConfig: WebDavConfig?
): ImageRequest {
    return when {
        coverUrl.startsWith("/") || coverUrl.startsWith("file://") || coverUrl.startsWith("data:") -> {
            ImageRequest.Builder(context)
                .data(coverUrl)
                .crossfade(true)
                .build()
        }
        else -> {
            val headers = if (webDavConfig != null && webDavConfig.username.isNotBlank() && webDavConfig.password.isNotBlank()) {
                NetworkHeaders.Builder()
                    .set("Authorization", Credentials.basic(webDavConfig.username, webDavConfig.password))
                    .build()
            } else {
                NetworkHeaders.EMPTY
            }
            ImageRequest.Builder(context)
                .data(coverUrl)
                .httpHeaders(headers)
                .crossfade(true)
                .build()
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
