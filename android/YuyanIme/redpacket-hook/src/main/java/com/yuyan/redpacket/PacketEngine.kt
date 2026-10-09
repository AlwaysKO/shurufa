package com.yuyan.redpacket

import java.util.IdentityHashMap

interface PacketPort {
    fun config(): PacketConfig
    fun allowed(): Boolean
    fun reserve(key: String): Boolean
    fun receive(packet: Packet): Any
    fun open(packet: Packet, token: String): Any
    fun send(request: Any, callback: (Any, Boolean) -> Unit): Boolean
    fun report(status: String)
    fun release(request: Any) {}
}

/** 工作线程限定；最多八个在途任务，业务回调和网络回调通过对象身份关联。 */
class PacketEngine(private val port: PacketPort, private val user: Int, private val clock: () -> Long) {
    private class Job(val packet: Packet, val flow: PacketFlow) { var openStarted = false }
    private class Deferred(val packet: Packet, val expiresAt: Long)
    private var draining = false
    private val deferred = linkedMapOf<String, Deferred>()
    private val jobs = linkedMapOf<String, Job>()
    private val requests = IdentityHashMap<Any, Job>()
    private val probes = linkedSetOf<String>()
    fun nextDeadlineDelay(): Long? {
        val next = (jobs.values.map { it.flow.deadlineAt } + deferred.values.map { it.expiresAt }).minOrNull() ?: return null
        return maxOf(1L, next - clock())
    }
    fun deferredCount() = deferred.size
    fun resumeDeferred() {
        if (draining) return
        draining = true
        try {
            discardExpired()
            while (jobs.isEmpty() && deferred.isNotEmpty()) {
                val config = port.config()
                if (config.mode != Mode.AUTO || config.user != user) { deferred.clear(); return }
                if (!port.allowed()) return
                val next = deferred.entries.first().let { it.key to it.value }
                deferred.remove(next.first)
                accept(next.second.packet)
                if (next.first in deferred) {
                    // 复核时保护重新开启，仍沿用首次入队的固定截止，且不立即重取。
                    deferred[next.first] = next.second
                    return
                }
            }
        } finally { draining = false }
    }
    private fun discardExpired() { deferred.entries.removeAll { clock() >= it.value.expiresAt } }
    private fun defer(packet: Packet) {
        discardExpired()
        if (packet.key in deferred || packet.key in jobs) return
        if (deferred.size >= 4) { port.report("busy"); return }
        deferred[packet.key] = Deferred(packet, clock() + 86_400_000L - packet.ageAtDetection)
        port.report("protected")
    }
    fun accept(packet: Packet) {
        expire()
        val config = port.config()
        if (config.user != user || config.mode == Mode.OFF) return
        if (config.mode == Mode.PROBE) {
            if (probes.add(packet.key)) {
                while (probes.size > 2048) probes.remove(probes.first())
                port.report("candidate"); port.report("probe")
            }
            return
        }
        if (packet.group !in config.groups) return
        if (packet.key in deferred || packet.key in jobs) return
        if (!config.permits(packet.group, port.allowed())) { defer(packet); return }
        if (jobs.size >= 8) { port.report("busy"); return }
        if (!port.reserve(packet.key)) { port.report("duplicate"); return }
        val job = Job(packet, PacketFlow(clock()))
        jobs[packet.key] = job
        port.report("candidate")
        try {
            val request = port.receive(packet)
            if (!permitted(job)) { job.flow.stop(); finish(job); return }
            if (!job.flow.beginReceive(request, clock())) { finish(job); return }
            requests[request] = job
            port.report("receiving")
            dispatch(job, request)
        } catch (_: Throwable) { job.flow.stop(); finish(job) }
    }
    private fun dispatch(job: Job, request: Any) {
        val accepted = try {
            port.send(request) { scene, success ->
                if (scene === request && requests[scene] === job) {
                    job.flow.transport(scene, success, clock()); drive(job)
                }
            }
        } catch (_: Throwable) { false }
        job.flow.sent(request, accepted, clock())
        drive(job)
    }
    fun receiveBusiness(request: Any, allowed: Boolean, token: String) {
        val job = requests[request] ?: return
        job.flow.receiveBusiness(request, allowed, token, clock())
        drive(job)
    }
    fun openBusiness(request: Any, claimed: Boolean) {
        val job = requests[request] ?: return
        job.flow.openBusiness(request, claimed, clock())
        drive(job)
    }
    private fun permitted(job: Job): Boolean = port.config().let {
        it.user == user && it.permits(job.packet.group, port.allowed())
    }
    private fun drive(job: Job) {
        if (job.flow.terminal()) { finish(job); return }
        if (job.flow.canOpen(clock()) && !job.openStarted) {
            job.openStarted = true
            try {
                if (!permitted(job)) { job.flow.stop(); finish(job); return }
                val request = port.open(job.packet, job.flow.timingIdentifier)
                if (!permitted(job)) { job.flow.stop(); finish(job); return }
                if (!job.flow.beginOpen(request, clock())) { finish(job); return }
                requests[request] = job
                port.report("opening")
                dispatch(job, request)
            } catch (_: Throwable) { job.flow.stop(); finish(job) }
        }
    }
    private fun finish(job: Job) {
        if (jobs.remove(job.packet.key) !== job) return
        requests.entries.removeAll { if (it.value === job) { port.release(it.key); true } else false }
        port.report(job.flow.state.name.lowercase())
        resumeDeferred()
    }
    fun expire() { discardExpired(); jobs.values.toList().forEach { it.flow.expire(clock()); if (it.flow.terminal()) finish(it) } }
    fun stop() { deferred.clear(); jobs.values.toList().forEach { it.flow.stop(); finish(it) } }
}
