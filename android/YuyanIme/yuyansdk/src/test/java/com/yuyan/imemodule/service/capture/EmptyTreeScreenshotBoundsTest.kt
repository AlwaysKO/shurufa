package com.yuyan.imemodule.service.capture

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyTreeScreenshotBoundsTest {
    @Test fun `without keyboard screenshot keeps complete window bottom`() {
        assertEquals(
            IntRect(0, 0, 1200, 2664),
            emptyTreeScreenshotBounds(IntRect(0, 0, 1200, 2664), inputMethodTop = null),
        )
    }

    @Test fun `visible keyboard is excluded at its actual top`() {
        assertEquals(
            IntRect(0, 0, 1200, 1760),
            emptyTreeScreenshotBounds(IntRect(0, 0, 1200, 2664), inputMethodTop = 1760),
        )
    }
}
