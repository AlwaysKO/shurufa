package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.capture.CapturePersistResult
import org.junit.Assert.*
import org.junit.Test

class ScreenshotUpdatePolicyTest {
    @Test fun scrollingAloneNeverGrantsUnknownPageCaptureEligibility() {
        val policy = ScreenshotUpdatePolicy()
        val scope = ScreenshotScope(1, 1)
        org.junit.Assert.assertFalse(policy.canResumeScroll(scope))
        policy.allowScrollResume(scope) // 既有合法首采已排队，尚未完成身份确认。
        org.junit.Assert.assertTrue(policy.canResumeScroll(scope))
        org.junit.Assert.assertFalse(policy.canResumeScroll(ScreenshotScope(2, 1)))
        policy.rejectScrollResume(scope)
        org.junit.Assert.assertFalse(policy.canResumeScroll(scope))
        policy.allowScrollResume(scope)
        policy.clear()
        org.junit.Assert.assertFalse(policy.canResumeScroll(scope))
    }

    @Test fun fastFilePathIsEnabledOnlyAfterReliableSameFrameContentWasRecorded() {
        val policy=ScreenshotUpdatePolicy(); val scope=ScreenshotScope(10,3)
        policy.confirm(10,3); assertFalse(policy.hasSavedContent(scope))
        policy.recordSavedContent(scope,"a","title","body",CapturePersistResult.INSERTED,false)
        assertFalse(policy.hasSavedContent(scope))
        policy.recordSavedContent(scope,"a","title","body",CapturePersistResult.ALREADY_PERSISTED,true)
        assertTrue(policy.hasSavedContent(scope))
        assertFalse(policy.hasSavedContent(ScreenshotScope(11,3)))
        policy.clear();assertFalse(policy.hasSavedContent(scope))
    }

    @Test fun exactContentDedupRequiresConfirmedScopeAndSuccessfulRecord() {
        val policy=ScreenshotUpdatePolicy(); val scope=ScreenshotScope(10,3)
        policy.recordSavedContent(scope,"a","title","body", CapturePersistResult.INSERTED, true)
        assertFalse(policy.isSavedContent(scope,"a","title","body"))
        policy.confirm(10,3)
        assertFalse(policy.isSavedContent(scope,"a","title","body"))
        policy.recordSavedContent(scope,"a","title","body", CapturePersistResult.INSERTED, true)
        assertTrue(policy.isSavedContent(scope,"a","title","body"))
        assertFalse(policy.isSavedContent(scope,"b","title","body"))
        assertFalse(policy.isSavedContent(scope,"a","other-title","body"))
        assertFalse(policy.isSavedContent(scope,"a","title","one-pixel-changed"))
        assertFalse(policy.isSavedContent(ScreenshotScope(11,3),"a","title","body"))
        assertFalse(policy.isSavedContent(scope,"a",null,"body"))
        assertFalse(policy.isSavedContent(scope,"a","title",null))
        policy.clear(); policy.confirm(10,3)
        assertFalse(policy.isSavedContent(scope,"a","title","body"))
    }
    @Test fun failedSaveAndConfirmationReplayCannotPoisonTheSameFrameCache() {
        val policy=ScreenshotUpdatePolicy(); val scope=ScreenshotScope(10,3); policy.confirm(10,3)
        policy.recordSavedContent(scope,"a","title","body",CapturePersistResult.FAILED,true)
        assertFalse(policy.isSavedContent(scope,"a","title","body"))
        policy.recordSavedContent(scope,"a","second-title","first-body",CapturePersistResult.INSERTED,false)
        assertFalse(policy.isSavedContent(scope,"a","second-title","first-body"))
        policy.recordSavedContent(scope,"a","title","body",CapturePersistResult.ALREADY_PERSISTED,true)
        assertTrue(policy.isSavedContent(scope,"a","title","body"))
    }
    @Test fun newConfirmationDoesNotInheritOldSuccessfulContent() {
        val policy=ScreenshotUpdatePolicy(); val old=ScreenshotScope(10,3)
        policy.confirm(10,3); policy.recordSavedContent(old,"a","title","body", CapturePersistResult.INSERTED, true)
        policy.confirm(10,4)
        policy.recordSavedContent(old,"a","title","body",CapturePersistResult.INSERTED,true)
        assertFalse(policy.isSavedContent(ScreenshotScope(10,4),"a","title","body"))
    }

    @Test fun lateContentAndScrollAreAcceptedOnlyAfterChatConfirmation() {
        val policy = ScreenshotUpdatePolicy()
        assertFalse(policy.accepts(10, 3, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        policy.confirm(10, 3)
        assertTrue(policy.accepts(10, 3, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(policy.accepts(10, 3, AccessibilityEvent.TYPE_VIEW_SCROLLED))
        assertFalse(policy.accepts(10, 3, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
    }
    @Test fun changeDuringInitialConfirmationIsRecheckedOnlyAfterConfirmation() {
        val gate = ScreenshotRequestGate(); val scope = ScreenshotScope(10, 3)
        assertFalse(gate.markChanged(scope))
        gate.offer(scope)
        assertFalse(gate.markChanged(ScreenshotScope(11, 3)))
        assertTrue(gate.markChanged(scope))
        assertEquals(scope, gate.complete(confirmed = scope))
        gate.offer(scope); gate.markChanged(scope)
        assertNull(gate.complete())
        gate.offer(scope); gate.markChanged(scope); gate.clearPending()
        assertNull(gate.complete(confirmed = scope))
    }
    @Test fun overlappingChecksKeepOnlyTheLatestFollowup() {
        val gate = ScreenshotRequestGate()
        assertTrue(gate.offer(ScreenshotScope(10, 3)))
        assertFalse(gate.offer(ScreenshotScope(10, 3)))
        assertFalse(gate.offer(ScreenshotScope(11, 4)))
        assertEquals(ScreenshotScope(11, 4), gate.complete())
        assertNull(gate.complete())
        assertTrue(gate.offer(ScreenshotScope(11, 4)))
    }
    @Test fun navigationClearsQueuedButNotRunningRequest() {
        val gate = ScreenshotRequestGate()
        gate.offer(ScreenshotScope(10, 3)); gate.offer(ScreenshotScope(10, 3)); gate.clearPending()
        assertNull(gate.complete())
    }
    @Test fun newWindowOrGenerationCannotInheritChatEligibility() {
        val policy = ScreenshotUpdatePolicy(); policy.confirm(10, 3)
        assertFalse(policy.accepts(11, 3, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(policy.accepts(10, 4, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        policy.clear()
        assertFalse(policy.accepts(10, 3, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    }
    @Test fun readableTruncatedTitleAllowsLateContentButNeverCrossesNavigation() {
        val policy=ScreenshotUpdatePolicy()
        policy.observeTitle(10,3,"pending")
        assertFalse(policy.accepts(10,3,AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        policy.observeTitle(10,3,"truncated")
        assertTrue(policy.accepts(10,3,AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(policy.accepts(11,3,AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        policy.recordSavedContent(ScreenshotScope(10,3),"partial","title","body",CapturePersistResult.INSERTED,true)
        assertTrue(policy.isSavedContent(ScreenshotScope(10,3),"partial","title","body"))
        policy.clear()
        assertFalse(policy.accepts(10,3,AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    }
}
