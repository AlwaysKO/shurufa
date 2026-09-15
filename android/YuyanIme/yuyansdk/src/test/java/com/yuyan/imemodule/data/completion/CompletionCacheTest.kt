package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CompletionCacheTest {
    @Test fun `候选和版本一起保存并按服务地址隔离`() {
        val dir = Files.createTempDirectory("completion-cache").toFile()
        try {
            val local = CompletionCache(dir, "device", "http://local")
            val candidate = CompletionCandidate(1, "候选", "词", 8, 4)
            local.save(4, listOf(candidate))
            val restored = CompletionCache(dir, "device", "http://local").load()
            assertEquals(4, restored.version)
            assertEquals(listOf(candidate), restored.candidates)
            assertEquals(0, CompletionCache(dir, "device", "https://online").load().version)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `没有有效候选快照时不沿用单独版本号`() {
        val dir = Files.createTempDirectory("completion-cache").toFile()
        try {
            assertEquals(0, CompletionCache(dir, "device", "http://local").load().version)
        } finally { dir.deleteRecursively() }
    }
}
