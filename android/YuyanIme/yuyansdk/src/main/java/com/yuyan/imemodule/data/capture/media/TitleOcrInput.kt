package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable

/** 单次截图请求持有的原始顶部像素，不持有整图、不落盘、不跨会话缓存。 */
class TitleOcrInput(private val captureTopPx: Int = 0, private val captureHeightPx: Int? = null) : Closeable {
    private var header: Bitmap? = null
    private var closed = false

    @Synchronized fun captureFrom(source: Bitmap) {
        if (closed) return
        header?.recycle()
        header = null
        val cropped = Bitmap.createBitmap(source, 0, 0, source.width, titleHeaderHeight(source.width, source.height))
        header = if (cropped === source) source.copy(Bitmap.Config.ARGB_8888, false) else cropped
    }

    /** 返回值的所有权转给调用者；调用者须在 OCR 真正结束后回收。 */
    @Synchronized fun takeOrDecode(path: String): Bitmap? {
        if (closed) return null
        val captured = header
        header = null
        return captured ?: decodeTitleHeader(path)
    }

    @Synchronized override fun close() {
        closed = true
        header?.recycle()
        header = null
    }
}

internal fun titleHeaderHeight(width: Int, height: Int): Int =
    (width * 0.18).toInt().coerceIn(96, 220).coerceAtMost(height)

/** 无原始输入时保留已验证的旧文件回退；正常两次截图均直接移交原始顶部。 */
internal fun decodeTitleHeader(path: String): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(path) ?: return null
    var header: Bitmap? = null
    try {
        header = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, titleHeaderHeight(bitmap.width, bitmap.height))
        return header
    } finally {
        if (header !== bitmap) bitmap.recycle()
    }
}

/** ML Kit 的 Task 无法随协程撤销；取消后仍等回调收尾，但不消费过期结果。 */
internal suspend fun <T> awaitTitleOcrCompletion(block: suspend () -> T): T {
    val result = withContext(NonCancellable) { block() }
    currentCoroutineContext().ensureActive()
    return result
}

internal data class WechatTitleBand(val top:Int,val height:Int)
internal fun wechatTitleBand(statusBarBottom:Int,screenshotTop:Int,density:Float):WechatTitleBand = WechatTitleBand(0,216)
