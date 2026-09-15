package com.yuyan.imemodule.keyboard

enum class VoiceLongPressFinish { NONE, STOP, CANCEL }

internal fun voiceLongPressFinish(active: Boolean, cancelled: Boolean): VoiceLongPressFinish = when {
    !active -> VoiceLongPressFinish.NONE
    cancelled -> VoiceLongPressFinish.CANCEL
    else -> VoiceLongPressFinish.STOP
}
