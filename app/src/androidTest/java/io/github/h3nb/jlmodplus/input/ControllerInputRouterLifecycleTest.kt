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

package io.github.h3nb.jlmodplus.input

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.Displayable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerInputRouterLifecycleTest {
    @Test
    fun neutralTakeoverReleasesOldOutputOnceAndNewOwnerContinuesNormally() {
        val host = RecordingHost()
        val router = ControllerInputRouter(
            InstrumentationRegistry.getInstrumentation().targetContext,
            host,
            null,
        )
        try {
            val gate = privateField<ControllerLifecycleGate>(router, "lifecycleGate")
            val applyDecision = ControllerInputRouter::class.java.getDeclaredMethod(
                "applyLifecycleDecision",
                ControllerLifecycleDecision::class.java,
            ).apply { isAccessible = true }
            val updateDirectional = ControllerInputRouter::class.java.getDeclaredMethod(
                "updateDirectional",
                String::class.java,
                Set::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
            ).apply { isAccessible = true }

            fun offer(deviceId: Int, neutral: Boolean) {
                applyDecision.invoke(router, gate.offerMotion(deviceId, neutral))
            }

            offer(deviceId = 1, neutral = true)
            updateDirectional.invoke(
                router,
                "test-a",
                setOf(ControllerInputRouter.CONTROL_DPAD_RIGHT),
                "left-movement",
                true,
            )
            assertEquals(listOf(HostCommand.NavigateRight to true), host.commands)

            val activeA = gate.snapshot()
            offer(deviceId = 2, neutral = false)
            assertEquals(activeA, gate.snapshot())
            assertEquals(listOf(HostCommand.NavigateRight to true), host.commands)

            offer(deviceId = 2, neutral = true)
            assertEquals(2, gate.snapshot().activeDeviceId)
            assertEquals(
                listOf(
                    HostCommand.NavigateRight to true,
                    HostCommand.NavigateRight to false,
                ),
                host.commands,
            )

            updateDirectional.invoke(
                router,
                "test-b",
                setOf(ControllerInputRouter.CONTROL_DPAD_RIGHT),
                "left-movement",
                true,
            )
            assertEquals(
                listOf(
                    HostCommand.NavigateRight to true,
                    HostCommand.NavigateRight to false,
                    HostCommand.NavigateRight to true,
                ),
                host.commands,
            )
        } finally {
            router.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(instance) as T
        }

    private class RecordingHost : ControllerHostSink {
        val commands = mutableListOf<Pair<HostCommand, Boolean>>()
        private val target = ControllerHostTarget("router-lifecycle-test", 1L)

        override fun currentCanvas(): Canvas? = null

        override fun currentDisplayable(): Displayable? = null

        override fun currentControllerTarget(): ControllerHostTarget = target

        override fun onHostCommand(command: HostCommand, pressed: Boolean): Boolean {
            commands += command to pressed
            return true
        }

        override fun onControllerInputAccepted() = Unit

        override fun onControllerNotice(message: String) = Unit
    }
}
