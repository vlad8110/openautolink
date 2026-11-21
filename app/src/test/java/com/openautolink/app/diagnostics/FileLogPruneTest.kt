package com.openautolink.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileLogPruneTest {
    @Test fun keepsNewestPerPrefixAndLeavesOtherFiles() {
        val dir = Files.createTempDirectory("oal-logs").toFile()
        try {
            for (i in 0 until 13) {
                java.io.File(dir, "oal_$i.log").apply { writeText("x"); setLastModified(1_000_000L + i * 1000) }
                java.io.File(dir, "logcat_$i.log").apply { writeText("x"); setLastModified(1_000_000L + i * 1000) }
            }
            java.io.File(dir, "ev_telemetry.csv").writeText("keep")
            java.io.File(dir, "oal_host_media.log").apply { writeText("keep"); setLastModified(1L) }
            FileLogWriter.pruneOldLogs(dir, keep = 10)
            val names = dir.list()!!.toSet()
            assertEquals(10, names.count { it.startsWith("oal_") && it != "oal_host_media.log" })
            assertEquals(10, names.count { it.startsWith("logcat_") })
            assertTrue("oldest removed", "oal_0.log" !in names && "logcat_2.log" !in names)
            assertTrue("newest kept", "oal_12.log" in names && "logcat_3.log" in names)
            assertTrue("other files untouched", "ev_telemetry.csv" in names && "oal_host_media.log" in names)
        } finally { dir.deleteRecursively() }
    }
}
