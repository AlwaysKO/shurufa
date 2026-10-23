package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.data.collect.CollectionConsent

/** 只消费通话状态，促使已有授权的系统录音复制；不读取号码、不启动麦克风。 */
class CallRecordingPhoneReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=TelephonyManager.ACTION_PHONE_STATE_CHANGED)return
        val prefs=CallRecordingRuntime.preferences(context)
        if(!CollectionConsent.enabled(context) || !CallRecordingRuntime.consent(context).wantsUpload ||
            ContextCompat.checkSelfPermission(context,Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED) {
            prefs.edit().remove("phone_was_offhook").apply();return
        }
        when(intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK->prefs.edit().putBoolean("phone_was_offhook",true).apply()
            TelephonyManager.EXTRA_STATE_IDLE->{
                if(!prefs.getBoolean("phone_was_offhook",false))return
                prefs.edit().remove("phone_was_offhook").apply()
                WechatCallUpload.phoneFinished(context)
            }
        }
    }
}
