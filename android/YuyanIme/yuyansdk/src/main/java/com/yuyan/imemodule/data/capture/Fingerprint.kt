package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.model.CapturedMessage
import java.security.MessageDigest

private val WHITESPACE = Regex("\\s+")

fun normalizeCapturedText(text: String?): String =
    text?.trim()?.replace(WHITESPACE, " ").orEmpty()

fun sha256(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun fingerprintParts(parts: List<String>): String {
    val canonical = parts.joinToString("|") { part -> "${part.length}:$part" }
    return sha256(canonical.toByteArray(Charsets.UTF_8))
}

fun contentFingerprint(message: CapturedMessage): String = fingerprintParts(
    listOf(
        message.messageType.wireName,
        normalizeCapturedText(message.text),
        message.assetSha256.sorted().joinToString(","),
    ),
)

fun messageFingerprint(message: CapturedMessage): String? {
    val conversationKey = message.conversationKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val senderKey = message.senderKey.trim().takeIf { it.isNotEmpty() } ?: return null
    return fingerprintParts(
        listOf(
            conversationKey,
            senderKey,
            message.direction.wireName,
            message.messageType.wireName,
            contentFingerprint(message),
            message.displayedTime?.trim().orEmpty(),
            message.previousContentFingerprint.orEmpty(),
            message.nextContentFingerprint.orEmpty(),
            message.sameContentOrdinal.toString(),
        ),
    )
}
