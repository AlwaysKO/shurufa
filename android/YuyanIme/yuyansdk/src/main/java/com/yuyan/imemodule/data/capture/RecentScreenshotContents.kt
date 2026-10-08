package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.media.ScreenshotContentBlocks
import com.yuyan.imemodule.data.capture.media.SCREENSHOT_ROW_HASH_BYTES

/** 身份来自同一帧可信标题；不能使用通知占位名或后帧确认的标题。 */
data class ScreenshotContentEvidence(val identity: String, val titleHash: String, val blocks: ScreenshotContentBlocks)

internal class RecentScreenshotContents(private val now: () -> Long = System::currentTimeMillis) {
    private data class Key(val identity: String, val title: String, val width: Int)
    private data class Saved(val blocks: ScreenshotContentBlocks, val at: Long)
    private val saved = linkedMapOf<Key, MutableList<Saved>>()
    private fun key(e: ScreenshotContentEvidence) = Key(e.identity, e.titleHash, e.blocks.width)
    private fun validRows(blocks: ScreenshotContentBlocks): Boolean = blocks.height in 1..8192 &&
        blocks.rowHashes.size == blocks.height * SCREENSHOT_ROW_HASH_BYTES
    private fun eligibilityReason(e: ScreenshotContentEvidence): ScreenshotContentReason? = when {
        e.identity.isBlank() || e.titleHash.isBlank() -> ScreenshotContentReason.IDENTITY_UNVERIFIED
        e.blocks.width <= 0 || e.blocks.height !in 1..8192 -> ScreenshotContentReason.INVALID_BOUNDS
        e.blocks.hashes.size < 2 -> ScreenshotContentReason.INSUFFICIENT_ANCHORS
        e.blocks.hashes.size > 128 -> ScreenshotContentReason.TOO_MANY_BLOCKS
        e.blocks.hashes.distinct().size != e.blocks.hashes.size -> ScreenshotContentReason.REPEATED_ANCHORS
        e.blocks.hasClippedEdges && !validRows(e.blocks) -> ScreenshotContentReason.ROWS_UNVERIFIED
        else -> null
    }

    @Synchronized fun contains(evidence: ScreenshotContentEvidence): Boolean =
        matchReason(evidence) == ScreenshotContentReason.SAME_CONTENT

    @Synchronized fun matchReason(evidence: ScreenshotContentEvidence): ScreenshotContentReason {
        eligibilityReason(evidence)?.let { return it }
        val current = evidence.blocks
        var hasRecent = false
        val matched = saved[key(evidence)].orEmpty().any { old ->
            if (now() - old.at !in 0..300_000) false else {
                hasRecent = true
                if (old.blocks.hasClippedEdges || current.hasClippedEdges) {
                    // 锚点相同不等于边缘碎片相同；整个当前视口必须在单帧里唯一连续出现。
                    current.height <= old.blocks.height && validRows(old.blocks) && validRows(current) &&
                        old.blocks.hashes.windowed(current.hashes.size).count { it == current.hashes } == 1 &&
                        uniqueRowCoverage(old.blocks, current)
                } else old.blocks.hashes == current.hashes ||
                    (current.height < old.blocks.height && current.hashes.size < old.blocks.hashes.size &&
                        old.blocks.hashes.windowed(current.hashes.size).count { it == current.hashes } == 1)
            }
        }
        return when {
            matched -> ScreenshotContentReason.SAME_CONTENT
            hasRecent -> ScreenshotContentReason.CONTENT_CHANGED
            else -> ScreenshotContentReason.NO_SAVED_CONTENT
        }
    }

    @Synchronized fun record(evidence: ScreenshotContentEvidence) {
        if (eligibilityReason(evidence) != null) return
        val key = key(evidence)
        val frames = saved.remove(key) ?: mutableListOf()
        frames.removeAll { now() - it.at !in 0..300_000 || sameFrame(it.blocks, evidence.blocks) }
        frames += Saved(evidence.blocks.copy(hashes = evidence.blocks.hashes.toList(), rowHashes = evidence.blocks.rowHashes.copyOf()), now())
        while (frames.size > 8) frames.removeAt(0)
        saved[key] = frames
        while (saved.size > 16) saved.remove(saved.keys.first())
    }
    @Synchronized fun clearIdentity(identity: String) { saved.keys.removeAll { it.identity == identity } }
    @Synchronized fun clear() = saved.clear()

    private fun sameFrame(a: ScreenshotContentBlocks, b: ScreenshotContentBlocks): Boolean =
        a.width == b.width && a.height == b.height && a.hashes == b.hashes &&
            a.hasClippedEdges == b.hasClippedEdges && a.rowHashes.contentEquals(b.rowHashes)

    /** 以一行的 SHA256 为一个符号做线性匹配，避免大段空白导致逐起点扫描退化。 */
    private fun uniqueRowCoverage(saved: ScreenshotContentBlocks, current: ScreenshotContentBlocks): Boolean {
        fun sameRow(a: ByteArray, aRow: Int, b: ByteArray, bRow: Int): Boolean {
            val aStart = aRow * SCREENSHOT_ROW_HASH_BYTES
            val bStart = bRow * SCREENSHOT_ROW_HASH_BYTES
            for (i in 0 until SCREENSHOT_ROW_HASH_BYTES) if (a[aStart + i] != b[bStart + i]) return false
            return true
        }
        val pattern = current.rowHashes
        val prefix = IntArray(current.height)
        var matched = 0
        for (row in 1 until current.height) {
            while (matched > 0 && !sameRow(pattern, row, pattern, matched)) matched = prefix[matched - 1]
            if (sameRow(pattern, row, pattern, matched)) matched++
            prefix[row] = matched
        }
        matched = 0
        var occurrences = 0
        for (row in 0 until saved.height) {
            while (matched > 0 && !sameRow(saved.rowHashes, row, pattern, matched)) matched = prefix[matched - 1]
            if (sameRow(saved.rowHashes, row, pattern, matched)) matched++
            if (matched == current.height) {
                if (++occurrences > 1) return false
                matched = prefix[matched - 1]
            }
        }
        return occurrences == 1
    }
}
