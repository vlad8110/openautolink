package com.openautolink.app.transport.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarlinkitAdapterRoutingTest {
    // The adapter is owned by CarlinkitAdapter and claims the screen only when an iPhone
    // connects; if the phone flow ever saw it, it would grab the screen or get an AOA switch.
    @Test fun carlinkitAdapterIsNeverAPhoneCandidate() {
        assertTrue(UsbDevicePolicy.isKnownNonPhoneDevice(UsbConstants.CARLINKIT_VID, 0x1520))
        assertTrue(UsbDevicePolicy.isKnownNonPhoneDevice(UsbConstants.CARLINKIT_VID, 0x1521))
    }

    @Test fun androidPhonesStayCandidates() {
        assertFalse(UsbDevicePolicy.isKnownNonPhoneDevice(0x18d1, 0x4ee7))
        assertFalse(UsbDevicePolicy.isKnownNonPhoneDevice(0x04e8, 0x6860))
    }
}
