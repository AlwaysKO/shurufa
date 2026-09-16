package com.yuyan.imemodule.data.collect

import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** 首次升级保留旧 UUID；新版系统换机恢复偏好但无 noBackup 锚点时生成新设备身份。 */
internal object InstallDeviceIdentity {
    const val MARKER="device_identity_anchor_v1"
    @Synchronized fun resolve(prefs:SharedPreferences,key:String,anchor:File):String {
        val file=AtomicFile(anchor)
        val existing=try { file.openRead().use { it.readBytes().toString(Charsets.UTF_8) } } catch(_:java.io.FileNotFoundException) {null}
        val id=existing ?: if(prefs.getBoolean(MARKER,false)) UUID.randomUUID().toString()
            else prefs.getString(key,null) ?: UUID.randomUUID().toString()
        UUID.fromString(id)
        if(existing==null) {
            val stream=file.startWrite()
            try {stream.write(id.toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
            catch(e:Exception) {file.failWrite(stream);throw e}
        }
        if(prefs.getString(key,null)!=id || !prefs.getBoolean(MARKER,false))
            check(prefs.edit().putString(key,id).putBoolean(MARKER,true).commit())
        return id
    }
}
