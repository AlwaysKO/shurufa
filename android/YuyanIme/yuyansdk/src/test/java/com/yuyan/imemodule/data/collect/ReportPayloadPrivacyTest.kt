package com.yuyan.imemodule.data.collect
import org.junit.Assert.*
import org.junit.Test
class ReportPayloadPrivacyTest {
 @Test fun `聊天逐条过滤认证数字不牺牲同批普通消息`() {
  val filtered=filterChatReportPayload("""{"device_id":"d","conversation":{},"messages":[{"text":"123456"},{"text":"普通消息"}]}""")
  assertFalse(filtered.contains("123456")); assertTrue(filtered.contains("普通消息"))
 }
}
