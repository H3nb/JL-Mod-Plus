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

package ru.playsoftware.j2meloader.memory

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

class MemoryEditorBubbleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun entireBubbleReceivesTouchAndAccessibilityClick() {
        val touchActions = mutableListOf<Int>()
        var openCount = 0
        val description = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.memory_editor)

        composeRule.setContent {
            JLModPlusTheme {
                Box(Modifier.size(72.dp)) {
                    MemoryEditorBubble(
                        visible = true,
                        onOpen = { openCount++ },
                        onTouch = { event ->
                            touchActions += event.actionMasked
                            true
                        },
                    )
                }
            }
        }

        val bubble = composeRule.onNodeWithContentDescription(description)
        bubble.performTouchInput {
            down(Offset(1f, 1f))
            up()
        }
        bubble.performTouchInput {
            down(Offset(width.toFloat() - 1f, 1f))
            up()
        }
        bubble.performTouchInput {
            down(Offset(1f, height.toFloat() - 1f))
            up()
        }
        bubble.performTouchInput {
            down(Offset(width.toFloat() - 1f, height.toFloat() - 1f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(4, touchActions.count { it == MotionEvent.ACTION_DOWN })
            assertEquals(4, touchActions.count { it == MotionEvent.ACTION_UP })
        }

        touchActions.clear()
        bubble.performTouchInput {
            down(Offset(8f, 36f))
            moveTo(Offset(64f, 36f))
            up()
        }
        composeRule.runOnIdle {
            assertTrue(touchActions.contains(MotionEvent.ACTION_MOVE))
            assertEquals(MotionEvent.ACTION_UP, touchActions.last())
        }

        bubble.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, openCount) }
    }
}
