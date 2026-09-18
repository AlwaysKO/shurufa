package com.yuyan.imemodule.service.capture

import com.yuyan.imemodule.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ChatCaptureLifecycleConfigTest {
    @Test fun serviceCanObserveLeavingChatWithoutSystemLevelPackageFilterOrInitialBatchDelay() {
        RuntimeEnvironment.getApplication().resources.getXml(R.xml.passive_chat_accessibility_service).use { xml ->
            while (xml.eventType != XmlPullParser.START_TAG) xml.next()
            val ns = "http://schemas.android.com/apk/res/android"
            assertNull(xml.getAttributeValue(ns, "packageNames"))
            assertEquals(0, xml.getAttributeIntValue(ns, "notificationTimeout", -1))
        }
    }
}
