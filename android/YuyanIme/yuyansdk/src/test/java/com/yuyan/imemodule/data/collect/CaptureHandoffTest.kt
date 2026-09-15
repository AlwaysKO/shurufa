package com.yuyan.imemodule.data.collect
import com.yuyan.imemodule.data.capture.net.CaptureApi
import com.yuyan.imemodule.data.capture.net.PendingMessageUploadPayload
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
class CaptureHandoffTest {
 @Test fun `聊天队列仅在持久交接成功后才能确认`() {
  val payload=PendingMessageUploadPayload("d",JsonObject(emptyMap()),JsonObject(emptyMap()))
  var saved=""
  val api=CaptureApi("http://127.0.0.1:1","d", enqueue={ path, body -> saved=path+body; false })
  assertFalse(api.uploadMessages(listOf(payload))); assertTrue(saved.contains("/chat/messages/batch"))
  assertTrue(CaptureApi("http://127.0.0.1:1","d",enqueue={_,_->true}).uploadMessages(listOf(payload)))
 }
}
