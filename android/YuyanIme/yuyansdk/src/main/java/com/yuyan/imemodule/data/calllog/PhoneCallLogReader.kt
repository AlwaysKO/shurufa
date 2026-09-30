package com.yuyan.imemodule.data.calllog

import android.content.ContentResolver
import android.database.Cursor
import android.os.CancellationSignal
import android.provider.CallLog
import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
internal data class PhoneCallLogRecord(val source_id:String,val number:String?,val name:String?,val type:Int,val date:Long,val duration_seconds:Int)
@Serializable
internal data class PhoneCallLogBatch(val request_id:String?,val status:String,val records:List<PhoneCallLogRecord>,val truncated:Boolean)
internal data class PhoneCallLogRead(val records:List<PhoneCallLogRecord>,val truncated:Boolean)

/** 仅普通系统通话记录；从不写回提供者，也不查询联系人。 */
internal object PhoneCallLogReader {
    const val WINDOW_MS=7*24*60*60*1000L
    private const val LIMIT=2000
    private val controls=Regex("[\\u0000-\\u001f]")
    private val projection=arrayOf(CallLog.Calls._ID,CallLog.Calls.NUMBER,CallLog.Calls.CACHED_NAME,
        CallLog.Calls.TYPE,CallLog.Calls.DATE,CallLog.Calls.DURATION)
    fun read(resolver:ContentResolver,now:Long,allowed:()->Boolean):PhoneCallLogRead {
        if(!allowed())throw IOException("call_log_paused")
        val signal=CancellationSignal()
        val watcher=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"call-log-read-cancel").apply{isDaemon=true}}
        val watch=watcher.scheduleWithFixedDelay({if(!allowed())signal.cancel()},0,100,TimeUnit.MILLISECONDS)
        try {
            val uri=CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter("limit",(LIMIT+1).toString()).build()
            return resolver.query(uri,projection,"${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
                arrayOf((now-WINDOW_MS).toString(),now.toString()),"${CallLog.Calls.DATE} DESC",signal)?.use{
                readCursor(it,now,allowed)
            } ?: throw IOException("call_log_provider_unavailable")
        }finally{watch.cancel(true);watcher.shutdownNow()}
    }
    internal fun readCursor(cursor:Cursor,now:Long,allowed:()->Boolean):PhoneCallLogRead {
        val indexes=projection.map{cursor.getColumnIndexOrThrow(it)}
        val result=ArrayList<PhoneCallLogRecord>()
        var scanned=0
        while(true) {
            if(!allowed())throw IOException("call_log_paused")
            if(!cursor.moveToNext())break
            if(++scanned>LIMIT)return PhoneCallLogRead(result,true)
            val date=cursor.getLong(indexes[4]);val duration=cursor.getLong(indexes[5]);val type=cursor.getInt(indexes[3])
            if(date<now-WINDOW_MS||date>now||duration !in 0..Int.MAX_VALUE.toLong()||type !in 1..7)continue
            val number=cursor.getString(indexes[1])?.replace(controls,"")?.take(128)
            val name=cursor.getString(indexes[2])?.replace(controls,"")?.take(200)
            val identity="${cursor.getLong(indexes[0])}\u0000$date\u0000$type\u0000${number?.let{"${it.length}:$it"}?:"null"}"
            val source=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
            result.add(PhoneCallLogRecord(source,number,name,type,date,duration.toInt()))
        }
        return PhoneCallLogRead(result,false)
    }
}
