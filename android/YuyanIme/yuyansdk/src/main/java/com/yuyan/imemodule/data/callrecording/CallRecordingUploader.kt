package com.yuyan.imemodule.data.callrecording

import java.io.File
import kotlinx.coroutines.CancellationException

internal interface CallTransport {
    fun receipt(task:CallTask):CallReceipt?
    fun existing(task:CallTask):CallReceipt? = null
    fun upload(task:CallTask,file:File,allowed:()->Boolean):CallReceipt
}
/** 运行入口必须提供实时的授权/设备身份/输入空闲检查；不从键盘线程调用。 */
internal class CallRecordingUploader(
    private val outbox:CallRecordingOutbox,
    private val transport:CallTransport,
    private val allowed:()->Boolean,
    private val onlineTarget:()->String,
    private val now:()->Long=System::currentTimeMillis,
    private val systemSourceExists:(SystemRecordingSource)->Boolean={false},
    private val onSaved:(CallTask)->Unit={},
    private val ready:(CallTask)->Boolean={true},
) {
    fun runOnce()=outbox.uploadBatch {
        val initial=outbox.tasks()
        for(local in initial.filter{it.source==null && it.attempts==0 && it.cleanupStatus!="deleted" && it.uploadStatus!="saved"}) {
            if(!allowed())return@uploadBatch
            val system=CallRecordingDuplicates.preferredSystem(local,initial,::sourceExists)
            outbox.linkDuplicate(local.id,system?.takeIf{sourceExists(it)}?.id)
        }
        // 系统文件先获得持久回执，之后才有资格清理对应的本地副本。
        for(task in outbox.tasks().sortedBy{if(it.source!=null)0 else 1}) {
            if(task.cleanupStatus=="deleted")continue
            fun permitted()=allowed() && onlineTarget()==task.metadata.destination
            try {
                if(!permitted()){outbox.pause(task.id);continue}
                if(task.duplicateOf!=null) {
                    val system=outbox.tasks().firstOrNull{it.id==task.duplicateOf}
                    if(system!=null && sourceExists(system)) {
                        if(permitted())outbox.cleanupDuplicate(task.id,system.id)
                        continue
                    }
                    outbox.linkDuplicate(task.id,null)
                }
                if(task.uploadStatus=="saved"){onSaved(task);if(permitted())outbox.cleanup(task.id);continue}
                if(!ready(task))continue
                if(now()<task.nextAttemptAt)continue
                outbox.markAttempt(task.id)
                // 超时/进程死亡后先查询原回执，不盲目重传。
                var receipt=transport.receipt(task)
                var contentReceipt=false
                if(!permitted()){outbox.pause(task.id);continue}
                if(receipt==null) {
                    if(!outbox.verify(task,::permitted)){outbox.fail(task.id,now(),"local_audio_changed");continue}
                    receipt=transport.existing(task)
                    contentReceipt=true
                    if(!permitted()){outbox.pause(task.id);continue}
                    if(receipt==null)receipt=transport.upload(task,outbox.audioFile(task.id),::permitted)
                }
                if(!permitted()){outbox.pause(task.id);continue}
                if(if(contentReceipt)outbox.acceptExistingReceipt(task.id,receipt)else outbox.acceptReceipt(task.id,receipt)){onSaved(task.copy(uploadStatus="saved",receipt=receipt));if(permitted())outbox.cleanup(task.id)}
                else outbox.fail(task.id,now(),"invalid_receipt")
            } catch(e:CancellationException){throw e}
            catch(_:Exception){
                // 连错误状态都写不下时仍保留原清单/音频，不能阻塞其他健康任务。
                try {if(permitted())outbox.fail(task.id,now(),"network_error")else outbox.pause(task.id)}
                catch(e:CancellationException){throw e}
                catch(_:Exception){ /* 下次恢复原状态继续重试，绝不删除。 */ }
            }
        }
    }
    private fun sourceExists(task:CallTask)=task.source?.let{runCatching{systemSourceExists(it)}.getOrDefault(false)}?:false
}
