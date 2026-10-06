package com.yuyan.imemodule.data.collect

import java.io.Closeable
import java.net.URI
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ImageUploadNetwork { OFFLINE, MOBILE, WIFI, USB }

/** Shared, content-free policy. All timestamps are monotonic, never wall-clock time. */
internal class ImageUploadSchedule(private val clock: () -> Long) {
    private var lastActivity: Long? = null
    private val touches = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var preparing = false
    private var uploading = false
    private var lastUpload: Long? = null
    private data class Charge(val time: Long, val bytes: Long)
    private val charges = ArrayDeque<Charge>()

    @Synchronized fun noteKeyActivity() {
        lastActivity = clock()
    }

    @Synchronized fun noteTouch(action: Int, source: Any) {
        when (action) {
            0 -> touches.add(source) // ACTION_DOWN
            1, 3 -> touches.remove(source) // Final ACTION_UP / ACTION_CANCEL only
        }
        lastActivity = clock()
    }

    @Synchronized fun isInputIdle(): Boolean =
        touches.isEmpty() && (lastActivity?.let { clock() - it >= IDLE_MS } ?: true)

    @Synchronized fun beginPreparation(requireIdle: Boolean = true): Closeable? {
        if ((requireIdle && !isInputIdle()) || preparing) return null
        return acquire(upload = false)
    }

    @Synchronized fun maxImageBytes(network: ImageUploadNetwork, screenOff: Boolean = false, allowMobile: Boolean = false): Long {
        if (network != ImageUploadNetwork.WIFI && !(allowMobile && network == ImageUploadNetwork.MOBILE)) return 0
        val now = clock()
        if (lastUpload?.let { now - it < if (screenOff) 1000L else IMAGE_INTERVAL_MS } == true) return 0
        while (charges.isNotEmpty() && now - charges.first.time >= WINDOW_MS) charges.removeFirst()
        val budget = if (screenOff) 32L * 1024L * 1024L else WINDOW_BYTES
        return (budget - charges.sumOf { it.bytes }).coerceAtLeast(0)
    }

    /** Bytes are the actual UTF-8 JSON request size, including Base64 and metadata. */
    @Synchronized fun tryStartImage(network: ImageUploadNetwork, bytes: Long, screenOff: Boolean = false, allowMobile: Boolean = false): Closeable? {
        if (preparing || uploading || bytes <= 0 || bytes > maxImageBytes(network, screenOff, allowMobile)) return null
        lastUpload = clock()
        charges.addLast(Charge(clock(), bytes))
        // Failed requests also spent bandwidth: closing a permit never refunds quota.
        return acquire(upload = true)
    }

    // 亮屏慢传不能长期阻止新截图；准备和截图上传分别最多一个，释放互不影响。
    private fun acquire(upload: Boolean): Closeable {
        if (upload) uploading = true else preparing = true
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) synchronized(this) {
                if (upload) uploading = false else preparing = false
            }
        }
    }

    companion object {
        private const val IDLE_MS = 3000L
        private const val WINDOW_MS = 60000L
        private const val WINDOW_BYTES = 8L * 1024L * 1024L
        private const val IMAGE_INTERVAL_MS = 3000L

        fun isUsbTarget(target: String): Boolean = runCatching {
            val uri = URI(target)
            (uri.scheme == "http" || uri.scheme == "https") &&
                uri.host in setOf("127.0.0.1", "::1", "[::1]")
        }.getOrDefault(false)
    }
}
