package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot

/** 明确的编辑工具栏或独立网页结构；联系人名字和消息正文不参与页面黑名单。 */
internal fun isWechatNonChatTree(root: UiNodeSnapshot): Boolean {
    val width = root.bounds.right - root.bounds.left
    val height = root.bounds.bottom - root.bounds.top
    if (width <= 0 || height <= 0) return false
    fun flatten(node: UiNodeSnapshot): List<UiNodeSnapshot> = listOf(node) + node.children.flatMap(::flatten)
    val nodes = flatten(root)
    val explicitChat = nodes.any { node ->
        listOf("chatting_title", "chatting_content_et", "chat_input").any { node.viewId.orEmpty().contains(it, true) }
    }
    if (explicitChat) return false
    if (nodes.any { node ->
        node.children.isEmpty() && node.bounds.top >= root.bounds.top &&
            node.bounds.bottom <= root.bounds.top + width * .22 &&
            node.bounds.left >= root.bounds.left + width * .75 &&
            (node.text ?: node.contentDescription).orEmpty().trim() in EDIT_ACTIONS
    }) return true
    return nodes.any { node ->
        node.className.orEmpty().endsWith("WebView") &&
            node.bounds.right - node.bounds.left >= width * .7 &&
            node.bounds.bottom - node.bounds.top >= height * .4
    }
}
/** 仅供 exact title band 使用：导航结构被擦除前判断，不对聊天正文做 OCR。 */
internal fun isWechatNonChatHeader(header: Bitmap, lines: List<OcrTextLine>): Boolean {
    val width = header.width
    val height = header.height
    if (height < 16 || width < height * 5 || header.isRecycled) return false
    val background = header.getPixel(width / 2, 0)
    val channels = listOf(Color.red(background), Color.green(background), Color.blue(background))
    // 只认识微信标准浅色/深色导航栏；不猜彩色主题或特殊标题布局。
    if (channels.maxOrNull()!! - channels.minOrNull()!! > 24 || channels.average() in 65.0..209.0) return false
    fun ink(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val pixel = header.getPixel(x, y)
        return maxOf(abs(Color.red(pixel) - Color.red(background)), abs(Color.green(pixel) - Color.green(background)),
            abs(Color.blue(pixel) - Color.blue(background))) > 80
    }
    if (lines.any { it.left >= width * .75 && it.top >= height * .18 && it.bottom <= height && it.text.trim() in EDIT_ACTIONS }) return true
    val cardinal = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    val diagonal = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    val step = maxOf(1, height / 144)
    val radius = (height * .10).toInt()
    // 左导航 X 的四条对角线必须同时存在；返回箭头只有两臂，不能误拒绝聊天。
    for (y in height * 35 / 100..height * 65 / 100 step step) {
        for (x in height / 4..height * 65 / 100 step step) {
            if (ink(x, y) && diagonal.all { (dx, dy) -> ink(x + dx * radius, y + dy * radius) } &&
                cardinal.all { (dx, dy) -> !ink(x + dx * radius, y + dy * radius) }) return true
        }
    }
    // 小程序胶囊：右侧同心关闭圆环、中心实点以及左侧三点必须共同出现。
    val gap = (height * .105).toInt()
    val ring = (height * .17).toInt()
    val outside = (height * .235).toInt()
    val tolerance = maxOf(1, height / 48)
    for (y in height * 35 / 100..height * 65 / 100 step step) {
        for (x in width - height..width - height / 2 step step) {
            if (!ink(x, y)) continue
            if (cardinal.all { (dx, dy) -> !ink(x + dx * gap, y + dy * gap) &&
                    ink(x + dx * ring, y + dy * ring) && !ink(x + dx * outside, y + dy * outside) } &&
                listOf(.93, 1.10, 1.28).all { offset ->
                    val dotX = x - (height * offset).toInt()
                    (-tolerance..tolerance).any { ink(dotX + it, y) }
                }) return true
        }
    }
    val title = selectWechatChatTitle(lines, width, height) ?: return false
    if (canonicalWechatPageTitle(title) != null) return false
    // 付款等普通页面只有返回和标题。标准聊天右侧应有菜单；严格全空才排除。
    return (height * 18 / 100 until height * 82 / 100).all { y ->
        (width - height until width - height * 15 / 100).all { x -> !ink(x, y) }
    }
}

private val EDIT_ACTIONS = setOf("完成", "保存", "确定", "取消")
