package com.yuyan.imemodule.data.callrecording

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.yuyan.imemodule.data.collect.CollectionConsent

/** 只监听系统公开音频的变更；实际读取仍由工作线程执行最近七天及稳定性检查。 */
internal object CallRecordingMediaObserver {
    private var resolver:ContentResolver?=null
    private var observer:ContentObserver?=null
    private fun allowed(context:Context)=CallRecordingRuntime.consent(context).wantsUpload &&
        CollectionConsent.enabled(context) && SystemRecordingMediaStore(context).readable()

    @Synchronized fun observe(context:Context) {
        val app=context.applicationContext
        if(!allowed(app)){stop();return}
        if(resolver===app.contentResolver && observer!=null)return
        stop()
        val listener=object:ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange:Boolean) {
                synchronized(this@CallRecordingMediaObserver) {
                    if(observer!==this)return
                    if(!allowed(app)){stop();return}
                    CallRecordingRuntime.preferences(app).edit().putBoolean("system_change_pending",true).apply()
                }
                CallRecordingJobService.wake(app)
                WechatCallUpload.mediaChanged(app)
            }
        }
        try {
            app.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,true,listener)
            resolver=app.contentResolver;observer=listener
        }catch(_:Exception){stop()} // 厂商不提供通知时仍保留周期扫描。
    }
    @Synchronized fun stop() {
        val source=resolver;val listener=observer
        resolver=null;observer=null
        if(source!=null && listener!=null)runCatching{source.unregisterContentObserver(listener)}
    }
}
