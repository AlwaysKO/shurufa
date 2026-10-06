package com.yuyan.imemodule.data.collect

internal data class DeliverySelection(
    val events:Boolean=true,
    val regular:Boolean=true,
    val location:Boolean=true,
    val chatText:Boolean=true,
    val images:Boolean=true,
)
