/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Shared presentation timing for app-owned full-screen destinations. */
internal const val PAGE_MOTION_MILLIS = 220
internal const val PAGE_FADE_MILLIS = 180
internal const val PAGE_TRAVEL_DP = 16

/** Positive is forward, negative is back; peers use their own visual order. */
internal fun pageMotionDirection(
    initialDepth: Int,
    targetDepth: Int,
    initialSibling: Int = 0,
    targetSibling: Int = 0,
): Int = when {
    targetDepth > initialDepth -> 1
    targetDepth < initialDepth -> -1
    targetSibling > initialSibling -> 1
    targetSibling < initialSibling -> -1
    else -> 0
}

internal fun pageEnter(direction: Int, travelPx: Int): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(PAGE_MOTION_MILLIS, easing = FastOutSlowInEasing),
        initialOffsetX = { direction.coerceIn(-1, 1) * travelPx },
    ) + fadeIn(animationSpec = tween(PAGE_FADE_MILLIS))

internal fun pageExit(direction: Int, travelPx: Int): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(PAGE_MOTION_MILLIS, easing = FastOutSlowInEasing),
        targetOffsetX = { -direction.coerceIn(-1, 1) * travelPx / 2 },
    ) + fadeOut(animationSpec = tween(PAGE_FADE_MILLIS))
