package `is`.xyz.mpv

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

internal object Utils {
    fun findRealPath(fd: Int): String? {
        var input: InputStream? = null
        return try {
            val path = File("/proc/self/fd/$fd").canonicalPath
            if (!path.startsWith("/proc") && File(path).canRead()) {
                input = FileInputStream(path)
                input.read()
                path
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            input?.close()
        }
    }
}