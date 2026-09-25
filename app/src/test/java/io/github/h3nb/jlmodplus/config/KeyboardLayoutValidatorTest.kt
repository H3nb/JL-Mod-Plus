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
import javax.microedition.lcdui.keyboard.VirtualKeyboard
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

        val invalidRecoveryOrigin = layoutWithSingleKey(
            origin = 99,
            mode = RectSnap.NO_SNAP,
            offsetX = 0.0f,
        )
        assertNotNull(KeyboardLayoutValidator.validate(invalidRecoveryOrigin))

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

    @Test
    fun v4KeepsActiveTypeOrthogonalToDormantCustomState() {
        assertNull(KeyboardLayoutValidator.validate(v4File(type = 3)))
        assertNull(KeyboardLayoutValidator.validate(v4File(type = 3, base = 7)))
        assertNull(KeyboardLayoutValidator.validate(
            v4File(type = 6, base = 8, portrait = true, landscape = true),
        ))

        assertNotNull(KeyboardLayoutValidator.validate(v4File(type = 0)))
        assertNotNull(KeyboardLayoutValidator.validate(
            v4File(type = 3, portrait = true),
        ))
        assertNotNull(KeyboardLayoutValidator.validate(
            v4File(type = 3, base = 7, portrait = true, cycle = true),
        ))
    }

    @Test
    fun v4AcceptsEverySupportedBaseWithOptionalOrientationOverrides() {
        for (base in 1..8) {
            assertNull(KeyboardLayoutValidator.validate(v4File(base = base)))
            assertNull(KeyboardLayoutValidator.validate(
                v4File(base = base, portrait = true),
            ))
            assertNull(KeyboardLayoutValidator.validate(
                v4File(base = base, landscape = true),
            ))
        }
        assertNotNull(KeyboardLayoutValidator.validate(v4File(base = 9)))
        assertNotNull(KeyboardLayoutValidator.validate(v4File(base = 0)))
    }

    @Test
    fun v4AcceptsBothIndependentOverridesWithoutBase() {
        assertNull(KeyboardLayoutValidator.validate(
            v4File(portrait = true, landscape = true),
        ))
    }

    @Test
    fun v4AcceptsMigrationFallbackOnlyButRejectsSingleUnbackedOverride() {
        assertNull(KeyboardLayoutValidator.validate(v4File(legacy = true)))
        assertNotNull(KeyboardLayoutValidator.validate(v4File(portrait = true)))
        assertNotNull(KeyboardLayoutValidator.validate(v4File(landscape = true)))
    }

    @Test
    fun v4RejectsDuplicateSingletonBlocks() {
        val file = tempFile()
        DataOutputStream(FileOutputStream(file)).use { out ->
            writeHeader(out, 4)
            writeType(out, 0)
            writeBase(out, 7)
            writeBase(out, 8)
            writeEnd(out)
        }
        assertNotNull(KeyboardLayoutValidator.validate(file))
    }

    @Test
    fun v4RejectsMalformedCountsTopologyAndGroupedGeometry() {
        assertNotNull(KeyboardLayoutValidator.validate(
            v4File(base = 7, portrait = true, keyCount = 27),
        ))
        assertNotNull(KeyboardLayoutValidator.validate(
            v4File(base = 7, portrait = true, cycle = true),
        ))
        assertNotNull(KeyboardLayoutValidator.validate(
            v4File(base = 7, portrait = true, groupedX = Float.NaN),
        ))
        assertNotNull(KeyboardLayoutValidator.validate(
            v4File(base = 7, portrait = true, groupedRadius = 0.01f),
        ))
        assertNotNull(KeyboardLayoutValidator.validate(
            v4File(base = 7, portrait = true, groupedRadius = 0.50f),
        ))
    }

    @Test
    fun v4TruncationFailsCleanlyAndUnknownBlocksAreSkipped() {
        val truncated = tempFile()
        DataOutputStream(FileOutputStream(truncated)).use { out ->
            writeHeader(out, 4)
            writeType(out, 0)
            writeBase(out, 7)
            out.writeInt(PORTRAIT_OVERRIDE)
            out.writeInt(V4_SNAPSHOT_LENGTH)
            out.writeInt(28)
        }
        assertNotNull(KeyboardLayoutValidator.validate(truncated))

        val unknown = tempFile()
        DataOutputStream(FileOutputStream(unknown)).use { out ->
            writeHeader(out, 4)
            writeType(out, 0)
            writeBase(out, 7)
            out.writeInt(99)
            out.writeInt(5)
            out.write(byteArrayOf(1, 2, 3, 4, 5))
            writeEnd(out)
        }
        assertNull(KeyboardLayoutValidator.validate(unknown))
    }

    @Test
    fun encodedBytesUseTheSameValidationRulesAsFiles() {
        val valid = v4File(type = 3).readBytes()
        assertNull(KeyboardLayoutValidator.validateBytes(valid))
        assertNotNull(KeyboardLayoutValidator.validateBytes(byteArrayOf(1, 2, 3)))
        assertNotNull(KeyboardLayoutValidator.validateBytes(ByteArray(0)))
    }

    @Test
    fun futureLayoutVersionRemainsUnsupported() {
        assertNotNull(KeyboardLayoutValidator.validate(layoutFile(version = 5, type = 0)))
    }

    private fun v4File(
        type: Int = 0,
        base: Int? = null,
        legacy: Boolean = false,
        portrait: Boolean = false,
        landscape: Boolean = false,
        keyCount: Int = 28,
        cycle: Boolean = false,
        groupedX: Float = 0.25f,
        groupedRadius: Float = 0.16f,
    ): File {
        val file = tempFile()
        DataOutputStream(FileOutputStream(file)).use { out ->
            writeHeader(out, 4)
            writeType(out, type)
            if (base != null) writeBase(out, base)
            if (legacy) writeV4Snapshot(out, LEGACY_SHARED, keyCount, cycle, groupedX, groupedRadius)
            if (portrait) writeV4Snapshot(out, PORTRAIT_OVERRIDE, keyCount, cycle, groupedX, groupedRadius)
            if (landscape) writeV4Snapshot(out, LANDSCAPE_OVERRIDE, keyCount, cycle, groupedX, groupedRadius)
            writeEnd(out)
        }
        return file
    }

    private fun writeBase(out: DataOutputStream, base: Int) {
        out.writeInt(BASE_VARIANT)
        out.writeInt(1)
        out.writeByte(base)
    }

    private fun writeV4Snapshot(
        out: DataOutputStream,
        block: Int,
        keyCount: Int,
        cycle: Boolean,
        groupedX: Float,
        groupedRadius: Float,
    ) {
        out.writeInt(block)
        out.writeInt(V4_SNAPSHOT_LENGTH)
        out.writeInt(keyCount)
        repeat(keyCount) { index ->
            out.writeInt(VirtualKeyboard.persistedKeyHashForIndex(index))
            out.writeBoolean(true)
            val origin = when {
                cycle && index == 0 -> 1
                cycle && index == 1 -> 0
                else -> -1
            }
            out.writeInt(origin)
            out.writeInt(RectSnap.INT_NORTHWEST)
            out.writeFloat(0.0f)
            out.writeFloat(0.0f)
        }
        // For malformed key-count coverage keep the declared outer block length canonical. The
        // validator must reject the count before attempting to consume the absent records.
        if (keyCount != 28) return

        out.writeInt(12)
        repeat(12) { out.writeFloat(1.0f) }
        out.writeBoolean(true)
        out.writeBoolean(false)
        out.writeFloat(groupedX)
        out.writeFloat(0.75f)
        out.writeFloat(groupedRadius)
        out.writeFloat(0.25f)
        out.writeFloat(0.75f)
        out.writeFloat(groupedRadius)
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
        const val BASE_VARIANT = 4
        const val LEGACY_SHARED = 5
        const val PORTRAIT_OVERRIDE = 6
        const val LANDSCAPE_OVERRIDE = 7
        const val KEY_RECORD_SIZE_V2 = 21
        const val V4_SNAPSHOT_LENGTH = 670
    }
}
