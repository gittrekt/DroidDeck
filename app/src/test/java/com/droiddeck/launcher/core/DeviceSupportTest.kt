package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportTest {
    @Test fun lowLatencyWifiSafeCheck() {
        assertTrue(DeviceSupport.lowLatencyWifiSafe())
    }

    @Test fun supportedReflectsAdrenoOrMali() {
        assertEquals(DeviceSupport.adreno() || DeviceSupport.mali(), DeviceSupport.supported())
    }
}
