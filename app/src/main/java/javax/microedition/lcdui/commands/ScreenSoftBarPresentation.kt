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

package javax.microedition.lcdui.commands

import javax.microedition.lcdui.Command

/**
 * Presentation-only result for a Screen's three host soft-key positions.
 * Command ownership and activation remain in the Java ME Displayable.
 */
internal data class ScreenSoftBarPresentation(
    val left: Command? = null,
    val middle: Command? = null,
    val right: Command? = null,
    val overflow: List<Command> = emptyList(),
)

/**
 * Applies the MIDP/vendor soft-menu placement policy without changing the
 * command objects or their listener dispatch path.
 *
 * Lower command priority values remain more important because the existing
 * Command.compareTo implementation is the project's compatibility ordering.
 * The first OK command is preferred for the middle key; the first BACK or EXIT
 * command is preferred for the right key. Remaining commands are placed in
 * the menu whenever more than one would otherwise compete for the left key.
 */
internal object ScreenSoftBarPolicy {
    @JvmStatic
    fun present(source: List<Command>): ScreenSoftBarPresentation {
        val ordered = source.sorted()
        val middle = ordered.firstOrNull { it.commandType == Command.OK }
        val right = ordered.firstOrNull {
            it.commandType == Command.BACK || it.commandType == Command.EXIT
        }
        val remaining = ordered.toMutableList().apply {
            middle?.let(::remove)
            right?.let(::remove)
        }

        val arranged = buildList {
            right?.let(::add)
            middle?.let(::add)
            addAll(remaining)
        }
        var menuStart = 0
        if (middle != null && arranged.size > 1) {
            menuStart++
        }
        if (right != null) {
            menuStart++
        }

        val menuCandidates = arranged.drop(menuStart)
        val middleSlot = if (right != null) middle else null
        val rightSlot = when {
            right != null -> right
            middle != null && arranged.size > 1 -> middle
            else -> null
        }
        if (menuCandidates.size > 1) {
            return ScreenSoftBarPresentation(
                middle = middleSlot,
                right = rightSlot,
                overflow = menuCandidates,
            )
        }

        return ScreenSoftBarPresentation(
            left = menuCandidates.firstOrNull(),
            middle = middleSlot,
            right = rightSlot,
        )
    }
}
