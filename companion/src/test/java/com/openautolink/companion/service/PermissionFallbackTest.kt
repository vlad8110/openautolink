package com.openautolink.companion.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 32, 34], application = Application::class,
    shadows = [PermissionFallbackTest.Adapter::class])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class PermissionFallbackTest {
    companion object {
        var deniedBluetooth = false
        var bluetoothName: String? = "  Test phone  "
        var bluetoothReads = 0
    }
    @Implements(BluetoothAdapter::class)
    class Adapter : org.robolectric.shadows.ShadowBluetoothAdapter() {
        @Implementation override fun getName(): String? {
            bluetoothReads++
            if (deniedBluetooth) throw SecurityException("revoked Bluetooth permission")
            return bluetoothName
        }
    }
    private val app = RuntimeEnvironment.getApplication() as Application
    private fun readBluetoothName(): String? {
        val advertiser = TcpAdvertiser(app, object : TcpAdvertiser.StateListener {
            override fun onConnecting() = Unit
            override fun onProxyConnected() = Unit
            override fun onProxyDisconnected() = Unit
        })
        // Constructor only: never starts a listener, network monitor, proxy or coroutine job.
        val method = TcpAdvertiser::class.java.getDeclaredMethod("localBluetoothName").apply { isAccessible = true }
        return method.invoke(advertiser) as String?
    }
    @Test fun BluetoothPermissionDenialKeepsNameOptionalAndLaterReadStillWorks() {
        bluetoothReads = 0; deniedBluetooth = true; bluetoothName = "  Test phone  "
        assertNull(readBluetoothName()); assertEquals(1, bluetoothReads)
        deniedBluetooth = false
        assertEquals("Test phone", readBluetoothName()); assertEquals(2, bluetoothReads)
        bluetoothName = "  "; assertNull(readBluetoothName())
        bluetoothName = null; assertNull(readBluetoothName())
    }
}
