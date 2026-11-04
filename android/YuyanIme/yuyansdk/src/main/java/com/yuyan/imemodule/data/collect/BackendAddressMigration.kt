package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.calllog.PhoneCallLogRuntime
import com.yuyan.imemodule.data.calllog.PhoneCallLogJobService
import com.yuyan.imemodule.data.callrecording.CallRecordingRuntime
import com.yuyan.imemodule.data.callrecording.CallRecordingJobService
import com.yuyan.imemodule.data.callrecording.CallRecordingOutbox
import com.yuyan.imemodule.data.usage.UsageStore
import org.json.JSONArray
import org.json.JSONObject

/** 只消费当前受信后台配置产生的日志；不能用于手工改绑任意设备或重开授权。 */
internal object BackendAddressMigration {
    const val HISTORY_KEY="backend_address_moves_v1"
    private val lock=Any()
    private fun history(prefs:SharedPreferences)=JSONArray(prefs.getString(HISTORY_KEY,"[]"))
    fun append(prefs:SharedPreferences,old:String,next:String):String = history(prefs).apply {
        put(JSONObject().put("old",old).put("next",next))
    }.toString()

    // 按时间顺序折叠，支持 A→B→A，不使用会陷入循环的地址映射表。
    fun resolveTarget(context:Context,target:String):String {
        return resolveTarget(PreferenceManager.getDefaultSharedPreferences(context.applicationContext),target)
    }
    internal fun resolveTarget(prefs:SharedPreferences,target:String):String {
        val moves=history(prefs)
        var resolved=target
        for(i in 0 until moves.length()) {
            val move=moves.getJSONObject(i)
            if(resolved==move.getString("old"))resolved=move.getString("next")
        }
        return resolved
    }
    fun previousTargets(context:Context,target:String):List<String> {
        val moves=history(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))
        return (0 until moves.length()).map{moves.getJSONObject(it).getString("old")}
            .distinct().filter{it!=target && resolveTarget(context,it)==target}
    }

    /** IO 线程；每个子步骤幂等，进度必须最后持久化，失败不抹掉迁移线索。 */
    fun recover(context:Context):Boolean {
        CallRecordingOutbox.checkWorker()
        val app=context.applicationContext
        ServerConfig.init(app)
        return synchronized(lock) {
            val preferences=PreferenceManager.getDefaultSharedPreferences(app)
            val progress=app.getSharedPreferences("backend_address_migration_v1",Context.MODE_PRIVATE)
            val before=history(preferences)
            val bindings=listOfNotNull(CallRecordingRuntime.preferences(app).getString("destination",null),
                PhoneCallLogRuntime.preferences(app).getString("destination",null))
            val stale=bindings.any{it.isNotEmpty() && resolveTarget(preferences,it)!=ServerConfig.baseUrl}
            if(!stale && progress.getInt("applied",0)==before.length() && !progress.getBoolean("notify",false))
                return@synchronized true
            val device=DataCollector.deviceId(app)
            val prior=listOfNotNull(CallRecordingRuntime.consent(app).migrationSource(device),
                PhoneCallLogRuntime.consent(app).migrationSource(device)).distinct()
            for(old in prior) {
                if(resolveTarget(app,old)==ServerConfig.baseUrl)continue
                if(!ImageUploadRuntime.isBackgroundWorkAllowed())return@synchronized false
                ServerConfig.rememberPreviousTarget(old)
            }
            val moves=history(PreferenceManager.getDefaultSharedPreferences(app))
            var index=progress.getInt("applied",0)
            check(index in 0..moves.length()) { "invalid_backend_migration_progress" }
            while(index<moves.length()) {
                if(!ImageUploadRuntime.isBackgroundWorkAllowed())return@synchronized false
                val move=moves.getJSONObject(index)
                val old=move.getString("old");val next=move.getString("next")
                require(normalizeOnlineServerUrl(old)==old && normalizeOnlineServerUrl(next)==next)
                val local=LocalInputStore(app)
                try {local.replaceTarget(old,next)} finally {local.close()}
                UsageStore(app).use{it.replaceTarget(old,next)}
                CallRecordingRuntime.outbox(app).replaceTarget(device,old,next)
                CallRecordingRuntime.consent(app).replaceTarget(device,old,next)
                PhoneCallLogRuntime.consent(app).replaceTarget(device,old,next)
                check(progress.edit().putInt("applied",index+1).putBoolean("notify",true).commit())
                index++
            }
            if(progress.getBoolean("notify",false)) {
                if(!ImageUploadRuntime.isBackgroundWorkAllowed())return@synchronized false
                // 只通知恢复 Job；绝不从后台自行启动麦克风或授予权限。
                PhoneCallLogJobService.wake(app)
                CallRecordingJobService.wake(app)
                UploadRecovery.wake(app)
                check(progress.edit().putBoolean("notify",false).commit())
            }
            true
        }
    }
}
