package com.yuyan.redpacket

import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

enum class Mode { OFF, PROBE, AUTO }
data class PacketConfig(val mode: Mode = Mode.OFF, val user: Int = -1, val groups: Set<String> = emptySet()) {
    fun acceptHost(pkg: String, process: String, actualUser: Int, version: String, code: Long) =
        mode != Mode.OFF && user >= 0 && user == actualUser && pkg == "com.tencent.mm" &&
            process == pkg && version == "8.0.78" && code == 3180L
    fun permits(group: String, protectedAllowed: Boolean) =
        mode == Mode.AUTO && protectedAllowed && group.endsWith("@chatroom") && group in groups
}
data class StoredMessage(val type: Int?, val isSend: Int?, val talker: String?, val content: String?, val createdAt: Long?) {
    override fun toString() = "StoredMessage(redacted)"
}
class Packet(val sendId: String, val nativeUrl: String, val group: String, val msgType: Int, val channelId: Int, val key: String, val ageAtDetection: Long = 0) {
    override fun toString() = "Packet(redacted)"
}
fun packetHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

object PacketParser {
    fun parse(message: StoredMessage, row: Long, now: Long): Packet? = runCatching {
        if (row < 0 || message.type != 436207665 || message.isSend != 0) return null
        val group = message.talker?.takeIf { it.endsWith("@chatroom") && it.length in 10..256 } ?: return null
        val created = message.createdAt ?: return null
        if (now - created !in 0..15_000) return null
        val raw = message.content?.takeIf { it.length in 1..32_768 } ?: return null
        val xml = if (raw.trimStart().startsWith('<')) raw.trimStart() else {
            val prefix = raw.indexOf(":\n")
            if (prefix !in 1..256) return null
            raw.substring(prefix + 2).trimStart()
        }
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return null
        val builder = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.setErrorHandler(DefaultHandler())
        val document = builder.parse(InputSource(StringReader(xml)))
        if (document.documentElement.tagName != "msg") return null
        val nodes = document.getElementsByTagName("nativeurl")
        if (nodes.length != 1) return null
        val node = nodes.item(0)
        if (node.parentNode.nodeName != "wcpayinfo" || node.parentNode.parentNode.nodeName != "appmsg") return null
        val nativeUrl = node.textContent.trim()
        val uri = URI(nativeUrl)
        if (uri.scheme != "wxpay" || uri.host != "c2cbizmessagehandler" ||
            uri.path != "/hongbao/receivehongbao" || uri.fragment != null || uri.userInfo != null) return null
        val params = linkedMapOf<String, String>()
        for (part in (uri.rawQuery ?: return null).split('&')) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2) return null
            val key = URLDecoder.decode(pair[0], "UTF-8")
            val value = URLDecoder.decode(pair[1], "UTF-8")
            if (params.put(key, value) != null) return null
        }
        val sendId = params["sendid"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,512}")) } ?: return null
        val type = params["msgtype"]?.toIntOrNull()?.takeIf { it == 1 } ?: return null
        val channel = params["channelid"]?.toIntOrNull()?.takeIf { it == 1 } ?: return null
        Packet(sendId, nativeUrl, group, type, channel, packetHash("$group\u0000$sendId"), now - created)
    }.getOrNull()
}

enum class FlowState { NEW, RECEIVING, READY, OPENING, CLAIMED, REJECTED, FAILED, UNKNOWN, STOPPED }

/** 所有调用由同一工作队列串行执行；回调凭对象身份关联，三份成功证据缺一不可。 */
class PacketFlow(private val startedAt: Long) {
    val deadlineAt = startedAt + 12_000
    var state = FlowState.NEW; private set
    var timingIdentifier = ""; private set
    private var current: Any? = null
    private var accepted = false
    private var business = false
    private var network = false
    fun beginReceive(request: Any, now: Long): Boolean {
        expire(now)
        if (state != FlowState.NEW) return false
        reset(request)
        state = FlowState.RECEIVING
        return true
    }
    fun beginOpen(request: Any, now: Long): Boolean {
        if (!canOpen(now)) return false
        reset(request)
        state = FlowState.OPENING
        return true
    }
    private fun reset(request: Any) { current = request; accepted = false; business = false; network = false }
    fun sent(request: Any, accepted: Boolean, now: Long) {
        if (!matches(request, now)) return
        if (!accepted) state = FlowState.FAILED else { this.accepted = true; advance() }
    }
    fun transport(request: Any, success: Boolean, now: Long) {
        if (!matches(request, now)) return
        if (!success) state = FlowState.FAILED else { network = true; advance() }
    }
    fun receiveBusiness(request: Any, allowed: Boolean, token: String, now: Long): Boolean {
        if (!matches(request, now) || state != FlowState.RECEIVING) return false
        if (!allowed || token.isBlank()) state = FlowState.REJECTED
        else { timingIdentifier = token; business = true; advance() }
        return true
    }
    fun openBusiness(request: Any, claimed: Boolean, now: Long) {
        if (!matches(request, now) || state != FlowState.OPENING) return
        if (!claimed) state = FlowState.UNKNOWN else { business = true; advance() }
    }
    fun canOpen(now: Long): Boolean { expire(now); return state == FlowState.READY }
    fun expire(now: Long) {
        if (state in setOf(FlowState.NEW, FlowState.RECEIVING, FlowState.READY, FlowState.OPENING) &&
            now - startedAt !in 0 until 12_000) state = FlowState.UNKNOWN
    }
    fun stop() { if (!terminal()) state = if (state == FlowState.NEW) FlowState.STOPPED else FlowState.UNKNOWN }
    fun terminal() = state !in setOf(FlowState.NEW, FlowState.RECEIVING, FlowState.READY, FlowState.OPENING)
    private fun matches(request: Any, now: Long): Boolean {
        expire(now)
        return current === request && state in setOf(FlowState.RECEIVING, FlowState.OPENING)
    }
    private fun advance() {
        if (accepted && business && network) state = when (state) {
            FlowState.RECEIVING -> FlowState.READY
            FlowState.OPENING -> FlowState.CLAIMED
            else -> state
        }
    }
}
