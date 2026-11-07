/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Visual-only motion for tab selection; PagerState remains the destination owner. */
internal class PagerTabMotion {
    private val mutator = MutatorMutex()
    private val opacity = Animatable(1f)
    private val displacementDp = Animatable(0f)

    var isJumping by mutableStateOf(false)
        private set

    val alpha: Float get() = opacity.value
    val translationDp: Float get() = displacementDp.value

    suspend fun navigate(pager: PagerState, targetPage: Int) {
        mutator.mutate {
            if (targetPage == pager.settledPage && !pager.isScrollInProgress) return@mutate
            when {
                // A finger-controlled swipe stays native; every tab selection uses identical motion.
                pager.isScrollInProgress -> pager.scrollToPage(targetPage)
                else -> {
                    val direction = if (targetPage > pager.settledPage) 1 else -1
                    isJumping = true
                    try {
                        coroutineScope {
                            launch { opacity.animateTo(0f, tween(PAGE_MOTION_MILLIS / 2)) }
                            launch {
                                displacementDp.animateTo(
                                    -direction * PAGE_TRAVEL_DP / 2f,
                                    tween(PAGE_MOTION_MILLIS / 2, easing = FastOutSlowInEasing),
                                )
                            }
                        }
                        pager.scrollToPage(targetPage)
                        displacementDp.snapTo(direction * PAGE_TRAVEL_DP / 2f)
                        coroutineScope {
                            launch { opacity.animateTo(1f, tween(PAGE_MOTION_MILLIS / 2)) }
                            launch {
                                displacementDp.animateTo(
                                    0f, tween(PAGE_MOTION_MILLIS / 2, easing = FastOutSlowInEasing),
                                )
                            }
                        }
                    } finally {
                        // A newer selection may cancel this coroutine midway.
                        withContext(NonCancellable) {
                            opacity.snapTo(1f)
                            displacementDp.snapTo(0f)
                            isJumping = false
                        }
                    }
                }
            }
        }
    }
}

/** User-driven pager swipes are unmodified; bar/rail selections share one transition. */
internal suspend fun PagerState.navigateToTab(targetPage: Int, motion: PagerTabMotion) {
    motion.navigate(this, targetPage)
}
