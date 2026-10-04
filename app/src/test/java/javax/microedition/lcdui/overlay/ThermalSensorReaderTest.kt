/* Licensed under the Apache License, Version 2.0. */
package javax.microedition.lcdui.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThermalSensorReaderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun componentLabelsAndMilliDegreesDetermineHottestReadingWithoutZoneNumberAssumptions() {
        zone(71, "cpu-2-0-0", "43200")
        zone(4, "CPU-1-0-0", "45500")
        zone(12, "cpuss-0-1", "43900")
        zone(1, "gpu-0", "35900")
        zone(98, "gpu-5", "37100")
        zone(2, "battery", "90000")
        zone(3, "cpu_therm", "95000")
        zone(5, "soc", "96000")
        zone(6, "tsens_tz_sensor0", "97000")

        val result = ThermalSensorReader(folder.root).sample(cpu = true, gpu = true)
        assertEquals(45.5, result.cpuTempC, 0.0001)
        assertEquals(37.1, result.gpuTempC, 0.0001)
    }

    @Test
    fun selectedSensorsRefreshAndFailedReadsDoNotReuseOldValues() {
        val cpu = zone(5, "cpu-0-0-0", "30000")
        val gpu = zone(7, "gpu-1", "31000")
        val reader = ThermalSensorReader(folder.root)
        val cpuOnly = reader.sample(cpu = true, gpu = false)
        assertEquals(30.0, cpuOnly.cpuTempC, 0.0001)
        assertTrue(cpuOnly.gpuTempC.isNaN())

        File(cpu, "temp").writeText("47000")
        val changed = reader.sample(cpu = true, gpu = true)
        assertEquals(47.0, changed.cpuTempC, 0.0001)
        assertEquals(31.0, changed.gpuTempC, 0.0001)
        assertTrue(File(cpu, "temp").delete())
        File(gpu, "temp").writeText("unavailable")
        val failed = reader.sample(cpu = true, gpu = true)
        assertTrue(failed.cpuTempC.isNaN())
        assertTrue(failed.gpuTempC.isNaN())
    }

    @Test
    fun malformedAndSentinelValuesAreUnavailableWhileRealZeroIsValid() {
        zone(0, "cpu", "-2147483648")
        zone(1, "cpu-1", "201000")
        zone(2, "cpu-2", "NaN")
        zone(3, "cpu-3", "42.0")
        zone(4, "gpu", "0")
        var result = ThermalSensorReader(folder.root).sample(cpu = true, gpu = true)
        assertTrue(result.cpuTempC.isNaN())
        assertEquals(0.0, result.gpuTempC, 0.0)
        zone(5, "cpu-4", "-12000")
        result = ThermalSensorReader(folder.root).sample(cpu = true, gpu = false)
        assertEquals(-12.0, result.cpuTempC, 0.0)
    }

    @Test
    fun missingRootAndUnidentifiedComponentsStayUnavailable() {
        var result = ThermalSensorReader(File(folder.root, "missing"))
            .sample(cpu = true, gpu = true)
        assertTrue(result.cpuTempC.isNaN())
        assertTrue(result.gpuTempC.isNaN())
        zone(0, "cpu-load", "45000")
        zone(1, "gpu-frequency", "55000")
        folder.newFolder("other").apply {
            File(this, "type").writeText("cpu")
            File(this, "temp").writeText("46000")
        }
        result = ThermalSensorReader(folder.root).sample(cpu = true, gpu = true)
        assertTrue(result.cpuTempC.isNaN())
        assertTrue(result.gpuTempC.isNaN())
    }

    private fun zone(index: Int, type: String, temperature: String): File =
        folder.newFolder("thermal_zone$index").apply {
            File(this, "type").writeText("$type\n")
            File(this, "temp").writeText("$temperature\n")
        }
}
