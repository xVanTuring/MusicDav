package com.spotify.music.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.layout.Layout

//@Composable
//fun AlbumTitleText(
//    modifier: Modifier = Modifier,
//    text: String
//) {
//    // Always use marquee logic to handle any text that doesn't fit
//    MarqueeText(
//        modifier = modifier,
//        text = text
//    )
//}

//@Composable
//fun MarqueeText(
//    text: String,
//    modifier: Modifier = Modifier,
//    velocity: Float = 50f // 每秒移动的 px
//) {
//    var textWidth by remember { mutableStateOf(0f) }
//    var containerWidth by remember { mutableStateOf(0f) }
//    val offset = remember { Animatable(0f) }
//
//    val density = LocalDensity.current
//
//    // 启动动画
//    LaunchedEffect(textWidth, containerWidth) {
//        if (textWidth > containerWidth) {
//            offset.snapTo(0f)
//
//            // 无限循环
//            while (true) {
//                val distance = textWidth - containerWidth
//                val duration = (distance / velocity * 1000).toInt()
//
//                offset.animateTo(
//                    targetValue = -distance,
//                    animationSpec = tween(duration, easing = LinearEasing)
//                )
//                offset.snapTo(0f)
//            }
//        } else {
//            offset.snapTo(0f)
//        }
//    }
//
//    Layout(
//        modifier = modifier
//            .onGloballyPositioned {
//                containerWidth = it.size.width.toFloat()
//            },
//        content = {
//            Text(
//                text = text,
//                maxLines = 1,
//                softWrap = false,
//                overflow = TextOverflow.Visible,
//                onTextLayout = { result ->
//                    textWidth = result.size.width.toFloat()
//                }
//            )
//        }
//    ) { measurables, constraints ->
//
//        val placeable = measurables[0].measure(constraints)
//
//        layout(constraints.maxWidth, placeable.height) {
//            val x = offset.value.toInt()
//            placeable.placeRelative(x, 0)
//        }
//    }
//}

@Composable
fun MarqueeText(
    modifier: Modifier = Modifier,
    text: String
) {
//    val density = LocalDensity.current
//    val scrollAnim = remember { Animatable(0f) }
//    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
//    var containerWidth by remember { mutableFloatStateOf(0f) }
//    var shouldAnimate by remember { mutableStateOf(false) }
//    var animationStarted by remember { mutableStateOf(false) }

    // Check if we need to animate whenever measurements are updated
//    LaunchedEffect(textLayoutResult, containerWidth) {
//        if (textLayoutResult != null && containerWidth > 0) {
//            val textWidth = textLayoutResult!!.size.width.toFloat()
//            val needsAnimation = textWidth > containerWidth
//
//            if (needsAnimation && !animationStarted) {
//                shouldAnimate = true
//                animationStarted = true
//
//                // Start the marquee animation
//                scrollAnim.snapTo(0f)
//                scrollAnim.animateTo(
//                    targetValue = -(textWidth - containerWidth),
//                    animationSpec = infiniteRepeatable(
//                        animation = tween(
//                            durationMillis = (text.length * 50).coerceAtLeast(2000),
//                            easing = LinearEasing
//                        ),
//                        repeatMode = RepeatMode.Restart
//                    )
//                )
//            } else if (!needsAnimation) {
//                shouldAnimate = false
//                animationStarted = false
//                // Reset animation if no longer needed
//                scrollAnim.snapTo(0f)
//            }
//        }
//    }

    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
//        overflow = if (shouldAnimate) TextOverflow.Visible else TextOverflow.Ellipsis,
//        onTextLayout = { result ->
//            textLayoutResult = result
//        },
        modifier = modifier
            .fillMaxWidth(),
//            .onGloballyPositioned { coordinates ->
//                containerWidth = coordinates.size.width.toFloat()
//            }
//            .padding(start = with(density) {
//                if (shouldAnimate) scrollAnim.value.toDp() else 0.dp
//            }),
        softWrap = false
    )
}