package com.yuyan.imemodule.data.redpacket

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.yuyan.imemodule.data.capture.adapter.ParseResult
import com.yuyan.imemodule.data.capture.adapter.WeChatChatAdapter
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.ui.AccessibilityTreeReader

/** 所有节点只在一次读取/点击内持有；找不到可操作控件时不使用坐标猜测。 */
internal class PacketWindow(private val root: AccessibilityNodeInfo, activityName: String) : AutoCloseable {
    private val nodes = linkedMapOf<String, AccessibilityNodeInfo>()
    private val parents = mutableMapOf<String, String>()
    private val labels = mutableMapOf<String, List<String>>()
    private val identities = mutableMapOf<String, Pair<Int, String>>()
    private val anchors = mutableMapOf<String, String>()
    private var titleNode: String? = null
    val page: PacketPage
    val cardSignatures: Map<String, String>
    val nodeCount: Int get() = nodes.size

    init {
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int): List<String> {
            nodes[path] = node
            val words = listOfNotNull(node.text?.toString()?.trim(), node.contentDescription?.toString()?.trim())
                .filter(String::isNotEmpty).toMutableList()
            if (depth < 30) for (index in 0 until node.childCount.coerceAtMost(100)) {
                if (nodes.size >= 600) break
                val child = node.getChild(index) ?: continue
                val childPath = "$path/$index"
                parents[childPath] = path
                words += visit(child, childPath, depth + 1)
            }
            labels[path] = words
            return words
        }
        visit(root, "root", 0)
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val parsed = AccessibilityTreeReader(maxDepth = 30, maxNodes = 600).read(root)
            ?.let { WeChatChatAdapter().parse(it) } as? ParseResult.Success
        val chat = parsed?.viewport?.takeIf { it.messages.any { message -> message.inputAreaBounds != null } }
        titleNode = nodes.entries.firstOrNull { (_, node) ->
            val bounds = Rect().also(node::getBoundsInScreen)
            chat?.titleBounds?.let { bounds == Rect(it.left, it.top, it.right, it.bottom) } == true
        }?.key
        val allWords = labels["root"].orEmpty()
        val panel = activityName.contains("luckymoney", true) ||
            (chat == null && allWords.any { it.contains("发了一个红包") || it == "红包详情" })
        val result = if (panel) when {
            allWords.any { it.contains("已存入零钱") } -> "已领取"
            allWords.any { it.contains("手慢了") || it.contains("已被领完") } -> "已被领完"
            allWords.any { it.contains("已过期") || it.contains("超过24小时") } -> "已过期"
            else -> null
        } else null
        fun clickableAncestor(path: String): String? {
            var current: String? = path
            repeat(5) {
                val id = current ?: return null
                val node = nodes[id] ?: return null
                if (node.isClickable && node.isEnabled && node.isVisibleToUser) return id
                current = parents[id]
            }
            return null
        }
        val cardIds = if (chat == null || panel) emptyList() else nodes.keys.mapNotNull { id ->
            val node = nodes.getValue(id)
            if (node.text?.toString()?.trim() != "微信红包" && node.contentDescription?.toString()?.trim() != "微信红包") return@mapNotNull null
            val target = clickableAncestor(id) ?: return@mapNotNull null
            val bounds = Rect().also(nodes.getValue(target)::getBoundsInScreen)
            val incoming = bounds.width() in 1 until (rootBounds.width() * .8).toInt() &&
                bounds.centerX() < rootBounds.centerX() && bounds.top >= (chat.titleBounds?.bottom ?: rootBounds.top)
            target.takeIf { isAvailablePacketCard(labels[target].orEmpty(), incoming) }?.also { anchors[it] = id }
        }.distinct().sortedBy { id -> Rect().also(nodes.getValue(id)::getBoundsInScreen).top }
        cardSignatures = cardIds.associateWith { id ->
            val bounds = Rect().also(nodes.getValue(id)::getBoundsInScreen)
            val identity = if (android.os.Build.VERSION.SDK_INT >= 33) nodes.getValue(id).uniqueId else null
            "${chat?.conversation?.displayName}|${identity ?: bounds}|${labels[id].orEmpty().joinToString("|")}"
        }
        val open = if (!panel) null else nodes.keys.firstNotNullOfOrNull { id ->
            val node = nodes.getValue(id)
            if (listOf(node.text?.toString(), node.contentDescription?.toString()).any { it in setOf("开", "拆红包", "打开红包") })
                clickableAncestor(id)?.also { anchors[it] = id } else null
        }
        val info = if (chat == null || panel) null else nodes.keys.firstNotNullOfOrNull { id ->
            val node = nodes.getValue(id)
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.top < rootBounds.top + rootBounds.height() * .18 &&
                listOf(node.text?.toString(), node.contentDescription?.toString()).any {
                    it in setOf("聊天信息", "聊天信息按钮", "群聊信息")
                }) clickableAncestor(id)?.also { anchors[it] = id } else null
        }
        page = PacketPage(chat?.conversation?.displayName,
            chat?.conversation?.conversationType == ConversationType.GROUP, cardIds, panel, open, result,
            chatInfoButton = info, verifiedGroupDetails = chat == null && !panel &&
                "群聊名称" in allWords && "群公告" in allWords)
        nodes.forEach { (id, node) -> identities[id] = node.windowId to packetNodeIdentity(node) }
    }

    fun clickIdentity(id: String): Pair<Int, String>? = identities[id]

    fun click(id: String): Boolean {
        val node = nodes[id] ?: return false
        fun unchanged(key: String): Boolean {
            val current = nodes[key] ?: return false
            return current.refresh() && identities[key] == (current.windowId to packetNodeIdentity(current))
        }
        return unchanged(id) && anchors[id]?.let(::unchanged) != false &&
            titleNode?.let(::unchanged) != false && node.packageName?.toString() == PACKET_WECHAT &&
            node.isVisibleToUser && node.isEnabled && node.isClickable &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @Suppress("DEPRECATION")
    override fun close() { nodes.values.forEach { it.recycle() }; nodes.clear() }
}

internal fun packetNodeIdentity(node: AccessibilityNodeInfo): String =
    "${node.viewIdResourceName}|${node.className}|${Rect().also(node::getBoundsInScreen)}|${node.text}|${node.contentDescription}"
