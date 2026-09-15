package com.yuyan.imemodule.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLongPressLifecycleTest {
    @Test
    fun releasingOrCancellingActiveVoiceLongPressStopsOrCancelsRecognition() {
        assertEquals(VoiceLongPressFinish.STOP, voiceLongPressFinish(true, false))
        assertEquals(VoiceLongPressFinish.CANCEL, voiceLongPressFinish(true, true))
        assertEquals(VoiceLongPressFinish.NONE, voiceLongPressFinish(false, false))
    }
}
