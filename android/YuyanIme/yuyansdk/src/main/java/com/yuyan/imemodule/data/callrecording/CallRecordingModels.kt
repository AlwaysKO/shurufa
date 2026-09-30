package com.yuyan.imemodule.data.callrecording

import kotlinx.serialization.Serializable

@Serializable
internal data class CallMetadata(
    val platform:String="phone", val call_type:String="voice",
    val counterpart_display:String?=null, val counterpart_source:String="unknown",
    val call_started_at:Long?=null, val call_ended_at:Long?=null, val call_duration_ms:Long?=null,
    val call_duration_estimated:Boolean=false,
    val recording_started_at:Long, val recording_ended_at:Long, val audio_duration_ms:Long,
    val recording_status:String="ended", val quality_status:String="unverified", val failure_reason:String?=null,
    val mime_type:String="audio/mp4", val byte_size:Long=0, val sha256:String="",
    val consent_version:String="call-audio-v1", val destination:String,
)
@Serializable
internal data class CallReceipt(
    val stored:Boolean, val record_id:String, val device_id:String,
    val byte_size:Long, val sha256:String, val stored_at:String,
)
@Serializable
internal data class CallTask(
    val id:String, val deviceId:String, val metadata:CallMetadata,
    val uploadStatus:String="pending", val cleanupStatus:String="retained",
    val attempts:Int=0, val nextAttemptAt:Long=0, val lastError:String?=null,
    val receipt:CallReceipt?=null,
    val source:SystemRecordingSource?=null,
    val duplicateOf:String?=null,
    val serverRecordId:String?=null,
)

/** 外部原件只有只读 URI；队列始终拥有独立缓存，清理代码永不操作 URI。 */
@Serializable
internal data class SystemRecordingSource(
    val uri:String, val size:Long, val modifiedAt:Long, val startTimeKnown:Boolean,
)
