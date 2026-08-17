/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.applist

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

internal data class LibraryFastScrollBucket(
    val label: String,
    val appIndex: Int,
)

/**
 * Builds section anchors from the list that is already ordered by the repository.
 * This intentionally does not sort or mutate data: the UI follows the active SQL order.
 */
internal fun buildLibraryFastScrollBuckets(
    apps: List<LibraryAppUiItem>,
    sortVariant: Int,
    locale: Locale = Locale.getDefault(),
): List<LibraryFastScrollBucket> {
    val selector: (LibraryAppUiItem) -> String = when (sortVariant and Int.MAX_VALUE) {
        0 -> LibraryAppUiItem::title
        2 -> LibraryAppUiItem::author
        else -> return emptyList()
    }
    if (apps.isEmpty()) return emptyList()

    val buckets = LinkedHashMap<String, Int>()
    apps.forEachIndexed { index, app ->
        val label = libraryFastScrollLabel(selector(app), locale)
        buckets.putIfAbsent(label, index)
    }
    return buckets.map { (label, index) -> LibraryFastScrollBucket(label, index) }
}

internal fun libraryFastScrollLabel(
    value: String,
    locale: Locale = Locale.getDefault(),
): String {
    val first = value.trimStart().firstOrNull() ?: return "#"
    val upper = first.toString().uppercase(locale).firstOrNull() ?: return "#"
    return if (upper in 'A'..'Z') upper.toString() else "#"
}

/**
 * Keeps the side index legible on short landscape viewports without changing the drag mapping.
 * First/last anchors are always retained and intermediate labels are sampled evenly.
 */
internal fun visibleLibraryFastScrollBuckets(
    buckets: List<LibraryFastScrollBucket>,
    maxSlots: Int,
): List<LibraryFastScrollBucket> {
    if (buckets.isEmpty() || maxSlots <= 0) return emptyList()
    if (buckets.size <= maxSlots) return buckets
    if (maxSlots == 1) return listOf(buckets.first())

    return (0 until maxSlots)
        .map { slot ->
            val index = (slot.toFloat() * (buckets.lastIndex.toFloat() / (maxSlots - 1)))
                .roundToInt()
                .coerceIn(0, buckets.lastIndex)
            buckets[index]
        }
        .distinctBy(LibraryFastScrollBucket::appIndex)
}

internal fun libraryFastScrollBucketIndexForPosition(
    y: Float,
    heightPx: Int,
    bucketCount: Int,
): Int {
    if (heightPx <= 0 || bucketCount <= 0) return -1
    val fraction = (y / heightPx.toFloat()).coerceIn(0f, 0.999999f)
    return (fraction * bucketCount).toInt().coerceIn(0, bucketCount - 1)
}

/**
 * Compact right-edge alphabet navigator. The visible letters may be sampled on short screens,
 * while tap/drag gestures continue to address every real bucket in list order.
 */
@Composable
internal fun LibraryFastScroller(
    buckets: List<LibraryFastScrollBucket>,
    onBucketSelected: (LibraryFastScrollBucket) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (buckets.size < 2) return

    var heightPx by remember { mutableIntStateOf(0) }
    var activeIndex by remember { mutableIntStateOf(-1) }

    fun selectAt(y: Float) {
        val index = libraryFastScrollBucketIndexForPosition(y, heightPx, buckets.size)
        if (index >= 0 && index != activeIndex) {
            activeIndex = index
            onBucketSelected(buckets[index])
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val maxSlots = (maxHeight.value / FAST_SCROLL_MIN_SLOT_HEIGHT_DP)
            .toInt()
            .coerceAtLeast(FAST_SCROLL_MIN_VISIBLE_SLOTS)
        val visibleBuckets = remember(buckets, maxSlots) {
            visibleLibraryFastScrollBuckets(buckets, maxSlots)
        }

        Row(
            modifier = Modifier
                .fillMaxHeight()
                .onSizeChanged { heightPx = it.height }
                .pointerInput(buckets, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selectAt(down.position.y)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            selectAt(change.position.y)
                            change.consume()
                        }
                        activeIndex = -1
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activeIndex >= 0) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = buckets[activeIndex].label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(FAST_SCROLL_INDEX_WIDTH_DP.dp)
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                visibleBuckets.forEach { bucket ->
                    val active = activeIndex >= 0 && buckets[activeIndex] == bucket
                    Text(
                        text = bucket.label,
                        modifier = Modifier.heightIn(min = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private const val FAST_SCROLL_MIN_SLOT_HEIGHT_DP = 14f
private const val FAST_SCROLL_MIN_VISIBLE_SLOTS = 3
private const val FAST_SCROLL_INDEX_WIDTH_DP = 24
