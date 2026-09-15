package com.yuyan.imemodule.service

import android.speech.SpeechRecognizer

enum class VoiceRecognizerMode { ON_DEVICE, SYSTEM }

internal fun isCurrentVoiceCallback(
    activeSessionId: Long,
    callbackSessionId: Long,
    sameConnection: Boolean,
    samePackage: Boolean,
): Boolean = activeSessionId == callbackSessionId && sameConnection && samePackage

internal fun chooseVoiceRecognizerMode(sdkInt: Int, onDeviceAvailable: Boolean): VoiceRecognizerMode =
    if (sdkInt >= 31 && onDeviceAvailable) VoiceRecognizerMode.ON_DEVICE else VoiceRecognizerMode.SYSTEM

internal fun shouldFallbackToSystem(
    mode: VoiceRecognizerMode,
    error: Int,
    alreadyRetried: Boolean,
): Boolean = mode == VoiceRecognizerMode.ON_DEVICE && !alreadyRetried && error in setOf(
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    SpeechRecognizer.ERROR_CLIENT,
)
