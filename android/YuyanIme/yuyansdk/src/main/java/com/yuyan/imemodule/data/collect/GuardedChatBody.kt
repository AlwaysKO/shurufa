package com.yuyan.imemodule.data.collect

import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException

/** Only used on OkHttp's IO caller. Recheck Wi-Fi/consent/input before each <=8 KiB write. */
internal class GuardedChatBody(
    private val delegate: RequestBody,
    private val allowed: () -> Boolean,
    private val pause: () -> Unit = { Thread.sleep(64) }, // at most 128 KiB/s, including JSON/Base64
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val guarded = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                var remaining=byteCount
                while (remaining>0) {
                    try { pause() } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Chat upload interrupted",e) }
                    if (!allowed()) throw IOException("Chat upload paused")
                    val size=minOf(8192L,remaining)
                    super.write(source,size)
                    sink.flush()
                    remaining-=size
                }
            }
        }.buffer()
        delegate.writeTo(guarded)
        guarded.flush()
    }
}
