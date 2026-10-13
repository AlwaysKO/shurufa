package com.yuyan.imemodule.data.redpacket

import org.junit.Assert.*
import org.junit.Test

class SilentPacketRoutingTest {
    @Test fun authenticatesMainAndCloneWithoutHardcodedProfileIds() {
        assertEquals(0, silentPacketSourceUser(10251, 10251))
        assertEquals(128, silentPacketSourceUser(12810251, 12810251))
        assertEquals(10, silentPacketSourceUser(1010251, 1010251))
    }
    @Test fun rejectsMismatchedOrNonApplicationIdentities() {
        for ((source, creator) in listOf(10251 to 12810251, 10251 to 10252, -1 to -1,
            2000 to 2000, 12802000 to 12802000)) assertNull(silentPacketSourceUser(source, creator))
    }
}
