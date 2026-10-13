package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import android.os.Build
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CancellationException

internal enum class NativePacketUi { NONE, RECEIVE, DETAIL }
internal data class NativePacketTicket(val frameId: Long, val capturedAt: Long, val displayId: Int,
    val userId: Int, val width: Int, val height: Int, val packetUi: NativePacketUi = NativePacketUi.NONE)
internal data class NativePacketSnapshot(val ticket: NativePacketTicket, val windowId: Int,
    val page: PacketPage, val targets: Map<String, IntRect>, val signatures: Map<String, String>,
    val blockedBounds: List<IntRect> = emptyList())
internal enum class NativePacketReadStatus {
    SERVICE_UNAVAILABLE, INVALID_TICKET, WINDOW_UNAVAILABLE, WINDOW_AMBIGUOUS,
    ROOT_UNAVAILABLE, TREE_REJECTED, STALE, CANCELLED, VALID,
}
internal data class NativePacketReadFacts(val status: NativePacketReadStatus, val displayId: Int,
    val windowId: Int = -1, val windowCount: Int = 0, val nodeCount: Int = 0,
    val textNodeCount: Int = 0, val clickableCount: Int = 0, val headerPresent: Boolean = false,
    val cardCount: Int = 0, val openPresent: Boolean = false, val resultPresent: Boolean = false,
    val elapsedMs: Long = 0, val packetUi: NativePacketUi = NativePacketUi.NONE) {
    fun render(): String = "nativeStatus=$status displayId=$displayId windowId=$windowId windowCount=$windowCount " +
        "nodeCount=$nodeCount textNodeCount=$textNodeCount clickableCount=$clickableCount headerPresent=$headerPresent " +
        "cardCount=$cardCount openPresent=$openPresent resultPresent=$resultPresent elapsedMs=$elapsedMs packetUi=$packetUi"
}
internal fun interface NativePacketWindowSource { fun read(ticket: NativePacketTicket): NativePacketWindow? }
private class NativeReadRejected(val status: NativePacketReadStatus, val count: Int = 0,
    val id: Int = -1, val nodes: Int = 0) : RuntimeException()

