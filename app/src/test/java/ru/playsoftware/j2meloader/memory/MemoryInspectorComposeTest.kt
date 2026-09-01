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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryInspectorComposeTest {
    @Test
    fun inspectorCellsStayRelativeToTheCandidateAnchor() {
        val bytes = ByteArray(16)
        // 0x1004 = 42, 0x1008 = -7 in little-endian Int32.
        bytes[4] = 42
        bytes[8] = 0xf9.toByte()
        bytes[9] = 0xff.toByte()
        bytes[10] = 0xff.toByte()
        bytes[11] = 0xff.toByte()
        val snapshot = MemoryInspectorSnapshot(
            candidateId = 9,
            type = MemoryEngineContract.TYPE_INT,
            label = "HP",
            startAddress = 0x1000,
            anchorAddress = 0x1004,
            bytes = bytes,
        )

        val cells = buildInspectorCells(snapshot, MemoryEngineContract.TYPE_INT)

        assertEquals(listOf(-4, 0, 4, 8), cells.map { it.offset })
        assertEquals(0x1004, cells.first { it.offset == 0 }.address)
        assertEquals("42", cells.first { it.offset == 0 }.value)
        assertEquals("-7", cells.first { it.offset == 4 }.value)
        assertEquals(42L, cells.first { it.offset == 0 }.bits)
        assertEquals(0xfffffff9L, cells.first { it.offset == 4 }.bits)
    }

    @Test
    fun inspectorUsesExactPrimitiveWidthsAndRejectsAuto() {
        assertEquals(1, inspectorTypeWidth(MemoryEngineContract.TYPE_BYTE))
        assertEquals(2, inspectorTypeWidth(MemoryEngineContract.TYPE_SHORT))
        assertEquals(2, inspectorTypeWidth(MemoryEngineContract.TYPE_CHAR))
        assertEquals(4, inspectorTypeWidth(MemoryEngineContract.TYPE_INT))
        assertEquals(4, inspectorTypeWidth(MemoryEngineContract.TYPE_FLOAT))
        assertEquals(8, inspectorTypeWidth(MemoryEngineContract.TYPE_LONG))
        assertEquals(8, inspectorTypeWidth(MemoryEngineContract.TYPE_DOUBLE))
        assertEquals(0, inspectorTypeWidth(MemoryEngineContract.TYPE_AUTO))

        val invalid = MemoryInspectorSnapshot(
            candidateId = 1,
            type = MemoryEngineContract.TYPE_INT,
            label = "",
            startAddress = 0x2000,
            anchorAddress = 0x1fff,
            bytes = ByteArray(8),
        )
        assertTrue(buildInspectorCells(invalid, MemoryEngineContract.TYPE_INT).isEmpty())
    }

    @Test
    fun inspectorFloatViewUsesLittleEndianSnapshotBytes() {
        val bits = 1.5f.toBits()
        val bytes = byteArrayOf(
            bits.toByte(),
            (bits ushr 8).toByte(),
            (bits ushr 16).toByte(),
            (bits ushr 24).toByte(),
        )
        val snapshot = MemoryInspectorSnapshot(
            candidateId = 2,
            type = MemoryEngineContract.TYPE_FLOAT,
            label = "Speed",
            startAddress = 0x3000,
            anchorAddress = 0x3000,
            bytes = bytes,
        )

        val cell = buildInspectorCells(snapshot, MemoryEngineContract.TYPE_FLOAT).single()

        assertEquals(0, cell.offset)
        assertEquals("1.5", cell.value)
        assertEquals(bits.toLong() and 0xffff_ffffL, cell.bits)
    }
}
