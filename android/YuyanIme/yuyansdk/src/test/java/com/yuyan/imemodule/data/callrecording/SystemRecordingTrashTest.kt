package com.yuyan.imemodule.data.callrecording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SystemRecordingTrashTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val root=File(context.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
    private val now=systemRecordingStartTime("20261112210000")!!
    private var permission=true
    private val source get()=SystemRecordingTrash(root,{permission},{now})
    private fun audio(name:String="微信-测试-202610241855.m4a"):File =
        File(root,".File_Recycle/1794488168973/storage/emulated/0/Sounds/CallRecord/hn_recycle_content/$name").apply {
            parentFile!!.mkdirs();writeText("0000ftypM4A sample");setLastModified(now-60000)
        }
    @Test fun `删除的电话录音也按三十天读取且不混入微信分类`() {
        val original=audio("通话-测试-202610241855.m4a")
        val doc=source.list("phone"){true}.single()
        assertTrue(doc.trashed);assertEquals("phone",doc.platform)
        assertTrue(source.list("wechat"){true}.isEmpty())
        assertArrayEquals(original.readBytes(),source.open(doc).use{it.readBytes()})
        assertTrue(original.exists())
    }
    @Test fun `只扫描已知回收录音路径且三十天基于录音时间`() {
        val good=audio();audio("微信-测试-202610011200.m4a");audio("照片.jpg");audio("微信-测试-202610241855.json")
        File(root,".File_Recycle/unrelated.m4a").writeText("not recording")
        assertEquals(listOf(good.name),source.list("wechat"){true}.map{it.name})
        assertTrue(source.list("phone"){true}.isEmpty())
        val doc=source.list("wechat"){true}.single()
        assertTrue(doc.trashed);assertTrue(source.unchanged(doc))
        assertArrayEquals(good.readBytes(),source.open(doc).use{it.readBytes()})
        assertTrue(good.exists())
    }
    @Test fun `权限撤回后禁止扫描打开和已打开流继续读取`() {
        audio();val doc=source.list("wechat"){true}.single();val input=source.open(doc)
        permission=false
        assertTrue(source.list("wechat"){true}.isEmpty());assertFalse(source.unchanged(doc))
        assertTrue(runCatching{source.open(doc)}.isFailure)
        input.use{assertTrue(runCatching{it.read()}.isFailure)}
    }
    @Test fun `拒绝符号链接和伪造越界来源`() {
        val good=audio();val doc=source.list("wechat"){true}.single()
        val other=File(root,"other.m4a").apply{writeText("private")}
        good.delete();Files.createSymbolicLink(good.toPath(),other.toPath())
        assertTrue(source.list("wechat"){true}.isEmpty())
        assertTrue(runCatching{source.open(doc)}.isFailure)
        assertTrue(runCatching{source.open(doc.copy(uri=android.net.Uri.fromFile(other).toString()))}.isFailure)
    }
    @Test fun `旧录音同意不能自动扩展回收站且撤权和换目标立即失效`() {
        val consent=CallRecordingConsent(context.getSharedPreferences(UUID.randomUUID().toString(),0))
        val device=UUID.randomUUID().toString();val target="https://example.test"
        assertTrue(consent.grantCombined(device,target))
        assertFalse(consent.trashAllowed(device,target))
        assertTrue(consent.grantCombined(device,target,includeTrash=true))
        assertTrue(consent.trashAllowed(device,target))
        assertFalse(consent.trashAllowed(device,"https://other.test"))
        consent.revokeUpload();assertFalse(consent.trashAllowed(device,target))
    }
    @Test fun `导入大小覆盖真机大文件但保留二百MiB边界`() {
        assertEquals(200L*1024*1024,CallRecordingOutbox.MAX_AUDIO_BYTES)
    }
}
