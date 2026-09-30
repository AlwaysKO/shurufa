package com.yuyan.imemodule.data.calllog

import android.content.Context
import android.database.MatrixCursor
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PhoneCallLogTest {
    private val now=1_800_000_000_000L
    private fun cursor(vararg rows:Array<Any?>)=MatrixCursor(arrayOf(CallLog.Calls._ID,CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,CallLog.Calls.TYPE,CallLog.Calls.DATE,CallLog.Calls.DURATION)).apply{rows.forEach{addRow(it)}}
    @Test fun `最近七天边界包含当天但排除过期未来和无效记录`() {
        val cutoff=now-7*24*60*60*1000L
        cursor(arrayOf(1,"10086","客服",2,now,25),arrayOf(2,null,null,3,cutoff,0),
            arrayOf(3,"1",null,1,cutoff-1,1),arrayOf(4,"2",null,1,now+1,1),
            arrayOf(5,"3",null,1,now,-1)).use {
            val result=PhoneCallLogReader.readCursor(it,now){true}
            assertEquals(2,result.records.size);assertEquals(25,result.records.first().duration_seconds)
            assertNull(result.records.last().number);assertFalse(result.truncated)
        }
    }
    @Test fun `清除提供者控制字符但保留正常号码姓名`() {
        cursor(arrayOf(1,"100\n86\u0000","客\t服\r",2,now,5)).use{
            val row=PhoneCallLogReader.readCursor(it,now){true}.records.single()
            assertEquals("10086",row.number);assertEquals("客服",row.name)
        }
    }
    @Test fun `提供者记录id复用不会覆盖不同电话但重复读取标识一致`() {
        fun id(date:Long,number:String)=cursor(arrayOf(8,number,null,2,date,4)).use{PhoneCallLogReader.readCursor(it,now){true}.records.single().source_id}
        assertEquals(id(now,"10086"),id(now,"10086"))
        assertNotEquals(id(now,"10086"),id(now-1,"10086"))
        assertNotEquals(id(now,"10086"),id(now,"10010"))
    }
    @Test fun `最多两千条并报告截断`() {
        val c=cursor();repeat(2001){c.addRow(arrayOf<Any?>(it,"10086",null,1,now-it,1))}
        c.use{val result=PhoneCallLogReader.readCursor(it,now){true};assertEquals(2000,result.records.size);assertTrue(result.truncated)}
    }
    @Test fun `遍历途中输入或撤回时不返回部分结果`() {
        cursor(arrayOf(1,"10086",null,1,now,5),arrayOf(2,"10010",null,1,now-1,4)).use {
            var checks=0
            try {PhoneCallLogReader.readCursor(it,now){++checks<3};fail("应取消读取")}catch(_:IOException){}
        }
    }
    @Test fun `新权限默认关闭且只允许明确授权设备目标`() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        val prefs=c.getSharedPreferences(UUID.randomUUID().toString(),0)
        val consent=PhoneCallLogConsent(prefs)
        val device=UUID.randomUUID().toString();val target="https://example.test"
        assertFalse(consent.enabled);assertFalse(consent.allowed(device,target))
        assertTrue(consent.grant(device,target,consent.revision));assertTrue(consent.allowed(device,target))
        assertFalse(consent.allowed(UUID.randomUUID().toString(),target));assertFalse(consent.allowed(device,"https://elsewhere.test"))
        val ticket=consent.revision;consent.revoke();assertFalse(consent.grant(device,target,ticket));assertFalse(consent.allowed(device,target))
    }
}
