package com.yuyan.imemodule.application

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeAssetsTest {
    @Test
    fun sourceContainsCompiledT9ChineseDictionary() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val assetsDirectory = sequenceOf(
            workingDirectory.resolve("src/main/assets/rime/build"),
            workingDirectory.resolve("yuyansdk/src/main/assets/rime/build"),
        ).first { it.isDirectory }

        assertTrue("缺少九宫格拼音方案", assetsDirectory.resolve("t9_pinyin.prism.bin").length() > 0)
        assertTrue("缺少中文词典", assetsDirectory.resolve("pinyin.table.bin").length() > 0)
    }

    @Test
    fun existingInstallIsForcedToRefreshRimeAssets() {
        assertTrue(CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM > 20260325)
    }
}
