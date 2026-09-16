package com.yuyan.imemodule.data.collect
import org.junit.Assert.*
import org.junit.Test
class PersonalDictionaryTargetsTest {
    private val online="https://my.dog8ball.com"
    private val local="http://127.0.0.1:3000"
    @Test fun `两个当前目标都上报仅主后台恢复线上旧状态键不变`() {
        val plans=dictionarySyncTargets(listOf(local,online,online+"/"),online,online)
        assertEquals(2,plans.size)
        assertFalse(plans.first { it.url==local }.restoreFromTarget)
        assertTrue(plans.first { it.url==online }.restoreFromTarget)
        assertEquals("",plans.first { it.url==online }.statePrefix)
        assertTrue(plans.first { it.url==local }.statePrefix.isNotEmpty())
    }
    @Test fun `切换主控不改变任一目标凭据命名空间且不会自动选故障替代主控`() {
        val before=dictionarySyncTargets(listOf(local,online),online,online)
        val after=dictionarySyncTargets(listOf(local,online),online,local)
        assertEquals(before.map { it.statePrefix },after.map { it.statePrefix })
        assertEquals(listOf(local),after.filter { it.restoreFromTarget }.map { it.url })
        assertFalse(dictionarySyncTargets(listOf(online),online,local).single().restoreFromTarget)
    }
}
