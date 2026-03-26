package com.github.opentmp1

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Persistent file-based logger. Writes timestamped log entries to daily files
 * in the app's internal storage. Auto-prunes files older than 7 days.
 *
 * Usage:
 *   AppLogger.init(context)
 *   AppLogger.i("Camera", "Connected to device")
 *   AppLogger.e("Camera", "Failed", exception)
 */
object AppLogger {

    enum class Level(val tag: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    private const val DIR_NAME = "logs"
    private const val RETENTION_DAYS = 7
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024 // 2 MB per day file

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var logDir: File? = null
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger-Writer").apply { isDaemon = true }
    }

    // In-memory ring buffer of the last 200 entries for quick display
    private val recentEntries = ConcurrentLinkedQueue<String>()
    private const val RING_SIZE = 200

    /**
     * Initialize the logger. Call once from Application or Activity onCreate.
     * Prunes old log files and writes a session-start marker.
     */
    fun init(context: Context) {
        logDir = File(context.filesDir, DIR_NAME).also { it.mkdirs() }
        pruneOldFiles()
        i("AppLogger", "=== Session started === (app version: ${getVersionName(context)})")
    }

    fun d(component: String, message: String) = log(Level.DEBUG, component, message)
    fun i(component: String, message: String) = log(Level.INFO, component, message)
    fun w(component: String, message: String) = log(Level.WARN, component, message)
    fun e(component: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "$message\n$sw"
        } else message
        log(Level.ERROR, component, msg)
    }

    /**
     * Log a battery/power snapshot (called periodically while streaming).
     */
    fun power(batteryPct: Int, voltMv: Int, currentUa: Int, tempTenths: Int, drainRate: Float, charging: Boolean) {
        val line = "bat=$batteryPct%% V=${voltMv}mV I=${currentUa}µA T=${tempTenths/10f}°C drain=%.1f%%/min chg=$charging".format(drainRate)
        log(Level.INFO, "Power", line)
    }

    /**
     * Returns the most recent log entries (up to [RING_SIZE]) for display.
     */
    fun getRecentEntries(): List<String> = recentEntries.toList()

    /**
     * Read all log files from the last [days] days and return their content.
     */
    fun readLogs(days: Int = RETENTION_DAYS): String {
        val dir = logDir ?: return "(Logger not initialized)"
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val files = dir.listFiles { f -> f.name.endsWith(".log") && f.lastModified() >= cutoff }
            ?.sortedBy { it.name } ?: return "(No log files)"
        if (files.isEmpty()) return "(No log files)"

        val sb = StringBuilder()
        for (f in files) {
            try {
                sb.append(f.readText())
            } catch (ex: Exception) {
                sb.append("[Error reading ${f.name}: ${ex.message}]\n")
            }
        }
        return sb.toString()
    }

    /**
     * Get a summary: file count, total size, oldest/newest date.
     */
    fun getSummary(): String {
        val dir = logDir ?: return "Not initialized"
        val files = dir.listFiles { f -> f.name.endsWith(".log") } ?: return "No logs"
        if (files.isEmpty()) return "No logs"
        val sorted = files.sortedBy { it.name }
        val totalSize = files.sumOf { it.length() }
        val sizeStr = when {
            totalSize > 1024 * 1024 -> "%.1f MB".format(totalSize / (1024f * 1024f))
            totalSize > 1024 -> "%.1f KB".format(totalSize / 1024f)
            else -> "$totalSize B"
        }
        return "${files.size} file(s), $sizeStr | ${sorted.first().name.removeSuffix(".log")} → ${sorted.last().name.removeSuffix(".log")}"
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun log(level: Level, component: String, message: String) {
        val now = Date()
        val timestamp = timeFormat.format(now)
        val entry = "$timestamp ${level.tag}/$component: $message"

        // Also forward to Android logcat
        when (level) {
            Level.DEBUG -> Log.d(component, message)
            Level.INFO -> Log.i(component, message)
            Level.WARN -> Log.w(component, message)
            Level.ERROR -> Log.e(component, message)
        }

        // Add to ring buffer
        recentEntries.add(entry)
        while (recentEntries.size > RING_SIZE) recentEntries.poll()

        // Async file write
        val dir = logDir ?: return
        val dayStr = dateFormat.format(now)
        writeExecutor.execute {
            try {
                val file = File(dir, "$dayStr.log")
                // Rotate if file is too big
                if (file.exists() && file.length() > MAX_FILE_SIZE) {
                    val rotated = File(dir, "$dayStr-overflow.log")
                    if (!rotated.exists()) file.renameTo(rotated)
                }
                FileWriter(file, true).use { it.write(entry + "\n") }
            } catch (ex: Exception) {
                Log.e("AppLogger", "Failed to write log", ex)
            }
        }
    }

    private fun pruneOldFiles() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        dir.listFiles { f -> f.name.endsWith(".log") }?.forEach { f ->
            if (f.lastModified() < cutoff) {
                Log.d("AppLogger", "Pruning old log: ${f.name}")
                f.delete()
            }
        }
    }

    private fun getVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }
}
