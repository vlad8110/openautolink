package com.openautolink.app.transport.usb

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UsbManifestAuthorizationTest {
    @Test fun appleUsbMuxHasNarrowAlwaysUseFilter() {
        val file = listOf(File("src/main/res/xml/usb_device_filter.xml"), File("app/src/main/res/xml/usb_device_filter.xml")).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("usb-device")
        val filters = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        assertTrue("Apple USBMUX must be OS matched to offer Always use", filters.any {
            it.getAttribute("vendor-id") == "1452" && it.getAttribute("class") == "255" &&
                it.getAttribute("subclass") == "254" && it.getAttribute("protocol") == "2"
        })
        val carlinkit = filters.filter { it.getAttribute("vendor-id") == "4884" }
        assertEquals("Carlinkit adapter: exactly its two PIDs", setOf("5408", "5409"),
            carlinkit.map { it.getAttribute("product-id") }.toSet())
        assertEquals("Only AOA, Apple USBMUX and the Carlinkit adapter are allowed", 5, filters.size)
        assertTrue(filters.all { it.hasAttribute("vendor-id") })
    }
}
