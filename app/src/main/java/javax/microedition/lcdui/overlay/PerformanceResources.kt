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

package javax.microedition.lcdui.overlay

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import io.github.h3nb.jlmodplus.config.PerformanceOverlayOptions

/**
 * Samples only selected diagnostics for the process hosting the MIDlet. Call [sample] on the
 * overlay's worker, never from paint: obtaining PSS can itself interrupt execution briefly.
 * CPU uses host monotonic time and 100% represents one core, without core-count normalization.
 */
class PerformanceResources(context: Context, private val metricsMask: Int) : AutoCloseable {
    private val appContext = context.applicationContext
    private var closed = false
    private var previousCpuNanos = Long.MIN_VALUE
    private var previousCpuMillis = -1L
    private var cpuPercent = Double.NaN
    private var lastMemoryNanos = Long.MIN_VALUE
    private var ramMiB = Double.NaN
    private var javaHeapMiB = Double.NaN
    private var nativeHeapMiB = Double.NaN
    private var lastThermalNanos = Long.MIN_VALUE
    private var thermalStatus = -1
    /** Unavailable values are NaN; thermal status is -1 when unavailable. */
    data class Snapshot(
        val cpuPercent: Double,
        val ramMiB: Double,
        val javaHeapMiB: Double,
        val nativeHeapMiB: Double,
        val thermalStatus: Int,
    )

    @Synchronized
    fun sample(nowNanos: Long): Snapshot {
        if (!closed) {
            if (enabled(PerformanceOverlayOptions.CPU)) sampleCpu(nowNanos)
            if (metricsMask and MEMORY_MASK != 0 && due(nowNanos, lastMemoryNanos, SLOW_INTERVAL)) {
                lastMemoryNanos = nowNanos
                sampleMemory()
            }
            if (enabled(PerformanceOverlayOptions.THERMAL) &&
                due(nowNanos, lastThermalNanos, SLOW_INTERVAL)
            ) {
                lastThermalNanos = nowNanos
                sampleThermalStatus()
            }
        }
        return Snapshot(cpuPercent, ramMiB, javaHeapMiB, nativeHeapMiB, thermalStatus)
    }

    /** Discard the baseline across a pause so the next percentage excludes paused time. */
    @Synchronized
    fun resetCpuSample() {
        previousCpuNanos = Long.MIN_VALUE
        previousCpuMillis = -1L
        cpuPercent = Double.NaN
    }

    @Synchronized
    override fun close() {
        closed = true
    }

    private fun enabled(bit: Int) = metricsMask and bit != 0

    private fun sampleCpu(nowNanos: Long) {
        if (!due(nowNanos, previousCpuNanos, CPU_INTERVAL)) return
        try {
            val currentCpuMillis = Process.getElapsedCpuTime()
            cpuPercent = if (previousCpuNanos != Long.MIN_VALUE &&
                nowNanos > previousCpuNanos && previousCpuMillis >= 0L &&
                currentCpuMillis >= previousCpuMillis
            ) {
                (currentCpuMillis - previousCpuMillis) * 100_000_000.0 /
                    (nowNanos - previousCpuNanos)
            } else {
                Double.NaN
            }
            previousCpuMillis = currentCpuMillis
            previousCpuNanos = nowNanos
        } catch (_: RuntimeException) {
            resetCpuSample()
        }
    }

    private fun sampleMemory() {
        if (enabled(PerformanceOverlayOptions.RAM)) {
            ramMiB = try {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                info.totalPss.takeIf { it > 0 }?.div(1024.0) ?: Double.NaN
            } catch (_: RuntimeException) {
                Double.NaN
            }
        }
        if (enabled(PerformanceOverlayOptions.JAVA_HEAP)) {
            javaHeapMiB = try {
                val runtime = Runtime.getRuntime()
                bytesToMiB(runtime.totalMemory() - runtime.freeMemory())
            } catch (_: RuntimeException) {
                Double.NaN
            }
        }
        if (enabled(PerformanceOverlayOptions.NATIVE_HEAP)) {
            nativeHeapMiB = try {
                bytesToMiB(Debug.getNativeHeapAllocatedSize())
            } catch (_: RuntimeException) {
                Double.NaN
            }
        }
    }

    private fun sampleThermalStatus() {
        thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
            try {
                val manager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                manager?.currentThermalStatus?.takeIf {
                    it in PowerManager.THERMAL_STATUS_NONE..PowerManager.THERMAL_STATUS_SHUTDOWN
                } ?: -1
            } catch (_: RuntimeException) {
                -1
            }
        } else {
            -1
        }
    }

    private fun bytesToMiB(bytes: Long): Double =
        if (bytes >= 0L) bytes / 1_048_576.0 else Double.NaN

    private fun due(now: Long, previous: Long, interval: Long): Boolean =
        previous == Long.MIN_VALUE || now < previous || now - previous >= interval

    private companion object {
        const val CPU_INTERVAL = 1_000_000_000L
        const val SLOW_INTERVAL = 5_000_000_000L
        const val MEMORY_MASK = PerformanceOverlayOptions.RAM or
            PerformanceOverlayOptions.JAVA_HEAP or PerformanceOverlayOptions.NATIVE_HEAP
    }
}
