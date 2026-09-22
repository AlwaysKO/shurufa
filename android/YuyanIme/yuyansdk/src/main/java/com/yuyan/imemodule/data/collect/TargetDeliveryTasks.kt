package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 每目标独立占用；不持有聊天数据。 */
internal class TargetDeliveryTasks(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val busy = ConcurrentHashMap.newKeySet<String>()

    suspend fun run(
        targets: List<String>,
        onBusy: (String) -> Unit = {},
        onFailure: (String, Exception) -> Unit = { _, _ -> },
        action: suspend (String) -> Unit,
    ) = supervisorScope {
        targets.forEach { target ->
            launch(dispatcher) {
                // 启动后才占用；启动前取消的协程不会执行 finally。
                if (!busy.add(target)) { onBusy(target); return@launch }
                try { action(target) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onFailure(target, error) }
                finally { busy.remove(target) }
            }
        }
    }
}
