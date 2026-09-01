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

import android.view.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryInputComposeTest {
    @Test
    fun hardwareKeypadTokensCoverNumericAndFloatingInput() {
        assertEquals("0", memoryHardwareInputToken(KeyEvent.KEYCODE_0))
        assertEquals("7", memoryHardwareInputToken(KeyEvent.KEYCODE_NUMPAD_7))
        assertEquals("-", memoryHardwareInputToken(KeyEvent.KEYCODE_NUMPAD_SUBTRACT))
        assertEquals("+", memoryHardwareInputToken(KeyEvent.KEYCODE_NUMPAD_ADD))
        assertEquals(".", memoryHardwareInputToken(KeyEvent.KEYCODE_PERIOD))
        assertEquals("e", memoryHardwareInputToken(KeyEvent.KEYCODE_E))
        assertEquals(null, memoryHardwareInputToken(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun integerSpecsRejectWrongCharactersAndOutOfRangeValues() {
        val signed = MemoryInputSpec.forType(MemoryEngineContract.TYPE_BYTE)
        assertTrue(signed.acceptsPartial("-"))
        assertTrue(signed.acceptsPartial("-12"))
        assertFalse(signed.acceptsPartial("1.2"))
        assertTrue(signed.isComplete("127"))
        assertTrue(signed.isComplete("-128"))
        assertFalse(signed.isComplete("128"))

        val unsigned = MemoryInputSpec.forType(MemoryEngineContract.TYPE_CHAR)
        assertTrue(unsigned.acceptsPartial("65535"))
        assertFalse(unsigned.acceptsPartial("-1"))
        assertFalse(unsigned.acceptsPartial("1.0"))
        assertTrue(unsigned.isComplete("65535"))
        assertFalse(unsigned.isComplete("65536"))
    }

    @Test
    fun floatingSpecAllowsUsefulPartialScientificNotationOnly() {
        val spec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_DOUBLE)
        for (partial in listOf("-", ".", "-.", "1.", "1.5", "1.5E", "1.5E-", "1.5E-3")) {
            assertTrue(partial, spec.acceptsPartial(partial))
        }
        for (invalid in listOf("1..2", "E2", "1E2E3", "1A", "--1")) {
            assertFalse(invalid, spec.acceptsPartial(invalid))
        }
        assertFalse(spec.isComplete("1.5E-"))
        assertTrue(spec.isComplete("1.5E-3"))
    }

    @Test
    fun float32RejectsOverflowThatDoubleWouldAccept() {
        val floatSpec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_FLOAT)
        val doubleSpec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_DOUBLE)
        assertFalse(floatSpec.isComplete("1e39"))
        assertTrue(doubleSpec.isComplete("1e39"))
    }

    @Test
    fun editorOperationsRespectSelectionCursorAndValidation() {
        val intSpec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_INT)
        var value = TextFieldValue("123", TextRange(1, 2))
        value = MemoryInputEditing.insert(value, "9", intSpec)
        assertEquals("193", value.text)
        assertEquals(2, value.selection.start)

        value = MemoryInputEditing.backspace(value)
        assertEquals("13", value.text)
        value = MemoryInputEditing.toggleSign(value, intSpec)
        assertEquals("-13", value.text)
        assertEquals("-13", MemoryInputEditing.insert(value, ".", intSpec).text)
    }

    @Test
    fun mixedTypeValidationFailsClosedForNarrowCandidates() {
        assertTrue(memoryInputCompleteForTypes(
            "100",
            listOf(MemoryEngineContract.TYPE_BYTE, MemoryEngineContract.TYPE_INT),
        ))
        assertFalse(memoryInputCompleteForTypes(
            "200",
            listOf(MemoryEngineContract.TYPE_BYTE, MemoryEngineContract.TYPE_INT),
        ))
        assertFalse(memoryInputCompleteForTypes(
            "1.5",
            listOf(MemoryEngineContract.TYPE_INT, MemoryEngineContract.TYPE_DOUBLE),
        ))
    }

    @Test
    fun inputSessionDeactivatesOnlyItsCurrentField() {
        val session = MemoryInputSession()
        val first = Any()
        val second = Any()
        session.activate(first, "1", MemoryInputSpec.forType(MemoryEngineContract.TYPE_INT)) { }
        session.deactivate(second)
        assertTrue(session.active)
        session.deactivate(first)
        assertFalse(session.active)
    }

    @Test
    fun signToggleTargetsExponentWhenCursorIsAfterExponent() {
        val spec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_DOUBLE)
        var value = TextFieldValue("1E3", TextRange(2))
        value = MemoryInputEditing.toggleSign(value, spec)
        assertEquals("1E-3", value.text)
        value = MemoryInputEditing.toggleSign(value, spec)
        assertEquals("1E3", value.text)
    }
}
