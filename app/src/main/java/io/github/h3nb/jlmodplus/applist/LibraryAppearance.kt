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

package io.github.h3nb.jlmodplus.applist

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.util.Constants

enum class LibraryLayout(val storedValue: Int) {
    List(Constants.LIBRARY_LAYOUT_LIST),
    Grid(Constants.LIBRARY_LAYOUT_GRID);

    companion object {
        @JvmStatic
        fun fromStoredValue(value: Int): LibraryLayout =
            entries.firstOrNull { it.storedValue == value } ?: List
    }
}

enum class LibraryIconRatio(
    val widthToHeight: Float,
    val storedValue: Int,
    val preferenceKey: String,
) {
    Square(1f, Constants.LIBRARY_ICON_RATIO_SQUARE, "square"),
    Portrait(3f / 4f, Constants.LIBRARY_ICON_RATIO_PORTRAIT, "portrait");

    companion object {
        @JvmStatic
        fun fromStoredValue(value: Int): LibraryIconRatio =
            entries.firstOrNull { it.storedValue == value } ?: Square

        @JvmStatic
        fun fromPreferenceKey(key: String): LibraryIconRatio =
            entries.firstOrNull { it.preferenceKey == key } ?: Square
    }
}

/** The tile silhouette is independent from the selected 1:1 or 3:4 ratio. */
enum class LibraryIconShape(
    val storedValue: Int,
    val preferenceKey: String,
) {
    Round(Constants.LIBRARY_ICON_SHAPE_ROUND, "round"),
    Square(Constants.LIBRARY_ICON_SHAPE_SQUARE, "square");

    companion object {
        @JvmStatic
        fun fromStoredValue(value: Int): LibraryIconShape =
            entries.firstOrNull { it.storedValue == value } ?: Round

        @JvmStatic
        fun fromPreferenceKey(key: String): LibraryIconShape =
            entries.firstOrNull { it.preferenceKey == key } ?: Round
    }
}

enum class LibraryGridIconSize(
    val minCellWidth: Dp,
    val maxArtworkSize: Dp,
    val storedValue: Int,
    val preferenceKey: String,
) {
    Small(72.dp, 56.dp, Constants.LIBRARY_GRID_ICON_SIZE_SMALL, "small"),
    Standard(88.dp, 72.dp, Constants.LIBRARY_GRID_ICON_SIZE_STANDARD, "standard"),
    Large(112.dp, 96.dp, Constants.LIBRARY_GRID_ICON_SIZE_LARGE, "large");

    companion object {
        @JvmStatic
        fun fromStoredValue(value: Int): LibraryGridIconSize =
            entries.firstOrNull { it.storedValue == value } ?: Standard

        @JvmStatic
        fun fromPreferenceKey(key: String): LibraryGridIconSize =
            entries.firstOrNull { it.preferenceKey == key } ?: Standard
    }
}

enum class LibraryGridSpacing(
    val value: Dp,
    val storedValue: Int,
    val preferenceKey: String,
) {
    /** Tiles touch at their edges; useful when the grid should read as a continuous sheet. */
    None(0.dp, Constants.LIBRARY_GRID_SPACING_NONE, "none"),
    Compact(4.dp, Constants.LIBRARY_GRID_SPACING_COMPACT, "compact"),
    Standard(8.dp, Constants.LIBRARY_GRID_SPACING_STANDARD, "standard"),
    Spacious(12.dp, Constants.LIBRARY_GRID_SPACING_SPACIOUS, "spacious");

    companion object {
        @JvmStatic
        fun fromStoredValue(value: Int): LibraryGridSpacing =
            entries.firstOrNull { it.storedValue == value } ?: Standard

        @JvmStatic
        fun fromPreferenceKey(key: String): LibraryGridSpacing =
            entries.firstOrNull { it.preferenceKey == key } ?: Standard
    }
}
