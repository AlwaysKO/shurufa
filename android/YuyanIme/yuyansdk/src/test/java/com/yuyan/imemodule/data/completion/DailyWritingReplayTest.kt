package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.AssociationCandidateMerger
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** 自编聊天/工作写作上下文；检验真实资产，不冒充搜狗或Rime运行输出。 */
class DailyWritingReplayTest {
    private fun index(): OfflineAssociationIndex {
        val assets = File("src/main/assets/completion").takeIf { it.isDirectory }
            ?: File("yuyansdk/src/main/assets/completion")
        return GZIPInputStream(assets.resolve("offline_associations.tsv.gzip").inputStream())
            .reader().use(OfflineAssociationIndex::parse)
    }

    @Test fun `聊天与文章长上下文能继续日常表达`() {
        val index = index()
        for ((context, completion) in listOf(
            "今天事情比较多，我晚点" to "联系你",
            "文件已经整理好了，麻烦把文件" to "发我",
            "讨论结束以后，麻烦确认" to "一下",
            "今天外面下雨，回家注意" to "安全",
            "如果你有时间，给我" to "回电话",
            "更新流程分为三步，第一步系统更新" to "完成",
            "本次修改涉及文档，文档已" to "更新",
            "经过检查，问题解决" to "了",
            "下面记录工作进度，代码已" to "更新",
            "下一步验证系统状态，网络连接" to "失败",
        )) {
            val result = index.query(context)
            assertTrue(context + result.partial, completion in result.partial.filterNot { it in result.lowPriority })
        }
    }

    @Test fun `普通聊天和写作不因二字后缀把古文插到原生候选前`() {
        val index = index()
        val native = listOf("这样", "继续", "再说", "完成", "处理")
        for (context in listOf("我想说不知", "这件事情不是", "我们先讨论然后", "关于天地", "写到这里还是")) {
            val merged = AssociationCandidateMerger.merge(index.query(context), native, listOf("，", "。"), emptyList())
            assertEquals(context, native, merged.take(5).map { it.text })
            assertEquals(listOf(0, 1, 2, 3, 4), merged.take(5).map { it.rimeIndex })
        }
    }
}
