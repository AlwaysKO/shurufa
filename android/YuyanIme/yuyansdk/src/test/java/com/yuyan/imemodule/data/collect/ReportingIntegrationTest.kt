package com.yuyan.imemodule.data.collect
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class ReportingIntegrationTest {
 private fun source(path:String)=File("src/main/java/com/yuyan/imemodule/$path").readText()
 @Test fun `剪贴板本地写库前也检查总开关`() {
  val code=source("service/ClipboardHelper.kt")
  assertTrue(code.indexOf("CollectionConsent.enabled") in 0 until code.indexOf("clipboardDao().insert"))
 }
 @Test fun `上报入口使用持久队列且有后台重试服务`() {
  assertTrue(source("data/completion/CompletionSync.kt").contains("enqueueReport"))
  assertTrue(source("data/phrase/PhraseSync.kt").contains("enqueueReport"))
  assertTrue(source("data/sticker/StickerSync.kt").contains("enqueueReport"))
  assertTrue(source("data/capture/net/CaptureUploader.kt").contains("enqueueRawReport"))
  assertTrue(File("src/main/AndroidManifest.xml").readText().contains("ReportSyncJobService"))
 }
 @Test fun `构建默认地址统一线上本地地址只用于双传`() {
  val gradle=File("build.gradle").readText()
  assertFalse(gradle.contains("myapi.dog8ball.com"))
  assertFalse(gradle.contains("'\"http://127.0.0.1:3000\"'"))
 }
}
