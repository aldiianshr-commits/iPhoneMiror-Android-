package com.aldiianshr.iphonemiror.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryTest {
    @Test fun cleansMobdevNames() {
        assertEquals("aa:bb:cc:dd:ee:ff", AppleNetworkBrowser.cleanName("aa:bb:cc:dd:ee:ff@fe80::1"))
        assertEquals("Aldi's iPhone", AppleNetworkBrowser.cleanName("Aldi's\\032iPhone"))
    }

    @Test fun continuityLabels() {
        assertEquals("Nearby Info", BluetoothAppleScanner.continuityLabel(0x10))
        assertEquals("Tipe 0x42", BluetoothAppleScanner.continuityLabel(0x42))
        assertEquals("—", BluetoothAppleScanner.continuityLabel(null))
    }
}
