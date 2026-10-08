/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.ui

import androidx.compose.foundation.pager.PagerState
import kotlin.math.abs

/**
 * Bottom-bar/rail selection is destination navigation, not a swipe through intervening tabs.
 * Keep the adjacent-page transition, but jump directly for distant destinations or when a
 * gesture/animation is already moving the pager. User-initiated pager swipes are unchanged.
 */
internal suspend fun PagerState.navigateToTab(targetPage: Int) {
    if (targetPage == settledPage && !isScrollInProgress) return
    if (isScrollInProgress || abs(targetPage - settledPage) > 1) {
        scrollToPage(targetPage)
    } else {
        animateScrollToPage(targetPage)
    }
}
