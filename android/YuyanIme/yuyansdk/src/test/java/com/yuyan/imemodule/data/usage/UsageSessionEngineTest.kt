package com.yuyan.imemodule.data.usage

import org.junit.Assert.*
import org.junit.Test

class UsageSessionEngineTest {
    @Test fun `own app variants are excluded without extending previous app time`() {
        for (pkg in listOf("com.yuyan.pinyin", "com.yuyan.pinyin.debug", "com.yuyan.pinyin.release", "com.yuyan.pinyin.offline", "com.yuyan.pinyin.offline.debug", "com.yuyan.pinyin.offline.release")) {
            val r=UsageSessionEngine.reduce(UsageState(cursor=100), listOf(event(110,"resume","a"),event(200,"resume",pkg),event(400,"resume","b"),event(500,"lock")),600)
            assertEquals(listOf("a","b"),r.records.map { it.packageName })
            assertEquals(listOf(90L,100L),r.records.map { it.endMs-it.startMs })
        }
    }
    @Test fun `restored own session emits no usage while similarly named third party stays`() {
        val r=UsageSessionEngine.reduce(UsageState(cursor=200,active=UsageActive("com.yuyan.pinyin.offline.debug","one",100)),listOf(event(300,"resume","com.yuyan.pinyin.other"),event(400,"lock")),500)
        assertEquals(listOf("com.yuyan.pinyin.other"),r.records.map { it.packageName })
    }
    private fun event(time: Long, type: String, pkg: String = "a", token: String = "one") = UsageEvent(time, type, pkg, token)
    @Test fun `switch and lock close intervals without background time`() {
        val r = UsageSessionEngine.reduce(UsageState(cursor = 100), listOf(event(110,"resume"), event(200,"resume","b"), event(250,"lock")), 300)
        assertEquals(listOf(90L,50L), r.records.map { it.endMs-it.startMs })
        assertNull(r.state.active)
    }
    @Test fun `same app activity transition merges but old activity pause cannot close new one`() {
        val r = UsageSessionEngine.reduce(UsageState(cursor = 100), listOf(event(110,"resume"),event(150,"resume",token="two"),event(160,"pause"),event(250,"lock")), 300)
        assertEquals(1,r.records.size)
        assertEquals(110L,r.records.single().startMs)
        assertEquals(250L,r.records.single().endMs)
    }
    @Test fun `pause resume of same app in short transition is one session`() {
        val first = UsageSessionEngine.reduce(UsageState(cursor=100),listOf(event(110,"resume"),event(200,"pause")),250)
        assertTrue(first.records.isEmpty())
        val second = UsageSessionEngine.reduce(first.state,listOf(event(300,"resume",token="two"),event(400,"lock")),500)
        assertEquals(110L,second.records.single().startMs)
        assertEquals(400L,second.records.single().endMs)
    }
    @Test fun `pause closes at event time not delayed polling time`() {
        val r = UsageSessionEngine.reduce(UsageState(cursor=100),listOf(event(110,"resume"),event(200,"pause")),5000)
        assertEquals(200L,r.records.single().endMs)
        assertNull(r.state.active)
    }
    @Test fun `screen off blocks spurious resumed events until unlock`() {
        val r = UsageSessionEngine.reduce(UsageState(cursor=100),listOf(event(110,"resume"),event(195,"lock"),event(200,"off"),event(210,"resume","b"),event(220,"on"),event(230,"resume","b"),event(300,"unlock"),event(310,"resume","b"),event(400,"pause","b")),5000)
        assertEquals(listOf(110L,300L),r.records.map { it.startMs })
    }
    @Test fun `screen on without keyguard does not block subsequent apps`() {
        val r=UsageSessionEngine.reduce(UsageState(cursor=100),listOf(event(110,"resume"),event(200,"off"),event(300,"on"),event(310,"resume","b"),event(400,"pause","b")),5000)
        assertEquals(listOf("a","b"),r.records.map { it.packageName })
    }
    @Test fun `unknown boot identity on process restart never bridges an old active session`() {
        val old=UsageState(cursor=100000,active=UsageActive("a","one",90000),boot=-1,elapsed=10000)
        assertEquals("process_restart",usageDiscontinuity(old,1010000,900000,-1,true))
        assertNull(usageDiscontinuity(old,1010000,920000,-1,false))
        assertEquals("reboot",usageDiscontinuity(old.copy(boot=1),1010000,900000,2,false))
    }
    @Test fun `late stop of same class old activity cannot close the resumed instance`() {
        val events=listOf(event(110,"resume"),event(200,"pause"),event(250,"resume"),event(300,"stop"),event(10000,"pause"))
        val r=UsageSessionEngine.reduce(UsageState(cursor=100),events,12000)
        assertEquals(110L,r.records.single().startMs)
        assertEquals(10000L,r.records.single().endMs)
    }
    @Test fun `resume under keyguard starts only at unlock even without another resume`() {
        val first=UsageSessionEngine.reduce(UsageState(cursor=100),listOf(event(110,"lock"),event(200,"resume")),250)
        assertTrue(first.records.isEmpty())
        val second=UsageSessionEngine.reduce(first.state,listOf(event(300,"unlock"),event(500,"pause")),2000)
        assertEquals(300L,second.records.single().startMs)
        assertEquals(500L,second.records.single().endMs)
    }
    @Test fun `paused candidate under keyguard cannot resume on unlock`() {
        val r=UsageSessionEngine.reduce(UsageState(cursor=100),listOf(event(110,"lock"),event(200,"resume"),event(250,"pause"),event(300,"unlock")),2000)
        assertTrue(r.records.isEmpty())
        assertNull(r.state.active)
    }
    @Test fun `screen on restores only a resumed unpaused candidate without keyguard`() {
        val r=UsageSessionEngine.reduce(UsageState(cursor=100),listOf(event(110,"off"),event(200,"resume"),event(300,"on"),event(400,"pause")),2000)
        assertEquals(300L,r.records.single().startMs)
        assertEquals(400L,r.records.single().endMs)
    }
    @Test fun `checkpoint resumes active interval but never replays earlier events`() {
        val first=UsageSessionEngine.reduce(UsageState(cursor=100),listOf(event(110,"resume")),200)
        val second=UsageSessionEngine.reduce(first.state,listOf(event(110,"resume"),event(210,"resume"),event(250,"pause")),5000)
        assertEquals(110L,second.records.single().startMs)
        assertEquals(5000L,second.state.cursor)
    }
    @Test fun `reboot and excessive unobserved window are gaps not usage`() {
        val active=UsageState(cursor=200,active=UsageActive("a","one",110))
        val boot=UsageSessionEngine.reduce(active,listOf(event(250,"startup")),300)
        assertEquals("gap",boot.records.single().kind)
        assertNull(boot.state.active)
        val stale=UsageSessionEngine.gap(active,90000,"permission_lost")
        assertEquals(110L,stale.records.single().startMs)
        assertEquals("gap",stale.records.single().kind)
    }
    @Test fun `end boundary is exclusive and deterministic records deduplicate replay`() {
        val events=listOf(event(110,"resume"),event(200,"lock"),event(300,"resume","b"))
        val r=UsageSessionEngine.reduce(UsageState(cursor=100),events,300)
        assertNull(r.state.active)
        assertEquals(r.records,UsageSessionEngine.reduce(UsageState(cursor=100),events,300).records)
    }
}
