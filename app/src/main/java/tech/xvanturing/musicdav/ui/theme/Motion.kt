package tech.xvanturing.musicdav.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

/**
 * Vinyl Lounge motion tokens. Shared durations/easings + a handful of
 * common transition builders so later screens animate consistently.
 */
object MotionSpec {
    const val fast: Int = 150
    const val medium: Int = 250
    const val slow: Int = 400
}

val VinylEasingStandard = FastOutSlowInEasing
val VinylEasingDecelerate = LinearOutSlowInEasing

/**
 * New screen slides in from the right while fading in; the outgoing screen
 * fades out. Use for standard forward navigation (push).
 */
fun forwardPush(): ContentTransform {
    val enter = slideInHorizontally(
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingDecelerate)
    ) { fullWidth -> fullWidth } + fadeIn(
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingStandard)
    )
    val exit = slideOutHorizontally(
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingStandard)
    ) { fullWidth -> -fullWidth } + fadeOut(
        animationSpec = tween(MotionSpec.fast, easing = VinylEasingStandard)
    )
    return enter togetherWith exit
}

/**
 * Full-screen overlay (e.g. NowPlaying) sliding up from the bottom.
 */
fun slideUpEnter(): EnterTransition =
    slideInVertically(
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingDecelerate)
    ) { fullHeight -> fullHeight } + fadeIn(
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingStandard)
    )

/**
 * Dismissal counterpart to [slideUpEnter] - overlay slides back down.
 */
fun slideDownExit(): ExitTransition =
    slideOutVertically(
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingStandard)
    ) { fullHeight -> fullHeight } + fadeOut(
        animationSpec = tween(MotionSpec.fast, easing = VinylEasingStandard)
    )

/**
 * Cross-fade with a subtle scale, for tab / segment switches within the
 * same screen.
 */
fun fadeThrough(): ContentTransform {
    val enter = fadeIn(
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingStandard)
    ) + scaleIn(
        initialScale = 0.96f,
        animationSpec = tween(MotionSpec.medium, easing = VinylEasingStandard)
    )
    val exit = fadeOut(
        animationSpec = tween(MotionSpec.fast, easing = VinylEasingStandard)
    )
    return enter togetherWith exit
}
