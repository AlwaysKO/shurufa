package com.yuyan.imemodule.data.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

internal object PhoneCallLogRuntime {
    private val lock=ReentrantLock()
    fun preferences(context:Context)=context.applicationContext.getSharedPreferences("phone_call_log_consent_v1",Context.MODE_PRIVATE)
    fun consent(context:Context)=PhoneCallLogConsent(preferences(context))
    fun hasPermission(context:Context)=ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED
    fun restore(context:Context){if(consent(context).enabled)PhoneCallLogJobService.schedule(context)else PhoneCallLogJobService.cancel(context)}
    fun run(context:Context,running:()->Boolean) {
        val app=context.applicationContext
        if(!running()||!ImageUploadRuntime.isInputIdle()||!CollectionConsent.enabled(app)||!consent(app).enabled||!lock.tryLock())return
        try {
            ServerConfig.init(app)
            val device=DataCollector.deviceId(app);val target=ServerConfig.baseUrl
            val consent=consent(app);val ticket=consent.revision;val masterEpoch=CollectionConsent.epoch
            val allowed={running()&&ImageUploadRuntime.isInputIdle()&&CollectionConsent.enabled(app)&&CollectionConsent.epoch==masterEpoch&&
                consent.revision==ticket&&consent.allowed(device,target)&&ServerConfig.baseUrl==target}
            if(!allowed())return
            val http=PhoneCallLogHttp(target,device,{app.getSharedPreferences("personal_dictionary_sync_v1",0).getString("token",null)},allowed)
            val request=http.requestId()
            if(!hasPermission(app)){
                http.send(PhoneCallLogBatch(request,"permission_required",emptyList(),false))
                preferences(app).edit().putString("status","需要系统记录权限；请在此页点击授权").apply();return
            }
            val readAllowed={allowed()&&hasPermission(app)}
            val read=try{PhoneCallLogReader.read(app.contentResolver,System.currentTimeMillis(),readAllowed)}
                catch(e:Exception){
                    if(allowed()){
                        val state=if(hasPermission(app))"failed" else "permission_required"
                        http.send(PhoneCallLogBatch(request,state,emptyList(),false))
                        preferences(app).edit().putString("status",if(state=="failed")"系统记录读取失败，稍后重试"else"系统权限已撤回").apply()
                    }
                    return
                }
            if(!readAllowed())return
            val batch=PhoneCallLogBatch(request,"synced",read.records,read.truncated)
            val canonical=Json.encodeToString(PhoneCallLogBatch.serializer(),batch.copy(request_id=null))
            val fingerprint=MessageDigest.getInstance("SHA-256").digest((device+"\u0000"+target+"\u0000"+canonical).toByteArray()).joinToString(""){"%02x".format(it)}
            if(request==null&&preferences(app).getString("last_fingerprint",null)==fingerprint)return
            // 发送含记录的请求期间另加实时系统权限检查；状态回报不包含记录。
            PhoneCallLogHttp(target,device,{app.getSharedPreferences("personal_dictionary_sync_v1",0).getString("token",null)},readAllowed).send(batch)
            if(readAllowed())preferences(app).edit().putString("last_fingerprint",fingerprint)
                .putLong("last_synced_at",System.currentTimeMillis()).putString("status","已同步最近7天 ${read.records.size} 条普通记录${if(read.truncated)"（达到2000条上限）"else""}").apply()
        }catch(_:Exception){
            if(running()&&consent(app).enabled&&ImageUploadRuntime.isInputIdle())preferences(app).edit().putString("status","同步暂未完成，稍后自动重试").apply()
        }finally{lock.unlock()}
    }
}
