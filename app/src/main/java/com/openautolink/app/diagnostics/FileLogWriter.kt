package com.openautolink.app.diagnostics

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes [LocalLogEntry] lines to a timestamped log file on external storage.
 *
 * Files are written to the first available removable storage (USB stick),
 * falling back to the primary external storage (internal shared storage).
 * All files go under an `openautolink/logs/` directory.
 *
 * Each start creates a new file: `oal_2026-04-28_14-30-00.log`; hitting
 * [MAX_FILE_SIZE_BYTES] rolls over to a new file. Only the newest [KEEP_FILES]
 * `oal_*` and `logcat_*` files are kept.
 * Lines use the format:
 *   `HH:mm:ss.SSS I/tag: message`
 *
 * Thread-safe: all writes go through a synchronized block.
 */
class FileLogWriter(private val context: Context) {

    companion object {
        private const val TAG = "FileLogWriter"
        private const val DIR_NAME = "openautolink/logs"
        private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB per file
        private const val MIN_FREE_SPACE_BYTES = 10L * 1024 * 1024 // stop if < 10 MB free
        private const val FLUSH_EVERY_LINES = 64
        internal const val KEEP_FILES = 10

        /** Delete all but the newest [keep] timestamped `oal_<date>*.log` / `logcat_<date>*.log` in [dir]. */
        internal fun pruneOldLogs(dir: File, keep: Int = KEEP_FILES) {
            for (prefix in listOf("oal_", "logcat_")) {
                // Digit after the prefix: only our timestamped files, never e.g. oal_host_media.log.
                dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".log") &&
                    f.name.getOrNull(prefix.length)?.isDigit() == true }
                    .orEmpty().sortedByDescending { it.lastModified() }.drop(keep)
                    .forEach { runCatching { it.delete() } }
            }
        }
    }

    @Volatile
    var isActive: Boolean = false
        private set

    /** Absolute path of the current log file, or null if not started. */
    @Volatile
    var currentFilePath: String? = null
        private set

    private var writer: BufferedWriter? = null
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /** Bytes written since file open. Tracked locally so the hot path doesn't
     *  do a `File.length()` syscall per log line. */
    private var bytesWritten: Long = 0L

    /** Lines since last flush — flushed in batches to avoid per-line fsync churn. */
    private var linesSinceFlush: Int = 0

    /**
     * Start writing logs to a new file. Returns the file path or null on failure.
     *
     * When [requireRemovable] is true, only removable storage (USB stick) is used.
     * If no removable volume is mounted, this returns null and does NOT fall back
     * to internal shared storage — used by the auto-start-on-USB pref so we don't
     * silently fill internal storage when no USB drive is present.
     */
    fun start(requireRemovable: Boolean = false): String? {
        synchronized(lock) {
            if (isActive) return currentFilePath

            val dir = resolveLogDir(requireRemovable) ?: run {
                if (requireRemovable) {
                    OalLog.i(TAG, "USB-only file logging requested but no removable storage found")
                } else {
                    OalLog.w(TAG, "No writable storage found for file logging")
                }
                return null
            }

            return try {
                val file = openNewFile(dir)
                isActive = true
                OalLog.i(TAG, "File logging started: ${file.absolutePath}")
                file.absolutePath
            } catch (e: Exception) {
                OalLog.e(TAG, "Failed to start file logging: ${e.message}")
                null
            }
        }
    }

    /** Must hold [lock]. Opens a fresh oal_*.log in [dir], then prunes old logs. */
    private fun openNewFile(dir: File): File {
        val stamp = fileNameFormat.format(Date())
        var file = File(dir, "oal_$stamp.log")
        var n = 1
        while (file.exists()) file = File(dir, "oal_${stamp}_${n++}.log")
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 8192)
        bytesWritten = 0L
        linesSinceFlush = 0
        currentFilePath = file.absolutePath
        pruneOldLogs(dir)
        return file
    }

    /**
     * Stop writing and flush/close the file.
     */
    fun stop() {
        synchronized(lock) {
            if (!isActive) return
            isActive = false
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) { }
            writer = null
            OalLog.i(TAG, "File logging stopped: $currentFilePath")
            currentFilePath = null
        }
    }

    /**
     * Write a single log entry. Called from [DiagnosticLog.addLocal].
     * No-op if not active or file limits exceeded.
     */
    fun write(entry: LocalLogEntry) {
        if (!isActive) return
        synchronized(lock) {
            val w = writer ?: return
            try {
                // Roll over at the per-file cap. Tracked locally to avoid
                // a stat() syscall on the hot path.
                if (bytesWritten >= MAX_FILE_SIZE_BYTES) {
                    w.close()
                    val dir = currentFilePath?.let { File(it).parentFile }
                    if (dir == null || !ensureDir(dir)) {
                        // Out of space: stop rather than fill the disk.
                        isActive = false
                        writer = null
                        return
                    }
                    openNewFile(dir)
                    write(entry)
                    return
                }

                val levelChar = when (entry.level) {
                    DiagnosticLevel.DEBUG -> 'D'
                    DiagnosticLevel.INFO -> 'I'
                    DiagnosticLevel.WARN -> 'W'
                    DiagnosticLevel.ERROR -> 'E'
                }

                val ts = timeFormat.format(Date(entry.timestamp))
                // Build the line once so we can both write it and account for
                // its bytes accurately without a second formatter pass.
                val line = "$ts $levelChar/${entry.tag}: ${entry.message}\n"
                w.write(line)
                bytesWritten += line.length

                // Flush in batches to avoid per-line fsync. WARN/ERROR flush
                // immediately so we don't lose them on crash.
                linesSinceFlush++
                if (entry.level == DiagnosticLevel.WARN ||
                    entry.level == DiagnosticLevel.ERROR ||
                    linesSinceFlush >= FLUSH_EVERY_LINES) {
                    w.flush()
                    linesSinceFlush = 0
                }
            } catch (e: Exception) {
                // Don't recurse through OalLog — just stop silently
                isActive = false
                try { w.close() } catch (_: Exception) { }
                writer = null
            }
        }
    }

    /**
     * Flush any buffered entries to disk, also write existing ring buffer entries.
     */
    fun writeExistingLogs(entries: List<LocalLogEntry>) {
        if (!isActive) return
        synchronized(lock) {
            for (entry in entries) {
                write(entry)
            }
        }
    }

    /**
     * Find the best directory for log files.
     * Prefers removable storage (USB stick) over internal shared storage.
     * When [removableOnly] is true, returns null if no removable volume is mounted.
     */
    private fun resolveLogDir(removableOnly: Boolean = false): File? {
        // Try removable storage volumes first (USB sticks)
        val externalDirs = context.getExternalFilesDirs(null)
        for (dir in externalDirs) {
            if (dir == null) continue
            // Skip primary (non-removable) storage
            if (Environment.isExternalStorageRemovable(dir)) {
                val logDir = File(dir, DIR_NAME)
                if (ensureDir(logDir)) return logDir
            }
        }

        if (removableOnly) return null

        // Fallback: primary external storage (internal shared storage)
        val primary = context.getExternalFilesDir(null)
        if (primary != null) {
            val logDir = File(primary, DIR_NAME)
            if (ensureDir(logDir)) return logDir
        }

        return null
    }

    /**
     * Returns true if any removable storage volume is currently mounted and writable.
     * Used by the USB-auto-start path to skip starting a writer when no stick is
     * present.
     */
    fun hasRemovableStorage(): Boolean {
        return try {
            val externalDirs = context.getExternalFilesDirs(null)
            externalDirs.any { dir ->
                dir != null && Environment.isExternalStorageRemovable(dir) && run {
                    val logDir = File(dir, DIR_NAME)
                    ensureDir(logDir)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureDir(dir: File): Boolean {
        if (!dir.exists() && !dir.mkdirs()) return false
        // Check free space
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes > MIN_FREE_SPACE_BYTES
        } catch (_: Exception) {
            false
        }
    }
}
