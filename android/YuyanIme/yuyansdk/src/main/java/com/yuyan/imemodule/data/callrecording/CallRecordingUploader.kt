package com.yuyan.imemodule.data.callrecording

import java.io.File
import kotlinx.coroutines.CancellationException

internal interface CallTransport {
    fun receipt(task:CallTask):CallReceipt?
    fun upload(task:CallTask,file:File,allowed:()->Boolean):CallReceipt
}
/** 运行入口必须提供实时的授权/设备身份/输入空闲检查；不从键盘线程调用。 */
internal class CallRecordingUploader(
    private val outbox:CallRecordingOutbox,
    private val transport:CallTransport,
    private val allowed:()->Boolean,
    private val onlineTarget:()->String,
    private val now:()->Long=System::currentTimeMillis,
) {
    fun runOnce()=outbox.uploadBatch {
        for(task in outbox.tasks()) {
            if(task.cleanupStatus=="deleted")continue
            fun permitted()=allowed() && onlineTarget()==task.metadata.destination
            try {
                if(!permitted()){outbox.pause(task.id);continue}
                if(task.uploadStatus=="saved"){outbox.cleanup(task.id);continue}
                if(now()<task.nextAttemptAt)continue
                outbox.markAttempt(task.id)
                // 超时/进程死亡后先查询原回执，不盲目重传。
                var receipt=transport.receipt(task)
                if(!permitted()){outbox.pause(task.id);continue}
                if(receipt==null) {
                    if(!outbox.verify(task,::permitted)){outbox.fail(task.id,now(),"local_audio_changed");continue}
                    receipt=transport.upload(task,outbox.audioFile(task.id),::permitted)
                }
                if(!permitted()){outbox.pause(task.id);continue}
                if(outbox.acceptReceipt(task.id,receipt))outbox.cleanup(task.id)
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
}
