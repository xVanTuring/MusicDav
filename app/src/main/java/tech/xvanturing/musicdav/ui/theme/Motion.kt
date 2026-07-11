package tech.xvanturing.musicdav.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset

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
 * Small fixed slide distance (in px) used for the shared-axis-style screen
 * transitions in [forwardPush] / `reversePush`, roughly 30dp at 3x density
 * (~30-45dp on common phone densities). Deliberately small — unlike a
 * full-width slide, a dropped frame only nudges the position slightly, and
 * the accompanying fade masks any composition hitches. This keeps the
 * transition forgiving on high-refresh-rate displays where the frame
 * budget is tight (e.g. ~8ms at 120Hz).
 */
private const val SharedAxisSlidePx = 90

/**
 * Smooth, barely-damped spatial spring for screen slides (no visible
 * bounce). Used for all spatial (position) motion so screens settle
 * organically instead of stopping abruptly like a tween does.
 */
private fun spatialSpring() = spring<IntOffset>(
    dampingRatio = 1f,                   // critically damped: smooth, no bounce
    stiffness = Spring.StiffnessMediumLow // graceful, not sluggish
)

/**
 * Faster, critically-damped spring for the small shared-axis screen pushes.
 * Higher stiffness + critical damping means it settles in ~250-300ms with no
 * oscillation tail, so AnimatedContent disposes the outgoing screen promptly
 * (a soft spring's ~1s tail keeps the old screen composed and — after fade-out
 * — invisibly hit-testable, which caused ghost clicks and prolonged the
 * compose/animation overlap during the transition).
 */
private fun screenPushSpring() = spring<IntOffset>(
    dampingRatio = 1f,
    stiffness = Spring.StiffnessMedium
)

/**
 * New screen slides in from a small offset to the right while fading in;
 * the outgoing screen slides out a small offset to the left while fading
 * out (Material "shared axis" style). Use for standard forward navigation
 * (push).
 */
fun forwardPush(): ContentTransform {
    val enter = slideInHorizontally(
        animationSpec = screenPushSpring()
    ) { SharedAxisSlidePx } + fadeIn(
        animationSpec = tween(200)
    )
    val exit = slideOutHorizontally(
        animationSpec = screenPushSpring()
    ) { -SharedAxisSlidePx } + fadeOut(
        animationSpec = tween(120)
    )
    return enter togetherWith exit
}

/**
 * Full-screen overlay (e.g. NowPlaying) sliding up from the bottom.
 */
fun slideUpEnter(): EnterTransition =
    slideInVertically(
        animationSpec = spatialSpring()
    ) { fullHeight -> fullHeight } + fadeIn(
        animationSpec = tween(180)
    )

/**
 * Dismissal counterpart to [slideUpEnter] - overlay slides back down.
 */
fun slideDownExit(): ExitTransition =
    slideOutVertically(
        animationSpec = spatialSpring()
    ) { fullHeight -> fullHeight } + fadeOut(
        animationSpec = tween(160)
    )

/**
 * Cross-fade with a subtle scale, for tab / segment switches within the
 * same screen.
 */
fun fadeThrough(): ContentTransform {
    val enter = fadeIn(
        animationSpec = tween(200)
    ) + scaleIn(
        initialScale = 0.97f,
        animationSpec = spring(dampingRatio = 0.95f, stiffness = Spring.StiffnessMediumLow)
    )
    val exit = fadeOut(
        animationSpec = tween(140)
    )
    return enter togetherWith exit
}

/**
 * Consumes ALL pointer events in the initial pass so neither this subtree nor
 * anything layered beneath it reacts to touches. Applied to the *exiting* child
 * of an [androidx.compose.animation.AnimatedContent] while it is still composed
 * (and, after fade-out, invisible) but not yet disposed — otherwise taps on
 * empty areas of the incoming screen fall through to the still-hit-testable
 * outgoing screen ("ghost clicks" that e.g. start playback).
 */
fun Modifier.blockPointerInput(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
