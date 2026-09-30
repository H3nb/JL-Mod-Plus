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

package javax.microedition.shell

/**
 * Presentation-only ordering for the Show Controls dialog.
 *
 * Rows always contain indices into the original VirtualKeyboard visibility array. Visual grouping
 * must never turn into a reordered persistence array.
 */
internal data class ShowControlsPlan(
    val actionRows: List<List<Int?>>,
    val directionalRows: List<List<Int?>>,
    val numericRows: List<List<Int?>>,
    val semantic: Boolean,
) {
    val groups: List<List<List<Int?>>>
        get() = listOf(actionRows, directionalRows, numericRows).filter { it.isNotEmpty() }

    companion object {
        private const val LegacyControlCount = 28
        private const val GroupedControlCount = 2

        private val CanonicalLegacyLabels = setOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "*", "#",
            "L", "R", "D", "C",
            "↖", "↑", "↗", "←", "→", "↙", "↓", "↘",
            "F", "A", "B", "M",
        )

        fun resolve(names: List<String>): ShowControlsPlan {
            val supportedSize =
                names.size == LegacyControlCount ||
                    names.size == LegacyControlCount + GroupedControlCount
            val legacy = names.take(LegacyControlCount)
            val canonicalShape =
                supportedSize &&
                    legacy.size == LegacyControlCount &&
                    legacy.distinct().size == LegacyControlCount &&
                    legacy.toSet() == CanonicalLegacyLabels
            if (!canonicalShape) return sequential(names.size)

            val legacyIndex = legacy.withIndex().associate { (index, label) -> label to index }
            fun key(label: String): Int = requireNotNull(legacyIndex[label])
            fun row(first: Int?, second: Int?, third: Int?) =
                listOf(first, second, third)

            val dpad = if (names.size > LegacyControlCount) LegacyControlCount else null
            val analog =
                if (names.size > LegacyControlCount + 1) LegacyControlCount + 1 else null

            return ShowControlsPlan(
                actionRows = listOf(
                    row(key("L"), key("M"), key("R")),
                    row(key("A"), dpad, key("B")),
                    row(key("C"), analog, key("D")),
                ),
                directionalRows = listOf(
                    row(key("↖"), key("↑"), key("↗")),
                    row(key("←"), key("F"), key("→")),
                    row(key("↙"), key("↓"), key("↘")),
                ),
                numericRows = listOf(
                    row(key("1"), key("2"), key("3")),
                    row(key("4"), key("5"), key("6")),
                    row(key("7"), key("8"), key("9")),
                    row(key("*"), key("0"), key("#")),
                ),
                semantic = true,
            )
        }

        private fun sequential(count: Int): ShowControlsPlan {
            val rows = (0 until count)
                .chunked(3)
                .map { values -> List<Int?>(3) { column -> values.getOrNull(column) } }
            return ShowControlsPlan(
                actionRows = rows,
                directionalRows = emptyList(),
                numericRows = emptyList(),
                semantic = false,
            )
        }
    }
}
