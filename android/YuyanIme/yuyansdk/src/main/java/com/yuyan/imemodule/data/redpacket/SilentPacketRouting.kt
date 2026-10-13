package com.yuyan.imemodule.data.redpacket

/** Both identities must refer to the same app in the same real Android user. */
internal fun silentPacketSourceUser(noticeUid: Int, creatorUid: Int): Int? = if (noticeUid == creatorUid && noticeUid >= 0 && noticeUid % 100_000 >= 10_000) noticeUid / 100_000 else null
