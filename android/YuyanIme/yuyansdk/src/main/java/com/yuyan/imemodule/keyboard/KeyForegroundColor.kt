package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.entity.keyboard.KeyType

internal fun resolveKeyForegroundColor(
    keyType: KeyType,
    normalColor: Int,
    accentColor: Int,
): Int = if (keyType == KeyType.AccentKey) accentColor else normalColor
