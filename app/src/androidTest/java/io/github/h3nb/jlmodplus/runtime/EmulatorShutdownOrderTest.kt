// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.runtime

import android.app.ActivityManager.RunningAppProcessInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorShutdownOrderTest {
    @Test
    fun auxiliaryDiesBeforeAuthorityForMainAndRuntimeCallers() {
        val main = process(PACKAGE, 1)
        val runtime = process("$PACKAGE:midlet", 2)
        val engine = process("$PACKAGE:memory_engine", 3)
        val permutations = listOf(
            listOf(main, runtime, engine), listOf(main, engine, runtime),
            listOf(runtime, main, engine), listOf(runtime, engine, main),
            listOf(engine, main, runtime), listOf(engine, runtime, main),
        )
        for (processes in permutations) {
            assertEquals(listOf(3, 2), EmulatorShutdownService.otherProcessShutdownOrder(
                processes, PACKAGE, ownPid = 1, ownUid = UID,
            ))
            assertEquals(listOf(3, 1), EmulatorShutdownService.otherProcessShutdownOrder(
                processes, PACKAGE, ownPid = 2, ownUid = UID,
            ))
        }
    }

    @Test
    fun callerAndUnrelatedProcessesAreExcludedFromPeerShutdown() {
        val processes = listOf(
            process("$PACKAGE:midlet", 2), process("$PACKAGE:memory_engine", 3),
            process("$PACKAGE:other", 4), process(PACKAGE, 1),
            process("$PACKAGE:foreign_uid", 5, UID + 1),
            process("$PACKAGE.other", 6), process("other.application", 7),
        )
        assertEquals(listOf(4, 2, 1), EmulatorShutdownService.otherProcessShutdownOrder(
            processes, PACKAGE, ownPid = 3, ownUid = UID,
        ))
        assertEquals(emptyList<Int>(), EmulatorShutdownService.otherProcessShutdownOrder(
            emptyList(), PACKAGE, ownPid = 1, ownUid = UID,
        ))
    }

    private fun process(name: String, pid: Int, uid: Int = UID) =
        RunningAppProcessInfo(name, pid, emptyArray()).apply { this.uid = uid }

    companion object {
        private const val PACKAGE = "test.emulator"
        private const val UID = 12345
    }
}
