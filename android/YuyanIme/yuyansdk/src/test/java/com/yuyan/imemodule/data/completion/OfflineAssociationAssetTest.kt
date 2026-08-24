package com.yuyan.imemodule.data.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class OfflineAssociationAssetTest {
    private fun loadIndex(): OfflineAssociationIndex {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val asset = sequenceOf(
            workingDirectory.resolve("src/main/assets/completion/offline_associations.tsv.gzip"),
            workingDirectory.resolve("yuyansdk/src/main/assets/completion/offline_associations.tsv.gzip"),
        ).first { it.isFile }
        return GZIPInputStream(asset.inputStream()).reader(Charsets.UTF_8).use(OfflineAssociationIndex::parse)
    }

    @Test
    fun assetContainsRequestedClassicalContinuations() {
        val index = loadIndex()

        assertEquals("难越", index.query("关山").partial.first())
        assertEquals("谁悲失路之人", index.query("关山难越").next.first())
        assertEquals("明月光", index.query("床前").partial.first())
    }

    @Test
    fun assetContainsDailyLifeAssociations() {
        val index = loadIndex()

        assertTrue("休息" in index.query("早点").partial)
        assertTrue("告诉我" in index.query("到家").partial)
        assertTrue("安全" in index.query("注意").partial)
    }
}
