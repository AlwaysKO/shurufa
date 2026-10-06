package com.yuyan.imemodule.data.callrecording

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import java.io.InputStream

/** 系统音频库优先，已有目录授权兜底；一个来源不可读不阻断其他来源。 */
internal class SystemRecordingSources(context: Context) {
    private val media = SystemRecordingMediaStore(context)
    private val documents = SystemRecordingDocuments(context)
    fun readable(platform: String) = media.readable() || documents.readable(platform)
    fun configured(platform: String) = readable(platform) || documents.tree(platform) != null

    data class Scan(val files: List<SystemRecordingDocument>, val complete: Boolean)

    fun scan(platform: String, allowed: () -> Boolean): Scan {
        CallRecordingOutbox.checkWorker()
        val files = mutableListOf<SystemRecordingDocument>()
        var complete = readable(platform)
        fun read(block: () -> List<SystemRecordingDocument>) {
            check(allowed()) { "transfer_paused" }
            try { files.addAll(block()) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { complete = false }
            check(allowed()) { "transfer_paused" }
        }
        if(media.readable()) read { media.list(platform, allowed) }
        if(documents.readable(platform) || documents.tree(platform) != null) read {
            documents.list(platform, allowed).also { complete = complete && documents.scanComplete }
        }
        val now = System.currentTimeMillis()
        return Scan(files.distinctBy { it.uri }.filter { systemRecordingWithinSevenDays(it, now) }, complete)
    }

    fun open(doc: SystemRecordingDocument): InputStream =
        if(isMedia(doc.uri)) media.open(doc) else documents.open(doc)
    fun unchanged(doc: SystemRecordingDocument) =
        if(isMedia(doc.uri)) media.unchanged(doc) else documents.unchanged(doc)
    fun exists(source: SystemRecordingSource) =
        if(isMedia(source.uri)) media.exists(source) else documents.exists(source)
    private fun isMedia(uri: String) = Uri.parse(uri).authority == "media"
}
