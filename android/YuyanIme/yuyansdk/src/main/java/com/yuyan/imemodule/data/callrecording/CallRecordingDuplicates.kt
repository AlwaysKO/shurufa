package com.yuyan.imemodule.data.callrecording

import kotlin.math.abs

internal object CallRecordingDuplicates {
    fun preferredSystem(local:CallTask,tasks:List<CallTask>,sourceExists:(CallTask)->Boolean={true}):CallTask? {
        if(local.source!=null || local.attempts!=0 || local.uploadStatus in listOf("saved","superseded"))return null
        val matches=tasks.filter{it.source!=null && sameCall(local,it)}.groupBy {
            // 媒体库和目录可公开同一原件的不同 URI；可信内容相同只算一个候选。
            val metadata=it.metadata
            if(Regex("[a-f0-9]{64}").matches(metadata.sha256) && metadata.byte_size in 1..CallRecordingOutbox.MAX_AUDIO_BYTES)
                metadata.sha256 to metadata.byte_size
            else it.id to -1L
        }
        val sameContent=matches.values.singleOrNull() ?: return null
        // 一条 URI 撤权不代表原件消失；只有至少一条来源仍可读才优先系统副本。
        val system=sameContent.firstOrNull(sourceExists) ?: return null
        // 两份本地片段都能匹配时，不凭相近时间将多次会话合并。
        if(tasks.count{it.source==null && sameCall(it,system)}!=1)return null
        return system
    }
    fun sameCall(local:CallTask,system:CallTask):Boolean {
        if(local.source!=null || system.source==null || local.deviceId!=system.deviceId)return false
        val a=local.metadata;val b=system.metadata
        if(a.destination!=b.destination || a.platform!=b.platform || a.call_type!=b.call_type)return false
        if(a.sha256.isNotEmpty() && a.sha256==b.sha256 && a.byte_size==b.byte_size)return true
        if(!system.source.startTimeKnown || a.recording_status!="ended" || b.recording_status!="ended")return false
        return abs(a.recording_started_at-b.recording_started_at)<=2000 &&
            abs(a.recording_ended_at-b.recording_ended_at)<=2000 && b.audio_duration_ms>=a.audio_duration_ms-1000
    }
}
