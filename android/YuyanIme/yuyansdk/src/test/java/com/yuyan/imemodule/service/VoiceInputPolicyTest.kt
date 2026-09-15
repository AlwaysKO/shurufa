package com.yuyan.imemodule.service

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputPolicyTest {
    @Test
    fun voiceCallbackMustBelongToCurrentEditorSession() {
        assertTrue(isCurrentVoiceCallback(2L, 2L, sameConnection = true, samePackage = true))
        assertFalse(isCurrentVoiceCallback(2L, 1L, sameConnection = true, samePackage = true))
        assertFalse(isCurrentVoiceCallback(2L, 2L, sameConnection = false, samePackage = true))
        assertFalse(isCurrentVoiceCallback(2L, 2L, sameConnection = true, samePackage = false))
    }
    @Test
    fun androidTwelveAndAbovePreferAvailableOnDeviceRecognizer() {
        assertEquals(VoiceRecognizerMode.ON_DEVICE, chooseVoiceRecognizerMode(31, true))
        assertEquals(VoiceRecognizerMode.SYSTEM, chooseVoiceRecognizerMode(31, false))
        assertEquals(VoiceRecognizerMode.SYSTEM, chooseVoiceRecognizerMode(30, true))
    }

    @Test
    fun onDeviceLanguageFailuresFallbackToSystemOnlyOnce() {
        assertTrue(shouldFallbackToSystem(VoiceRecognizerMode.ON_DEVICE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, false))
        assertTrue(shouldFallbackToSystem(VoiceRecognizerMode.ON_DEVICE, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, false))
        assertFalse(shouldFallbackToSystem(VoiceRecognizerMode.SYSTEM, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, false))
        assertFalse(shouldFallbackToSystem(VoiceRecognizerMode.ON_DEVICE, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, true))
    }
}
