package com.yuyan.imemodule.data.relationship

import org.junit.Assert.assertFalse
import org.junit.Test

class RelationshipReplyPolicyTest {
    @Test
    fun screenshotOnlyCaptureDoesNotRequestRealtimeReplies() {
        assertFalse(RelationshipReplyPolicy.ENABLED)
    }
}
