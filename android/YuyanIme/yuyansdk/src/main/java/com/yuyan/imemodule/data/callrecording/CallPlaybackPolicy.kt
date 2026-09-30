package com.yuyan.imemodule.data.callrecording

internal fun callPreviewAllowed(requestEpoch:Long,currentEpoch:Long,resumed:Boolean,recording:Boolean):Boolean =
    requestEpoch==currentEpoch && resumed && !recording
