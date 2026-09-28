package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.media.ScreenshotContentBlocks
import org.junit.Assert.*
import org.junit.Test

class RecentScreenshotContentsTest {
    private fun shot(vararg hashes:String,height:Int=500,identity:String="peer",title:String="title") =
        ScreenshotContentEvidence(identity,title,ScreenshotContentBlocks(200,height,hashes.toList()))
    @Test fun checksRecentFramesForSameConversationWithoutAWait() {
        val cache=RecentScreenshotContents()
        val a=shot("a","b");val b=shot("c","d")
        assertFalse(cache.contains(a));cache.record(a);cache.record(b)
        assertTrue(cache.contains(shot("a","b",height=450)))
        assertFalse(cache.contains(shot("a","b",identity="other")))
        assertFalse(cache.contains(shot("a","b",title="other")))
    }
    @Test fun countIncreaseAndAmbiguousRepeatedOrSingleBlocksAreNotFiltered() {
        val cache=RecentScreenshotContents()
        cache.record(shot("a","b"))
        assertFalse(cache.contains(shot("a","b","b")))
        cache.record(shot("a","a"));assertFalse(cache.contains(shot("a","a")))
        cache.record(shot("a"));assertFalse(cache.contains(shot("a")))
        assertFalse(cache.contains(shot("a","new")))
    }
    @Test fun shrinkingViewMayUseOneSavedContiguousSequenceButNotAUnionOfFrames() {
        val cache=RecentScreenshotContents()
        cache.record(shot("a","b","c",height=600))
        assertTrue(cache.contains(shot("b","c",height=350)))
        assertFalse(cache.contains(shot("b","c",height=600)))
        assertFalse(cache.contains(shot("a","c",height=350)))
        cache.record(shot("c","d",height=600))
        assertFalse(cache.contains(shot("a","d",height=350)))
    }
    @Test fun newlyRepeatedTailIsKeptAfterSmallerViewEvenIfSequenceWasSeenBefore() {
        val cache=RecentScreenshotContents()
        cache.record(shot("anchor","a","a",height=500))
        cache.record(shot("anchor","a",height=500))
        assertFalse(cache.contains(shot("anchor","a","a",height=500)))
    }
    @Test fun scopeResetAndExpiryRemoveHistory() {
        var time=0L;val cache=RecentScreenshotContents{time};val a=shot("a","b")
        cache.record(a);time=300_001;assertFalse(cache.contains(a))
        cache.record(a);cache.clear();assertFalse(cache.contains(a))
    }
    private fun rows(values: List<String>): ByteArray = values.flatMap { value ->
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toList()
    }.toByteArray()
    private fun rowShot(values: List<String>, vararg anchors: String, clipped: Boolean = true) =
        ScreenshotContentEvidence("peer", "title", ScreenshotContentBlocks(200, values.size,
            anchors.toList(), rows(values), clipped))

    @Test fun clippedFrameMayMatchUniqueExactRowsIncludingItsEdgeFragments() {
        val cache = RecentScreenshotContents()
        cache.record(rowShot(listOf("partial-top", "blank", "A", "blank", "B", "blank", "partial-bottom"), "a", "b"))
        assertTrue(cache.contains(rowShot(listOf("blank", "A", "blank", "B", "blank", "partial-bottom"), "a", "b")))
        assertTrue(cache.contains(rowShot(listOf("A", "blank", "B", "blank"), "a", "b")))
        assertFalse(cache.contains(rowShot(listOf("new-top", "blank", "A", "blank", "B", "blank", "partial-bottom"), "a", "b")))
        assertFalse(cache.contains(rowShot(listOf("blank", "A", "blank", "B", "blank", "new-bottom-character"), "a", "b")))
        assertFalse(cache.contains(rowShot(listOf("blank", "A", "blank", "B", "blank", "partial-bottom", "extra"), "a", "b")))
    }

    @Test fun clippedRowsCannotReuseFullSequenceShortcutOrCombineSeparateFrames() {
        val cache = RecentScreenshotContents()
        cache.record(rowShot(listOf("top", "A", "blank", "B", "bottom-one"), "a", "b"))
        cache.record(rowShot(listOf("other-top", "A", "blank", "B", "bottom-two"), "a", "b"))
        assertFalse(cache.contains(rowShot(listOf("top", "A", "blank", "B", "bottom-two"), "a", "b")))
        assertFalse(cache.contains(rowShot(listOf("top", "A", "new-blank", "B", "bottom-one"), "a", "b")))
        assertFalse(cache.contains(rowShot(listOf("top", "A", "blank", "B", "B", "bottom-one"), "a", "b", "b")))
        assertFalse(cache.contains(rowShot(listOf("top", "A", "blank", "B", "bottom-one"), "a")))
    }

    @Test fun clippedEvidenceRequiresValidRowLengthsAndUniqueRowAlignment() {
        val cache = RecentScreenshotContents()
        cache.record(rowShot(listOf("A", "B", "blank", "A", "B"), "a", "b"))
        assertFalse(cache.contains(rowShot(listOf("A", "B"), "a", "b")))
        val absentRows = shot("a", "b").copy(blocks = ScreenshotContentBlocks(200, 500, listOf("a", "b"), hasClippedEdges = true))
        cache.record(absentRows)
        assertFalse(cache.contains(absentRows))
        val malformed = rowShot(listOf("A", "B"), "a", "b").let { it.copy(blocks = it.blocks.copy(height = 3)) }
        cache.record(malformed)
        assertFalse(cache.contains(malformed))
    }

}