internal object NativePacketReader {
    fun read(ticket: NativePacketTicket, onFacts: (NativePacketReadFacts) -> Unit = {}, permitted: () -> Boolean = { true }): NativePacketSnapshot? {
        if (Build.VERSION.SDK_INT < 35 || PacketServiceState.nativeAccessibilityService == null) {
            runCatching { onFacts(NativePacketReadFacts(NativePacketReadStatus.SERVICE_UNAVAILABLE, ticket.displayId,packetUi=ticket.packetUi)) }
            return null
        }
        return read(ticket, NativePacketWindowSource { readAndroidWindow(it,permitted) }, SystemClock::uptimeMillis, onFacts,permitted)
    }
    internal fun read(ticket: NativePacketTicket, source: NativePacketWindowSource,
        clock: () -> Long, onFacts: (NativePacketReadFacts) -> Unit = {}, permitted: () -> Boolean = { true }): NativePacketSnapshot? {
        val started = clock()
        fun report(status: NativePacketReadStatus, window: NativePacketWindow? = null,
            snapshot: NativePacketSnapshot? = null, count: Int = window?.windowCount ?: 0,
            id: Int = window?.windowId ?: -1, nodeCount: Int = window?.nodes?.size ?: 0) {
            val facts = NativePacketReadFacts(status, ticket.displayId, id, count, nodeCount,
                window?.nodes?.count { it.text.isNotBlank() || it.description.isNotBlank() } ?: 0,
                window?.nodes?.count { it.clickable } ?: 0, snapshot?.page?.chatName != null,
                snapshot?.page?.cards?.size ?: 0, snapshot?.page?.openButton != null,
                snapshot?.page?.result != null, (clock() - started).coerceAtLeast(0),packetUi=ticket.packetUi)
            runCatching { onFacts(facts) }
        }
        fun fresh() = ticket.frameId > 0 && ticket.capturedAt >= 0 &&
            clock() - ticket.capturedAt in 0..1000 && ticket.displayId > 0 && ticket.userId >= 0 &&
            ticket.width in 1..4096 && ticket.height in 1..8192 && nativePacketPixelBudget(ticket.width.toLong()*ticket.height)
        if (!permitted()) { report(NativePacketReadStatus.CANCELLED); return null }
        if (!fresh()) { report(NativePacketReadStatus.INVALID_TICKET); return null }
        return try {
            val window = source.read(ticket)
            if (!permitted()) { report(NativePacketReadStatus.CANCELLED,window); null }
            else if (window == null) { report(NativePacketReadStatus.WINDOW_UNAVAILABLE); null }
            else if (!fresh()) { report(NativePacketReadStatus.STALE, window); null }
            else {
                val snapshot = parseNativePacketPage(ticket, window)
                if (!permitted()) { report(NativePacketReadStatus.CANCELLED,window); null }
                else if (!fresh()) { report(NativePacketReadStatus.STALE, window); null }
                else { report(if (snapshot == null) NativePacketReadStatus.TREE_REJECTED else NativePacketReadStatus.VALID, window, snapshot); snapshot }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: NativeReadRejected) { report(failure.status, count=failure.count, id=failure.id, nodeCount=failure.nodes); null }
        catch (_: Exception) { report(NativePacketReadStatus.TREE_REJECTED); null }
    }

    /** Public accessibility APIs expose no account UID. Shell ticket owns this exact display/user binding. */
    @Suppress("DEPRECATION")
    private fun readAndroidWindow(ticket: NativePacketTicket,permitted:()->Boolean): NativePacketWindow? {
        if (Build.VERSION.SDK_INT < 35) throw NativeReadRejected(NativePacketReadStatus.SERVICE_UNAVAILABLE)
        val service = PacketServiceState.nativeAccessibilityService
            ?: throw NativeReadRejected(NativePacketReadStatus.SERVICE_UNAVAILABLE)
        fun budget() {
            if(Thread.currentThread().isInterrupted || !permitted())throw CancellationException()
            if(SystemClock.uptimeMillis()-ticket.capturedAt !in 0..1000)throw NativeReadRejected(NativePacketReadStatus.STALE)
        }
        budget()
        if (!service.clearCache()) throw NativeReadRejected(NativePacketReadStatus.ROOT_UNAVAILABLE)
        val retainedWindows = mutableListOf<AccessibilityWindowInfo>()
        val retainedNodes = mutableListOf<AccessibilityNodeInfo>()
        fun metadata(): List<Pair<AccessibilityWindowInfo, NativePacketWindowMeta>> {
            budget()
            val windows = service.windowsOnAllDisplays
            if (windows.size() > 64) throw NativeReadRejected(NativePacketReadStatus.WINDOW_AMBIGUOUS)
            val result = mutableListOf<Pair<AccessibilityWindowInfo, NativePacketWindowMeta>>()
            for (i in 0 until windows.size()) for (window in windows.valueAt(i).orEmpty()) {
                retainedWindows += window
                if (retainedWindows.size > 256) throw NativeReadRejected(NativePacketReadStatus.WINDOW_AMBIGUOUS)
                // Only the ticket display is traversed; main-screen roots are never retrieved.
                if (window.displayId == ticket.displayId) {
                    val r=Rect();window.getBoundsInScreen(r)
                    result += window to NativePacketWindowMeta(window.displayId,window.id,
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION,IntRect(r.left,r.top,r.right,r.bottom),window.type,window.layer)
                }
            }
            return result
        }
        try {
            val windows = metadata()
            val meta = selectNativePacketWindow(ticket,windows.map { it.second })
                ?: throw NativeReadRejected(if(windows.isEmpty()) NativePacketReadStatus.WINDOW_UNAVAILABLE else NativePacketReadStatus.WINDOW_AMBIGUOUS,windows.size)
            val selected = windows.single { it.second == meta }.first
            budget()
            val root = selected.getRoot(0) ?: throw NativeReadRejected(NativePacketReadStatus.ROOT_UNAVAILABLE,1,meta.windowId)
            retainedNodes += root
            budget()
            if(!root.refresh()){throw NativeReadRejected(NativePacketReadStatus.ROOT_UNAVAILABLE,1,meta.windowId)}
            val flat = mutableListOf<NativePacketNode>()
            val seen = hashSetOf<AccessibilityNodeInfo>()
            var totalText=0
            fun visit(node: AccessibilityNodeInfo,path: String,parent: String?,depth: Int) {
                if(parent != null) retainedNodes += node
                budget()
                if (!seen.add(node) || depth > 30 || retainedNodes.size > 600)
                    throw NativeReadRejected(NativePacketReadStatus.TREE_REJECTED,1,meta.windowId,flat.size)
                if (node.windowId != meta.windowId || node.packageName?.toString() != PACKET_WECHAT || node.isPassword)
                    throw NativeReadRejected(NativePacketReadStatus.TREE_REJECTED,1,meta.windowId,flat.size)
                if (!node.isVisibleToUser) {
                    if (parent == null) throw NativeReadRejected(NativePacketReadStatus.ROOT_UNAVAILABLE,1,meta.windowId)
                    return
                }
                val text=node.text?.toString().orEmpty();val description=node.contentDescription?.toString().orEmpty()
                val className=node.className?.toString().orEmpty();totalText+=text.length+description.length
                if (text.length>2048 || description.length>2048 || className.length>256 || totalText>65_536 || node.childCount>100)
                    throw NativeReadRejected(NativePacketReadStatus.TREE_REJECTED,1,meta.windowId,flat.size)
                val r=Rect();node.getBoundsInScreen(r)
                flat += NativePacketNode(path,parent,IntRect(r.left,r.top,r.right,r.bottom),text,description,
                    node.isClickable,node.isEnabled,true,PACKET_WECHAT,node.windowId,className,node.isEditable)
                for (i in 0 until node.childCount) {
                    budget()
                    val child=node.getChild(i,0) ?: throw NativeReadRejected(NativePacketReadStatus.TREE_REJECTED,1,meta.windowId,flat.size)
                    visit(child,"$path/$i",path,depth+1)
                }
            }
            visit(root,"0",null,0)
            val afterWindows=metadata().map { it.second }
            val after=selectNativePacketWindow(ticket,afterWindows)
            budget()
            if (after != meta || !nativePacketWindowSetStable(windows.map { it.second },afterWindows) || PacketServiceState.nativeAccessibilityService !== service || !root.refresh())
                throw NativeReadRejected(NativePacketReadStatus.WINDOW_AMBIGUOUS,1,meta.windowId,flat.size)
            return NativePacketWindow(ticket.displayId,ticket.userId,meta.windowId,meta.bounds,flat.toList(),
                blockedBounds=windows.filter { it.second.windowId != meta.windowId }.map { it.second.bounds },windowCount=windows.size)
        } finally {
            retainedNodes.forEach { runCatching { it.recycle() } }
            retainedWindows.forEach { runCatching { it.recycle() } }
        }
    }
}

internal data class NativePacketWindowMeta(val displayId: Int, val windowId: Int, val application: Boolean, val bounds: IntRect,
    val windowType: Int = if (application) 1 else -1, val layer: Int = 0)
internal fun selectNativePacketWindow(ticket: NativePacketTicket, windows: List<NativePacketWindowMeta>): NativePacketWindowMeta? {
    if(ticket.displayId<=0||ticket.width<=0||ticket.height<=0)return null
    val visible=windows.filter { it.displayId==ticket.displayId }
    if(visible.any {it.windowId<0} || visible.map {it.windowId}.toSet().size!=visible.size)return null
    val selected=visible.filter { it.application && it.windowType==1 }.singleOrNull() ?: return null
    val r=selected.bounds
    if(selected.windowId<0||r.left<0||r.top<0||r.right>ticket.width||r.bottom>ticket.height||r.right<=r.left||r.bottom<=r.top)return null
    if(visible.any { it != selected && (it.application || it.windowType!=3 || !nativePacketSystemEdge(it.bounds,ticket)) })return null
    return selected
}
internal fun nativePacketSystemEdge(r:IntRect,ticket:NativePacketTicket):Boolean =
    r.left>=0&&r.top>=0&&r.right<=ticket.width&&r.bottom<=ticket.height&&r.right>r.left&&r.bottom>r.top&&
        r.right-r.left>=ticket.width*.80&&r.bottom-r.top<=ticket.height*.08&&
        (r.top==0||r.bottom==ticket.height)
internal fun nativePacketPixelBudget(pixels:Long):Boolean = pixels in 1..8_388_608L

internal fun nativePacketWindowSetStable(before:List<NativePacketWindowMeta>,after:List<NativePacketWindowMeta>):Boolean =
    before.size==after.size && before.toSet()==after.toSet()
