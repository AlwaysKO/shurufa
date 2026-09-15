package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 录制的开源C API候选在真实App过滤层重放，不冒充手机JNI或触摸测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicPhraseCorpusTest {
    @Test fun `公开语料与全部反馈重放不丢已有正确首选且不放行已知乱串`() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val storeField=OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible=true }
        fun closeStore() {
            (storeField.get(OfflineT9Candidates) as? com.yuyan.imemodule.data.collect.LocalInputStore)?.close()
            storeField.set(OfflineT9Candidates,null)
        }
        closeStore();context.deleteDatabase("local_input.db");OfflineT9Candidates.init(context)
        val indexField=OfflineT9Candidates::class.java.getDeclaredField("publicPhrases").apply { isAccessible=true }
        val index=indexField.get(OfflineT9Candidates)
        assertNotNull("实际打包的公开索引必须加载成功",index)
        val lines=GZIPInputStream(javaClass.getResourceAsStream("/t9-public-native-replay.tsv.gz")!!).bufferedReader().use { it.readLines() }
        var cursor=0;var count=0
        val garbage=setOf("总额而","总额儿","冲屌啊","灭除妈啊","本饿","饿喇叭","饿牙齿","饿雅典","饿我不")
        try {
            while(cursor<lines.size) {
                val header=lines[cursor++].split('\t');assertEquals("CASE",header[0]);assertEquals(5,header.size)
                val code=header[1];val target=header[2];val size=header[4].toInt()
                val texts=mutableListOf<String>();val readings=mutableListOf<String>()
                repeat(size) {
                    val row=lines[cursor++].split('\t');assertEquals("CAND",row[0]);assertEquals(3,row.size)
                    texts.add(row[1]);readings.add(row[2])
                }
                indexField.set(OfflineT9Candidates,null)
                val before=OfflineT9Candidates.select(code,texts,readings).firstPage
                indexField.set(OfflineT9Candidates,index)
                val after=OfflineT9Candidates.select(code,texts,readings).firstPage
                if(header[3]=="positive" && before.firstOrNull()?.text==target) {
                    assertEquals("原有正确首选不能退化: $code $target",target,after.firstOrNull()?.text)
                }
                assertTrue("乱串不能进入首屏: $code",after.none { it.text in garbage })
                println("PUBLIC_CORPUS\t$code\t$target\t${header[3]}\t${before.indexOfFirst { it.text==target }}\t${after.indexOfFirst { it.text==target }}\t${before.firstOrNull()?.text.orEmpty()}\t${after.firstOrNull()?.text.orEmpty()}")
                count++
            }
            assertEquals(305,count)
        } finally {
            indexField.set(OfflineT9Candidates,index);closeStore();context.deleteDatabase("local_input.db")
        }
    }
}
