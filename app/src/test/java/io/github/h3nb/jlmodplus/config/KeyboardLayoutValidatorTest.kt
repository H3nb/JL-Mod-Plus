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

package io.github.h3nb.jlmodplus.config

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import javax.microedition.lcdui.keyboard.RectSnap
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardLayoutValidatorTest {
    @Test
    fun standardLayoutTypesSevenAndEightAreValidButNineIsRejected() {
        for (type in 0..8) {
            assertNull(KeyboardLayoutValidator.validate(layoutFile(version = 3, type = type)))
        }
        assertNotNull(KeyboardLayoutValidator.validate(layoutFile(version = 3, type = 9)))
    }

    @Test
    fun scaleCountMatchesEachLayoutFormatVersion() {
        assertNull(KeyboardLayoutValidator.validate(layoutFile(version = 3, type = 0, scales = 12)))
        assertNotNull(KeyboardLayoutValidator.validate(layoutFile(version = 3, type = 0, scales = 13)))

        assertNull(KeyboardLayoutValidator.validate(layoutFile(version = 2, type = 0, scales = 6)))
        assertNotNull(KeyboardLayoutValidator.validate(layoutFile(version = 2, type = 0, scales = 7)))

        assertNull(KeyboardLayoutValidator.validate(layoutFile(version = 1, type = 0, scales = 6)))
        assertNotNull(KeyboardLayoutValidator.validate(layoutFile(version = 1, type = 0, scales = 7)))
    }

    @Test
    fun excessiveKeyCountIsRejectedBeforePayloadIsConsumed() {
        val file = tempFile()
        DataOutputStream(FileOutputStream(file)).use { out ->
            writeHeader(out, 3)
            writeType(out, 0)
            out.writeInt(KEYS)
            out.writeInt(4 + 29 * KEY_RECORD_SIZE_V2)
            out.writeInt(29)
        }

        assertNotNull(KeyboardLayoutValidator.validate(file))
    }

    @Test
    fun invalidSnapOriginAndNonFiniteOffsetsAreRejected() {
        val invalidOrigin = layoutWithSingleKey(origin = 28, offsetX = 0.0f)
        assertNotNull(KeyboardLayoutValidator.validate(invalidOrigin))

        val nonFinite = layoutWithSingleKey(origin = -1, offsetX = Float.NaN)
        assertNotNull(KeyboardLayoutValidator.validate(nonFinite))
    }

    @Test
    fun legacyNoSnapEntryRemainsAcceptedForRuntimeRecovery() {
        val file = layoutWithSingleKey(
            origin = -1,
            mode = RectSnap.NO_SNAP,
            offsetX = 0.0f,
        )

        assertNull(KeyboardLayoutValidator.validate(file))
    }

    @Test
    fun truncatedPayloadFailsCleanly() {
        val file = tempFile()
        DataOutputStream(FileOutputStream(file)).use { out ->
            writeHeader(out, 3)
            out.writeInt(TYPE)
            out.writeInt(1)
        }

        assertNotNull(KeyboardLayoutValidator.validate(file))
    }

    private fun layoutFile(version: Int, type: Int, scales: Int? = null): File {
        val file = tempFile()
        DataOutputStream(FileOutputStream(file)).use { out ->
            writeHeader(out, version)
            writeType(out, type)
            if (scales != null) {
                out.writeInt(SCALES)
                out.writeInt(4 + scales * 4)
                out.writeInt(scales)
                repeat(scales) { out.writeFloat(1.0f) }
            }
            writeEnd(out)
        }
        return file
    }

    private fun layoutWithSingleKey(
        origin: Int,
        mode: Int = RectSnap.INT_NORTHWEST,
        offsetX: Float,
    ): File {
        val file = tempFile()
        DataOutputStream(FileOutputStream(file)).use { out ->
            writeHeader(out, 3)
            writeType(out, 0)
            out.writeInt(KEYS)
            out.writeInt(4 + KEY_RECORD_SIZE_V2)
            out.writeInt(1)
            out.writeInt(12345)
            out.writeBoolean(true)
            out.writeInt(origin)
            out.writeInt(mode)
            out.writeFloat(offsetX)
            out.writeFloat(0.0f)
            writeEnd(out)
        }
        return file
    }

    private fun tempFile(): File =
        Files.createTempFile("jlmod-keyboard-layout", ".bin").toFile().apply { deleteOnExit() }

    private fun writeHeader(out: DataOutputStream, version: Int) {
        out.writeInt(SIGNATURE)
        out.writeInt(version)
    }

    private fun writeType(out: DataOutputStream, type: Int) {
        out.writeInt(TYPE)
        out.writeInt(1)
        out.writeByte(type)
    }

    private fun writeEnd(out: DataOutputStream) {
        out.writeInt(EOF)
        out.writeInt(0)
    }

    private companion object {
        const val SIGNATURE = 0x564B4C00
        const val EOF = -1
        const val KEYS = 0
        const val SCALES = 1
        const val TYPE = 3
        const val KEY_RECORD_SIZE_V2 = 21
    }
}
