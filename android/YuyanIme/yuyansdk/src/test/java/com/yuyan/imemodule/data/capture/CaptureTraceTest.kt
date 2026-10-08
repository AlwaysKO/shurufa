package com.yuyan.imemodule.data.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureTraceTest {
    @Test fun disabledTraceHasNoOutput() {
        assertNull(captureTraceLine(false, CaptureStage.EVENT, 12, 5, 1, true))
    }
    @Test fun enabledTraceContainsOnlyFixedStageAndNumericMetadata() {
        assertEquals("layer=SERVICE stage=EVENT window=12 generation=5 value=1 flag=true",
            captureTraceLine(true, CaptureStage.EVENT, 12, 5, 1, true))
    }
    @Test fun layerDistinguishesIndependentGenerationCounters() {
        assertTrue(captureTraceLine(true, CaptureStage.ASSET_READY, generation = 7,
            layer = CaptureLayer.MEDIA)!!.startsWith("layer=MEDIA "))
        assertTrue(captureTraceLine(true, CaptureStage.PERSIST_RESULT,
            layer = CaptureLayer.COORDINATOR)!!.startsWith("layer=COORDINATOR "))
    }
    @Test fun contentReasonIsAnOptionalFixedEnumAndDisabledTraceStillProducesNothing() {
        for (reason in ScreenshotContentReason.entries) {
            val line = captureTraceLine(true, CaptureStage.CONTENT_DECISION, reason = reason)!!
            assertTrue(line.matches(Regex("layer=[A-Z_]+ stage=CONTENT_DECISION window=-?\\d+ generation=-?\\d+ value=-?\\d+ flag=(true|false) reason=[a-z_]+")))
            assertNull(captureTraceLine(false, CaptureStage.CONTENT_DECISION, reason = reason))
        }
        assertTrue(captureTraceLine(true, CaptureStage.CONTENT_DECISION,
            reason = ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED)!!.endsWith("reason=body_boundary_unverified"))
    }

    @Test fun everyStageUsesTheSameRestrictedFormat() {
        for (stage in CaptureStage.entries) {
            val line = captureTraceLine(true, stage)
            assertNotNull(line)
            assertTrue(line!!.matches(Regex("layer=[A-Z_]+ stage=[A-Z_]+ window=-?\\d+ generation=-?\\d+ value=-?\\d+ flag=(true|false)")))
        }
    }
}
