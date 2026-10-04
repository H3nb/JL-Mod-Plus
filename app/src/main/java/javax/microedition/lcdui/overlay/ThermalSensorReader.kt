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

import java.io.File
import java.io.IOException
import java.util.Locale

/** Read-only fallback for explicitly named CPU/GPU thermal zones accessible to this process. */
internal class ThermalSensorReader(root: File = File("/sys/class/thermal")) {
    private val cpuSources = ArrayList<File>()
    private val gpuSources = ArrayList<File>()

    init {
        val zones = try {
            root.listFiles()
        } catch (_: SecurityException) {
            null
        }
        if (zones != null) {
            for (zone in zones) {
                if (!ZONE_NAME.matches(zone.name)) continue
                val type = readLine(File(zone, "type"))?.lowercase(Locale.ROOT) ?: continue
                val temperatures = when {
                    CPU_TYPE.matches(type) -> cpuSources
                    GPU_TYPE.matches(type) -> gpuSources
                    else -> continue
                }
                temperatures.add(File(zone, "temp"))
            }
        }
    }

    data class Snapshot(val cpuTempC: Double, val gpuTempC: Double)

    fun sample(cpu: Boolean, gpu: Boolean): Snapshot = Snapshot(
        if (cpu) hottest(cpuSources) else Double.NaN,
        if (gpu) hottest(gpuSources) else Double.NaN,
    )

    private fun hottest(sources: List<File>): Double {
        var maximum = Double.NaN
        for (source in sources) {
            // Linux thermal-zone ABI specifies integer millidegrees Celsius. Never guess units.
            val milliCelsius = readLine(source)?.toLongOrNull() ?: continue
            if (milliCelsius !in -100_000L..200_000L) continue
            val celsius = milliCelsius / 1000.0
            if (maximum.isNaN() || celsius > maximum) maximum = celsius
        }
        return maximum
    }

    private fun readLine(file: File): String? = try {
        file.bufferedReader().use { it.readLine()?.trim() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        val ZONE_NAME = Regex("thermal_zone[0-9]+")
        // These labels identify the component; numeric suffixes identify its sensors/clusters.
        // Anonymous tsens/SoC zones and board thermistors such as cpu_therm are ambiguous.
        val CPU_TYPE = Regex("(?:cpu|cpuss)(?:-[0-9]+)*")
        val GPU_TYPE = Regex("gpu(?:-[0-9]+)*")
    }
}
