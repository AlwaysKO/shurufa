package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class ReportingTraceTest {
    @Test fun disabledTraceProducesNothingAndEnabledTraceContainsOnlyTypedFields() {
        assertNull(reportingTraceLine(false, ReportingStage.GATE_USB, false, 0, false))
        assertEquals("stage=GATE_USB online=false value=0 flag=false",
            reportingTraceLine(true, ReportingStage.GATE_USB, false, 0, false))
    }
}
