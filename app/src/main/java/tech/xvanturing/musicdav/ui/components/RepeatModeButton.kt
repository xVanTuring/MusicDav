package tech.xvanturing.musicdav.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.xvanturing.musicdav.R
import tech.xvanturing.musicdav.data.PlayMode

/**
 * 三态循环模式按钮，两处（迷你播放条 + 全屏播放页）共用。
 * - PLAY_ONCE：无背景、灰色 Repeat 图标 → 一眼看出“关”。
 * - REPEAT_ALL：金色 Repeat + 金色 pill 背景。
 * - REPEAT_SINGLE：金色 RepeatOne + 金色 pill 背景。
 * pill 背景与图标颜色用 animateColorAsState 平滑且可打断（快速反复点不会像
 * AnimatedContent 那样堆叠新旧两份组合而卡顿）；每次切换用一个 spring 缩放
 * “弹一下”做触感反馈（首次组合不弹）。
 */
@Composable
fun RepeatModeButton(
    playMode: PlayMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 24.dp,
) {
    val isOn = playMode != PlayMode.PLAY_ONCE
    val icon = if (playMode == PlayMode.REPEAT_SINGLE) Icons.Default.RepeatOne else Icons.Default.Repeat
    val targetTint = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val targetPill = if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else Color.Transparent
    val tint by animateColorAsState(targetTint, animationSpec = tween(220), label = "repeatTint")
    val pill by animateColorAsState(targetPill, animationSpec = tween(220), label = "repeatPill")
    val description = when (playMode) {
        PlayMode.REPEAT_SINGLE -> stringResource(R.string.player_mode_repeat_single)
        PlayMode.REPEAT_ALL -> stringResource(R.string.player_mode_repeat_all)
        PlayMode.PLAY_ONCE -> stringResource(R.string.player_mode_play_once)
    }

    // 切换时轻微缩放弹一下（首次组合不触发）
    // 用 mutableStateOf 而非纯 var 持有：remember { false } 计算一次后永远
    // 缓存在 slot table 里，若只用 `var initialized = remember { false }`，
    // 每次重组都会重新读到最初的 false，协程里的赋值不会写回 slot，弹跳会
    // 一直触发不了；MutableState 的写入才会真正持久化。
    val scale = remember { Animatable(1f) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(playMode) {
        if (initialized) {
            scale.snapTo(0.82f)
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
        initialized = true
    }

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(pill),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(buttonSize)) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
            )
        }
    }
}
