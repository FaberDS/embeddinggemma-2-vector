package dev.pocketask

import org.junit.Assert.*
import org.junit.Test

class AndroidInferenceProfileTest {
    private fun memory(gib: Long) = gib * 1024 * 1024 * 1024

    @Test fun s21UsesCpuEvenWithMoreRam() {
        for (model in listOf("SM-G990B", "SM-G991B", "SM-G996U", "SM-G998B")) {
            assertTrue(androidInferenceProfile(memory(8), false, model).cpu)
        }
    }

    @Test fun sixGigabyteDevicesUseSmallerCpuContext() {
        assertEquals(AndroidInferenceProfile(true, 4096), androidInferenceProfile(memory(6), false, "Other"))
    }

    @Test fun androidLowRamFlagOverridesReportedMemory() {
        assertEquals(AndroidInferenceProfile(true, 4096), androidInferenceProfile(memory(8), true, "Other"))
    }

    @Test fun newerDevicesKeepGpuAndFullContext() {
        assertEquals(AndroidInferenceProfile(false, 8192), androidInferenceProfile(memory(8), false, "SM-S931B"))
    }
}
