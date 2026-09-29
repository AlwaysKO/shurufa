package com.yuyan.imemodule.data.collect

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.text.InputType
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.completion.canLearnInput

/** Consent is separate from old privacy-policy acceptance; upgrades must opt in too. */
object CollectionConsent {
    const val KEY = "reporting_consent_v1"
    private val sensitive = Regex("密码|验证码|校验码|动态口令|一次性口令|password|passcode|one.?time|verification.?code|otp|secret|token", RegexOption.IGNORE_CASE)
    fun enabled(context: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(context.applicationContext).getBoolean(KEY, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val changed = prefs.getBoolean(KEY, false) != enabled
        check(prefs.edit().putBoolean(KEY, enabled).commit())
        if (changed) com.yuyan.imemodule.data.usage.AppUsageTracker.masterConsentChanged(context.applicationContext)
    }
    fun allowsEditor(editor: EditorInfo?): Boolean = editor != null &&
        canLearnInput(editor.inputType, editor.imeOptions) &&
        editor.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_NUMBER &&
        !sensitive.containsMatchIn(listOf(editor.hintText, editor.label, editor.fieldName, editor.privateImeOptions).joinToString(" "))
    fun allowsText(text: String?): Boolean = text == null ||
        (!sensitive.containsMatchIn(text) && !Regex("\\s*\\d{1,10}\\s*").matches(text))
}
